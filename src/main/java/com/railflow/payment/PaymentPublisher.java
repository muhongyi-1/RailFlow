package com.railflow.payment;

public interface PaymentPublisher {
    void publish(PaymentEvent event) throws Exception;
    void publishDead(byte[] originalBody) throws Exception;
}
