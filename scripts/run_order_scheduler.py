"""Run the expired-order scheduler in a dedicated process.

Usage: python -m scripts.run_order_scheduler
Run only ONE scheduler worker per environment.
"""

import time

from app.tasks.scheduler import start_scheduler, scheduler


def main() -> None:
    start_scheduler()
    print("订单超时关单定时任务已启动；按 Ctrl+C 停止")
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("正在停止订单调度器")
    finally:
        scheduler.shutdown(wait=True)


if __name__ == "__main__":
    main()
