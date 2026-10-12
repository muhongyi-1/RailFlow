from sqlalchemy import (
    Integer,
    String,
    ForeignKey,
    Index
)

from sqlalchemy.orm import (
    Mapped,
    mapped_column
)

from app.db.base import Base


class TrainSeat(Base):

    """
    火车座位表

    bitmap:
        保存座位区间占用状态

    version:
        乐观锁字段
    """

    __tablename__ = "train_seats"


    id: Mapped[int] = mapped_column(
        Integer,
        primary_key=True,
        autoincrement=True
    )


    train_id: Mapped[int] = mapped_column(
        ForeignKey(
            "trains.id"
        ),
        nullable=False
    )


    coach_no: Mapped[int] = mapped_column(
        Integer,
        nullable=False
    )


    seat_no: Mapped[str] = mapped_column(
        String(10),
        nullable=False
    )


    seat_type: Mapped[str] = mapped_column(
        String(20),
        nullable=False,
        default="二等座"
    )


    # 区间位图
    bitmap: Mapped[int] = mapped_column(
        Integer,
        nullable=False,
        default=0
    )


    # 乐观锁版本
    version: Mapped[int] = mapped_column(
        Integer,
        nullable=False,
        default=0
    )


    __table_args__ = (

        Index(
            "idx_train_id",
            "train_id"
        ),

        Index(
            "idx_train_seat",
            "train_id",
            "coach_no",
            "seat_no"
        ),

    )