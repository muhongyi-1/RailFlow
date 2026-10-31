package com.railflow.maintenance;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InventoryReconcilerTest {
    private final InventoryReconciler.OrderView paid = order("paid", "PAID", 0, 2, 3);
    private static InventoryReconciler.OrderView order(String no, String status, int start, int end, int mask) {
        return new InventoryReconciler.OrderView(no, status, 1, 4, start, end, mask);
    }
    @Test void consistentNeedsNoRepair() {
        var report = InventoryReconciler.inspect(4, "A03", 1, 3, List.of(paid));
        assertTrue(report.consistent());
        assertFalse(report.safeToAdd());
    }
    @Test void missingBitsCanBeAdded() {
        var report = InventoryReconciler.inspect(4, "A03", 1, 0, List.of(paid));
        assertEquals(3, report.expected());
        assertEquals(3, report.missing());
        assertEquals(0, report.extra());
        assertTrue(report.safeToAdd());
    }
    @Test void extraBitsMustNeverBeClearedAutomatically() {
        var report = InventoryReconciler.inspect(4, "A03", 1, 7, List.of(paid));
        assertEquals(4, report.extra());
        assertFalse(report.safeToAdd());
    }
    @Test void overlappingOrdersRefuseRepairEvenIfBitmapEqualsUnion() {
        var report = InventoryReconciler.inspect(4, "A03", 1, 3,
                List.of(paid, order("pending", "PENDING_PAYMENT", 0, 2, 3)));
        assertFalse(report.issues().isEmpty());
        assertFalse(report.consistent());
        assertFalse(report.safeToAdd());
    }
    @Test void invalidMaskRefusesRepair() {
        var report = InventoryReconciler.inspect(4, "A03", 1, 0, List.of(order("bad", "PAID", 2, 3, 3)));
        assertFalse(report.issues().isEmpty());
        assertFalse(report.safeToAdd());
    }
    @Test void closedOrderDoesNotReserveInventory() {
        var report = InventoryReconciler.inspect(4, "A03", 1, 3, List.of(paid, order("closed", "CLOSED", 2, 3, 4)));
        assertTrue(report.consistent());
        assertEquals(1, report.activeCount());
    }
    @Test void negativeBitmapAndIncorrectReferenceBlockRepair() {
        assertFalse(InventoryReconciler.inspect(4, "A03", 1, -1, List.of(paid)).safeToAdd());
        var report = InventoryReconciler.inspect(4, "A03", 2, 0, List.of(paid));
        assertFalse(report.issues().isEmpty());
        assertFalse(report.safeToAdd());
    }
    @Test void signedIntHighestSupportedBitIsCalculatedWithoutOverflow() {
        var highest = order("last_segment", "PAID", 30, 31, 1 << 30);
        assertTrue(InventoryReconciler.inspect(4, "A03", 1, 0, List.of(highest)).safeToAdd());
        var all = order("all_segments", "PAID", 0, 31, Integer.MAX_VALUE);
        assertTrue(InventoryReconciler.inspect(4, "A03", 1, Integer.MAX_VALUE, List.of(all)).consistent());
    }
    @Test void invalidIntervalAndMixedExtraAndMissingBitsRefuseRepair() {
        assertFalse(InventoryReconciler.inspect(4, "A03", 1, 0,
                List.of(order("bad", "PAID", -1, 2, 3))).safeToAdd());
        var report = InventoryReconciler.inspect(4, "A03", 1, 4, List.of(paid));
        assertEquals(3, report.missing());
        assertEquals(4, report.extra());
        assertFalse(report.safeToAdd());
    }
}
