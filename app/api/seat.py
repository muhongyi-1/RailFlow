"""查询指定车次、区间可售的座位；占座只能走订单创建接口。"""

from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app.db.session import get_db
from app.services.seat_service import search_available_seats

router = APIRouter(prefix="/api/v1/seats", tags=["座位查询"])


@router.get("/available")
def available_seats(
    train_id: int,
    start_station: int,
    end_station: int,
    db: Session = Depends(get_db),
):
    seats = search_available_seats(db, train_id, start_station, end_station)
    return [
        {
            "seat_id": seat.id,
            "coach_no": seat.coach_no,
            "seat_no": seat.seat_no,
            "bitmap": seat.bitmap,
        }
        for seat in seats
    ]
