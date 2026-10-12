"""车次查询的业务层。"""

from datetime import date

from fastapi import HTTPException
from sqlalchemy.orm import Session

from app.repositories.train_repo import (
    find_train_segments,
    get_station_ids,
    get_stop_indexes,
)
from app.schemas.train import TrainSearchResult


def search_trains(
    db: Session,
    from_station: str,
    to_station: str,
    travel_date: date,
) -> list[TrainSearchResult]:
    # 请求参数是站点编码，不是中文站名。
    from_station = from_station.strip()
    to_station = to_station.strip()

    if not from_station or not to_station:
        raise HTTPException(
            status_code=400,
            detail="站点编码不能为空",
        )

    if from_station == to_station:
        raise HTTPException(
            status_code=400,
            detail="出发站和到达站不能相同",
        )

    station_ids = get_station_ids(
        db,
        [from_station, to_station],
    )

    missing = [
        code
        for code in (from_station, to_station)
        if code not in station_ids
    ]

    if missing:
        raise HTTPException(
            status_code=404,
            detail=f"站点编码不存在：{', '.join(missing)}",
        )

    rows = find_train_segments(
        db,
        station_ids[from_station],
        station_ids[to_station],
        travel_date,
    )

    indexes = get_stop_indexes(
        db,
        [row["train_id"] for row in rows],
    )

    return [
        TrainSearchResult(
            train_id=row["train_id"],
            train_number=row["train_number"],
            run_date=row["run_date"],
            from_station=from_station,
            to_station=to_station,
            departure_time=row["departure_time"],
            arrival_time=row["arrival_time"],
            start_station=indexes[
                (row["train_id"], row["from_stop_order"])
            ],
            end_station=indexes[
                (row["train_id"], row["to_stop_order"])
            ],
        )
        for row in rows
    ]