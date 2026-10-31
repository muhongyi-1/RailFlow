package com.railflow.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.Return;
import com.rabbitmq.client.ReturnCallback;
import java.io.IOException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RabbitPaymentPublisherTest {
    private final RabbitConnections connections = mock(RabbitConnections.class);
    private final Connection connection = mock(Connection.class);
    private final Channel channel = mock(Channel.class);
    private final RabbitPaymentPublisher publisher = new RabbitPaymentPublisher(connections, new ObjectMapper());
    private final PaymentEvent event = new PaymentEvent("0123456789abcdef0123456789abcdef", "pay_one", "SUCCESS", 1);

    @BeforeEach
    void setUp() throws Exception {
        when(connections.open()).thenReturn(connection);
        when(connection.createChannel()).thenReturn(channel);
    }

    @Test
    void publicationIsPersistentMandatoryAndWaitsForBrokerConfirmation() throws Exception {
        publisher.publish(event);
        var properties = ArgumentCaptor.forClass(AMQP.BasicProperties.class);
        InOrder ordered = inOrder(channel);
        ordered.verify(channel).confirmSelect();
        ordered.verify(channel).basicPublish(eq(""), eq(RabbitQueues.PAYMENT_QUEUE), eq(true), properties.capture(), any());
        ordered.verify(channel).waitForConfirmsOrDie(10000);
        assertThat(properties.getValue().getDeliveryMode()).isEqualTo(2);
        assertThat(properties.getValue().getMessageId()).isEqualTo("pay_one");
        verify(channel).close();
        verify(connection).close();
    }

    @Test
    void brokerTimeoutFailsPublication() throws Exception {
        doThrow(new TimeoutException("no confirm")).when(channel).waitForConfirmsOrDie(10000);
        assertThatThrownBy(() -> publisher.publish(event)).isInstanceOf(TimeoutException.class);
        verify(channel).close();
        verify(connection).close();
    }

    @Test
    void unroutableMandatoryMessageFailsEvenIfBrokerConfirms() throws Exception {
        AtomicReference<ReturnCallback> callback = new AtomicReference<>();
        doAnswer(invocation -> {
            callback.set(invocation.getArgument(0));
            return null;
        }).when(channel).addReturnListener(any(ReturnCallback.class));
        doAnswer(invocation -> {
            callback.get().handle(new Return(312, "NO_ROUTE", "", RabbitQueues.PAYMENT_QUEUE, null, new byte[0]));
            return null;
        }).when(channel).basicPublish(anyString(), anyString(), anyBoolean(), any(), any());
        assertThatThrownBy(() -> publisher.publish(event)).isInstanceOf(IOException.class)
                .hasMessageContaining("unroutable");
    }

    @Test
    void deadLettersPreserveOriginalBytesAndAwaitConfirmation() throws Exception {
        byte[] invalidOriginal = {0, 1, 2, 3};
        publisher.publishDead(invalidOriginal);
        verify(channel).basicPublish(eq(""), eq(RabbitQueues.PAYMENT_DEAD_QUEUE), eq(true), any(), same(invalidOriginal));
        verify(channel).waitForConfirmsOrDie(10000);
    }
}
