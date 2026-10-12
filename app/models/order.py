"""订单模型。时间统一按 UTC 存储。"""

from datetime import datetime, timedelta, timezone
from decimal import Decimal
from uuid import uuid4

from sqlalchemy import (
    CheckConstraint,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    Numeric,
    String,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base
from app.utils.order_status import OrderStatus


def utc_now() -> datetime:
    # MySQL DATETIME 不保存时区信息。
    # 本项目约定这些时间值统一表示 UTC。
    return datetime.now(timezone.utc).replace(tzinfo=None)


def default_expiry() -> datetime:
    # 默认给用户 15 分钟支付。
    return utc_now() + timedelta(minutes=15)


class Order(Base):
    __tablename__ = "orders"

    id: Mapped[int] = mapped_column(
        Integer,
        primary_key=True,
        autoincrement=True,
    )

    # 对外使用的订单号。
    order_no: Mapped[str] = mapped_column(
        String(32),
        nullable=False,
        unique=True,
        default=lambda: uuid4().hex,
    )

    user_id: Mapped[int] = mapped_column(
        Integer,
        nullable=False,
    )

    # 同一次下单的重试，使用相同 request_id。
    request_id: Mapped[str] = mapped_column(
        String(64),
        nullable=False,
    )

    train_id: Mapped[int] = mapped_column(
        ForeignKey("trains.id"),
        nullable=False,
    )

    seat_id: Mapped[int] = mapped_column(
        ForeignKey("train_seats.id"),
        nullable=False,
    )

    start_station: Mapped[int] = mapped_column(
        Integer,
        nullable=False,
    )

    end_station: Mapped[int] = mapped_column(
        Integer,
        nullable=False,
    )

    # 本订单占用了哪些区间。
    request_mask: Mapped[int] = mapped_column(
        Integer,
        nullable=False,
    )

    # 金额使用定点数，不使用 float。
    amount: Mapped[Decimal] = mapped_column(
        Numeric(10, 2),
        nullable=False,
    )

    status: Mapped[str] = mapped_column(
        String(16),
        nullable=False,
        default=OrderStatus.PENDING_PAYMENT.value,
    )

    created_at: Mapped[datetime] = mapped_column(
        DateTime,
        nullable=False,
        default=utc_now,
    )

    expires_at: Mapped[datetime] = mapped_column(
        DateTime,
        nullable=False,
        default=default_expiry,
    )

    paid_at: Mapped[datetime | None] = mapped_column(
        DateTime,
        nullable=True,
    )

    # 用户取消或系统超时关闭的时间。
    closed_at: Mapped[datetime | None] = mapped_column(
        DateTime,
        nullable=True,
    )

    __table_args__ = (
        # 同一个用户的同一次请求，最多创建一笔订单。
        UniqueConstraint(
            "user_id",
            "request_id",
            name="uq_orders_user_request",
        ),

        # 查询某个用户的订单列表。
        Index(
            "idx_orders_user_created",
            "user_id",
            "created_at",
        ),

        # Day6 查询到期的待支付订单。
        Index(
            "idx_orders_status_expires",
            "status",
            "expires_at",
        ),

        CheckConstraint(
            "user_id > 0",
            name="ck_orders_user",
        ),

        CheckConstraint(
            "start_station >= 0 "
            "AND end_station > start_station "
            "AND end_station <= 31",
            name="ck_orders_interval",
        ),

        CheckConstraint(
            "request_mask > 0",
            name="ck_orders_mask",
        ),

        CheckConstraint(
            "amount >= 0",
            name="ck_orders_amount",
        ),

        CheckConstraint(
            "status IN "
            "('PENDING_PAYMENT', 'PAID', 'CANCELED', 'CLOSED')",
            name="ck_orders_status",
        ),

        {
            "mysql_engine": "InnoDB",
            "mysql_charset": "utf8mb4",
        },
    )