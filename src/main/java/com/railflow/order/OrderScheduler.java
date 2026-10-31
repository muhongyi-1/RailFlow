package com.railflow.order;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Start this only in the dedicated scheduler process. */
@Component
@ConditionalOnProperty(name = "railflow.scheduler.enabled", havingValue = "true")
public class OrderScheduler {
    private final ExpirationService expiration;

    public OrderScheduler(ExpirationService expiration) {
        this.expiration = expiration;
    }

    @Scheduled(fixedDelayString = "${railflow.scheduler.interval-ms:60000}")
    public void closeExpiredOrders() {
        expiration.closeExpiredOrders();
    }
}
