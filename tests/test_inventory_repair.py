"""Day9-2：纯内存对账和补偿决策测试，不连接 MySQL。"""

from scripts.inventory_repair import OrderView, inspect_seat


def paid_order():
    return OrderView("paid_001", "PAID", 1, 4, 0, 2, 3)


def test_consistent():
    report = inspect_seat(4, "A03", 1, 3, [paid_order()])
    assert report.consistent
    assert not report.safe_to_add


def test_missing_bits_can_be_added():
    report = inspect_seat(4, "A03", 1, 0, [paid_order()])
    assert report.expected == 3
    assert report.missing == 3
    assert report.extra == 0
    assert report.safe_to_add


def test_extra_bits_must_not_be_removed_automatically():
    report = inspect_seat(4, "A03", 1, 7, [paid_order()])
    assert report.extra == 4
    assert not report.safe_to_add


def test_overlapping_orders_must_not_be_repaired_automatically():
    other = OrderView("pending_002", "PENDING_PAYMENT", 1, 4, 0, 2, 3)
    report = inspect_seat(4, "A03", 1, 3, [paid_order(), other])
    assert report.issues
    assert not report.safe_to_add


def test_wrong_mask_must_not_be_repaired_automatically():
    broken = OrderView("bad_003", "PAID", 1, 4, 2, 3, 3)
    report = inspect_seat(4, "A03", 1, 0, [broken])
    assert report.issues
    assert not report.safe_to_add


def test_closed_order_is_not_active():
    closed = OrderView("closed_004", "CLOSED", 1, 4, 2, 3, 4)
    report = inspect_seat(4, "A03", 1, 3, [paid_order(), closed])
    assert report.consistent
    assert report.active_count == 1
