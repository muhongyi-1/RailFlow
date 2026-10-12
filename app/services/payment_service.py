"""Payment confirmation and idempotent message processing."""
from sqlalchemy.orm import Session

from app.models.order import Order, utc_now
from app.utils.order_status import OrderStatus, can_transition


class PaymentNotApplicable(Exception):
    """永久性业务失败：订单不存在、已经关闭或支付超时。"""


def apply_payment_success(db: Session, order_no: str) -> str:
    """在事务中完成 PENDING_PAYMENT -> PAID。

    返回 PAID 或 ALREADY_PAID；其他状态抛出 PaymentNotApplicable。
    与 Day6 自动关单共用订单行锁，避免同时更改状态。
    """
    try:
        order = (
            db.query(Order)
            .filter(Order.order_no == order_no)
            .with_for_update()
            .first()
        )
        if order is None:
            raise PaymentNotApplicable(f"订单不存在：{order_no}")

        current = OrderStatus(order.status)
        if current == OrderStatus.PAID:
            # MQ 的同一消息可能重复投递；已支付的订单不重复处理
            db.rollback()
            return "ALREADY_PAID"

        if not can_transition(current, OrderStatus.PAID):
            raise PaymentNotApplicable(f"当前状态禁止支付：{order.status}")

        now = utc_now()  # 订单模型按 UTC 存储，不能用 datetime.now()
        if order.expires_at <= now:
            raise PaymentNotApplicable("订单已超过支付期限，需要人工对账/退款")

        order.status = OrderStatus.PAID.value
        order.paid_at = now
        db.commit()
        return "PAID"

    except Exception:
        db.rollback()
        raise


def pay_order(db: Session, order_no: str) -> Order:
    """保留 Day6 的同名函数，供需要的旧测试调用；Day7 新入口不直接调用它。"""
    apply_payment_success(db, order_no)
    return db.query(Order).filter(Order.order_no == order_no).first()
