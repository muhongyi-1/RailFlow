"""Day9-3 并发竞态模拟测试。不连接 MySQL/RabbitMQ。"""

from datetime import timedelta

from scripts.payment_close_race import (
    BASE_TIME, CLOSED, PAID, PENDING,
    SimulatedOrder, simultaneous_race,
)

EXPIRY = BASE_TIME + timedelta(seconds=5)


def test_payment_before_deadline_wins_and_seat_remains_reserved():
    order = SimulatedOrder(expires_at=EXPIRY, other_active_mask=4)
    assert order.payment_success(BASE_TIME + timedelta(seconds=4)) == "PAID"
    assert order.close_expired(BASE_TIME + timedelta(seconds=6)) == "SKIPPED_NOT_PENDING"
    assert (order.status, order.bitmap) == (PAID, 7)


def test_close_after_deadline_wins_and_preserves_other_segment():
    order = SimulatedOrder(expires_at=EXPIRY, other_active_mask=4)
    assert order.close_expired(BASE_TIME + timedelta(seconds=6)) == "CLOSED"
    assert order.payment_success(BASE_TIME + timedelta(seconds=7)) == "REJECTED_CLOSED_RECONCILE"
    assert (order.status, order.bitmap) == (CLOSED, 4)


def test_at_exact_deadline_payment_is_rejected_and_close_allowed():
    order = SimulatedOrder(expires_at=EXPIRY)
    assert order.payment_success(EXPIRY) == "REJECTED_EXPIRED_RECONCILE"
    assert order.close_expired(EXPIRY) == "CLOSED"
    assert (order.status, order.bitmap) == (CLOSED, 0)


def test_early_close_does_not_release_reserved_seat():
    order = SimulatedOrder(expires_at=EXPIRY)
    assert order.close_expired(BASE_TIME) == "SKIPPED_NOT_EXPIRED"
    assert (order.status, order.bitmap) == (PENDING, 3)


def test_late_consumer_must_reconcile_not_mark_paid():
    order = SimulatedOrder(expires_at=EXPIRY)
    callback_received = BASE_TIME + timedelta(seconds=4)
    consume_time = BASE_TIME + timedelta(seconds=6)
    assert callback_received < EXPIRY < consume_time
    assert order.payment_success(consume_time) == "REJECTED_EXPIRED_RECONCILE"
    assert (order.status, order.bitmap) == (PENDING, 3)
    assert order.close_expired(consume_time) == "CLOSED"


def test_duplicate_payment_is_idempotent():
    order = SimulatedOrder(expires_at=EXPIRY)
    assert order.payment_success(BASE_TIME) == "PAID"
    assert order.payment_success(BASE_TIME + timedelta(seconds=2)) == "ALREADY_PAID"
    assert order.status == PAID
    assert order.bitmap == 3


def test_missing_bitmap_prevents_close_and_data_change():
    order = SimulatedOrder(expires_at=EXPIRY, bitmap=0)
    assert order.close_expired(BASE_TIME + timedelta(seconds=6)) == "BLOCKED_BITMAP_MISSING"
    assert (order.status, order.bitmap) == (PENDING, 0)


def test_overlap_prevents_close_and_data_change():
    order = SimulatedOrder(expires_at=EXPIRY, other_active_mask=3)
    assert order.close_expired(BASE_TIME + timedelta(seconds=6)) == "BLOCKED_OVERLAPPING_ORDER"
    assert (order.status, order.bitmap) == (PENDING, 3)


def test_simultaneous_threads_after_deadline_never_pay_expired_order():
    for _ in range(40):
        order, pay_result, close_result = simultaneous_race(BASE_TIME + timedelta(seconds=6))
        assert pay_result in {"REJECTED_EXPIRED_RECONCILE", "REJECTED_CLOSED_RECONCILE"}
        assert close_result == "CLOSED"
        assert (order.status, order.bitmap) == (CLOSED, 0)


def test_simultaneous_threads_before_deadline_never_close_order():
    for _ in range(40):
        order, pay_result, close_result = simultaneous_race(BASE_TIME + timedelta(seconds=4))
        assert pay_result == "PAID"
        assert close_result in {"SKIPPED_NOT_PENDING", "SKIPPED_NOT_EXPIRED"}
        assert (order.status, order.bitmap) == (PAID, 3)
