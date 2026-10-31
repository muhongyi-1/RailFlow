package com.railflow.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.railflow.common.ApiException;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PaymentControllerTest {
    private static final String SECRET = "test_only_secret";
    private static final String ORDER = "0123456789abcdef0123456789abcdef";
    private final ObjectMapper mapper = new ObjectMapper();
    private final PaymentPublisher publisher = mock(PaymentPublisher.class);
    private final PaymentService payments = mock(PaymentService.class);
    private ValidatorFactory factory;
    private PaymentEvents events;
    private PaymentController controller;

    @BeforeEach
    void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        events = new PaymentEvents(mapper, factory.getValidator());
        controller = new PaymentController(events, publisher, payments, SECRET, false);
    }

    @AfterEach
    void closeValidator() {
        factory.close();
    }

    private byte[] body(long timestamp) throws Exception {
        return mapper.writeValueAsBytes(new PaymentEvent(ORDER, "pay_test_001", "SUCCESS", timestamp));
    }

    private void rejects(byte[] body, int status) {
        assertThatThrownBy(() -> controller.callback(body, PaymentSignature.generate(body, SECRET)))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status()).isEqualTo(status));
    }

    @Test
    void signatureMatchesIndependentHmacTestVectorAndExactBytes() {
        byte[] bytes = "The quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.UTF_8);
        assertThat(PaymentSignature.generate(bytes, "key")).isEqualTo(
                "sha256=f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
        assertThat(PaymentSignature.verify(bytes, "key", PaymentSignature.generate(bytes, "wrong"))).isFalse();
        assertThat(PaymentSignature.verify("{} ".getBytes(StandardCharsets.UTF_8), SECRET,
                PaymentSignature.generate("{}".getBytes(StandardCharsets.UTF_8), SECRET))).isFalse();
    }

    @Test
    void acceptedCallbackPublishesAndDoesNotDirectlyPay() throws Exception {
        long now = Instant.now().getEpochSecond();
        byte[] body = body(now);
        var response = controller.callback(body, PaymentSignature.generate(body, SECRET));
        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).containsEntry("accepted", true).containsEntry("payment_no", "pay_test_001");
        verify(publisher).publish(new PaymentEvent(ORDER, "pay_test_001", "SUCCESS", now));
        verifyNoInteractions(payments);
    }

    @Test
    void invalidSignatureNeverPublishes() throws Exception {
        byte[] body = body(Instant.now().getEpochSecond());
        assertThatThrownBy(() -> controller.callback(body, "sha256=bad"))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status()).isEqualTo(401));
        verifyNoInteractions(publisher);
    }

    @Test
    void missingSecretIsServiceUnavailable() throws Exception {
        var unconfigured = new PaymentController(events, publisher, payments, "", false);
        assertThatThrownBy(() -> unconfigured.callback(body(1), "anything"))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status()).isEqualTo(503));
    }

    @Test
    void rejectsMalformedUnknownNullAndInvalidEventFields() throws Exception {
        rejects("{".getBytes(StandardCharsets.UTF_8), 422);
        rejects("null".getBytes(StandardCharsets.UTF_8), 422);
        rejects(mapper.writeValueAsBytes(new PaymentEvent(ORDER, "bad space", "FAILED", 1)), 422);
        rejects(mapper.writeValueAsBytes(new PaymentEvent("bad-order", "good", "SUCCESS", 1)), 422);
        rejects(mapper.writeValueAsBytes(new PaymentEvent(ORDER, "good", "SUCCESS", 0)), 422);
        String json = new String(body(Instant.now().getEpochSecond()), StandardCharsets.UTF_8);
        rejects((json.substring(0, json.length() - 1) + ",\"amount\":1}").getBytes(StandardCharsets.UTF_8), 422);
        rejects((json + " {}").getBytes(StandardCharsets.UTF_8), 422);
        verifyNoInteractions(publisher);
    }

    @Test
    void rejectsOldFutureAndOverflowTimestamps() throws Exception {
        long now = Instant.now().getEpochSecond();
        rejects(body(now - 301), 400);
        rejects(body(now + 1000), 400);
        rejects(body(Long.MAX_VALUE), 400);
        verifyNoInteractions(publisher);
    }

    @Test
    void brokerFailureIsRetriableAndLegacyRouteIsDisabled() throws Exception {
        doThrow(new java.io.IOException("broker unavailable")).when(publisher).publish(any());
        rejects(body(Instant.now().getEpochSecond()), 503);
        assertThatThrownBy(() -> controller.legacyDirectPayment(ORDER))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status()).isEqualTo(403));
        verifyNoInteractions(payments);
    }

    @Test
    void explicitlyEnabledLegacyRouteKeepsBusinessConflictStatus() {
        var legacy = new PaymentController(events, publisher, payments, SECRET, true);
        when(payments.applyPaymentSuccess(ORDER)).thenThrow(new PaymentNotApplicable("closed"));
        assertThatThrownBy(() -> legacy.legacyDirectPayment(ORDER))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status()).isEqualTo(409));
    }
}
