# -*- coding: utf-8 -*-
# -*- coding: utf-8 -*-
"""
@File    : init_db.py
@Author  : 你的名字
@Time    : 2026/10/10 14:01
@Desc    : 
"""
from app.db.base import Base
from app.db.session import engine

# 必须导入模型，使 SQLAlchemy 注册表结构
from app import models


def main():
    # 仅创建不存在的表
    Base.metadata.create_all(bind=engine)
    print("数据库表初始化完成")


if __name__ == "__main__":
    main()