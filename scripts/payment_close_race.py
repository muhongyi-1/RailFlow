"""RailFlow：支付成功与超时关单的并发竞态演练（纯内存）。

运行方式（在项目根目录）：
    python -m scripts.payment_close_race
    python -m pytest tests/test_payment_close_race.py -q

重要：这只是现有业务规则的并发模型，使用 threading.Lock 模拟 MySQL
SELECT FOR UPDATE 的串行化效果。脚本不连接数据库、RabbitMQ 或 Redis，
不能替代真实 MySQL + RabbitMQ 的集成测试。

对应真实项目：
    app/services/payment_service.py : apply_payment_success()
    app/tasks/order_task.py       : close_expired_orders()
"""

from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from threading import Barrier, Lock


# 固定基准时间，测试不依赖电脑系统时间、时区或睡眠。
BASE_TIME = datetime(2026, 10, 12, 12, 0, 0)
PENDING = "PENDING_PAYMENT"
PAID = "PAID"
CLOSED = "CLOSED"


@dataclass
class SimulatedOrder:
    """仅供演练的订单 + 库存快照，不是数据库 ORM 模型。"""

    expires_at: datetime
    request_mask: int = 3
    other_active_mask: int = 0
    status: str = PENDING
    bitmap: int | None = None
    paid_at: datetime | None = None
    closed_at: datetime | None = None
    _lock: Lock = field(default_factory=Lock, init=False, repr=False)

    def __post_init__(self) -> None:
        if self.bitmap is None:
            self.bitmap = self.request_mask | self.other_active_mask

    def payment_success(self, processed_at: datetime) -> str:
        """模拟支付消费者：锁订单 -> 查状态 -> 查截止时间 -> 提交状态。"""
        with self._lock:
            if self.status == PAID:
                return "ALREADY_PAID"
            if self.status != PENDING:
                return "REJECTED_CLOSED_RECONCILE"
            # 按现有项目业务规则：到期时刻也不再允许确认支付。
            if processed_at >= self.expires_at:
                return "REJECTED_EXPIRED_RECONCILE"
            self.status = PAID
            self.paid_at = processed_at
            # 支付成功，必须保留座位占用位。
            return "PAID"

    def close_expired(self, processed_at: datetime) -> str:
        """模拟关单任务：锁订单、检查位图和其他有效订单、同事务更新。"""
        with self._lock:
            if self.status != PENDING:
                return "SKIPPED_NOT_PENDING"
            if processed_at < self.expires_at:
                return "SKIPPED_NOT_EXPIRED"
            if self.request_mask <= 0 or (self.bitmap & self.request_mask) != self.request_mask:
                return "BLOCKED_BITMAP_MISSING"
            if self.other_active_mask & self.request_mask:
                return "BLOCKED_OVERLAPPING_ORDER"
            self.bitmap &= ~self.request_mask
            self.status = CLOSED
            self.closed_at = processed_at
            return "CLOSED"


def simultaneous_race(now: datetime) -> tuple[SimulatedOrder, str, str]:
    """两个线程同一时刻开始；用同一把内存锁模拟数据库订单行锁。"""
    order = SimulatedOrder(expires_at=BASE_TIME + timedelta(seconds=5))
    gate = Barrier(2)

    def pay() -> str:
        gate.wait()
        return order.payment_success(now)

    def close() -> str:
        gate.wait()
        return order.close_expired(now)

    with ThreadPoolExecutor(max_workers=2) as pool:
        f_pay = pool.submit(pay)
        f_close = pool.submit(close)
        pay_result = f_pay.result()
        close_result = f_close.result()
    return order, pay_result, close_result


def run_demo() -> None:
    expiry = BASE_TIME + timedelta(seconds=5)
    print("========== RailFlow 支付 / 关单竞态演练 ==========")
    print("[安全] 纯内存演练，不连接 MySQL、Redis、RabbitMQ。")

    print("\n场景1：支付在截止前完成，关单在截止后执行")
    a = SimulatedOrder(expires_at=expiry, other_active_mask=4)
    pay = a.payment_success(BASE_TIME + timedelta(seconds=4))
    close = a.close_expired(BASE_TIME + timedelta(seconds=6))
    print(f"支付={pay}, 关单={close}, 最终状态={a.status}, Bitmap={a.bitmap}（期望 7）")
    assert (pay, close, a.status, a.bitmap) == ("PAID", "SKIPPED_NOT_PENDING", PAID, 7)

    print("\n场景2：先超时关单，后收到成功支付消息")
    b = SimulatedOrder(expires_at=expiry, other_active_mask=4)
    close = b.close_expired(BASE_TIME + timedelta(seconds=6))
    pay = b.payment_success(BASE_TIME + timedelta(seconds=7))
    print(f"关单={close}, 支付={pay}, 最终状态={b.status}, Bitmap={b.bitmap}（期望 4）")
    assert (close, pay, b.status, b.bitmap) == (
        "CLOSED", "REJECTED_CLOSED_RECONCILE", CLOSED, 4
    )

    print("\n场景3：回调先入队，消费者到期后才处理")
    c = SimulatedOrder(expires_at=expiry)
    callback_received = BASE_TIME + timedelta(seconds=4)
    consumed_at = BASE_TIME + timedelta(seconds=6)
    pay = c.payment_success(consumed_at)
    print(
        f"回调到达={callback_received.time()}, 消费时间={consumed_at.time()}, "
        f"处理={pay}, 最终状态={c.status}"
    )
    assert pay == "REJECTED_EXPIRED_RECONCILE"
    print("[提醒] 外部若已经真实扣款，必须记录支付流水并对账/退款；不能把 CLOSED 强行改成 PAID。")

    print("\n场景4：截止时间之后两个线程同时竞争")
    d, pay, close = simultaneous_race(BASE_TIME + timedelta(seconds=6))
    print(f"支付={pay}, 关单={close}, 最终状态={d.status}, Bitmap={d.bitmap}（期望 0）")
    assert close == "CLOSED" and d.status == CLOSED and d.bitmap == 0
    assert pay in {"REJECTED_EXPIRED_RECONCILE", "REJECTED_CLOSED_RECONCILE"}

    print("\n场景5：重复投递相同支付成功消息")
    e = SimulatedOrder(expires_at=expiry)
    first = e.payment_success(BASE_TIME + timedelta(seconds=1))
    second = e.payment_success(BASE_TIME + timedelta(seconds=2))
    print(f"第一次={first}, 第二次={second}, 最终状态={e.status}, Bitmap={e.bitmap}")
    assert (first, second, e.status, e.bitmap) == ("PAID", "ALREADY_PAID", PAID, 3)

    print("\n[通过] 五个演练场景符合当前业务规则。")
    print("[边界] 这里只验证内存并发模型，尚未验证真实 MySQL 行锁或 RabbitMQ 的投递时序。")


if __name__ == "__main__":
    run_demo()
