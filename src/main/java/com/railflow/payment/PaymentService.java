package com.railflow.payment;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public PaymentService(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public String applyPaymentSuccess(String orderNo) {
        return transaction.execute(status -> {
            var orders = jdbc.query("SELECT id, status, expires_at FROM orders WHERE order_no = ? FOR UPDATE",
                    (rs, row) -> new PaymentOrder(rs.getLong("id"), rs.getString("status"),
                            rs.getObject("expires_at", LocalDateTime.class)), orderNo);
            if (orders.isEmpty()) {
                throw new PaymentNotApplicable("订单不存在：" + orderNo);
            }
            var order = orders.get(0);
            if ("PAID".equals(order.status())) {
                return "ALREADY_PAID";
            }
            if (!"PENDING_PAYMENT".equals(order.status())) {
                throw new PaymentNotApplicable("当前状态禁止支付：" + order.status());
            }
            // Read time after acquiring the same order lock used by the expiration task.
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            if (!order.expiresAt().isAfter(now)) {
                throw new PaymentNotApplicable("订单已超过支付期限，需要人工对账/退款");
            }
            jdbc.update("UPDATE orders SET status = 'PAID', paid_at = ? WHERE id = ?", now, order.id());
            return "PAID";
        });
    }

    private record PaymentOrder(long id, String status, LocalDateTime expiresAt) {}
}
