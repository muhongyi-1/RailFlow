# Java 迁移说明

基于原 main 提交 `9c51dde`，Java 迁移保存在独立分支 `codex/java-migration`。原 Python 版本可从 Git 历史恢复。

## 覆盖范围

| 原 Python 模块 | Java 实现 |
|---|---|
| app/main.py、app/api、app/schemas | RailFlowApplication、web、domain、payment/PaymentController |
| models、db、repositories | domain、db/DatabaseInitializer、schema.sql、Spring JDBC 查询 |
| services/train_service.py、seat_service.py | train/TrainService |
| services/order_service.py、utils | order/OrderService、common/SeatBitmap、domain/OrderStatus |
| services/payment_service.py、core/payment_mq.py、workers/payment_consumer.py | payment 包 |
| tasks、scripts/run_order_scheduler.py | order/ExpirationService、OrderScheduler、order-scheduler 命令 |
| scripts/create_tables.py、init_data.py | create-tables、init-data 命令 |
| scripts/inventory_audit.py、inventory_repair.py | maintenance/InventoryMaintenance、InventoryReconciler |
| scripts/dead_letter_audit.py、payment_close_race.py | maintenance/DeadLetterClassifier、PaymentCloseRace |
| scripts/booking_load_test.py、simulate_payment_callback.py | maintenance/MaintenanceCommands |
| tests/*.py | src/test/java 的 JUnit 测试 |
| requirements.txt、Python Docker/CI | pom.xml、Java 多阶段镜像、Maven CI |

不再保留 Python 运行代码。SQL、YAML、properties、Markdown 是数据库、配置和文档，不应改为 Java 源文件。

## 兼容性及改进

- HTTP 路径、snake_case JSON、金额字符串、UTC 订单时间、队列名保持兼容。Java 校验错误仍返回 422 和 detail，但错误细节结构与 FastAPI 不完全相同。
- 保留已有表名、字段、约束和 INT Bitmap。建表使用 CREATE TABLE IF NOT EXISTS，初始化不重置 Bitmap；不会自动修改现有数据库结构。新建 MySQL 表要求使用默认 InnoDB 引擎。
- DATABASE_URL 从 SQLAlchemy URL 改为 JDBC URL，账号密码独立配置。Java 的 .env 使用 properties 语法，无需给值加引号；有反斜杠时需转义。旧的 Python .env 必须手动迁移。
- 维护命令改用同一 JAR 的 --railflow.command，并使用 --key=value 参数形式。退出码 0 表示成功，1 表示对账异常或请求失败，2 表示参数错误/指定座位不存在。
- MySQL 事务明确使用 READ COMMITTED，防止等待座位锁后读旧快照；幂等检查不反向锁订单，避免与关单的锁顺序冲突。
- Bitmap 工具限制为 31 个非负位；关单额外验证存储掩码与区间吻合；消费者对消息结构也做校验。
- 支付发布与消费确认使用独立 channel；失败死信必须得到 broker confirm，之后才能 ACK 原消息。
- API 文档由 Springdoc 生成；/health 仅检查进程存活。

## 验证

已通过 Java 17 下 `mvn -B -ntp verify`：95 项测试，0 失败、0 错误。

覆盖车次方向/日期/零基及间隔站序、HTTP 参数和响应、幂等重放与冲突、并发抢同一座位、订单插入失败回滚、Redis 故障降级、支付截止边界、重复支付、数据库支付/关单竞态、脏库存拒绝释放、安全补位及各死信分类、HMAC、消息 ACK/DLQ/NACK 顺序。

打包 JAR 的 inventory-repair --demo、payment-close-race 命令已运行成功，无需数据库连接。

最终 JAR 已在本机临时端口实际启动：`/health` 返回 ok，`/` 返回 RailFlow，`/v3/api-docs` 返回 OpenAPI 3.1 文档（8 个路径）。非法 CLI 组合返回退出码 2；测试后临时服务已关闭。

测试数据库为 H2 MySQL 兼容模式，MQ/Redis 使用 mock。当前机器 Docker 引擎未运行，尚未执行真实 MySQL、Redis、RabbitMQ 端到端联调或 Docker 镜像构建；不能将这些测试视为生产并发正确性的证明。真实支付/鉴权/退款/Outbox 不在原项目功能范围内，迁移没有虚构这些能力。
