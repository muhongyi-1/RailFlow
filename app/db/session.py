# -*- coding: utf-8 -*-
# -*- coding: utf-8 -*-
"""
@File    : session.py
@Author  : 你的名字
@Time    : 2026/10/10 13:53
@Desc    : 
"""

from collections.abc import Generator

from sqlalchemy import create_engine
from sqlalchemy.orm import Session, sessionmaker

from app.core.config import settings


# Engine 管理数据库连接池
engine = create_engine(
    settings.database_url,
    pool_pre_ping=True
)

# Session 工厂，用于创建数据库会话
SessionLocal = sessionmaker(
    bind=engine,
    autoflush=False
)


def get_db() -> Generator[Session, None, None]:
    """
    给每个请求提供独立的数据库 Session。

    请求结束后自动关闭 Session，
    将其持有的连接归还给连接池。
    """
    with SessionLocal() as session:
        yield session
