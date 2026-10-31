package com.railflow.payment;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "railflow.worker.enabled", havingValue = "true")
public class PaymentWorker implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(PaymentWorker.class);
    private final RabbitConnections connections;
    private final PaymentMessageHandler handler;
    private volatile boolean running;
    private Connection connection;
    private Channel channel;

    public PaymentWorker(RabbitConnections connections, PaymentMessageHandler handler) {
        this.connections = connections;
        this.handler = handler;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        try {
            connection = connections.open();
            channel = connection.createChannel();
            RabbitQueues.declare(channel);
            channel.basicQos(1);
            channel.basicConsume(RabbitQueues.PAYMENT_QUEUE, false,
                    (tag, delivery) -> handler.handle(channel, delivery.getEnvelope().getDeliveryTag(), delivery.getBody()),
                    tag -> log.warn("RabbitMQ payment consumer canceled: {}", tag));
            running = true;
            log.info("正在监听 RabbitMQ 队列 {}", RabbitQueues.PAYMENT_QUEUE);
        } catch (Exception exception) {
            stop();
            throw new IllegalStateException("支付消费者启动失败，请检查 RabbitMQ 配置", exception);
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        try {
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
        } catch (Exception exception) {
            log.debug("关闭支付 channel 失败", exception);
        }
        try {
            if (connection != null && connection.isOpen()) {
                connection.close();
            }
        } catch (Exception exception) {
            log.debug("关闭支付 connection 失败", exception);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
