# -*- coding: utf-8 -*-
# -*- coding: utf-8 -*-
"""
@File    : scheduler.py
@Author  : 你的名字
@Time    : 2026/10/12 15:54
@Desc    : 
"""
from apscheduler.schedulers.background import BackgroundScheduler

from app.db.session import SessionLocal
from app.tasks.order_task import close_expired_orders


scheduler = BackgroundScheduler()



def run_close_expired_orders():

    db = SessionLocal()

    try:

        count = close_expired_orders(db)

        if count:
            print(
                f"自动关闭订单数量: {count}"
            )

    finally:
        db.close()



def start_scheduler():

    scheduler.add_job(
        run_close_expired_orders,
        "interval",
        minutes=1
    )

    scheduler.start()