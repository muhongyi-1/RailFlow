package com.railflow.maintenance;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

class InventoryMaintenanceTest {
    private JdbcTemplate jdbc;
    private InventoryMaintenance inventory;

    @BeforeEach void setup() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:maintenance_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        inventory = new InventoryMaintenance(jdbc, new DataSourceTransactionManager(dataSource));
        jdbc.execute("CREATE TABLE train_seats(id BIGINT PRIMARY KEY,train_id BIGINT,seat_no VARCHAR(10),bitmap INT)");
        jdbc.execute("CREATE TABLE orders(id BIGINT PRIMARY KEY,order_no VARCHAR(32),status VARCHAR(20),train_id BIGINT,"
                + "seat_id BIGINT,start_station INT,end_station INT,request_mask INT,expires_at TIMESTAMP,paid_at TIMESTAMP,closed_at TIMESTAMP)");
        jdbc.update("INSERT INTO train_seats VALUES(4,1,'A03',0)");
    }

    private void order(long id, String status, long seatId, int start, int end, int mask) {
        jdbc.update("INSERT INTO orders(id,order_no,status,train_id,seat_id,start_station,end_station,request_mask,expires_at) VALUES(?,?,?,?,?,?,?,?,?)",
                id, "order_" + id, status, 1, seatId, start, end, mask, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
    }
    private int bitmap() { return jdbc.queryForObject("SELECT bitmap FROM train_seats WHERE id=4", Integer.class); }

    @Test void previewDetectsMissingBitsWithoutWriting() {
        order(1, "PAID", 4, 0, 2, 3);
        assertEquals(1, inventory.inspectSeat(4, false));
        assertEquals(0, bitmap());
    }
    @Test void applyAddsOnlyMissingBitsAndNeverChangesOrders() {
        order(1, "PAID", 4, 0, 2, 3);
        order(2, "PENDING_PAYMENT", 4, 2, 3, 4);
        jdbc.update("UPDATE train_seats SET bitmap=4 WHERE id=4");
        assertEquals(0, inventory.inspectSeat(4, true));
        assertEquals(7, bitmap());
        assertEquals("PAID", jdbc.queryForObject("SELECT status FROM orders WHERE id=1", String.class));
        assertEquals("PENDING_PAYMENT", jdbc.queryForObject("SELECT status FROM orders WHERE id=2", String.class));
    }
    @Test void applyRefusesExtraBitsAndLeavesDataIntact() {
        order(1, "PAID", 4, 0, 2, 3);
        jdbc.update("UPDATE train_seats SET bitmap=7 WHERE id=4");
        assertEquals(1, inventory.inspectSeat(4, true));
        assertEquals(7, bitmap());
    }
    @Test void applyRefusesOverlappingOrdersEvenIfMissingBitsCouldBeFilled() {
        order(1, "PAID", 4, 0, 2, 3);
        order(2, "PENDING_PAYMENT", 4, 1, 3, 6);
        assertEquals(1, inventory.inspectSeat(4, true));
        assertEquals(0, bitmap());
    }
    @Test void overduePendingStillCountsAsReservedAndAuditIsReadOnly() {
        order(1, "PENDING_PAYMENT", 4, 0, 2, 3);
        jdbc.update("UPDATE train_seats SET bitmap=3 WHERE id=4");
        assertEquals(0, inventory.audit(null, 20));
        assertEquals(3, bitmap());
        assertEquals("PENDING_PAYMENT", jdbc.queryForObject("SELECT status FROM orders WHERE id=1", String.class));
    }
    @Test void missingSeatReturnsDistinctErrorAndOrphanOrderIsDetected() {
        assertEquals(2, inventory.audit(999L, 20));
        assertEquals(2, inventory.inspectSeat(999, true));
        order(1, "PAID", 999, 0, 2, 3);
        assertEquals(1, inventory.audit(null, 20));
    }
    @Test void deadLetterAuditDoesNotAlterClosedOrderOrInventory() {
        order(1, "CLOSED", 4, 0, 2, 3);
        assertEquals(0, inventory.deadLetterAudit("order_1", "pay_test", 1791805579));
        assertEquals("CLOSED", jdbc.queryForObject("SELECT status FROM orders WHERE id=1", String.class));
        assertEquals(0, bitmap());
    }
}
