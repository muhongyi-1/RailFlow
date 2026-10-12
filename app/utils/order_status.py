from enum import Enum


class OrderStatus(str, Enum):

    # 创建订单成功，等待支付
    PENDING_PAYMENT = "PENDING_PAYMENT"

    # 支付完成
    PAID = "PAID"

    # 用户取消
    CANCELED = "CANCELED"

    # 超时关闭
    CLOSED = "CLOSED"


# 状态流转规则
ALLOWED_TRANSITIONS = {

    OrderStatus.PENDING_PAYMENT: {
        OrderStatus.PAID,
        OrderStatus.CANCELED,
        OrderStatus.CLOSED,
    },

    OrderStatus.PAID: set(),

    OrderStatus.CANCELED: set(),

    OrderStatus.CLOSED: set(),
}


def can_transition(
        current: OrderStatus,
        target: OrderStatus
) -> bool:
    """
    判断订单状态是否允许变化
    """

    return target in ALLOWED_TRANSITIONS.get(
        current,
        set()
    )