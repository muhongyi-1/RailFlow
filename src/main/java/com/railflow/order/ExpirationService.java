package com.railflow.order;

import com.railflow.common.SeatBitmap;
import com.railflow.domain.Order;
import com.railflow.domain.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
public class ExpirationService {
    private static final Logger LOG = LoggerFactory.getLogger(ExpirationService.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;

    @Autowired
    public ExpirationService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this(jdbc, transactionManager, Clock.systemUTC());
    }

    public ExpirationService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public int closeExpiredOrders() {
        List<Long> candidates = jdbc.query("""
                SELECT id FROM orders WHERE status = 'PENDING_PAYMENT' AND expires_at <= ?
                ORDER BY expires_at, id LIMIT 100
                """, (rs, n) -> rs.getLong(1), now());
        int closed = 0;
        for (long id : candidates) {
            try {
                if (Boolean.TRUE.equals(transaction.execute(status -> closeOne(id)))) closed++;
            } catch (RuntimeException ex) {
                // Each order has a separate transaction; corrupt inventory never blocks other seats.
                LOG.error("订单 id={} 关单失败，已回滚：{}", id, ex.getMessage());
            }
        }
        return closed;
    }

    private boolean closeOne(long id) {
        List<Order> orders = jdbc.query("""
                SELECT * FROM orders WHERE id = ? AND status = 'PENDING_PAYMENT' AND expires_at <= ?
                FOR UPDATE SKIP LOCKED
                """, Order.MAPPER, id, now());
        if (orders.isEmpty()) return false;
        Order order = orders.get(0);
        if (!order.status().canTransitionTo(OrderStatus.CLOSED)) return false;
        List<Integer> bitmaps = jdbc.query(
                "SELECT bitmap FROM train_seats WHERE id = ? AND train_id = ? FOR UPDATE",
                (rs, n) -> rs.getInt(1), order.seatId(), order.trainId());
        if (bitmaps.isEmpty()) throw new IllegalStateException("对应座位不存在或车次不匹配");
        int mask = order.requestMask();
        int bitmap = bitmaps.get(0);
        if (mask <= 0 || mask != SeatBitmap.mask(order.startStation(), order.endStation())) {
            throw new IllegalStateException("订单区间与 request_mask 不一致，禁止自动释放库存");
        }
        if ((bitmap & mask) != mask) throw new IllegalStateException("订单库存缺位，请先人工对账");
        // Bitwise check in Java makes the exact same query work with both MySQL and H2.
        List<Integer> otherMasks = jdbc.query("""
                SELECT request_mask FROM orders WHERE seat_id = ? AND id <> ?
                  AND status IN ('PENDING_PAYMENT', 'PAID')
                """, (rs, n) -> rs.getInt(1), order.seatId(), order.id());
        if (otherMasks.stream().anyMatch(other -> (other & mask) != 0)) {
            throw new IllegalStateException("存在重叠有效订单，禁止自动释放库存");
        }
        jdbc.update("UPDATE train_seats SET bitmap = ?, version = version + 1 WHERE id = ?",
                bitmap & ~mask, order.seatId());
        jdbc.update("UPDATE orders SET status = 'CLOSED', closed_at = ? WHERE id = ?", now(), order.id());
        return true;
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
