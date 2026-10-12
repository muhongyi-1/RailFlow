"""Day10-2：支付死信分类规则纯单元测试（不访问数据库）。"""
from datetime import datetime, timedelta

from scripts.dead_letter_audit import classify_exception, utc_naive_from_unix

NOW = datetime(2026, 10, 12, 12, 0, 0)


def test_closed_requires_reconciliation():
    assert classify_exception("CLOSED", NOW - timedelta(minutes=5), NOW)[0] == "CLOSED_PAYMENT_RECONCILE"


def test_canceled_requires_reconciliation():
    assert classify_exception("CANCELED", NOW, NOW)[0] == "CLOSED_PAYMENT_RECONCILE"


def test_paid_checks_duplicate_or_other_error():
    assert classify_exception("PAID", NOW, NOW)[0] == "ALREADY_PAID_REVIEW"


def test_expired_pending_order():
    assert classify_exception("PENDING_PAYMENT", NOW - timedelta(seconds=1), NOW)[0] == "EXPIRED_PENDING_REVIEW"


def test_active_pending_order():
    assert classify_exception("PENDING_PAYMENT", NOW + timedelta(seconds=1), NOW)[0] == "ACTIVE_PENDING_REVIEW"


def test_missing_order_is_not_auto_created():
    assert classify_exception(None, None, NOW)[0] == "ORDER_NOT_FOUND"


def test_timestamp_utc_conversion():
    assert utc_naive_from_unix(1791805579) == datetime(2026, 10, 12, 11, 46, 19)
