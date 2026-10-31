package com.railflow.maintenance;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

/** One-shot commands use --option=value, as supported by Spring Boot ApplicationArguments. */
@Component
public class MaintenanceCommands {
    private final InventoryMaintenance inventory;
    private final ObjectMapper mapper;
    private final String paymentSecret;

    public MaintenanceCommands(InventoryMaintenance inventory, ObjectMapper mapper,
                               @Value("${railflow.payment.secret:}") String paymentSecret) {
        this.inventory = inventory;
        this.mapper = mapper;
        this.paymentSecret = paymentSecret;
    }

    public void run(String command, ApplicationArguments args) {
        int code = execute(command, args);
        if (code != 0) throw new CommandFailure(code, "命令 " + command + " 结束，退出码=" + code);
    }

    public int execute(String command, ApplicationArguments args) {
        return switch (command) {
            case "inventory-audit" -> inventory.audit(optionalPositiveLong(args, "seat-id"), positiveInt(args, "limit", 20));
            case "inventory-repair" -> repair(args);
            case "dead-letter-audit" -> inventory.deadLetterAudit(required(args, "order-no"), required(args, "payment-no"),
                    Long.parseLong(required(args, "event-timestamp")));
            case "payment-close-race" -> { PaymentCloseRace.demo(); yield 0; }
            case "booking-load-test" -> bookingLoadTest(args);
            case "simulate-payment-callback" -> simulatePaymentCallback(args);
            default -> throw new IllegalArgumentException("未知维护命令：" + command);
        };
    }

    private int repair(ApplicationArguments args) {
        boolean demo = flag(args, "demo");
        boolean apply = flag(args, "apply");
        Long seatId = optionalPositiveLong(args, "seat-id");
        if (demo == (seatId != null)) throw new IllegalArgumentException("必须且只能指定 --demo 或 --seat-id=ID");
        if (demo) {
            if (apply) throw new IllegalArgumentException("--demo 不能与 --apply 同时使用");
            InventoryMaintenance.demo();
            return 0;
        }
        if (apply) {
            Long confirmation = optionalPositiveLong(args, "confirm-seat-id");
            if (!flag(args, "maintenance-mode") || !seatId.equals(confirmation))
                throw new IllegalArgumentException("写入前必须停止所有相关服务，并指定 --maintenance-mode 和 --confirm-seat-id=ID（与 --seat-id 一致）");
            System.out.println("[警告] 维护模式库存补位；请确认已停止 API、支付消费者、关单任务及所有写入服务。");
            System.out.print("输入 REPAIR-" + seatId + " 继续：");
            try {
                String line = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
                if (line == null || !line.strip().equals("REPAIR-" + seatId)) {
                    System.out.println("[取消] 未确认，未执行补偿。");
                    return 0;
                }
            } catch (java.io.IOException e) {
                throw new IllegalStateException("无法读取确认；未执行补偿", e);
            }
        }
        return inventory.inspectSeat(seatId, apply);
    }

    private int simulatePaymentCallback(ApplicationArguments args) {
        if (paymentSecret.isBlank()) throw new IllegalStateException("支付回调密钥未配置，请设置 RAILFLOW_PAYMENT_CALLBACK_SECRET");
        String orderNo = value(args, "order-no", null);
        if (orderNo == null && args.getNonOptionArgs().size() == 1) orderNo = args.getNonOptionArgs().get(0);
        if (orderNo == null || !orderNo.matches("[a-fA-F0-9]{32}")) throw new IllegalArgumentException("需提供合法 --order-no=32位十六进制订单号");
        String paymentNo = value(args, "payment-no", "pay_" + UUID.randomUUID().toString().replace("-", ""));
        if (!paymentNo.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("--payment-no 格式非法");
        String url = value(args, "url", "http://127.0.0.1:8011/api/v1/payment/callback");
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("order_no", orderNo);
        event.put("payment_no", paymentNo);
        event.put("status", "SUCCESS");
        event.put("timestamp", Instant.now().getEpochSecond());
        try {
            byte[] raw = mapper.writeValueAsBytes(event);
            HttpRequest request = HttpRequest.newBuilder(httpUri(url)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json").header("X-Payment-Signature", signature(raw, paymentSecret))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(raw)).build();
            System.out.println("模拟支付回调：订单=" + orderNo + "，支付流水=" + paymentNo);
            HttpResponse<String> response = client().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            System.out.println("HTTP 状态码：" + response.statusCode());
            System.out.println("响应内容：" + response.body());
            return response.statusCode() >= 200 && response.statusCode() < 300 ? 0 : 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("支付请求被中断", e);
        } catch (java.io.IOException e) {
            System.out.println("连接失败：" + e.getMessage());
            return 1;
        }
    }

    static String signature(byte[] raw, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(raw));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("无法生成 HMAC-SHA256", e);
        }
    }

    record LoadResult(String status, String orderNo, double milliseconds, String error) {}

    private int bookingLoadTest(ApplicationArguments args) {
        long trainId = positiveLong(args, "train-id", 1);
        Long seatId = optionalPositiveLong(args, "seat-id");
        if (seatId == null) throw new IllegalArgumentException("必须指定 --seat-id=ID");
        long userId = positiveLong(args, "user-id", 1);
        int start = Integer.parseInt(value(args, "start", "0"));
        int end = Integer.parseInt(value(args, "end", "2"));
        if (start < 0 || start >= end || end > 31) throw new IllegalArgumentException("站点区间必须满足 0 <= start < end <= 31");
        int count = positiveInt(args, "count", 20);
        int workers = positiveInt(args, "workers", 10);
        if (count > 500 || workers > 100) throw new IllegalArgumentException("count 必须为 1..500，workers 必须为 1..100");
        String mode = value(args, "mode", "different");
        if (!Set.of("same", "different").contains(mode)) throw new IllegalArgumentException("--mode 必须为 same 或 different");
        URI uri = httpUri(value(args, "url", "http://127.0.0.1:8011/api/v1/orders"));
        String runId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        HttpClient client = client();
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        List<Future<LoadResult>> futures = new ArrayList<>();
        System.out.println("开始：模式=" + mode + "，请求=" + count + "，并发线程=" + workers);
        System.out.println("注意：测试产生真实 PENDING_PAYMENT 订单，只应使用空闲测试区间，绝不要直接清零 Bitmap。");
        long begin = System.nanoTime();
        try {
            for (int i = 0; i < count; i++) {
                Map<String, Object> payload = Map.of("train_id", trainId, "seat_id", seatId,
                        "start_station", start, "end_station", end, "user_id", userId,
                        "request_id", "d8_" + runId + "_" + (mode.equals("same") ? 0 : i));
                futures.add(pool.submit(() -> send(client, uri, payload)));
            }
            List<LoadResult> results = new ArrayList<>();
            for (Future<LoadResult> future : futures) results.add(future.get());
            double elapsed = (System.nanoTime() - begin) / 1_000_000_000.0;
            Map<String, Integer> statuses = new LinkedHashMap<>();
            Set<String> orders = new TreeSet<>();
            for (LoadResult result : results) {
                statuses.merge(result.status(), 1, Integer::sum);
                if (result.status().equals("200") && result.orderNo() != null) orders.add(result.orderNo());
            }
            System.out.println("HTTP 状态码统计：" + statuses);
            System.out.println("成功响应涉及的不同订单数：" + orders.size() + "，订单号：" + orders);
            System.out.printf("耗时：%.2f s，吞吐量：%.1f req/s，平均响应：%.1f ms，最大响应：%.1f ms%n", elapsed,
                    count / elapsed, results.stream().mapToDouble(LoadResult::milliseconds).average().orElse(0),
                    results.stream().mapToDouble(LoadResult::milliseconds).max().orElse(0));
            results.stream().filter(result -> !result.status().equals("200")).limit(5)
                    .forEach(result -> System.out.println("失败示例：" + result.status() + " " + result.error()));
            System.out.println(mode.equals("different")
                    ? "验收标准：初始空闲区间只能产生 1 个成功订单，其余主要为 409/429。"
                    : "验收标准：相同 request_id 的成功响应始终对应同一笔订单。");
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("压测被中断", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("压测任务失败", e.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    private LoadResult send(HttpClient client, URI uri, Map<String, Object> payload) {
        long begin = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(payload))).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            double millis = (System.nanoTime() - begin) / 1_000_000.0;
            if (response.statusCode() != 200) return new LoadResult(Integer.toString(response.statusCode()), null, millis, trim(response.body()));
            String orderNo = mapper.readTree(response.body()).path("order_no").asText(null);
            return new LoadResult("200", orderNo, millis, "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new LoadResult("NETWORK_ERROR", null, (System.nanoTime() - begin) / 1_000_000.0, "Interrupted");
        } catch (java.io.IOException e) {
            return new LoadResult("NETWORK_ERROR", null, (System.nanoTime() - begin) / 1_000_000.0, trim(e.toString()));
        }
    }

    private static HttpClient client() { return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(); }
    private static URI httpUri(String value) {
        URI uri = URI.create(value);
        if ((!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) || uri.getHost() == null)
            throw new IllegalArgumentException("--url 必须是有效 HTTP(S) 地址");
        return uri;
    }
    private static String trim(String value) { return value.length() <= 150 ? value : value.substring(0, 150); }
    private static boolean flag(ApplicationArguments args, String key) {
        if (!args.containsOption(key)) return false;
        List<String> values = args.getOptionValues(key);
        if (values != null && !values.isEmpty()) throw new IllegalArgumentException("--" + key + " 是无参数标志");
        return true;
    }
    private static String required(ApplicationArguments args, String key) {
        String value = value(args, key, null);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("必须指定 --" + key + "=值");
        return value;
    }
    private static String value(ApplicationArguments args, String key, String fallback) {
        if (!args.containsOption(key)) return fallback;
        List<String> values = args.getOptionValues(key);
        if (values == null || values.size() != 1 || values.get(0).isEmpty())
            throw new IllegalArgumentException("参数需使用 --" + key + "=值，且不能重复");
        return values.get(0);
    }
    private static Long optionalPositiveLong(ApplicationArguments args, String key) {
        String value = value(args, key, null);
        if (value == null) return null;
        long parsed = Long.parseLong(value);
        if (parsed <= 0) throw new IllegalArgumentException("--" + key + " 必须是正整数");
        return parsed;
    }
    private static long positiveLong(ApplicationArguments args, String key, long fallback) {
        Long value = optionalPositiveLong(args, key);
        return value == null ? fallback : value;
    }
    private static int positiveInt(ApplicationArguments args, String key, int fallback) {
        return Math.toIntExact(positiveLong(args, key, fallback));
    }

    public static final class CommandFailure extends RuntimeException implements ExitCodeGenerator {
        private final int code;
        public CommandFailure(int code, String message) { super(message); this.code = code; }
        @Override public int getExitCode() { return code; }
    }
}
