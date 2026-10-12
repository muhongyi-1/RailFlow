# -*- coding: utf-8 -*-
# -*- coding: utf-8 -*-
"""
@File    : seat_booking.py
@Author  : 你的名字
@Time    : 2026/10/11 21:50
@Desc    : 
"""
from pydantic import BaseModel, Field, model_validator


class SeatOccupyRequest(BaseModel):
    train_id: int = Field(gt=0)
    seat_id: int = Field(gt=0)

    # 当前 bitmap 使用 MySQL 有符号 INT。
    # 本阶段最多使用 31 个非负位，避免整数溢出。
    start_station: int = Field(ge=0, le=30)
    end_station: int = Field(ge=1, le=31)

    @model_validator(mode="after")
    def validate_interval(self):
        if self.end_station <= self.start_station:
            raise ValueError(
                "到达站位置必须大于出发站位置"
            )
        return self
