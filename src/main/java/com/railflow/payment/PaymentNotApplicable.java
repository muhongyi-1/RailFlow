package com.railflow.payment;

/** Permanent business rejection requiring reconciliation instead of automatic retries. */
public class PaymentNotApplicable extends RuntimeException {
    public PaymentNotApplicable(String message) {
        super(message);
    }
}
