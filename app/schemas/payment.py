"""Day7 模拟支付平台通知的数据结构。"""
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class PaymentCallbackRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    order_no: str = Field(min_length=1, max_length=32, pattern=r"^[a-fA-F0-9]{32}$")
    payment_no: str = Field(min_length=1, max_length=64, pattern=r"^[A-Za-z0-9_-]+$")
    status: Literal["SUCCESS"]
    timestamp: int = Field(gt=0, description="Unix 秒时间戳")


class PaymentCallbackResponse(BaseModel):
    accepted: bool
    message: str
    payment_no: str
