package com.railflow.maintenance;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MaintenanceCommandsTest {
    private final InventoryMaintenance inventory = mock(InventoryMaintenance.class);
    private final MaintenanceCommands commands = new MaintenanceCommands(inventory, new ObjectMapper(), "test_secret");

    @Test void missingMaintenanceAcknowledgementBlocksAnyDatabaseAccess() {
        assertThrows(IllegalArgumentException.class, () -> commands.execute("inventory-repair",
                new DefaultApplicationArguments("--seat-id=4", "--apply", "--confirm-seat-id=4")));
        verifyNoInteractions(inventory);
    }
    @Test void mismatchedSeatConfirmationBlocksDatabaseAccess() {
        assertThrows(IllegalArgumentException.class, () -> commands.execute("inventory-repair",
                new DefaultApplicationArguments("--seat-id=4", "--apply", "--maintenance-mode", "--confirm-seat-id=5")));
        verifyNoInteractions(inventory);
    }
    @Test void absentTypedConfirmationCancelsWithoutDatabaseAccess() {
        InputStream previous = System.in;
        try {
            System.setIn(new ByteArrayInputStream("no\n".getBytes(StandardCharsets.UTF_8)));
            assertEquals(0, commands.execute("inventory-repair", new DefaultApplicationArguments(
                    "--seat-id=4", "--apply", "--maintenance-mode", "--confirm-seat-id=4")));
        } finally { System.setIn(previous); }
        verifyNoInteractions(inventory);
    }
    @Test void explicitTypedConfirmationAllowsOnlyRequestedSeat() {
        InputStream previous = System.in;
        try {
            System.setIn(new ByteArrayInputStream("REPAIR-4\n".getBytes(StandardCharsets.UTF_8)));
            commands.execute("inventory-repair", new DefaultApplicationArguments(
                    "--seat-id=4", "--apply", "--maintenance-mode", "--confirm-seat-id=4"));
        } finally { System.setIn(previous); }
        verify(inventory).inspectSeat(4, true);
        verifyNoMoreInteractions(inventory);
    }
    @Test void demosArePureMemoryAndDemoCannotApply() {
        assertEquals(0, commands.execute("inventory-repair", new DefaultApplicationArguments("--demo")));
        assertEquals(0, commands.execute("payment-close-race", new DefaultApplicationArguments()));
        assertThrows(IllegalArgumentException.class, () -> commands.execute("inventory-repair", new DefaultApplicationArguments("--demo", "--apply")));
        verifyNoInteractions(inventory);
    }
    @Test void nonzeroAuditCodeCanBePropagatedByApplication() {
        when(inventory.audit(null, 20)).thenReturn(1);
        var failure = assertThrows(MaintenanceCommands.CommandFailure.class,
                () -> commands.run("inventory-audit", new DefaultApplicationArguments()));
        assertEquals(1, failure.getExitCode());
    }
    @Test void hmacMatchesRfc4231TestVector() {
        assertEquals("sha256=5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
                MaintenanceCommands.signature("what do ya want for nothing?".getBytes(StandardCharsets.US_ASCII), "Jefe"));
    }
    @Test void invalidLoadArgumentsAreRejectedBeforeSendingRequests() {
        assertThrows(IllegalArgumentException.class, () -> commands.execute("booking-load-test",
                new DefaultApplicationArguments("--seat-id=4", "--count=501")));
        assertThrows(IllegalArgumentException.class, () -> commands.execute("booking-load-test",
                new DefaultApplicationArguments("--seat-id=4", "--start=3", "--end=2")));
        verifyNoInteractions(inventory);
    }
}
