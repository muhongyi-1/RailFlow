package com.railflow.order;

import com.railflow.common.ApiException;
import com.railflow.domain.BookingRequest;
import com.railflow.domain.Order;
import com.railflow.domain.OrderStatus;
import com.railflow.payment.PaymentNotApplicable;
import com.railflow.payment.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderServiceTest {
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private OrderService orders;
    private ExpirationService expiration;

    @BeforeEach
    void setUp() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:orders-" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        orders = new OrderService(jdbc, manager, null, false);
        expiration = new ExpirationService(jdbc, manager);
        jdbc.update("INSERT INTO trains(id, train_number, run_date) VALUES (1,'G1','2026-10-31'),(2,'G2','2026-10-31')");
        jdbc.update("""
                INSERT INTO train_seats(id,train_id,coach_no,seat_no,seat_type,bitmap,version)
                VALUES (1,1,1,'01A','二等座',0,0),(2,1,1,'01B','二等座',0,0)
                """);
    }

    private BookingRequest request(long user, String request, long seat, int start, int end) {
        return new BookingRequest(user, request, 1, seat, start, end);
    }

    private int bitmap(long seat) {
        return jdbc.queryForObject("SELECT bitmap FROM train_seats WHERE id=?", Integer.class, seat);
    }

    private void expire(Order order) {
        jdbc.update("UPDATE orders SET expires_at=? WHERE id=?", LocalDateTime.now(ZoneOffset.UTC).minusSeconds(5), order.id());
    }

    @Test
    void createsAtomicReservationAndReplaysFromDatabaseWithoutRedis() {
        var request = request(11, "stable", 1, 0, 2);
        var created = orders.create(request);
        var replay = orders.create(request);
        assertThat(created.orderNo()).hasSize(32);
        assertThat(created.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(created.expiresAt()).isEqualTo(created.createdAt().plusMinutes(15));
        assertThat(created.amount()).isEqualByComparingTo("0.00");
        assertThat(replay).isEqualTo(created);
        assertThat(bitmap(1)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isEqualTo(1);
    }

    @Test
    void changedParametersForSameUserAndRequestAreRejected() {
        orders.create(request(11, "stable", 1, 0, 2));
        assertThatThrownBy(() -> orders.create(request(11, "stable", 2, 0, 2)))
                .isInstanceOf(ApiException.class).extracting("status").isEqualTo(409);
        assertThat(bitmap(2)).isZero();
    }

    @Test
    void idempotencyIsScopedToUserAndOrderQueryDoesNotExposeAnotherUsersOrder() {
        var first = orders.create(request(11, "same", 1, 0, 1));
        var second = orders.create(request(12, "same", 2, 0, 1));
        assertThat(second.orderNo()).isNotEqualTo(first.orderNo());
        assertThatThrownBy(() -> orders.query(12, first.orderNo())).isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(404);
    }

    @Test
    void overlappingIntervalsConflictButAdjacentIntervalsShareASeat() {
        orders.create(request(11, "first", 1, 0, 2));
        orders.create(request(12, "adjacent", 1, 2, 3));
        assertThatThrownBy(() -> orders.create(request(13, "overlap", 1, 1, 3)))
                .isInstanceOf(ApiException.class).extracting("status").isEqualTo(409);
        assertThat(bitmap(1)).isEqualTo(7);
    }

    @Test
    void seatMustBelongToRequestedTrain() {
        assertThatThrownBy(() -> orders.create(new BookingRequest(1, "wrong-train", 2, 1, 0, 1)))
                .isInstanceOf(ApiException.class).extracting("status").isEqualTo(404);
        assertThat(bitmap(1)).isZero();
    }

    @Test
    void databaseFailureRollsBackBitmapUpdate() {
        jdbc.execute("ALTER TABLE orders ADD CONSTRAINT reject_demo_zero_price CHECK(amount > 0)");
        assertThatThrownBy(() -> orders.create(request(11, "insert-fails", 1, 0, 2)))
                .isInstanceOf(ApiException.class);
        assertThat(bitmap(1)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isZero();
    }

    @Test
    void concurrentBuyersCannotOversellWithoutRedis() throws Exception {
        var results = concurrently(16, i -> () -> {
            try {
                orders.create(request(i + 1, "race-" + i, 1, 0, 2));
                return 200;
            } catch (ApiException ex) {
                return ex.status();
            }
        });
        assertThat(results).filteredOn(code -> code == 200).hasSize(1);
        assertThat(results).filteredOn(code -> code == 409).hasSize(15);
        assertThat(bitmap(1)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentSameRequestCreatesOneOrderAndReturnsItsIdentity() throws Exception {
        var results = concurrently(12, i -> () -> orders.create(request(11, "one-request", 1, 0, 2)).orderNo());
        assertThat(results.stream().distinct().count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isEqualTo(1);
    }

    @Test
    void sameIdempotencyKeyOnDifferentSeatsDoesNotLeakLosingReservation() throws Exception {
        var results = concurrently(2, i -> () -> {
            try {
                orders.create(request(11, "same-request", i + 1, 0, 2));
                return 200;
            } catch (ApiException ex) {
                return ex.status();
            }
        });
        assertThat(results).containsExactlyInAnyOrder(200, 409);
        assertThat(bitmap(1) + bitmap(2)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisOutageFallsBackToDatabase() {
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("offline"));
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("offline"));
        doThrow(new RedisConnectionFailureException("offline")).when(values).set(anyString(), anyString(), any(Duration.class));
        var withRedis = new OrderService(jdbc, manager, redis, true);
        var order = withRedis.create(request(11, "redis-down", 1, 0, 2));
        assertThat(withRedis.query(11, order.orderNo())).isEqualTo(order);
        assertThat(bitmap(1)).isEqualTo(3);
    }

    @Test
    void closesExpiredOrderAndPreservesOtherActiveSegments() {
        var expired = orders.create(request(11, "expired", 1, 0, 2));
        var active = orders.create(request(12, "active", 1, 2, 3));
        expire(expired);
        assertThat(expiration.closeExpiredOrders()).isEqualTo(1);
        assertThat(bitmap(1)).isEqualTo(4);
        assertThat(orders.query(11, expired.orderNo()).status()).isEqualTo(OrderStatus.CLOSED);
        assertThat(orders.query(11, expired.orderNo()).closedAt()).isNotNull();
        assertThat(orders.query(12, active.orderNo()).status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(expiration.closeExpiredOrders()).isZero();
    }

    @Test
    void doesNotClosePaidOrUnexpiredOrders() {
        var paid = orders.create(request(11, "paid", 1, 0, 2));
        orders.create(request(12, "fresh", 2, 0, 2));
        new PaymentService(jdbc, manager).applyPaymentSuccess(paid.orderNo());
        expire(paid);
        assertThat(expiration.closeExpiredOrders()).isZero();
        assertThat(bitmap(1)).isEqualTo(3);
        assertThat(bitmap(2)).isEqualTo(3);
    }

    @Test
    void refusesToReleaseMissingBitmapAndStillClosesOtherHealthyOrders() {
        var broken = orders.create(request(11, "broken", 1, 0, 2));
        var healthy = orders.create(request(12, "healthy", 2, 0, 2));
        expire(broken);
        expire(healthy);
        jdbc.update("UPDATE train_seats SET bitmap=1 WHERE id=1");
        assertThat(expiration.closeExpiredOrders()).isEqualTo(1);
        assertThat(bitmap(1)).isEqualTo(1);
        assertThat(bitmap(2)).isZero();
        assertThat(orders.query(11, broken.orderNo()).status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    @Test
    void refusesToReleaseOverlappingPaidOrder() {
        var expired = orders.create(request(11, "expired", 1, 0, 2));
        var paid = orders.create(request(12, "paid", 1, 2, 3));
        new PaymentService(jdbc, manager).applyPaymentSuccess(paid.orderNo());
        // Reproduce historical dirty data while leaving the occupied bitmap in place.
        jdbc.update("UPDATE orders SET request_mask=2, start_station=1, end_station=2 WHERE id=?", paid.id());
        expire(expired);
        assertThat(expiration.closeExpiredOrders()).isZero();
        assertThat(bitmap(1)).isEqualTo(7);
        assertThat(orders.query(11, expired.orderNo()).status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    @Test
    void refusesToReleaseOrderWhoseStoredMaskDoesNotMatchInterval() {
        var broken = orders.create(request(11, "wrong-mask", 1, 0, 2));
        expire(broken);
        jdbc.update("UPDATE orders SET request_mask=1 WHERE id=?", broken.id());
        assertThat(expiration.closeExpiredOrders()).isZero();
        assertThat(bitmap(1)).isEqualTo(3);
    }

    @Test
    void exactExpiryBoundaryCanBeClosed() {
        var instant = Instant.parse("2026-10-31T08:00:00Z");
        var fixed = new OrderService(jdbc, manager, null, false, Clock.fixed(instant, ZoneOffset.UTC));
        var created = fixed.create(request(11, "deadline", 1, 0, 1));
        var atDeadline = new ExpirationService(jdbc, manager, Clock.fixed(instant.plusSeconds(900), ZoneOffset.UTC));
        assertThat(atDeadline.closeExpiredOrders()).isEqualTo(1);
        assertThat(orders.query(11, created.orderNo()).status()).isEqualTo(OrderStatus.CLOSED);
    }

    @RepeatedTest(20)
    void realDatabasePaymentCloseRaceNeverPaysAnExpiredOrder() throws Exception {
        var order = orders.create(request(11, "pay-close", 1, 0, 2));
        expire(order);
        var payment = new PaymentService(jdbc, manager);
        var results = concurrently(2, i -> () -> {
            if (i == 0) {
                try {
                    return payment.applyPaymentSuccess(order.orderNo());
                } catch (PaymentNotApplicable ex) {
                    return "REJECTED";
                }
            }
            return "CLOSED_" + expiration.closeExpiredOrders();
        });
        assertThat(results).contains("REJECTED");
        String closeResult = results.get(1);
        assertThat(closeResult).isIn("CLOSED_0", "CLOSED_1");
        int firstScanClosed = closeResult.equals("CLOSED_1") ? 1 : 0;
        // SKIP LOCKED may defer the order while payment holds its lock. Both workers
        // have completed now, so one more scan must close it exactly once overall.
        assertThat(orders.query(11, order.orderNo()).status()).isEqualTo(
                firstScanClosed == 1 ? OrderStatus.CLOSED : OrderStatus.PENDING_PAYMENT);
        assertThat(bitmap(1)).isEqualTo(firstScanClosed == 1 ? 0 : 3);
        assertThat(firstScanClosed + expiration.closeExpiredOrders()).isEqualTo(1);
        assertThat(orders.query(11, order.orderNo()).status()).isEqualTo(OrderStatus.CLOSED);
        assertThat(bitmap(1)).isZero();
        assertThat(expiration.closeExpiredOrders()).isZero();
    }

    @Test
    void lockedExpiredOrderKeepsInventoryUntilNextScanAfterUnlock() throws Exception {
        var order = orders.create(request(11, "locked-expired", 1, 0, 2));
        expire(order);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var holder = pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
                jdbc.queryForObject("SELECT id FROM orders WHERE id=? FOR UPDATE", Long.class, order.id());
                locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Lock release timed out");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(expiration.closeExpiredOrders()).isZero();
            assertThat(orders.query(11, order.orderNo()).status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
            assertThat(bitmap(1)).isEqualTo(3);
            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
            assertThat(expiration.closeExpiredOrders()).isEqualTo(1);
            assertThat(orders.query(11, order.orderNo()).status()).isEqualTo(OrderStatus.CLOSED);
            assertThat(bitmap(1)).isZero();
            assertThat(expiration.closeExpiredOrders()).isZero();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private <T> List<T> concurrently(int count, java.util.function.IntFunction<Callable<T>> job) throws Exception {
        var pool = Executors.newFixedThreadPool(count);
        var gate = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                var task = job.apply(i);
                futures.add(pool.submit(() -> {
                    gate.await();
                    return task.call();
                }));
            }
            gate.countDown();
            List<T> results = new ArrayList<>();
            for (var future : futures) results.add(future.get(20, TimeUnit.SECONDS));
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
