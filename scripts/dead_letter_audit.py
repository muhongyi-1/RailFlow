"""RailFlow：死信支付事件的只读对账。

示例：
python -m scripts.dead_letter_audit \
  --order-no 20d62625e0f14824881d1380095fce7e \
  --payment-no pay_day7_final_001 \
  --event-timestamp 1791805579

安全保证：只 SELECT MySQL；不消费/删除/重发 MQ 消息，不更新订单或 Bitmap。
"""

import argparse
from datetime import datetime, timezone


def classify_exception(status: str | None, expires_at: datetime | None, now: datetime) -> tuple[str, str]:
    """只根据订单状态分类；不能用状态推断真实资金是否已扣款。"""
    if status is None:
        return (
            "ORDER_NOT_FOUND",
            "订单不存在：先核对消息来源、数据库环境和订单号；禁止盲目补单。",
        )
    if status == "PAID":
        return (
            "ALREADY_PAID_REVIEW",
            "订单已支付：核对支付平台流水和消费者日志，排查重复通知或其他处理异常。",
        )
    if status in ("CLOSED", "CANCELED"):
        return (
            "CLOSED_PAYMENT_RECONCILE",
            "订单已关闭或取消：保留死信，向支付平台核实是否真实扣款；如已扣款，进入人工退款/补偿流程，禁止直接改 PAID。",
        )
    if status == "PENDING_PAYMENT":
        if expires_at is None:
            return (
                "INVALID_EXPIRY_REVIEW",
                "订单缺少截止时间：检查数据完整性及关单任务。",
            )
        if expires_at <= now:
            return (
                "EXPIRED_PENDING_REVIEW",
                "订单已过截止时间但仍待支付：检查关单任务及消息失败原因，先完成支付平台对账。",
            )
        return (
            "ACTIVE_PENDING_REVIEW",
            "订单仍在支付时限内：检查消费者异常日志和死信原因，确认后决定是否安全重试。",
        )
    return (
        "UNKNOWN_STATUS",
        "未知订单状态：停止自动处理，检查数据库及消息格式。",
    )


def utc_naive_from_unix(timestamp: int) -> datetime:
    """本项目的 MySQL DATETIME 约定保存不带时区信息的 UTC 时间。"""
    return datetime.fromtimestamp(timestamp, tz=timezone.utc).replace(tzinfo=None)


def audit(order_no: str, payment_no: str, event_timestamp: int) -> int:
    # 在函数内部导入，方便纯逻辑单元测试不连接数据库。
    from app.db.session import SessionLocal
    from app.models.order import Order, utc_now
    from app.models.seat import TrainSeat
    from app.models.train import Train  # noqa: F401 -- 注册 ORM 外键表

    event_at = utc_naive_from_unix(event_timestamp)
    now = utc_now()
    db = SessionLocal()
    try:
        order = db.query(Order).filter(Order.order_no == order_no).first()
        seat = None
        if order is not None:
            seat = db.query(TrainSeat).filter(TrainSeat.id == order.seat_id).first()

        status = order.status if order is not None else None
        expires_at = order.expires_at if order is not None else None
        code, recommendation = classify_exception(status, expires_at, now)

        print("========== RailFlow 死信支付对账 ==========")
        print("[安全] 只读 MySQL；不消费、不重发、不删除 RabbitMQ 消息。")
        print("[来源] 订单号、支付流水号、消息时间戳来自手工查看的死信消息。")
        print(f"订单号：{order_no}")
        print(f"消息支付流水号：{payment_no}（尚未向支付平台核验）")
        print(f"消息生成时间（UTC）：{event_at}")
        print(f"对账检查时间（UTC）：{now}")

        if order is not None:
            print(f"数据库订单状态：{order.status}")
            print(f"支付截止时间（UTC）：{order.expires_at}")
            print(f"支付写入时间（UTC）：{order.paid_at}")
            print(f"关闭时间（UTC）：{order.closed_at}")
            print(f"座位：id={order.seat_id}, 当前 bitmap={seat.bitmap if seat else '座位记录缺失'}")
            if order.expires_at is not None:
                diff_seconds = int((order.expires_at - event_at).total_seconds())
                if diff_seconds > 0:
                    print(f"消息生成于支付截止前 {diff_seconds} 秒（不代表被消费者及时处理）。")
                elif diff_seconds == 0:
                    print("消息生成时间恰好等于支付截止时间。")
                else:
                    print(f"消息生成于支付截止后 {-diff_seconds} 秒。")
        else:
            print("数据库订单：未找到")

        print(f"初步分类：{code}")
        print(f"处理建议：{recommendation}")
        print("[边界] 此脚本未读取 RabbitMQ x-death、消费者日志或支付平台真实流水；不能确定死信根因或是否扣款。")
        return 0
    finally:
        db.rollback()  # 结束只读事务，不提交任何修改
        db.close()


def main() -> None:
    parser = argparse.ArgumentParser(description="RailFlow：真实死信关联订单只读对账")
    parser.add_argument("--order-no", required=True, help="死信 payload 中的订单号")
    parser.add_argument("--payment-no", required=True, help="死信 payload 中的支付流水号")
    parser.add_argument("--event-timestamp", required=True, type=int, help="死信 payload 的 UNIX 时间戳（秒）")
    args = parser.parse_args()
    raise SystemExit(audit(args.order_no, args.payment_no, args.event_timestamp))


if __name__ == "__main__":
    main()
