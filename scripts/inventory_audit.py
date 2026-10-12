"""RailFlow：订单和座位 Bitmap 只读对账。

运行（在 RailFlow 项目根目录）：
    python -m scripts.inventory_audit
    python -m scripts.inventory_audit --seat-id 4

安全说明：本脚本只查询数据库，不会修改订单、Bitmap 或清空座位。
"""

import argparse
from collections import defaultdict

from app.db.session import SessionLocal
from app.models.order import Order, utc_now
from app.models.seat import TrainSeat
from app.models.train import Train  # 注册 trains 模型，支持独立运行


ACTIVE_STATUSES = {"PAID", "PENDING_PAYMENT"}


def audit_inventory(seat_id: int | None = None, limit: int = 20) -> int:
    """核查座位与订单一致性；发现不一致时返回 1，否则返回 0。"""
    db = SessionLocal()
    try:
        seat_query = db.query(TrainSeat)
        order_query = db.query(Order)
        if seat_id is not None:
            seat_query = seat_query.filter(TrainSeat.id == seat_id)
            order_query = order_query.filter(Order.seat_id == seat_id)

        seats = seat_query.order_by(TrainSeat.id).all()
        orders = order_query.order_by(Order.id).all()

        if seat_id is not None and not seats:
            print(f"[错误] 座位 seat_id={seat_id} 不存在")
            return 2

        seat_map = {seat.id: seat for seat in seats}
        orders_by_seat = defaultdict(list)
        active_orders = []
        for order in orders:
            if order.status in ACTIVE_STATUSES:
                orders_by_seat[order.seat_id].append(order)
                active_orders.append(order)

        problems = []
        overdue = []
        now = utc_now()

        # 检查有效订单引用和掩码是否合理。
        for order in active_orders:
            seat = seat_map.get(order.seat_id)
            if seat is None:
                problems.append(
                    f"座位缺失：订单 {order.order_no} 引用 seat_id={order.seat_id}"
                )
            elif seat.train_id != order.train_id:
                problems.append(
                    f"车次不匹配：订单 {order.order_no} train_id={order.train_id}；"
                    f"座位 {seat.id} train_id={seat.train_id}"
                )

            start = int(order.start_station)
            end = int(order.end_station)
            mask = int(order.request_mask or 0)
            if not (0 <= start < end <= 31):
                problems.append(
                    f"乘车区间非法：订单 {order.order_no}，start={start}, end={end}"
                )
            else:
                correct_mask = ((1 << end) - 1) ^ ((1 << start) - 1)
                if mask != correct_mask:
                    problems.append(
                        f"订单掩码异常：订单 {order.order_no}，"
                        f"记录掩码={mask}，根据乘车区间计算应为={correct_mask}"
                    )

            # 已过期但还没关单的订单仍应继续计入 Bitmap，不能提前释放。
            if (
                order.status == "PENDING_PAYMENT"
                and order.expires_at is not None
                and order.expires_at <= now
            ):
                overdue.append(order.order_no)

        for seat in seats:
            active = orders_by_seat[seat.id]
            expected_bitmap = 0

            # 在有效订单范围内求位或，得到理论上应占用的区间。
            for order in active:
                expected_bitmap |= int(order.request_mask or 0)

            actual_bitmap = int(seat.bitmap or 0)
            if actual_bitmap != expected_bitmap:
                missing = expected_bitmap & ~actual_bitmap
                extra = actual_bitmap & ~expected_bitmap
                problems.append(
                    f"Bitmap 不一致：seat_id={seat.id}({seat.seat_no})，"
                    f"实际={actual_bitmap}，应为={expected_bitmap}，"
                    f"缺少的位={missing}，多余的位={extra}"
                )

            # 即使实际 Bitmap 恰好等于位或值，也可能有两笔有效订单重叠。
            for index, left in enumerate(active):
                for right in active[index + 1 :]:
                    conflict_mask = int(left.request_mask) & int(right.request_mask)
                    if conflict_mask != 0:
                        problems.append(
                            f"有效订单重叠：seat_id={seat.id}({seat.seat_no})，"
                            f"订单 {left.order_no}({left.status}) 与 "
                            f"{right.order_no}({right.status})，"
                            f"重叠掩码={conflict_mask}"
                        )

        print("========== RailFlow 库存对账 ==========")
        print(f"检查座位数：{len(seats)}")
        print(f"检查订单数：{len(orders)}")
        print(f"有效订单数：{len(active_orders)}（PAID + PENDING_PAYMENT）")
        print(f"库存异常数：{len(problems)}")

        if problems:
            for issue in problems[:limit]:
                print(f"[异常] {issue}")
            if len(problems) > limit:
                print(f"... 其余 {len(problems) - limit} 条异常未展开")
        else:
            print("[通过] 没有发现 Bitmap 不一致、区间重叠或有效订单关联异常")

        if overdue:
            print(f"[提醒] 已过期但尚未关单：{len(overdue)} 笔")
            for order_no in overdue[:limit]:
                print(f"  待处理订单：{order_no}")
            print("这些订单在真正关闭之前仍然占座，本脚本不会替它们释放库存。")

        print("[安全] 本次仅执行查询，没有修改数据库。")
        return 1 if problems else 0
    finally:
        # 结束只读事务，释放数据库连接。
        db.rollback()
        db.close()


def main() -> None:
    parser = argparse.ArgumentParser(description="RailFlow 订单库存只读对账")
    parser.add_argument("--seat-id", type=int, help="只核查一个 seat_id，不传则检查全部")
    parser.add_argument("--limit", type=int, default=20, help="最多打印多少条异常")
    args = parser.parse_args()
    if args.limit <= 0:
        parser.error("--limit 必须大于 0")
    raise SystemExit(audit_inventory(seat_id=args.seat_id, limit=args.limit))


if __name__ == "__main__":
    main()
