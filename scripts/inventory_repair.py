"""RailFlow：库存安全补偿（演示 / 只读预览 / 维护模式补位）。

运行：
    python -m scripts.inventory_repair --demo
    python -m scripts.inventory_repair --seat-id 4

仅在停止所有会写入订单和座位的服务后，才考虑执行补偿：
    python -m scripts.inventory_repair --seat-id 4 --apply --maintenance-mode --confirm-seat-id 4

重要约束：
- 默认仅读取数据，不修改数据库。
- 只允许补回有效订单所需的“缺失占用位”，绝不自动清除“多余占用位”。
- 遇到重叠有效订单、非法掩码、车次错配，一律拒绝自动补偿。
- 不能恢复已经被错误售出的座位；发现重叠订单必须人工处理。
"""

import argparse
from dataclasses import dataclass, field
from typing import Iterable


ACTIVE_STATUSES = frozenset({"PAID", "PENDING_PAYMENT"})


@dataclass(frozen=True)
class OrderView:
    order_no: str
    status: str
    train_id: int
    seat_id: int
    start_station: int
    end_station: int
    request_mask: int


@dataclass
class ReconcileReport:
    seat_id: int
    seat_no: str
    current: int
    expected: int = 0
    missing: int = 0
    extra: int = 0
    active_count: int = 0
    issues: list[str] = field(default_factory=list)

    @property
    def consistent(self) -> bool:
        return not self.issues and self.current == self.expected

    @property
    def safe_to_add(self) -> bool:
        # 只允许修复“缺失位”；其余所有异常均需人工处理。
        return not self.issues and self.missing != 0 and self.extra == 0


def inspect_seat(
    seat_id: int,
    seat_no: str,
    train_id: int,
    bitmap: int,
    orders: Iterable[OrderView],
) -> ReconcileReport:
    """纯 Python 对账，不连接数据库，方便单元测试与离线演示。"""
    report = ReconcileReport(seat_id=seat_id, seat_no=seat_no, current=int(bitmap))
    if report.current < 0:
        report.issues.append("座位 Bitmap 为负数，禁止自动修复")

    used_mask = 0
    previous_orders: list[OrderView] = []

    for order in orders:
        if order.status not in ACTIVE_STATUSES:
            continue

        report.active_count += 1
        if order.seat_id != seat_id or order.train_id != train_id:
            report.issues.append(f"订单 {order.order_no} 座位/车次关联错误")
            continue

        start, end = int(order.start_station), int(order.end_station)
        mask = int(order.request_mask or 0)
        if not (0 <= start < end <= 31):
            report.issues.append(f"订单 {order.order_no} 乘车区间非法：{start} -> {end}")
            continue

        calculated_mask = ((1 << end) - 1) ^ ((1 << start) - 1)
        if mask != calculated_mask:
            report.issues.append(
                f"订单 {order.order_no} 掩码错误：存储={mask}，应为={calculated_mask}"
            )
            continue

        overlap = used_mask & mask
        if overlap:
            conflicting = [o.order_no for o in previous_orders if o.request_mask & mask]
            report.issues.append(
                f"订单 {order.order_no} 与 {','.join(conflicting)} 区间重叠，掩码={overlap}"
            )

        previous_orders.append(order)
        used_mask |= mask

    report.expected = used_mask
    report.missing = report.expected & ~report.current
    report.extra = report.current & ~report.expected
    return report


def show_report(report: ReconcileReport) -> None:
    print(f"座位：{report.seat_id} ({report.seat_no})，有效订单：{report.active_count}")
    print(
        f"当前 Bitmap={report.current}，理论 Bitmap={report.expected}，"
        f"缺失位={report.missing}，多余位={report.extra}"
    )
    if report.issues:
        for issue in report.issues:
            print(f"[危险] {issue}")
    if report.consistent:
        print("[正常] 座位与订单一致，无需补偿。")
    elif report.safe_to_add:
        print(f"[可补位] 建议仅添加掩码 {report.missing}；补偿后 Bitmap={report.expected}")
        print("[提示] 当前只是分析建议，没有执行数据库写入。")
    else:
        print("[禁止自动补偿] 数据存在重叠、错误或多余位，需要人工确认。")


def run_demo() -> None:
    """纯内存模拟异常：不会连接 MySQL。"""
    paid = OrderView("paid_demo", "PAID", 1, 4, 0, 2, 3)
    duplicate = OrderView("overlap_demo", "PENDING_PAYMENT", 1, 4, 0, 2, 3)
    invalid = OrderView("bad_mask_demo", "PAID", 1, 4, 2, 3, 3)
    scenarios = [
        ("场景一：正常", 3, [paid]),
        ("场景二：丢失占用位（只允许补位）", 0, [paid]),
        ("场景三：多余占用位（禁止自动清除）", 7, [paid]),
        ("场景四：有效订单重叠（禁止自动补偿）", 3, [paid, duplicate]),
        ("场景五：订单掩码错误（禁止自动补偿）", 3, [invalid]),
    ]
    print("========== RailFlow 内存故障演练 ==========")
    for name, bitmap, records in scenarios:
        print(f"\n{name}")
        show_report(inspect_seat(4, "A03", 1, bitmap, records))
    print("\n[安全] 演练数据只存在内存中，没有连接 MySQL。")


def inspect_db_seat(seat_id: int, apply: bool = False) -> int:
    """核对 MySQL 座位；apply=True 时仅补缺失位，事务内重新检查。"""
    # 延迟导入：--demo 不依赖本地数据库配置。
    from app.db.session import SessionLocal
    from app.models.order import Order
    from app.models.seat import TrainSeat
    from app.models.train import Train  # noqa: F401 - 注册模型元数据

    db = SessionLocal()
    try:
        seat_q = db.query(TrainSeat).filter(TrainSeat.id == seat_id)
        if apply:
            # 维护模式下只锁一张座位行；所有其他写入服务必须事先停止。
            seat_q = seat_q.with_for_update()
        seat = seat_q.first()
        if seat is None:
            print(f"[错误] 座位 {seat_id} 不存在")
            return 2

        order_q = db.query(Order).filter(Order.seat_id == seat_id)
        if apply:
            # 获取最新已提交值；维护模式下应没有其他写入者。
            order_q = order_q.with_for_update()
        raw_orders = order_q.order_by(Order.id).all()
        records = [
            OrderView(
                order_no=o.order_no,
                status=o.status,
                train_id=o.train_id,
                seat_id=o.seat_id,
                start_station=o.start_station,
                end_station=o.end_station,
                request_mask=o.request_mask,
            )
            for o in raw_orders
        ]
        report = inspect_seat(seat.id, seat.seat_no, seat.train_id, seat.bitmap, records)
        show_report(report)

        if not apply:
            print("[安全] 只读预览，没有修改 MySQL。")
            return 0 if report.consistent else 1

        if report.consistent:
            print("[完成] 没有异常，不需要修改任何数据。")
            db.rollback()
            return 0
        if not report.safe_to_add:
            print("[拒绝] 存在非缺位异常，禁止写入数据库。")
            db.rollback()
            return 1

        # 修复方案采用按位或，绝不清空其他已占用区间。
        old_bitmap = int(seat.bitmap or 0)
        seat.bitmap = old_bitmap | report.missing
        db.commit()
        print(f"[已补偿] seat_id={seat_id}，Bitmap {old_bitmap} -> {report.expected}")
        return 0
    except Exception:
        db.rollback()
        raise
    finally:
        db.close()


def main() -> None:
    parser = argparse.ArgumentParser(description="RailFlow 安全库存补偿")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--demo", action="store_true", help="只用内存模拟异常，不连接 MySQL")
    group.add_argument("--seat-id", type=int, help="对指定座位进行数据库对账")
    parser.add_argument("--apply", action="store_true", help="实际补回缺失位（默认不写数据库）")
    parser.add_argument(
        "--maintenance-mode", action="store_true",
        help="声明已停止 API、支付消费者、关单定时器和所有写入任务",
    )
    parser.add_argument("--confirm-seat-id", type=int, help="应用补偿时再次确认座位 ID")
    args = parser.parse_args()

    if args.demo:
        if args.apply:
            parser.error("--demo 不能与 --apply 同时使用")
        run_demo()
        return

    if args.seat_id <= 0:
        parser.error("--seat-id 必须是正整数")
    if args.apply:
        if not args.maintenance_mode or args.confirm_seat_id != args.seat_id:
            parser.error(
                "写入前必须停止所有相关服务，并同时指定 "
                "--maintenance-mode 和 --confirm-seat-id（与 --seat-id 一致）"
            )
        print("[警告] 即将使用维护模式执行有保护条件的库存补位；请确认已停止所有写入服务。")
        confirmation = input(f"输入 REPAIR-{args.seat_id} 继续：").strip()
        if confirmation != f"REPAIR-{args.seat_id}":
            print("[取消] 未确认，未执行补偿。")
            return
    raise SystemExit(inspect_db_seat(args.seat_id, apply=args.apply))


if __name__ == "__main__":
    main()
