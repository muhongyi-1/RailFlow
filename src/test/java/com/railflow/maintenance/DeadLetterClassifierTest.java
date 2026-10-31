package com.railflow.maintenance;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DeadLetterClassifierTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 12, 12, 0);
    @Test void closedAndCanceledRequireReconciliation() {
        assertEquals("CLOSED_PAYMENT_RECONCILE", DeadLetterClassifier.classify("CLOSED", NOW.minusMinutes(5), NOW).code());
        assertEquals("CLOSED_PAYMENT_RECONCILE", DeadLetterClassifier.classify("CANCELED", NOW, NOW).code());
    }
    @Test void paidRequiresReviewOfDuplicateOrOtherError() {
        assertEquals("ALREADY_PAID_REVIEW", DeadLetterClassifier.classify("PAID", NOW, NOW).code());
    }
    @Test void expiryBoundaryIsInclusive() {
        assertEquals("EXPIRED_PENDING_REVIEW", DeadLetterClassifier.classify("PENDING_PAYMENT", NOW.minusSeconds(1), NOW).code());
        assertEquals("EXPIRED_PENDING_REVIEW", DeadLetterClassifier.classify("PENDING_PAYMENT", NOW, NOW).code());
        assertEquals("ACTIVE_PENDING_REVIEW", DeadLetterClassifier.classify("PENDING_PAYMENT", NOW.plusSeconds(1), NOW).code());
    }
    @Test void missingOrderExpiryAndUnknownStateRemainManual() {
        assertEquals("ORDER_NOT_FOUND", DeadLetterClassifier.classify(null, null, NOW).code());
        assertEquals("INVALID_EXPIRY_REVIEW", DeadLetterClassifier.classify("PENDING_PAYMENT", null, NOW).code());
        assertEquals("UNKNOWN_STATUS", DeadLetterClassifier.classify("corrupt", NOW, NOW).code());
    }
    @Test void unixTimestampUsesUtcIndependentOfMachineTimezone() {
        assertEquals(LocalDateTime.of(2026, 10, 12, 11, 46, 19), DeadLetterClassifier.utcFromUnix(1791805579));
    }
}
