package com.railflow.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record BookingRequest(
        @Positive long userId,
        @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9_-]+$") String requestId,
        @Positive long trainId,
        @Positive long seatId,
        @Min(0) @Max(30) int startStation,
        @Min(1) @Max(31) int endStation) {

    @AssertTrue(message = "到达站位置必须大于出发站位置")
    @JsonIgnore
    public boolean isIntervalValid() {
        return startStation < endStation;
    }
}
