# -*- coding: utf-8 -*-
# -*- coding: utf-8 -*-
"""
@File    : train.py
@Author  : 你的名字
@Time    : 2026/10/10 13:59
@Desc    : 
"""

from datetime import date, time

from sqlalchemy import (
    Date,
    ForeignKey,
    Index,
    Integer,
    String,
    Time,
    UniqueConstraint
)

from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base


# ==============================
# 1. 车站表
# ==============================
class Station(Base):
    __tablename__ = "stations"

    # 主键
    id: Mapped[int] = mapped_column(
        Integer,
        primary_key=True,
        autoincrement=True
    )

    # 站点编码，例如 BJS
    code: Mapped[str] = mapped_column(
        String(16),
        unique=True,
        nullable=False
    )

    # 站点名称，例如 北京南
    name: Mapped[str] = mapped_column(
        String(64),
        nullable=False
    )


# ==============================
# 2. 车次表
# ==============================
class Train(Base):
    __tablename__ = "trains"

    __table_args__ = (
        # 相同日期，同一个车次号不能重复
        UniqueConstraint(
            "train_number",
            "run_date",
            name="uq_train_number_date"
        ),

        # 加快按运行日期筛选的查询
        Index("ix_trains_run_date", "run_date"),
    )

    id: Mapped[int] = mapped_column(
        Integer,
        primary_key=True,
        autoincrement=True
    )

    # 车次号
    train_number: Mapped[str] = mapped_column(
        String(20),
        nullable=False
    )

    # 该趟列车的运行日期
    run_date: Mapped[date] = mapped_column(
        Date,
        nullable=False
    )


# ==============================
# 3. 途经站表
# ==============================
class TrainStop(Base):
    __tablename__ = "train_stops"

    __table_args__ = (
        # 同一趟车的途经站序号不能重复
        UniqueConstraint(
            "train_id",
            "stop_order",
            name="uq_train_stop_order"
        ),

        # 当前简化模型中，一趟车不能重复经过同一站
        UniqueConstraint(
            "train_id",
            "station_id",
            name="uq_train_stop_station"
        ),

        # 便于按照车站筛选车次
        Index(
            "ix_train_stops_station_train",
            "station_id",
            "train_id"
        ),
    )

    id: Mapped[int] = mapped_column(
        Integer,
        primary_key=True,
        autoincrement=True
    )

    # 所属车次 ID
    train_id: Mapped[int] = mapped_column(
        ForeignKey("trains.id"),
        nullable=False
    )

    # 当前车站 ID
    station_id: Mapped[int] = mapped_column(
        ForeignKey("stations.id"),
        nullable=False
    )

    # 经停顺序：1、2、3、4...
    stop_order: Mapped[int] = mapped_column(
        Integer,
        nullable=False
    )

    # 到达时间；首站可以为空
    arrival_time: Mapped[time | None] = mapped_column(
        Time,
        nullable=True
    )

    # 出发时间；终点站可以为空
    departure_time: Mapped[time | None] = mapped_column(
        Time,
        nullable=True
    )
