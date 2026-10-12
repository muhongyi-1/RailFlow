"""车次查询的数据访问层。"""

from datetime import date

from sqlalchemy import select
from sqlalchemy.orm import Session, aliased

from app.models.train import Station, Train, TrainStop


def get_station_ids(
    db: Session,
    codes: list[str],
) -> dict[str, int]:
    """根据站点编码查询站点 ID。"""
    stations = db.scalars(
        select(Station).where(Station.code.in_(codes))
    ).all()

    return {
        station.code: station.id
        for station in stations
    }


def find_train_segments(
    db: Session,
    from_station_id: int,
    to_station_id: int,
    travel_date: date,
):
    # 同一张途经站表，分别作为出发站和到达站参与查询。
    origin = aliased(TrainStop, name="origin")
    destination = aliased(TrainStop, name="destination")

    stmt = (
        select(
            Train.id.label("train_id"),
            Train.train_number,
            Train.run_date,
            origin.stop_order.label("from_stop_order"),
            destination.stop_order.label("to_stop_order"),
            origin.departure_time,
            destination.arrival_time,
        )
        .select_from(Train)
        .join(
            origin,
            origin.train_id == Train.id,
        )
        .join(
            destination,
            destination.train_id == Train.id,
        )
        .where(
            Train.run_date == travel_date,
            origin.station_id == from_station_id,
            destination.station_id == to_station_id,
            # 必须先经过出发站，再经过到达站。
            origin.stop_order < destination.stop_order,
            origin.departure_time.is_not(None),
            destination.arrival_time.is_not(None),
        )
        .order_by(
            origin.departure_time,
            Train.id,
        )
    )

    return db.execute(stmt).mappings().all()


def get_stop_indexes(
    db: Session,
    train_ids: list[int],
) -> dict[tuple[int, int], int]:
    """将数据库站序转换成 Bitmap 使用的从 0 开始的位置序号。"""
    if not train_ids:
        return {}

    # 一次查询所有结果车次的途经站，避免逐车次查询。
    rows = db.execute(
        select(
            TrainStop.train_id,
            TrainStop.stop_order,
        )
        .where(TrainStop.train_id.in_(train_ids))
        .order_by(
            TrainStop.train_id,
            TrainStop.stop_order,
        )
    ).all()

    indexes = {}
    next_index = {}

    for train_id, stop_order in rows:
        index = next_index.get(train_id, 0)

        indexes[(train_id, stop_order)] = index
        next_index[train_id] = index + 1

    return indexes