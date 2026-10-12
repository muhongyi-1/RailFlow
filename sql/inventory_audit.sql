-- 1. 查看座位 Bitmap 与数据库真实订单占用是否一致。
-- PENDING_PAYMENT 即使已过期，只要尚未被关单，仍需要计入当前占用。
SELECT
    s.id AS seat_id,
    s.seat_no,
    s.bitmap AS current_bitmap,
    COALESCE(BIT_OR(CASE WHEN o.status IN ('PAID', 'PENDING_PAYMENT')
             THEN o.request_mask ELSE 0 END), 0) AS expected_bitmap
FROM train_seats s
LEFT JOIN orders o ON o.seat_id = s.id
GROUP BY s.id, s.seat_no, s.bitmap
ORDER BY s.id;

-- 2. 找出不同有效订单是否占用了同一座位的重叠区间。
SELECT
    a.seat_id,
    a.order_no AS order_a,
    b.order_no AS order_b,
    (a.request_mask & b.request_mask) AS overlap_mask
FROM orders a
JOIN orders b ON a.seat_id = b.seat_id AND a.id < b.id
WHERE a.status IN ('PAID', 'PENDING_PAYMENT')
  AND b.status IN ('PAID', 'PENDING_PAYMENT')
  AND (a.request_mask & b.request_mask) <> 0;

-- 3. 确认 MySQL 唯一索引存在。
SHOW INDEX FROM orders WHERE Key_name = 'uq_orders_user_request';
