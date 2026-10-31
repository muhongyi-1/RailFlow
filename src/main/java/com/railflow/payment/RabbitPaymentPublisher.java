package com.railflow.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.Return;
import com.rabbitmq.client.ReturnCallback;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class RabbitPaymentPublisher implements PaymentPublisher {
    private final RabbitConnections connections;
    private final ObjectMapper mapper;

    public RabbitPaymentPublisher(RabbitConnections connections, ObjectMapper mapper) {
        this.connections = connections;
        this.mapper = mapper;
    }

    @Override
    public void publish(PaymentEvent event) throws Exception {
        publishConfirmed(RabbitQueues.PAYMENT_QUEUE, mapper.writeValueAsBytes(event), event.paymentNo());
    }

    @Override
    public void publishDead(byte[] originalBody) throws Exception {
        publishConfirmed(RabbitQueues.PAYMENT_DEAD_QUEUE, originalBody, null);
    }

    private void publishConfirmed(String queue, byte[] bytes, String messageId) throws Exception {
        // Each publish owns its channel: concurrent HTTP calls never share confirm state.
        try (Connection connection = connections.open(); Channel channel = connection.createChannel()) {
            RabbitQueues.declare(channel);
            channel.confirmSelect();
            AtomicReference<Return> returned = new AtomicReference<>();
            channel.addReturnListener((ReturnCallback) returned::set);
            AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                    .contentType("application/json").contentEncoding("UTF-8")
                    .deliveryMode(2).messageId(messageId).build();
            channel.basicPublish("", queue, true, properties, bytes);
            // An ACK alone also acknowledges unroutable mandatory messages; check returns too.
            channel.waitForConfirmsOrDie(10000);
            if (returned.get() != null) {
                throw new IOException("RabbitMQ returned an unroutable payment message");
            }
        }
    }
}
