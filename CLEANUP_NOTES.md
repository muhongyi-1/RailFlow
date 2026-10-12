# RailFlow 代码整理记录

本次以用户上传的完整 ZIP 为基础，尽可能保留正式订单、支付与车次业务。

### 清理

- 过滤 `.env`、IDE 设置、`__pycache__`、Pytest 缓存与 `.pyc`（原始 ZIP 不变）。
- 删除空的非 `__init__.py` 占位模块及 2 个空测试。保留 Python 包所需的空 `__init__.py`。
- 删除不再接入主应用的旧 `POST /api/v1/seats/occupy` 及其仓储/服务/Redis Lua 锁实验，保留并注册只读 `GET /api/v1/seats/available`。
- 删除针对旧占座接口的 `check_day4_booking.py`、`check_redis_lock.py`、冗余的 `create_order_table.py` 与支付回调重复脚本。
- 将 Day8/9/10 命名替换为 `booking_load_test`、`inventory_audit`、`inventory_repair`、`payment_close_race`、`dead_letter_audit`，更新对应测试导入。`tests/day35/test_train_query.py` 改为 `tests/test_train_query.py`。
- 统一初始化入口，重复执行不覆盖已有 Bitmap；配置 UTF-8 `requirements.txt`、README、Dockerfile、`.env.example` 和 CI。

### 需要注意的兼容调整

- GitHub 版不含你的 `.env`。本地请保留原 `.env`，**新增 RABBITMQ_PASSWORD**（与现有 Docker 容器账号一致）和支付回调密钥；API 与消费者从同一 Settings 读取。
- 订单调度器需要独立运行 `python -m scripts.run_order_scheduler`（单实例）；避免在多 API 进程中重复运行定时器。
- 支付消费者的死信发布调整为持久消息加 broker confirm；若 DLQ 发布失败，原消息不会直接 ACK。
- 仍未接入真实支付与资金退款。

### 验证

- Python 源码 compileall 全部通过。
- 静态导入映射检查没有遗留旧 day 模块引用。
- 原环境基线：`40 passed`。清理版在缺少 Redis/RabbitMQ 驱动的隔离容器中，以**仅用于导入的临时假模块**执行 SQLite/纯内存测试 `40 passed`；**不代表真实 MQ/Redis 集成验证**。
- 26 个不需要任何外部驱动的测试在沙箱中直接通过。

### 本地复测

保留旧 `.env` 并填齐配置后，在原 Python 虚拟环境运行：

```powershell
python -m pytest -q
python -m scripts.inventory_audit
```

若看到 `40 passed` 且库存异常为 0，再在空闲区间上进行支付、关单等手工集成演练。切勿在现有 PAID 订单上清零 Bitmap。
