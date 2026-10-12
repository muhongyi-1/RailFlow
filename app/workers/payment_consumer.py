"""Consume simulated payment events and store final order status in MySQL.

Run: python -m app.workers.payment_consumer

Successful updates and duplicate notifications are ACKed. Business-invalid or
failed payments are retained in DLQ; failures publishing to DLQ are requeued
so that the original event is not silently lost.
"""

import json
import logging

import pika

from app.core.payment_mq import (
    PAYMENT_DEAD_QUEUE,
    PAYMENT_QUEUE,
    declare_payment_queues,
    get_connection,
)
from app.db.session import SessionLocal
from app.models.order import Order  # noqa: F401 - register ORM metadata
from app.models.seat import TrainSeat  # noqa: F401
from app.models.train import Train  # noqa: F401
from app.services.payment_service import apply_payment_success

logger = logging.getLogger(__name__)
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")


def handle_message(channel, method, properties, body):
    db = SessionLocal()
    try:
        event = json.loads(body.decode("utf-8"))
        logger.info("收到支付消息: %s", event)
        order_no = event.get("order_no")
        if not order_no:
            raise ValueError("支付消息缺少 order_no")

        result = apply_payment_success(db, order_no)
        logger.info("订单支付处理完成: %s", result)
    except Exception:
        db.rollback()
        logger.exception("支付消费失败，尝试保存到死信队列")
        try:
            # Confirm delivery means the broker accepted the persistent DLQ message.
            channel.basic_publish(
                exchange="",
                routing_key=PAYMENT_DEAD_QUEUE,
                body=body,
                mandatory=True,
                properties=pika.BasicProperties(
                    content_type="application/json", delivery_mode=2,
                ),
            )
        except Exception:
            logger.exception("死信发布未确认，原支付消息重新入队")
            if channel.is_open:
                channel.basic_nack(delivery_tag=method.delivery_tag, requeue=True)
        else:
            logger.warning("支付事件已进入死信队列，原消息 ACK")
            channel.basic_ack(delivery_tag=method.delivery_tag)
    else:
        # 业务提交成功后才 ACK；ACK 失败时由 RabbitMQ 重发，幂等逻辑处理。
        channel.basic_ack(delivery_tag=method.delivery_tag)
    finally:
        db.close()


def start_consumer() -> None:
    connection = get_connection()
    try:
        channel = connection.channel()
        declare_payment_queues(channel)
        channel.confirm_delivery()  # Confirm persistent DLQ publishes before ACKing failures
        channel.basic_qos(prefetch_count=1)
        channel.basic_consume(queue=PAYMENT_QUEUE, on_message_callback=handle_message)
        logger.info("正在监听 RabbitMQ 队列 %s", PAYMENT_QUEUE)
        channel.start_consuming()
    finally:
        if connection.is_open:
            connection.close()


if __name__ == "__main__":
    start_consumer()
