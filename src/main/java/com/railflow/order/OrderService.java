package com.railflow.order;

import com.railflow.common.ApiException;
import com.railflow.common.SeatBitmap;
import com.railflow.domain.BookingRequest;
import com.railflow.domain.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
public class OrderService {
    private static final Logger LOG = LoggerFactory.getLogger(OrderService.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final StringRedisTemplate redis;
    private final boolean redisEnabled;
    private final Clock clock;

    @Autowired
    public OrderService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                        StringRedisTemplate redis,
                        @Value("${railflow.redis.enabled:true}") boolean redisEnabled) {
        this(jdbc, transactionManager, redis, redisEnabled, Clock.systemUTC());
    }

    public OrderService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                        StringRedisTemplate redis, boolean redisEnabled, Clock clock) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.redisEnabled = redisEnabled && redis != null;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Order create(BookingRequest request) {
        validate(request);
        int mask = SeatBitmap.mask(request.startStation(), request.endStation());
        // Redis only provides a lookup hint. Database identity and request parameters remain authoritative.
        Order cached = cachedOrder(request);
        if (cached != null) return validateReplay(cached, request);
        Order existing = findByRequest(request);
        if (existing != null) return validateReplay(existing, request);

        String lockKey = "lock:railflow:seat:" + request.trainId() + ":" + request.seatId();
        String token = acquireLock(lockKey);
        try {
            Order result;
            try {
                result = transaction.execute(status -> createInTransaction(request, mask));
            } catch (DataIntegrityViolationException ex) {
                // A competing request may have used the same idempotency key on another seat.
                // The failed seat update has already rolled back before this fresh read.
                Order winner = findByRequest(request);
                if (winner == null) throw new ApiException(409, "订单创建冲突，请重试");
                result = validateReplay(winner, request);
            }
            cacheOrder(request, result);
            return result;
        } finally {
            releaseLock(lockKey, token);
        }
    }

    private Order createInTransaction(BookingRequest request, int mask) {
        List<Integer> bitmaps = jdbc.query(
                "SELECT bitmap FROM train_seats WHERE id = ? AND train_id = ? FOR UPDATE",
                (rs, n) -> rs.getInt(1), request.seatId(), request.trainId());
        if (bitmaps.isEmpty()) throw new ApiException(404, "指定车次的座位不存在");

        // The seat lock serializes occupancy. READ_COMMITTED sees a predecessor's committed order.
        // No order row lock is needed for this immutable replay check, avoiding a lock-order inversion
        // with expiration (which locks an existing order before locking its seat).
        Order existing = findByRequest(request);
        if (existing != null) return validateReplay(existing, request);
        int bitmap = bitmaps.get(0);
        if (!SeatBitmap.available(bitmap, mask)) throw new ApiException(409, "该座位当前区间已经占用");

        String orderNo = UUID.randomUUID().toString().replace("-", "");
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        jdbc.update("UPDATE train_seats SET bitmap = ?, version = version + 1 WHERE id = ?",
                bitmap | mask, request.seatId());
        jdbc.update("""
                INSERT INTO orders
                  (order_no, user_id, request_id, train_id, seat_id, start_station, end_station,
                   request_mask, amount, status, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 'PENDING_PAYMENT', ?, ?)
                """, orderNo, request.userId(), request.requestId(), request.trainId(), request.seatId(),
                request.startStation(), request.endStation(), mask, now, now.plusMinutes(15));
        return jdbc.queryForObject("SELECT * FROM orders WHERE order_no = ?", Order.MAPPER, orderNo);
    }

    public Order query(long userId, String orderNo) {
        if (userId <= 0) throw new ApiException(422, "user_id 必须大于 0");
        List<Order> orders = jdbc.query("SELECT * FROM orders WHERE user_id = ? AND order_no = ?",
                Order.MAPPER, userId, orderNo);
        if (orders.isEmpty()) throw new ApiException(404, "订单不存在");
        return orders.get(0);
    }

    private void validate(BookingRequest request) {
        if (request == null || request.userId() <= 0 || request.trainId() <= 0 || request.seatId() <= 0
                || request.requestId() == null || !request.requestId().matches("[A-Za-z0-9_-]{1,64}")) {
            throw new ApiException(422, "下单参数无效");
        }
    }

    private Order findByRequest(BookingRequest request) {
        List<Order> orders = jdbc.query("SELECT * FROM orders WHERE user_id = ? AND request_id = ?",
                Order.MAPPER, request.userId(), request.requestId());
        return orders.isEmpty() ? null : orders.get(0);
    }

    private Order validateReplay(Order old, BookingRequest request) {
        if (old.trainId() != request.trainId() || old.seatId() != request.seatId()
                || old.startStation() != request.startStation() || old.endStation() != request.endStation()) {
            throw new ApiException(409, "request_id 已被其他下单参数使用");
        }
        return old;
    }

    public static String idempotencyKey(long userId, String requestId) {
        return "order:request:" + userId + ":" + requestId;
    }

    private Order cachedOrder(BookingRequest request) {
        if (!redisEnabled) return null;
        String orderNo;
        try {
            orderNo = redis.opsForValue().get(idempotencyKey(request.userId(), request.requestId()));
        } catch (DataAccessException ex) {
            LOG.warn("Redis 幂等缓存不可用，改用数据库");
            return null;
        }
        if (orderNo == null) return null;
        List<Order> orders = jdbc.query(
                "SELECT * FROM orders WHERE order_no = ? AND user_id = ? AND request_id = ?",
                Order.MAPPER, orderNo, request.userId(), request.requestId());
        return orders.isEmpty() ? null : orders.get(0);
    }

    private void cacheOrder(BookingRequest request, Order order) {
        if (!redisEnabled) return;
        try {
            redis.opsForValue().set(idempotencyKey(request.userId(), request.requestId()), order.orderNo(),
                    Duration.ofHours(1));
        } catch (DataAccessException ex) {
            LOG.warn("订单已提交，Redis 缓存写入失败：{}", order.orderNo());
        }
    }

    /** Null means Redis was disabled/unavailable. A busy live lock instead returns HTTP 429. */
    private String acquireLock(String key) {
        if (!redisEnabled) return null;
        String token = UUID.randomUUID().toString();
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        try {
            do {
                if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, token, Duration.ofSeconds(30)))) {
                    return token;
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new ApiException(503, "下单请求已中断，请重试");
                }
            } while (System.nanoTime() < deadline);
        } catch (DataAccessException ex) {
            LOG.warn("Redis 座位锁不可用，改用数据库行锁");
            return null;
        }
        throw new ApiException(429, "座位抢购繁忙，请稍后重试");
    }

    private void releaseLock(String key, String token) {
        if (token == null) return;
        try {
            redis.execute(RELEASE, List.of(key), token);
        } catch (DataAccessException ex) {
            LOG.warn("Redis 座位锁释放失败，将由租约自动过期");
        }
    }
}
