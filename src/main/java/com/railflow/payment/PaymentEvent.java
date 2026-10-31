package com.railflow.payment;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** The simulated payment platform's version-one notification contract. */
public record PaymentEvent(
        @JsonProperty("order_no") @NotBlank @Pattern(regexp = "[a-fA-F0-9]{32}") String orderNo,
        @JsonProperty("payment_no") @NotBlank @Size(max = 64)
        @Pattern(regexp = "[A-Za-z0-9_-]+") String paymentNo,
        @NotBlank @Pattern(regexp = "SUCCESS") String status,
        @Min(1) long timestamp) {
}
