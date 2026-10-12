from datetime import datetime
from decimal import Decimal

from pydantic import BaseModel, ConfigDict, Field

from app.schemas.seat_booking import SeatOccupyRequest
from app.utils.order_status import OrderStatus


class OrderCreateRequest(SeatOccupyRequest):
    # 拒绝额外字段，例如客户端擅自传入 amount。
    model_config = ConfigDict(extra="forbid")

    user_id: int = Field(gt=0)

    request_id: str = Field(
        min_length=1,
        max_length=64,
        pattern=r"^[A-Za-z0-9_-]+$",
    )


class OrderResult(BaseModel):
    # 允许从 SQLAlchemy 对象的属性构造响应。
    model_config = ConfigDict(from_attributes=True)

    id: int
    order_no: str
    user_id: int
    request_id: str

    train_id: int
    seat_id: int
    start_station: int
    end_station: int
    request_mask: int

    amount: Decimal
    status: OrderStatus

    created_at: datetime
    expires_at: datetime
    paid_at: datetime | None
    closed_at: datetime | None