"""车次查询响应结构。"""

from datetime import date, time

from pydantic import BaseModel, Field


class TrainSearchResult(BaseModel):
    train_id: int
    train_number: str
    run_date: date

    from_station: str
    to_station: str

    departure_time: time
    arrival_time: time

    start_station: int = Field(
        ge=0,
        description="出发站的 0 基位置序号，传给座位接口",
    )

    end_station: int = Field(
        ge=1,
        description="到达站的 0 基位置序号，传给座位接口",
    )