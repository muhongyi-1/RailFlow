"""RabbitMQ 连接、队列声明、可靠发布（开发演示版）。"""
import json
from urllib.parse import quote

from app.core.config import settings

import pika


def rabbitmq_url() -> str:
    """Build local MQ URL from .env credentials (same for API and worker)."""
    if not settings.rabbitmq_password:
        raise RuntimeError("请先在 .env 中设置 RABBITMQ_PASSWORD")
    user = quote(settings.rabbitmq_user, safe="")
    password = quote(settings.rabbitmq_password, safe="")
    return (
        f"amqp://{user}:{password}@"
        f"{settings.rabbitmq_host}:{settings.rabbitmq_port}/%2F"
    )


PAYMENT_QUEUE = "railflow.payment.success.v1"
PAYMENT_DEAD_QUEUE = "railflow.payment.dead.v1"


def get_connection():
    params = pika.URLParameters(rabbitmq_url())
    params.socket_timeout = 5
    params.blocked_connection_timeout = 10
    params.heartbeat = 30
    return pika.BlockingConnection(params)


def declare_payment_queues(channel):
    # 死信队列：消费失败的消息先保留，便于人工排查
    channel.queue_declare(queue=PAYMENT_DEAD_QUEUE, durable=True)
    # 使用默认交换机，拒绝的消息进入死信队列
    channel.queue_declare(
        queue=PAYMENT_QUEUE,
        durable=True,
        arguments={
            "x-dead-letter-exchange": "",
            "x-dead-letter-routing-key": PAYMENT_DEAD_QUEUE,
        },
    )


def publish_payment_success(event: dict):
    """发布已验证的模拟支付成功事件；RabbitMQ 确认后才返回。"""
    connection = get_connection()
    try:
        channel = connection.channel()
        declare_payment_queues(channel)
        channel.confirm_delivery()  # 发布者确认
        payload = json.dumps(event, ensure_ascii=False).encode("utf-8")
        channel.basic_publish(
            exchange="",  # 默认 direct 交换机，routing_key 为队列名
            routing_key=PAYMENT_QUEUE,
            body=payload,
            mandatory=True,
            properties=pika.BasicProperties(
                content_type="application/json",
                delivery_mode=2,  # 消息持久化
                message_id=event["payment_no"],
            ),
        )
    finally:
        if connection.is_open:
            connection.close()
