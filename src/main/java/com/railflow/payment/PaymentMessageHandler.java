package com.railflow.payment;

import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PaymentMessageHandler {
    private static final Logger log = LoggerFactory.getLogger(PaymentMessageHandler.class);
    private final PaymentEvents events;
    private final PaymentService payments;
    private final PaymentPublisher publisher;

    public PaymentMessageHandler(PaymentEvents events, PaymentService payments, PaymentPublisher publisher) {
        this.events = events;
        this.payments = payments;
        this.publisher = publisher;
    }

    public void handle(Channel channel, long deliveryTag, byte[] body) throws IOException {
        try {
            PaymentEvent event = events.parse(body);
            // Queue residence time is allowed; the database order deadline remains authoritative.
            payments.applyPaymentSuccess(event.orderNo());
        } catch (Exception processingFailure) {
            log.warn("支付消费失败，尝试保存到死信队列", processingFailure);
            try {
                // Use a distinct publishing channel so consume ACKs cannot affect confirms.
                publisher.publishDead(body);
            } catch (Exception publishFailure) {
                log.warn("死信发布未确认，原支付消息重新入队", publishFailure);
                if (channel.isOpen()) {
                    channel.basicNack(deliveryTag, false, true);
                }
                return;
            }
        }
        // This is intentionally outside the processing catch. An ACK failure after a committed
        // payment must be redelivered, never mistaken for a business rejection and sent to DLQ.
        channel.basicAck(deliveryTag, false);
    }
}
