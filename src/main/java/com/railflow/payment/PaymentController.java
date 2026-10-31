package com.railflow.payment;

import com.railflow.common.ApiException;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payment")
public class PaymentController {
    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);
    private final PaymentEvents events;
    private final PaymentPublisher publisher;
    private final PaymentService payments;
    private final String secret;
    private final boolean legacyEnabled;

    public PaymentController(PaymentEvents events, PaymentPublisher publisher, PaymentService payments,
            @Value("${railflow.payment.secret:}") String secret,
            @Value("${railflow.payment.legacy-enabled:false}") boolean legacyEnabled) {
        this.events = events;
        this.publisher = publisher;
        this.payments = payments;
        this.secret = secret;
        this.legacyEnabled = legacyEnabled;
    }

    @PostMapping("/callback")
    public ResponseEntity<Map<String, Object>> callback(@RequestBody byte[] rawBody,
            @RequestHeader("X-Payment-Signature") String signature) {
        if (secret.isBlank()) {
            throw new ApiException(503, "支付回调密钥尚未配置");
        }
        if (!PaymentSignature.verify(rawBody, secret, signature)) {
            throw new ApiException(401, "支付回调签名不正确");
        }
        PaymentEvent event;
        try {
            event = events.parse(rawBody);
        } catch (Exception exception) {
            throw new ApiException(422, "支付通知数据不合法");
        }
        long now = Instant.now().getEpochSecond();
        // Range comparison cannot overflow when an untrusted timestamp is Long.MAX_VALUE.
        if (event.timestamp() < now - 300 || event.timestamp() > now + 300) {
            throw new ApiException(400, "支付回调时间戳已过期");
        }
        try {
            publisher.publish(event);
        } catch (Exception exception) {
            log.warn("支付通知发布失败，order_no={}", event.orderNo(), exception);
            throw new ApiException(503, "RabbitMQ 暂不可用，请稍后重试");
        }
        return ResponseEntity.accepted().body(Map.of("accepted", true,
                "message", "支付通知已入队，请查询订单最终状态", "payment_no", event.paymentNo()));
    }

    @PostMapping("/{order_no}")
    public Map<String, String> legacyDirectPayment(@PathVariable("order_no") String orderNo) {
        if (!legacyEnabled) {
            throw new ApiException(403, "已启用支付回调，旧支付接口默认关闭");
        }
        try {
            payments.applyPaymentSuccess(orderNo);
        } catch (PaymentNotApplicable exception) {
            throw new ApiException(409, exception.getMessage());
        }
        return Map.of("message", "模拟支付成功", "order_no", orderNo, "status", "PAID");
    }
}
