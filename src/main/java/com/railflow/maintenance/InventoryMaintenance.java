package com.railflow.maintenance;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class InventoryMaintenance {
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactionManager;

    public InventoryMaintenance(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactionManager = transactionManager;
    }

    record Seat(long id, long trainId, String seatNo, int bitmap) {}
    record Snapshot(InventoryReconciler.OrderView view, LocalDateTime expiresAt,
                    LocalDateTime paidAt, LocalDateTime closedAt) {}
    private static final RowMapper<Seat> SEATS = (rs, n) ->
            new Seat(rs.getLong("id"), rs.getLong("train_id"), rs.getString("seat_no"), rs.getInt("bitmap"));
    private static final RowMapper<Snapshot> ORDERS = (rs, n) -> new Snapshot(
            new InventoryReconciler.OrderView(rs.getString("order_no"), rs.getString("status"),
                    rs.getLong("train_id"), rs.getLong("seat_id"), rs.getInt("start_station"),
                    rs.getInt("end_station"), rs.getInt("request_mask")),
            timestamp(rs, "expires_at"), timestamp(rs, "paid_at"), timestamp(rs, "closed_at"));

    private static LocalDateTime timestamp(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDateTime.class);
    }

    private TransactionTemplate transaction(boolean readOnly) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setReadOnly(readOnly);
        tx.setIsolationLevel(readOnly ? TransactionDefinition.ISOLATION_REPEATABLE_READ
                : TransactionDefinition.ISOLATION_READ_COMMITTED);
        return tx;
    }

    public int audit(Long seatId, int limit) {
        if (limit <= 0) throw new IllegalArgumentException("--limit 必须大于 0");
        return transaction(true).execute(status -> {
            List<Seat> seats = seatId == null ? jdbc.query("SELECT * FROM train_seats ORDER BY id", SEATS)
                    : jdbc.query("SELECT * FROM train_seats WHERE id=? ORDER BY id", SEATS, seatId);
            List<Snapshot> orders = seatId == null ? jdbc.query("SELECT * FROM orders ORDER BY id", ORDERS)
                    : jdbc.query("SELECT * FROM orders WHERE seat_id=? ORDER BY id", ORDERS, seatId);
            if (seatId != null && seats.isEmpty()) {
                System.out.println("[错误] 座位 seat_id=" + seatId + " 不存在");
                return 2;
            }
            Map<Long, Seat> seatMap = new HashMap<>();
            Map<Long, List<InventoryReconciler.OrderView>> bySeat = new HashMap<>();
            seats.forEach(seat -> seatMap.put(seat.id(), seat));
            List<String> problems = new ArrayList<>();
            List<String> overdue = new ArrayList<>();
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            int active = 0;
            for (Snapshot snapshot : orders) {
                InventoryReconciler.OrderView order = snapshot.view();
                if (!InventoryReconciler.ACTIVE_STATUSES.contains(order.status())) continue;
                active++;
                bySeat.computeIfAbsent(order.seatId(), ignored -> new ArrayList<>()).add(order);
                if (!seatMap.containsKey(order.seatId()))
                    problems.add("座位缺失：订单 " + order.orderNo() + " 引用 seat_id=" + order.seatId());
                if (order.status().equals("PENDING_PAYMENT") && snapshot.expiresAt() != null
                        && !snapshot.expiresAt().isAfter(now)) overdue.add(order.orderNo());
            }
            for (Seat seat : seats) {
                List<InventoryReconciler.OrderView> associated = bySeat.getOrDefault(seat.id(), List.of());
                InventoryReconciler.Report report = InventoryReconciler.inspect(
                        seat.id(), seat.seatNo(), seat.trainId(), seat.bitmap(), associated);
                problems.addAll(report.issues());
                int expected = associated.stream().mapToInt(InventoryReconciler.OrderView::requestMask)
                        .reduce(0, (left, right) -> left | right);
                if (seat.bitmap() != expected) problems.add("Bitmap 不一致：seat_id=" + seat.id()
                        + "(" + seat.seatNo() + ")，实际=" + seat.bitmap() + "，应为=" + expected
                        + "，缺少的位=" + (expected & ~seat.bitmap()) + "，多余的位=" + (seat.bitmap() & ~expected));
            }
            System.out.printf("RailFlow 库存对账：座位=%d，订单=%d，有效订单=%d，异常=%d%n",
                    seats.size(), orders.size(), active, problems.size());
            problems.stream().limit(limit).forEach(problem -> System.out.println("[异常] " + problem));
            if (problems.size() > limit) System.out.println("其余 " + (problems.size() - limit) + " 条异常未展开");
            if (problems.isEmpty()) System.out.println("[通过] 没有发现库存或有效订单关联异常。");
            if (!overdue.isEmpty()) {
                System.out.println("[提醒] 已过期但尚未关单：" + overdue.size() + " 笔，关闭前仍然占座。");
                overdue.stream().limit(limit).forEach(order -> System.out.println("待处理订单：" + order));
            }
            System.out.println("[安全] 本次仅查询数据库。");
            return problems.isEmpty() ? 0 : 1;
        });
    }

    /** Must only be called with apply=true after CLI maintenance checks and typed confirmation. */
    public int inspectSeat(long seatId, boolean apply) {
        if (seatId <= 0) throw new IllegalArgumentException("--seat-id 必须是正整数");
        return transaction(!apply).execute(status -> {
            List<Seat> seats = jdbc.query("SELECT * FROM train_seats WHERE id=?" + (apply ? " FOR UPDATE" : ""), SEATS, seatId);
            if (seats.isEmpty()) {
                System.out.println("[错误] 座位 " + seatId + " 不存在");
                return 2;
            }
            Seat seat = seats.get(0);
            List<Snapshot> orders = jdbc.query("SELECT * FROM orders WHERE seat_id=? ORDER BY id"
                    + (apply ? " FOR UPDATE" : ""), ORDERS, seatId);
            InventoryReconciler.Report report = InventoryReconciler.inspect(seat.id(), seat.seatNo(), seat.trainId(),
                    seat.bitmap(), orders.stream().map(Snapshot::view).toList());
            showReport(report);
            if (!apply) {
                System.out.println("[安全] 只读预览，没有修改数据库。");
                return report.consistent() ? 0 : 1;
            }
            if (report.consistent()) return 0;
            if (!report.safeToAdd()) {
                status.setRollbackOnly();
                System.out.println("[拒绝] 存在非缺位异常，禁止写入数据库。");
                return 1;
            }
            int repaired = seat.bitmap() | report.missing();
            jdbc.update("UPDATE train_seats SET bitmap=? WHERE id=?", repaired, seatId);
            System.out.println("[已补偿] seat_id=" + seatId + "，Bitmap " + seat.bitmap() + " -> " + repaired);
            return 0;
        });
    }

    public int deadLetterAudit(String orderNo, String paymentNo, long eventTimestamp) {
        LocalDateTime eventAt = DeadLetterClassifier.utcFromUnix(eventTimestamp);
        return transaction(true).execute(status -> {
            List<Snapshot> orders = jdbc.query("SELECT * FROM orders WHERE order_no=?", ORDERS, orderNo);
            Snapshot order = orders.isEmpty() ? null : orders.get(0);
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            DeadLetterClassifier.Classification classification = DeadLetterClassifier.classify(
                    order == null ? null : order.view().status(), order == null ? null : order.expiresAt(), now);
            System.out.println("RailFlow 死信支付对账（仅查询数据库，不消费、重发或删除 MQ 消息）");
            System.out.println("订单号：" + orderNo + "，消息支付流水号：" + paymentNo + "（尚未向支付平台核验）");
            System.out.println("消息生成时间（UTC）：" + eventAt + "，对账时间（UTC）：" + now);
            if (order == null) System.out.println("数据库订单：未找到");
            else {
                List<Seat> seats = jdbc.query("SELECT * FROM train_seats WHERE id=?", SEATS, order.view().seatId());
                System.out.println("订单状态：" + order.view().status() + "，截止时间：" + order.expiresAt()
                        + "，支付时间：" + order.paidAt() + "，关闭时间：" + order.closedAt());
                System.out.println("座位：id=" + order.view().seatId() + "，Bitmap="
                        + (seats.isEmpty() ? "座位记录缺失" : seats.get(0).bitmap()));
                if (order.expiresAt() != null) System.out.println("消息生成到支付截止相差（秒）："
                        + java.time.Duration.between(eventAt, order.expiresAt()).getSeconds()
                        + "（正数表示消息早于截止，不代表及时消费）");
            }
            System.out.println("初步分类：" + classification.code());
            System.out.println("处理建议：" + classification.recommendation());
            System.out.println("[边界] 未读取 RabbitMQ x-death、消费者日志或平台真实流水；不能确定死信根因或是否扣款。");
            return 0;
        });
    }

    public static void showReport(InventoryReconciler.Report report) {
        System.out.printf("座位：%d (%s)，有效订单：%d，当前 Bitmap=%d，理论=%d，缺失位=%d，多余位=%d%n",
                report.seatId(), report.seatNo(), report.activeCount(), report.current(), report.expected(), report.missing(), report.extra());
        report.issues().forEach(issue -> System.out.println("[危险] " + issue));
        if (report.consistent()) System.out.println("[正常] 座位与订单一致，无需补偿。");
        else if (report.safeToAdd()) System.out.println("[可补位] 仅允许添加掩码 " + report.missing() + "；该报告本身不写入数据库。");
        else System.out.println("[禁止自动补偿] 存在重叠、错误或多余位，需要人工确认。");
    }

    public static void demo() {
        InventoryReconciler.OrderView paid = new InventoryReconciler.OrderView("paid_demo", "PAID", 1, 4, 0, 2, 3);
        InventoryReconciler.OrderView overlap = new InventoryReconciler.OrderView("overlap_demo", "PENDING_PAYMENT", 1, 4, 0, 2, 3);
        InventoryReconciler.OrderView invalid = new InventoryReconciler.OrderView("bad_mask_demo", "PAID", 1, 4, 2, 3, 3);
        String[] labels = {"正常", "丢失占用位", "多余占用位", "有效订单重叠", "订单掩码错误"};
        int[] bitmaps = {3, 0, 7, 3, 3};
        List<List<InventoryReconciler.OrderView>> scenarios = List.of(List.of(paid), List.of(paid), List.of(paid), List.of(paid, overlap), List.of(invalid));
        for (int i = 0; i < labels.length; i++) {
            System.out.println("场景" + (i + 1) + "：" + labels[i]);
            showReport(InventoryReconciler.inspect(4, "A03", 1, bitmaps[i], scenarios.get(i)));
        }
        System.out.println("[安全] 演练数据只存在内存中，没有连接数据库。");
    }
}
