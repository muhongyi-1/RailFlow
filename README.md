# RailFlow — 区间库存与异步支付票务系统

基于 **Python 3.12 / FastAPI / SQLAlchemy / MySQL / Redis / RabbitMQ** 的学习型后端项目。支持车次查询、座位区间 Bitmap 库存、订单幂等、模拟支付回调、RabbitMQ 异步消费、超时关单，以及库存对账与安全补偿工具。

> 项目用于工程实践与面试展示。支付使用本地模拟平台，不涉及真实资金；不能把死信分类视为自动退款。请勿在生产环境使用示例密码。

## 核心流程

```text
GET /api/v1/trains/search  → 途经站与日期查询
GET /api/v1/seats/available → Bitmap 区间空闲判断
POST /api/v1/orders         → 幂等键 + Redis 座位锁 + MySQL 行锁
                            → 同一事务创建 PENDING_PAYMENT 订单和占座
POST /api/v1/payment/callback → HMAC-SHA256 验签 → RabbitMQ
独立 payment_consumer       → 订单行锁 + 超时/状态校验 → PAID / DLQ
独立 run_order_scheduler    → 扫描过期订单 → CLOSED + 安全释放 Bitmap
```

**库存一致性基准**：对同一座位，当前 Bitmap 应当等于所有未关闭待支付订单及已支付订单的掩码按位或。发生历史脏数据时，不能直接清零 Bitmap。

## 目录说明

```text
app/
  api/              车次、可售座位、订单、模拟支付回调
  core/             .env 设置、Redis、RabbitMQ
  db/               Session、建表、演示车次数据
  models/           Station、Train、TrainStop、TrainSeat、Order
  repositories/     查询数据库的仓储封装
  schemas/          请求与响应 Pydantic 模型
  services/         车次与订单业务、支付状态机
  tasks/            过期关单任务与调度器
  workers/          RabbitMQ 支付消费者
scripts/             初始化、对账、补偿预览、压测与演练脚本
tests/               SQLite/内存自动化测试
sql/                 库存对账 SQL
.github/workflows/    CI 自动测试
```

## 本机启动（Windows PowerShell）

1. **准备配置**。复制示例文件并设置自己本机的值（已有 RailFlow `.env` 时，不要覆盖，需补充 `RABBITMQ_PASSWORD`）：

   ```powershell
   Copy-Item .env.example .env
   ```

   把 `CHANGE_ME` 全部替换成真实本机开发值。`DATABASE_URL` 是从 Windows 访问 MySQL 的 `127.0.0.1:3307`。如密码带特殊字符，URL 中必须做 URL 编码。FastAPI、支付模拟脚本共用 `RAILFLOW_PAYMENT_CALLBACK_SECRET`；API 与消费者共用 RabbitMQ 配置。

2. **安装依赖并启动基础设施**：

   ```powershell
   python -m venv .venv
   .\.venv\Scripts\Activate.ps1
   python -m pip install -r requirements.txt
   docker compose -f docker-compose.yml up -d
   docker compose -f docker-compose.rabbitmq.yml up -d
   docker run -d --name railflow-redis -p 127.0.0.1:6379:6379 redis:7.4-alpine
   ```

   **已有同名 Docker 容器正在运行时不要重复创建**，尤其不要删除数据库卷。RabbitMQ 后台：http://127.0.0.1:15672。

3. **新环境首次初始化**（不会清除旧订单或 Bitmap）：

   ```powershell
   python -m scripts.create_tables
   python -m scripts.init_data
   ```

4. **分别开启三个终端**，使用相同项目环境：

   ```powershell
   python -m uvicorn app.main:app --host 127.0.0.1 --port 8011
   python -m app.workers.payment_consumer
   python -m scripts.run_order_scheduler
   ```

   `/docs`：http://127.0.0.1:8011/docs；`/health`：http://127.0.0.1:8011/health。**同一环境只运行一个订单调度器。**

## 自动化测试

```powershell
python -m pytest -q
```

原版项目在本机运行 **40 passed**；清理版保留原有 40 个测试，并配置 GitHub Actions 在 SQLite/内存环境执行单元测试。完整的 MySQL / Redis / RabbitMQ 端到端验证需要另外启动容器。

## 功能脚本与命名

| 命令 | 作用 | 数据影响 |
|---|---|---|
| `python -m scripts.booking_load_test --train-id 1 --seat-id 3 --start 0 --end 2 --count 20 --workers 10 --mode different` | 并发抢票实验 | **真实创建订单**，只能使用空闲测试区间 |
| `python -m scripts.inventory_audit` | 全座位订单/Bitmap 对账 | 只读 |
| `python -m scripts.inventory_repair --demo` | 模拟五种异常 | 纯内存 |
| `python -m scripts.inventory_repair --seat-id 4` | 补偿前安全预览 | 只读 |
| `python -m scripts.payment_close_race` | 支付与关单竞争演练 | 纯内存 |
| `python -m scripts.dead_letter_audit --order-no <订单号> --payment-no <流水号> --event-timestamp <UNIX秒>` | 死信关联订单对账 | 只读 |
| `python -m scripts.simulate_payment_callback <订单号> --payment-no pay_test_001` | 模拟支付通知 | 发布真实 MQ 消息 |

对账补偿的 `--apply` 属于高风险维护操作：需停掉全部写入服务、备份数据，并在确认订单合法且无重叠后执行；**严禁随意清空所有座位 Bitmap**。

## 并发与一致性设计

- **Bitmap**：用每一位表示一段相邻站点区间，同一座位允许出售不重叠的乘车区间。
- **防超卖**：Redis 座位锁减少竞争；MySQL `SELECT FOR UPDATE` 行锁和事务保障座位占用与订单创建的一致性。这里不是 Redis Lua 原子预占。
- **下单幂等**：按 `(user_id, request_id)` 联合唯一约束兜底，Redis 缓存仅作加速。
- **支付幂等**：支付成功/重复投递分别返回 `PAID` / `ALREADY_PAID`；不代表真实资金交易流水去重。
- **超时关单**：独立任务锁订单与座位，发现 Bitmap 缺位或重叠有效订单时拒绝清除库存。
- **异常支付**：死信与数据库对账只做分类和人工补偿建议，尚未接入支付平台查询与退款接口。

## 重要局限

- 当前所有支付都是模拟平台的 HMAC 通知，非真实资金结算。
- 生产环境还需支付流水表、退款、Outbox、服务鉴权、运维告警及全面压力测试。
- 在未执行真正集成测试前，不能把纯内存竞态模拟当成真实 RabbitMQ 并发正确性的证据。
- Dockerfile 仅用于构建 API 镜像，数据库/Redis/MQ 为独立基础设施，部署时需调整容器网络地址与 `DATABASE_URL`。

## 维护与安全

`.env` 不进入 Git；仅提交 `.env.example`。**如果已把真实开发密钥提交到任何公开仓库，应立即轮换。** 不要通过修改 `CLOSED → PAID` 来处理迟到支付，也不要清空历史订单及队列。
