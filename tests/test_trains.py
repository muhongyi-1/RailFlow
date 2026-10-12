# -*- coding: utf-8 -*-
# -*- coding: utf-8 -*-
"""
@File    : test_trains.py
@Author  : 你的名字
@Time    : 2026/10/10 15:39
@Desc    : 
"""

from datetime import date, time

import pytest

from fastapi.testclient import TestClient

from sqlalchemy import create_engine
from sqlalchemy.orm import Session
from sqlalchemy.pool import StaticPool

from app.db.base import Base
from app.db.session import get_db
from app.main import app
from app.models import Station, Train, TrainStop


@pytest.fixture
def client():
    """
    每个测试使用独立的内存数据库，
    不会污染真正的 MySQL 数据。
    """

    test_engine = create_engine(
        "sqlite+pysqlite://",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool
    )

    # 创建测试表
    Base.metadata.create_all(test_engine)

    # 插入测试数据
    with Session(test_engine) as db:
        stations = [
            Station(code="BJS", name="北京南"),
            Station(code="JNX", name="济南西"),
            Station(code="SHH", name="上海虹桥")
        ]

        db.add_all(stations)
        db.flush()

        train = Train(
            train_number="G1001",
            run_date=date(2026, 10, 10)
        )

        db.add(train)
        db.flush()

        db.add_all([
            TrainStop(
                train_id=train.id,
                station_id=stations[0].id,
                stop_order=1,
                departure_time=time(8, 0)
            ),
            TrainStop(
                train_id=train.id,
                station_id=stations[1].id,
                stop_order=2,
                arrival_time=time(9, 25),
                departure_time=time(9, 30)
            ),
            TrainStop(
                train_id=train.id,
                station_id=stations[2].id,
                stop_order=3,
                arrival_time=time(12, 30)
            )
        ])

        db.commit()

    # 用测试 Session 替换正式数据库依赖
    def override_get_db():
        with Session(test_engine) as session:
            yield session

    app.dependency_overrides[get_db] = override_get_db

    with TestClient(app) as test_client:
        yield test_client

    # 测试结束，清理依赖覆盖
    app.dependency_overrides.clear()
    test_engine.dispose()


def test_health(client):
    response = client.get("/health")
    assert response.status_code == 200


def test_search_forward(client):
    response = client.get(
        "/api/v1/trains/search",
        params={
            "from_station": "BJS",
            "to_station": "SHH",
            "date": "2026-10-10"
        }
    )

    assert response.status_code == 200
    assert len(response.json()) == 1
    assert response.json()[0]["train_number"] == "G1001"


def test_search_reverse_empty(client):
    response = client.get(
        "/api/v1/trains/search",
        params={
            "from_station": "SHH",
            "to_station": "BJS",
            "date": "2026-10-10"
        }
    )

    assert response.status_code == 200
    assert response.json() == []


def test_search_same_station(client):
    response = client.get(
        "/api/v1/trains/search",
        params={
            "from_station": "BJS",
            "to_station": "BJS",
            "date": "2026-10-10"
        }
    )

    assert response.status_code == 400


def test_search_unknown_station(client):
    response = client.get(
        "/api/v1/trains/search",
        params={
            "from_station": "BJS",
            "to_station": "XXX",
            "date": "2026-10-10"
        }
    )

    assert response.status_code == 404
