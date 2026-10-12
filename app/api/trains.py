from datetime import date

from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app.db.session import get_db
from app.services.train_service import search_trains


router = APIRouter(
    prefix="/api/v1/trains",
    tags=["车次查询"]
)


@router.get("/search")
def search_train(
    from_station: str,
    to_station: str,
    date: date,
    db: Session = Depends(get_db),
):
    """
    查询车次

    参数:
    from_station:
        出发站编码

    to_station:
        到达站编码

    date:
        出发日期
    """

    return search_trains(
        db=db,
        from_station=from_station,
        to_station=to_station,
        travel_date=date,
    )