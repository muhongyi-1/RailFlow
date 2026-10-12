# -*- coding: utf-8 -*-
# -*- coding: utf-8 -*-
"""
@File    : seed.py
@Author  : 你的名字
@Time    : 2026/10/10 14:02
@Desc    : 
"""

from datetime import date, time

from sqlalchemy import select

from app.db.session import SessionLocal
from app.models import Station, Train, TrainStop


def main():
    # 使用事务保存演示数据
    with SessionLocal.begin() as db:

        # 演示车站编码，并非真实铁路系统编码
        codes = {
            "BJS": "北京南",
            "JNX": "济南西",
            "NJN": "南京南",
            "SHH": "上海虹桥"
        }

        # 1. 初始化车站
        for code, name in codes.items():
            station = db.scalar(
                select(Station).where(Station.code == code)
            )

            if station is None:
                db.add(Station(code=code, name=name))

        # 将待插入车站写入数据库事务
        db.flush()

        # 建立 站点编码 -> 数据库 ID 的映射
        stations = db.scalars(select(Station)).all()

        station_id = {
            station.code: station.id
            for station in stations
        }

        # 2. 创建当天的演示车次
        today = date.today()

        train = db.scalar(
            select(Train).where(
                Train.train_number == "G1001",
                Train.run_date == today
            )
        )

        # 如果当天已经创建，则跳过
        if train is None:
            train = Train(
                train_number="G1001",
                run_date=today
            )

            db.add(train)
            db.flush()

            # 3. 定义途经站
            stops = [
                ("BJS", None, time(8, 0)),
                ("JNX", time(9, 25), time(9, 30)),
                ("NJN", time(11, 10), time(11, 15)),
                ("SHH", time(12, 30), None)
            ]

            # 4. 保存站点顺序和到发时间
            for order, (code, arrival, departure) in enumerate(
                stops,
                start=1
            ):
                db.add(
                    TrainStop(
                        train_id=train.id,
                        station_id=station_id[code],
                        stop_order=order,
                        arrival_time=arrival,
                        departure_time=departure
                    )
                )

    print("初始化演示车次数据完成")


if __name__ == "__main__":
    main()
