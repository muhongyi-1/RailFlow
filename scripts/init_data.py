"""Initialize demo train stops and seats without overwriting sold inventory.

Run after create_tables: python -m scripts.init_data
"""

from datetime import date
from sqlalchemy import select

from app.db.seed import main as seed_train_stops
from app.db.session import SessionLocal
from app.models.train import Train
from app.models.seat import TrainSeat


def main() -> None:
    seed_train_stops()
    with SessionLocal.begin() as db:
        train = db.scalar(
            select(Train).where(
                Train.train_number == "G1001", Train.run_date == date.today()
            )
        )
        if train is None:
            raise RuntimeError("初始化车次失败")
        existing = set(db.scalars(
            select(TrainSeat.seat_no).where(TrainSeat.train_id == train.id)
        ))
        for seat_no in ("A01", "A02", "A03"):
            if seat_no not in existing:
                db.add(TrainSeat(
                    train_id=train.id, coach_no=8, seat_no=seat_no,
                    seat_type="二等座", bitmap=0, version=0,
                ))
    print("演示车次和座位初始化完成（未修改已有座位 Bitmap）")


if __name__ == "__main__":
    main()
