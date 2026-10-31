package com.railflow.domain;

public enum OrderStatus {
    PENDING_PAYMENT, PAID, CANCELED, CLOSED;

    public boolean canTransitionTo(OrderStatus target) {
        return this == PENDING_PAYMENT
                && (target == PAID || target == CANCELED || target == CLOSED);
    }
}
