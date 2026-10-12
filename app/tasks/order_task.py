"""RailFlow：超时订单关单与 Bitmap 安全释放。

由定时任务使用单独的 SQLAlchemy Session 调用：
    db = SessionLocal()
    try:
        close_expired_orders(db)
    finally:
        db.close()

本模块不会强制清理历史脏数据。发现 Bitmap 缺位或有效订单区间重叠时，
会回滚当前订单并记录错误，避免错误地释放已经售出的座位。
"""

import logging

from sqlalchemy.orm import Session

from app.models.order import Order, utc_now
from app.models.seat import TrainSeat
from app.models.train import Train  # 注册 trains 元数据，供独立任务运行时使用
from app.utils.order_status import OrderStatus, can_transition

logger = logging.getLogger(__name__)

# 每轮最多尝试处理 100 笔。每笔独立事务，避免一次持有 100 笔订单锁。
BATCH_SIZE = 100
ACTIVE_STATUSES = (
    OrderStatus.PENDING_PAYMENT.value,
    OrderStatus.PAID.value,
)


def close_expired_orders(db: Session) -> int:
    """关闭已过期的待支付订单，安全释放其占用区间。

    返回本轮实际成功关闭的订单数量。

    约定：传入关单任务专用 Session，不和 API/其他业务共享 Session。
    支付流程也使用订单行锁；下单、取消和释放座位的流程都必须使用座位行锁。
    """
    # 第一步只获取候选 ID，不在查询阶段批量锁住订单。
    try:
        candidates = (
            db.query(Order.id)
            .filter(
                Order.status == OrderStatus.PENDING_PAYMENT.value,
                Order.expires_at <= utc_now(),
            )
            .order_by(Order.expires_at, Order.id)
            .limit(BATCH_SIZE)
            .all()
        )
        candidate_ids = [item.id for item in candidates]
    finally:
        # 释放只读查询所开启的事务，保证随后每笔订单用独立事务。
        # 因此此函数必须使用任务专用 Session，不能混用有待提交操作的 Session。
        db.rollback()

    closed_count = 0
    failed_count = 0

    for order_id in candidate_ids:
        try:
            # 第二步锁订单。支付确认也锁同一行，二者不能同时改变订单状态。
            order = (
                db.query(Order)
                .filter(
                    Order.id == order_id,
                    Order.status == OrderStatus.PENDING_PAYMENT.value,
                    Order.expires_at <= utc_now(),
                )
                .with_for_update(skip_locked=True)
                .first()
            )
            if order is None:
                # 已被其他任务锁住、已经付款或者已经关闭，直接跳过。
                db.rollback()
                continue

            if not can_transition(OrderStatus(order.status), OrderStatus.CLOSED):
                db.rollback()
                continue

            # 第三步锁座位。与创建订单时的 MySQL 座位行锁同步。
            seat = (
                db.query(TrainSeat)
                .filter(
                    TrainSeat.id == order.seat_id,
                    TrainSeat.train_id == order.train_id,
                )
                .with_for_update()
                .first()
            )
            if seat is None:
                raise RuntimeError(f"订单 {order.order_no} 对应座位不存在或车次不匹配")

            mask = int(order.request_mask or 0)
            bitmap = int(seat.bitmap or 0)
            if mask <= 0:
                raise RuntimeError(f"订单 {order.order_no} 的 request_mask 非法: {mask}")

            # 第四步数据一致性保护：当前订单应当仍完整占有它的所有区间位。
            if (bitmap & mask) != mask:
                raise RuntimeError(
                    f"订单 {order.order_no} 库存缺位：seat_id={seat.id}, "
                    f"bitmap={bitmap}, request_mask={mask}；请先人工对账"
                )

            # 如果存在其他有效订单占用相同区间，直接释放将误伤对方（尤其 PAID）。
            # 下单、退座均在同一座位行锁下更新；这里无需再锁其他订单行。
            conflict = (
                db.query(Order.id, Order.order_no, Order.status)
                .filter(
                    Order.seat_id == order.seat_id,
                    Order.id != order.id,
                    Order.status.in_(ACTIVE_STATUSES),
                    Order.request_mask.op("&")(mask) != 0,
                )
                .order_by(Order.id)
                .first()
            )
            if conflict is not None:
                raise RuntimeError(
                    f"订单 {order.order_no} 与另一有效订单 {conflict.order_no} "
                    f"({conflict.status}) 占座区间重叠；禁止自动释放 seat_id={seat.id}"
                )

            # 第五步同一事务内更新 Bitmap 和订单状态。
            seat.bitmap = bitmap & ~mask
            order.status = OrderStatus.CLOSED.value
            order.closed_at = utc_now()
            db.commit()
            closed_count += 1

        except Exception:
            # 只回滚当前订单，不影响已提交的其他正常关单。
            db.rollback()
            failed_count += 1
            logger.exception("[订单定时任务] 订单 id=%s 关单失败，已回滚", order_id)

    if closed_count or failed_count:
        print(
            f"[订单定时任务] 本轮关闭 {closed_count} 笔，"
            f"异常跳过 {failed_count} 笔"
        )
    return closed_count
