package com.railflow.payment;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

class PaymentServiceTest {
    private JdbcTemplate jdbc;
    private PaymentService payments;

    @BeforeEach
    void database() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:payment_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE orders (id BIGINT AUTO_INCREMENT PRIMARY KEY, order_no VARCHAR(32) UNIQUE,"
                + "status VARCHAR(32), expires_at TIMESTAMP, paid_at TIMESTAMP)");
        payments = new PaymentService(jdbc, new DataSourceTransactionManager(dataSource));
    }

    private void order(String number, String status, LocalDateTime expiry) {
        jdbc.update("INSERT INTO orders (order_no,status,expires_at) VALUES (?,?,?)", number, status, expiry);
    }

    @Test
    void pendingPaymentCommitsOnceAndDuplicateRetainsOriginalPaidTime() {
        order("one", "PENDING_PAYMENT", LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1));
        assertThat(payments.applyPaymentSuccess("one")).isEqualTo("PAID");
        var paidAt = jdbc.queryForObject("SELECT paid_at FROM orders WHERE order_no='one'", LocalDateTime.class);
        assertThat(paidAt).isNotNull();
        assertThat(payments.applyPaymentSuccess("one")).isEqualTo("ALREADY_PAID");
        assertThat(jdbc.queryForObject("SELECT paid_at FROM orders WHERE order_no='one'", LocalDateTime.class))
                .isEqualTo(paidAt);
    }

    @Test
    void closedCanceledExpiredAndMissingOrdersCannotBePaid() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        order("closed", "CLOSED", now.plusMinutes(5));
        order("canceled", "CANCELED", now.plusMinutes(5));
        order("expired", "PENDING_PAYMENT", now.minusSeconds(1));
        for (String number : new String[]{"closed", "canceled", "expired", "missing"}) {
            assertThatThrownBy(() -> payments.applyPaymentSuccess(number)).isInstanceOf(PaymentNotApplicable.class);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE paid_at IS NOT NULL", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE order_no='expired'", String.class))
                .isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void previouslyPaidOrderRemainsIdempotentEvenAfterDeadline() {
        order("paid", "PAID", LocalDateTime.now(ZoneOffset.UTC).minusDays(1));
        assertThat(payments.applyPaymentSuccess("paid")).isEqualTo("ALREADY_PAID");
    }
}
