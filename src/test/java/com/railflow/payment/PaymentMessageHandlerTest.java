package com.railflow.payment;

import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentMessageHandlerTest {
    private final PaymentEvents events = mock(PaymentEvents.class);
    private final PaymentService payments = mock(PaymentService.class);
    private final PaymentPublisher publisher = mock(PaymentPublisher.class);
    private final Channel channel = mock(Channel.class);
    private final byte[] body = {1, 2, 3};
    private final PaymentEvent event = new PaymentEvent("0123456789abcdef0123456789abcdef", "pay_one", "SUCCESS", 1);
    private final PaymentMessageHandler handler = new PaymentMessageHandler(events, payments, publisher);

    @BeforeEach
    void setUp() throws Exception {
        when(events.parse(body)).thenReturn(event);
        when(channel.isOpen()).thenReturn(true);
    }

    @Test
    void successAndDuplicatesAckAfterDatabaseCommit() throws Exception {
        when(payments.applyPaymentSuccess(event.orderNo())).thenReturn("PAID", "ALREADY_PAID");
        handler.handle(channel, 4, body);
        handler.handle(channel, 5, body);
        InOrder ordered = inOrder(payments, channel);
        ordered.verify(payments).applyPaymentSuccess(event.orderNo());
        ordered.verify(channel).basicAck(4, false);
        ordered.verify(payments).applyPaymentSuccess(event.orderNo());
        ordered.verify(channel).basicAck(5, false);
        verifyNoInteractions(publisher);
    }

    @Test
    void businessRejectionIsDurablyDeadLetteredBeforeAcknowledgement() throws Exception {
        when(payments.applyPaymentSuccess(event.orderNo())).thenThrow(new PaymentNotApplicable("closed"));
        handler.handle(channel, 7, body);
        InOrder ordered = inOrder(publisher, channel);
        ordered.verify(publisher).publishDead(body);
        ordered.verify(channel).basicAck(7, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void invalidPayloadIsNotAppliedAndGoesToDeadLetterQueue() throws Exception {
        when(events.parse(body)).thenThrow(new IOException("invalid JSON"));
        handler.handle(channel, 8, body);
        verifyNoInteractions(payments);
        verify(publisher).publishDead(body);
        verify(channel).basicAck(8, false);
    }

    @Test
    void unconfirmedDeadLetterRequeuesOriginalWithoutAck() throws Exception {
        when(payments.applyPaymentSuccess(event.orderNo())).thenThrow(new IllegalStateException("database down"));
        doThrow(new IOException("DLQ unavailable")).when(publisher).publishDead(body);
        handler.handle(channel, 9, body);
        verify(channel).basicNack(9, false, true);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void acknowledgementFailureAfterCommitDoesNotDeadLetterSuccessfulPayment() throws Exception {
        when(payments.applyPaymentSuccess(event.orderNo())).thenReturn("PAID");
        doThrow(new IOException("connection lost")).when(channel).basicAck(10, false);
        assertThatThrownBy(() -> handler.handle(channel, 10, body)).isInstanceOf(IOException.class);
        verifyNoInteractions(publisher);
    }
}
