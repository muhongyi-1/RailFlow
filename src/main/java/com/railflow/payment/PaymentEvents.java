package com.railflow.payment;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import jakarta.validation.Validator;
import java.io.IOException;
import org.springframework.stereotype.Component;

@Component
public class PaymentEvents {
    private final ObjectReader reader;
    private final Validator validator;

    public PaymentEvents(ObjectMapper mapper, Validator validator) {
        this.reader = mapper.readerFor(PaymentEvent.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.validator = validator;
    }

    public PaymentEvent parse(byte[] bytes) throws IOException {
        PaymentEvent event = reader.readValue(bytes);
        if (event == null || !validator.validate(event).isEmpty()) {
            throw new IllegalArgumentException("支付通知数据不合法");
        }
        return event;
    }
}
