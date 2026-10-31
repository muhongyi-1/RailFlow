package com.railflow.maintenance;

import java.time.LocalDateTime;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.railflow.maintenance.PaymentCloseRace.BASE_TIME;

class PaymentCloseRaceTest {
    private static final LocalDateTime EXPIRY = BASE_TIME.plusSeconds(5);
    @Test void paidOrderRetainsItsAndOtherIntervals() {
        var order = new PaymentCloseRace.SimulatedOrder(EXPIRY, 3, 4, null);
        assertEquals("PAID", order.paymentSuccess(BASE_TIME.plusSeconds(4)));
        assertEquals("SKIPPED_NOT_PENDING", order.closeExpired(BASE_TIME.plusSeconds(6)));
        assertEquals(7, order.bitmap());
        assertEquals("PAID", order.status());
    }
    @Test void closedOrderPreservesOtherIntervalAndRejectsLatePayment() {
        var order = new PaymentCloseRace.SimulatedOrder(EXPIRY, 3, 4, null);
        assertEquals("CLOSED", order.closeExpired(BASE_TIME.plusSeconds(6)));
        assertEquals("REJECTED_CLOSED_RECONCILE", order.paymentSuccess(BASE_TIME.plusSeconds(7)));
        assertEquals(4, order.bitmap());
    }
    @Test void exactDeadlineRejectsPaymentAndAllowsClose() {
        var order = new PaymentCloseRace.SimulatedOrder(EXPIRY);
        assertEquals("REJECTED_EXPIRED_RECONCILE", order.paymentSuccess(EXPIRY));
        assertEquals("CLOSED", order.closeExpired(EXPIRY));
        assertEquals(0, order.bitmap());
    }
    @Test void earlyCloseDoesNotReleaseInventory() {
        var order = new PaymentCloseRace.SimulatedOrder(EXPIRY);
        assertEquals("SKIPPED_NOT_EXPIRED", order.closeExpired(BASE_TIME));
        assertEquals("PENDING_PAYMENT", order.status());
        assertEquals(3, order.bitmap());
    }
    @Test void delayedConsumptionMustNotMarkPaid() {
        var order = new PaymentCloseRace.SimulatedOrder(EXPIRY);
        assertEquals("REJECTED_EXPIRED_RECONCILE", order.paymentSuccess(BASE_TIME.plusSeconds(6)));
        assertEquals("PENDING_PAYMENT", order.status());
        assertEquals(3, order.bitmap());
        assertEquals("CLOSED", order.closeExpired(BASE_TIME.plusSeconds(6)));
    }
    @Test void duplicatePaymentIsIdempotent() {
        var order = new PaymentCloseRace.SimulatedOrder(EXPIRY);
        assertEquals("PAID", order.paymentSuccess(BASE_TIME));
        assertEquals("ALREADY_PAID", order.paymentSuccess(BASE_TIME.plusSeconds(2)));
        assertEquals(BASE_TIME, order.paidAt());
        assertEquals(3, order.bitmap());
    }
    @Test void missingBitmapRefusesClose() {
        var order = new PaymentCloseRace.SimulatedOrder(EXPIRY, 3, 0, 0);
        assertEquals("BLOCKED_BITMAP_MISSING", order.closeExpired(BASE_TIME.plusSeconds(6)));
        assertEquals("PENDING_PAYMENT", order.status());
        assertEquals(0, order.bitmap());
    }
    @Test void overlapRefusesClose() {
        var order = new PaymentCloseRace.SimulatedOrder(EXPIRY, 3, 3, null);
        assertEquals("BLOCKED_OVERLAPPING_ORDER", order.closeExpired(BASE_TIME.plusSeconds(6)));
        assertEquals("PENDING_PAYMENT", order.status());
        assertEquals(3, order.bitmap());
    }
    @Test void fortyRacesAfterExpiryNeverPayExpiredOrder() {
        for (int i = 0; i < 40; i++) {
            var result = PaymentCloseRace.simultaneousRace(BASE_TIME.plusSeconds(6));
            assertTrue(Set.of("REJECTED_EXPIRED_RECONCILE", "REJECTED_CLOSED_RECONCILE").contains(result.paymentResult()));
            assertEquals("CLOSED", result.closeResult());
            assertEquals("CLOSED", result.order().status());
            assertEquals(0, result.order().bitmap());
        }
    }
    @Test void fortyRacesBeforeExpiryNeverCloseOrder() {
        for (int i = 0; i < 40; i++) {
            var result = PaymentCloseRace.simultaneousRace(BASE_TIME.plusSeconds(4));
            assertEquals("PAID", result.paymentResult());
            assertTrue(Set.of("SKIPPED_NOT_PENDING", "SKIPPED_NOT_EXPIRED").contains(result.closeResult()));
            assertEquals("PAID", result.order().status());
            assertEquals(3, result.order().bitmap());
        }
    }
}
