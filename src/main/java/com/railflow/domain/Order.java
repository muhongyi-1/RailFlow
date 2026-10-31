package com.railflow.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;

/** Database DATETIME values and API timestamps are UTC without a timezone suffix. */
public record Order(
        long id,
        String orderNo,
        long userId,
        String requestId,
        long trainId,
        long seatId,
        int startStation,
        int endStation,
        int requestMask,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        OrderStatus status,
        LocalDateTime createdAt,
        LocalDateTime expiresAt,
        LocalDateTime paidAt,
        LocalDateTime closedAt) {

    public static final RowMapper<Order> MAPPER = (rs, rowNum) -> new Order(
            rs.getLong("id"), rs.getString("order_no"), rs.getLong("user_id"),
            rs.getString("request_id"), rs.getLong("train_id"), rs.getLong("seat_id"),
            rs.getInt("start_station"), rs.getInt("end_station"), rs.getInt("request_mask"),
            rs.getBigDecimal("amount"), OrderStatus.valueOf(rs.getString("status")),
            time(rs, "created_at"), time(rs, "expires_at"), time(rs, "paid_at"), time(rs, "closed_at"));

    private static LocalDateTime time(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDateTime.class);
    }
}
