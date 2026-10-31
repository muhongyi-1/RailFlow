package com.railflow.maintenance;

import java.time.LocalDateTime;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** An in-memory model, not evidence about MySQL row locks or RabbitMQ delivery ordering. */
public final class PaymentCloseRace {
    public static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 10, 12, 12, 0);
    private PaymentCloseRace() {}

    public static final class SimulatedOrder {
        private final LocalDateTime expiresAt;
        private final int requestMask;
        private final int otherActiveMask;
        private String status = "PENDING_PAYMENT";
        private int bitmap;
        private LocalDateTime paidAt;
        private LocalDateTime closedAt;

        public SimulatedOrder(LocalDateTime expiresAt) { this(expiresAt, 3, 0, null); }
        public SimulatedOrder(LocalDateTime expiresAt, int requestMask, int otherActiveMask, Integer bitmap) {
            this.expiresAt = expiresAt;
            this.requestMask = requestMask;
            this.otherActiveMask = otherActiveMask;
            this.bitmap = bitmap == null ? requestMask | otherActiveMask : bitmap;
        }
        public synchronized String paymentSuccess(LocalDateTime processedAt) {
            if (status.equals("PAID")) return "ALREADY_PAID";
            if (!status.equals("PENDING_PAYMENT")) return "REJECTED_CLOSED_RECONCILE";
            if (!processedAt.isBefore(expiresAt)) return "REJECTED_EXPIRED_RECONCILE";
            status = "PAID";
            paidAt = processedAt;
            return "PAID";
        }
        public synchronized String closeExpired(LocalDateTime processedAt) {
            if (!status.equals("PENDING_PAYMENT")) return "SKIPPED_NOT_PENDING";
            if (processedAt.isBefore(expiresAt)) return "SKIPPED_NOT_EXPIRED";
            if (requestMask <= 0 || (bitmap & requestMask) != requestMask) return "BLOCKED_BITMAP_MISSING";
            if ((otherActiveMask & requestMask) != 0) return "BLOCKED_OVERLAPPING_ORDER";
            bitmap &= ~requestMask;
            status = "CLOSED";
            closedAt = processedAt;
            return "CLOSED";
        }
        public synchronized String status() { return status; }
        public synchronized int bitmap() { return bitmap; }
        public synchronized LocalDateTime paidAt() { return paidAt; }
        public synchronized LocalDateTime closedAt() { return closedAt; }
    }

    public record RaceResult(SimulatedOrder order, String paymentResult, String closeResult) {}

    public static RaceResult simultaneousRace(LocalDateTime now) {
        SimulatedOrder order = new SimulatedOrder(BASE_TIME.plusSeconds(5));
        CyclicBarrier gate = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> payment = executor.submit(() -> { gate.await(); return order.paymentSuccess(now); });
            Future<String> close = executor.submit(() -> { gate.await(); return order.closeExpired(now); });
            return new RaceResult(order, payment.get(), close.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("竞态演练被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("竞态演练失败", e);
        } finally {
            executor.shutdownNow();
        }
    }

    public static void demo() {
        LocalDateTime expiry = BASE_TIME.plusSeconds(5);
        System.out.println("RailFlow 支付 / 关单竞态演练（纯内存）");
        SimulatedOrder first = new SimulatedOrder(expiry, 3, 4, null);
        check(first.paymentSuccess(BASE_TIME.plusSeconds(4)).equals("PAID"));
        check(first.closeExpired(BASE_TIME.plusSeconds(6)).equals("SKIPPED_NOT_PENDING"));
        check(first.bitmap() == 7);
        System.out.println("场景1通过：先支付，关单不释放库存，Bitmap=7。");
        SimulatedOrder second = new SimulatedOrder(expiry, 3, 4, null);
        check(second.closeExpired(BASE_TIME.plusSeconds(6)).equals("CLOSED"));
        check(second.paymentSuccess(BASE_TIME.plusSeconds(7)).equals("REJECTED_CLOSED_RECONCILE"));
        check(second.bitmap() == 4);
        System.out.println("场景2通过：先关单，迟到支付需对账，保留其他区间 Bitmap=4。");
        SimulatedOrder delayed = new SimulatedOrder(expiry);
        check(delayed.paymentSuccess(BASE_TIME.plusSeconds(6)).equals("REJECTED_EXPIRED_RECONCILE"));
        check(delayed.status().equals("PENDING_PAYMENT") && delayed.bitmap() == 3);
        System.out.println("场景3通过：回调入队早于截止时间，消费者超时处理仍拒绝支付。");
        RaceResult race = simultaneousRace(BASE_TIME.plusSeconds(6));
        check(race.closeResult().equals("CLOSED") && race.order().bitmap() == 0);
        check(race.paymentResult().startsWith("REJECTED_"));
        System.out.println("场景4通过：到期后并发竞争，支付=" + race.paymentResult() + "，关单=CLOSED。");
        SimulatedOrder duplicate = new SimulatedOrder(expiry);
        check(duplicate.paymentSuccess(BASE_TIME).equals("PAID"));
        check(duplicate.paymentSuccess(BASE_TIME.plusSeconds(1)).equals("ALREADY_PAID"));
        check(duplicate.bitmap() == 3);
        System.out.println("场景5通过：重复投递保持幂等，Bitmap=3。");
        System.out.println("[边界] 只验证内存并发模型，未连接或验证 MySQL / Redis / RabbitMQ。");
    }
    private static void check(boolean condition) {
        if (!condition) throw new IllegalStateException("演练断言失败");
    }
}
