package com.railflow.payment;

import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.Map;

public final class RabbitQueues {
    public static final String PAYMENT_QUEUE = "railflow.payment.success.v1";
    public static final String PAYMENT_DEAD_QUEUE = "railflow.payment.dead.v1";

    private RabbitQueues() {}

    public static void declare(Channel channel) throws IOException {
        channel.queueDeclare(PAYMENT_DEAD_QUEUE, true, false, false, null);
        channel.queueDeclare(PAYMENT_QUEUE, true, false, false,
                Map.of("x-dead-letter-exchange", "", "x-dead-letter-routing-key", PAYMENT_DEAD_QUEUE));
    }
}
