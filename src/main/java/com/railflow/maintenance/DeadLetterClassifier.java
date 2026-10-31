package com.railflow.maintenance;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** Classification suggests a manual investigation; it does not assert settlement or refund. */
public final class DeadLetterClassifier {
    private DeadLetterClassifier() {}
    public record Classification(String code, String recommendation) {}

    public static Classification classify(String status, LocalDateTime expiresAt, LocalDateTime now) {
        if (status == null) return new Classification("ORDER_NOT_FOUND",
                "订单不存在：先核对消息来源、数据库环境和订单号；禁止盲目补单。");
        return switch (status) {
            case "PAID" -> new Classification("ALREADY_PAID_REVIEW",
                    "订单已支付：核对支付平台流水和消费者日志，排查重复通知或其他处理异常。");
            case "CLOSED", "CANCELED" -> new Classification("CLOSED_PAYMENT_RECONCILE",
                    "订单已关闭或取消：保留死信，向支付平台核实是否真实扣款；如已扣款，进入人工退款/补偿流程，禁止直接改 PAID。");
            case "PENDING_PAYMENT" -> expiresAt == null
                    ? new Classification("INVALID_EXPIRY_REVIEW", "订单缺少截止时间：检查数据完整性及关单任务。")
                    : !expiresAt.isAfter(now)
                    ? new Classification("EXPIRED_PENDING_REVIEW", "订单已过截止时间但仍待支付：检查关单任务及消息失败原因，先完成支付平台对账。")
                    : new Classification("ACTIVE_PENDING_REVIEW", "订单仍在支付时限内：检查消费者异常日志和死信原因，确认后决定是否安全重试。");
            default -> new Classification("UNKNOWN_STATUS", "未知订单状态：停止自动处理，检查数据库及消息格式。");
        };
    }

    public static LocalDateTime utcFromUnix(long timestamp) {
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamp), ZoneOffset.UTC);
    }
}
