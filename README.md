# RailFlow — Java 区间库存与异步支付票务系统

Java 17 / Spring Boot 3.5 / Spring JDBC / MySQL / Redis / RabbitMQ。应用、消费者、调度器、运维脚本及测试均迁移为 Java，无需 Python 环境。支付仍是模拟 HMAC 通知。

## 构建与启动

准备 JDK 17+、Maven 3.6.3+，JAVA_HOME 指向 JDK。

```powershell
mvn -B verify
Copy-Item .env.example .env
```

把所有 CHANGE_ME 替换为开发配置。已有 .env 请手动迁移，不要覆盖。DATABASE_URL 改成 JDBC URL，用户名和密码分别使用 MYSQL_USER、MYSQL_PASSWORD。应用读取当前目录 .env（Java properties 格式，值无需引号，反斜杠需转义），也支持环境变量。

```powershell
docker compose up -d
docker compose -f docker-compose.rabbitmq.yml up -d
java -jar target/railflow-2.0.0.jar --railflow.command=create-tables
java -jar target/railflow-2.0.0.jar --railflow.command=init-data
```

已有同名容器时复用现有服务及数据卷。建表和演示数据初始化不清空已有订单或 Bitmap。演示车次为当日 G1001。

三个终端分别启动 API、支付消费者、关单调度器：

```powershell
java -jar target/railflow-2.0.0.jar
java -jar target/railflow-2.0.0.jar --railflow.command=payment-consumer
java -jar target/railflow-2.0.0.jar --railflow.command=order-scheduler
```

API 默认监听 127.0.0.1:8011；[Swagger UI](http://127.0.0.1:8011/docs)，OpenAPI 位于 /v3/api-docs。消费者和调度器不启动 HTTP 端口。同一环境运行一个调度器，API 默认不启动后台任务。

## 接口

| 接口 | 功能 |
|---|---|
| GET /api/v1/trains/search?from_station=BJS&to_station=SHH&date=2026-10-31 | 日期、途经站查询 |
| GET /api/v1/seats/available?train_id=1&start_station=0&end_station=2 | 区间空闲座位 |
| POST /api/v1/orders | 幂等下单与事务占座 |
| GET /api/v1/orders/{order_no}?user_id=1 | 用户订单查询 |
| POST /api/v1/payment/callback | 原始请求体验签、时间戳校验、可靠入队 |
| POST /api/v1/payment/{order_no} | 旧模拟直付，默认关闭 |
| GET /health、GET / | 存活检查、服务信息 |

JSON 保留 snake_case 字段，金额是十进制字符串，订单时间为 UTC。区间是 [start_station,end_station)，最多 31 位。区间值是沿途站点零基位置，不是车站主键；车次查询会返回这些位置。

下单请求（先查询实际车次和座位 ID）：

```json
{"user_id":1,"request_id":"booking_001","train_id":1,"seat_id":1,"start_station":0,"end_station":2}
```

同一用户重试需使用相同 request_id 和业务参数，改变参数返回 409。客户端不能指定金额。

## Java 运维命令

统一入口：`java -jar target/railflow-2.0.0.jar --railflow.command=<命令>`。选项用 --key=value；布尔开关可只写 --demo。

| 命令及附加选项 | 用途及数据影响 |
|---|---|
| inventory-audit | 全座位只读对账 |
| inventory-repair --demo | 纯内存五类异常演示 |
| inventory-repair --seat-id=4 | 指定座位只读补偿预览 |
| payment-close-race | 纯内存支付/关单顺序演练 |
| dead-letter-audit --order-no=<订单号> --payment-no=<流水号> --event-timestamp=<UNIX秒> | 只读订单分类，不消费 MQ 消息 |
| simulate-payment-callback --order-no=<订单号> --payment-no=pay_test_001 | 签名模拟通知，会发布 MQ 消息 |
| booking-load-test --train-id=1 --seat-id=3 --start=0 --end=2 --count=20 --workers=10 --mode=different | 并发创建真实测试订单，使用空闲测试区间 |

inventory-repair --apply 只补回明确缺失位，拒绝重叠、非法订单和多余占位。需停掉所有写入进程、备份数据，指定 --maintenance-mode --confirm-seat-id=<同一座位> 并按终端提示确认。不会清空 Bitmap，也不会自动退款。

## 一致性设计

- 占座和订单同一事务提交；MySQL 座位行锁及 (user_id,request_id) 唯一约束提供最终保障。Redis token 锁和缓存属于优化，可故障降级。
- READ COMMITTED 避免等待行锁后读取旧快照。每笔过期订单独立事务，关单前检查完整占位及有效订单重叠。
- 支付和关单锁同一订单行；关闭订单不再支付；重复成功通知返回 ALREADY_PAID。
- RabbitMQ 消息持久化，发布确认后才返回 202；失败事件先确认死信发布再 ACK，死信发布失败则 NACK 重入队。
- 库存基准是全部 PENDING_PAYMENT、PAID 订单掩码按位或，包括尚未关闭的过期订单。

## Docker、目录和验证边界

```powershell
docker build -t railflow-java .
```

镜像默认启动 API，消费者可传 --railflow.command=payment-consumer。容器的数据库/Redis/MQ 地址需指向相应服务；镜像设置 BIND_ADDRESS=0.0.0.0。

src/main/java/com/railflow/ 下有 common、db、domain、train、order、payment、maintenance、web 包；src/main/resources 保存配置和建表 SQL；src/test 是 Java 测试；sql/ 保留人工对账 SQL。

自动测试用 H2 MySQL 兼容模式和消息客户端 mock，不依赖外部基础设施。迁移说明见 [MIGRATION.md](MIGRATION.md)。H2、mock 和内存演练不证明真实 MySQL/Redis/RabbitMQ 的端到端并发正确性。

项目用于工程学习，尚需真实支付流水去重、查单/退款、Outbox、鉴权、告警和真实压力测试。/health 仅为存活检查。
