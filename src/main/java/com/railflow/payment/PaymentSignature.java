package com.railflow.payment;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class PaymentSignature {
    private PaymentSignature() {}

    /** Sign the original bytes; reserializing a JSON object would change its signature. */
    public static String generate(byte[] rawBody, String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("支付回调密钥尚未配置");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(rawBody));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", exception);
        }
    }

    public static boolean verify(byte[] rawBody, String secret, String received) {
        return received != null && MessageDigest.isEqual(
                generate(rawBody, secret).getBytes(StandardCharsets.UTF_8),
                received.strip().getBytes(StandardCharsets.UTF_8));
    }
}
