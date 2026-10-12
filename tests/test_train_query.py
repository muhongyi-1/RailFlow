# -*- coding: utf-8 -*-
# -*- coding: utf-8 -*-
"""
@File    : test_train_query.py
@Author  : 你的名字
@Time    : 2026/10/11 21:16
@Desc    : 
"""
"""验证车次查询规则及 Bitmap 区间衔接。"""

import unittest
from datetime import date, time, timedelta

from fastapi import HTTPException
from sqlalchemy import create_engine, delete
from sqlalchemy.orm import Session

from app.models.train import Station, Train, TrainStop
from app.services.train_service import search_trains
from app.utils.seat_bitmap import (
    build_mask,
    build_segment_mask,
    check_available,
)


class TrainQueryTests(unittest.TestCase):
    def setUp(self):
        self.engine = create_engine("sqlite:///:memory:")

        Train.metadata.create_all(
            self.engine,
            tables=[
                Station.__table__,
                Train.__table__,
                TrainStop.__table__,
            ],
        )

        self.db = Session(self.engine)
        self.day = date(2026, 10, 11)

        self.stations = [
            Station(code=code, name=code)
            for code in ["BJS", "JNN", "NJN", "SHH"]
        ]

        self.train = Train(
            train_number="G101",
            run_date=self.day,
        )

        self.db.add_all([*self.stations, self.train])
        self.db.flush()
        self.replace_stops([1, 2, 3, 4])

    def replace_stops(self, orders):
        self.db.execute(delete(TrainStop))

        for i, (station, order) in enumerate(
            zip(self.stations, orders)
        ):
            self.db.add(
                TrainStop(
                    train_id=self.train.id,
                    station_id=station.id,
                    stop_order=order,
                    arrival_time=(
                        None if i == 0 else time(8 + i * 2, 0)
                    ),
                    departure_time=(
                        None if i == 3 else time(8 + i * 2, 5)
                    ),
                )
            )

        self.db.commit()

    def tearDown(self):
        self.db.close()
        self.engine.dispose()

    def test_forward_route(self):
        result = search_trains(
            self.db, "BJS", "NJN", self.day
        )

        self.assertEqual(len(result), 1)
        self.assertEqual(result[0].train_number, "G101")
        self.assertEqual(
            (result[0].start_station, result[0].end_station),
            (0, 2),
        )
        self.assertEqual(
            build_mask(
                result[0].start_station,
                result[0].end_station,
            ),
            3,
        )

    def test_middle_route(self):
        result = search_trains(
            self.db, "JNN", "NJN", self.day
        )

        self.assertEqual(
            (result[0].start_station, result[0].end_station),
            (1, 2),
        )

    def test_reverse_route_is_empty(self):
        self.assertEqual(
            search_trains(self.db, "NJN", "BJS", self.day),
            [],
        )

    def test_other_date_is_empty(self):
        self.assertEqual(
            search_trains(
                self.db,
                "BJS",
                "SHH",
                self.day + timedelta(days=1),
            ),
            [],
        )

    def test_same_station_is_rejected(self):
        with self.assertRaises(HTTPException) as cm:
            search_trains(self.db, "BJS", "BJS", self.day)

        self.assertEqual(cm.exception.status_code, 400)

    def test_unknown_station_is_rejected(self):
        with self.assertRaises(HTTPException) as cm:
            search_trains(
                self.db, "BJS", "UNKNOWN", self.day
            )

        self.assertEqual(cm.exception.status_code, 404)

    def test_zero_based_database_orders(self):
        self.replace_stops([0, 1, 2, 3])

        result = search_trains(
            self.db, "BJS", "NJN", self.day
        )

        self.assertEqual(
            (result[0].start_station, result[0].end_station),
            (0, 2),
        )

    def test_gapped_database_orders(self):
        self.replace_stops([10, 20, 30, 40])

        result = search_trains(
            self.db, "BJS", "NJN", self.day
        )

        self.assertEqual(
            (result[0].start_station, result[0].end_station),
            (0, 2),
        )

    def test_bitmap_compatibility(self):
        self.assertEqual(
            build_mask(0, 2),
            build_segment_mask(0, 2),
        )
        self.assertFalse(
            check_available(3, build_mask(0, 2))
        )
        self.assertTrue(
            check_available(3, build_mask(2, 3))
        )

        with self.assertRaises(ValueError):
            build_mask(2, 2)


if __name__ == "__main__":
    unittest.main()