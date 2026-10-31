package com.railflow.maintenance;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Pure inventory analysis. Expired pending orders still reserve their intervals. */
public final class InventoryReconciler {
    public static final Set<String> ACTIVE_STATUSES = Set.of("PAID", "PENDING_PAYMENT");

    private InventoryReconciler() {}

    public record OrderView(String orderNo, String status, long trainId, long seatId,
                            int startStation, int endStation, int requestMask) {}

    public record Report(long seatId, String seatNo, int current, int expected, int missing,
                         int extra, int activeCount, List<String> issues) {
        public Report { issues = List.copyOf(issues); }
        public boolean consistent() { return issues.isEmpty() && current == expected; }
        public boolean safeToAdd() { return issues.isEmpty() && missing != 0 && extra == 0; }
    }

    public static Report inspect(long seatId, String seatNo, long trainId, int bitmap,
                                 Iterable<OrderView> orders) {
        List<String> issues = new ArrayList<>();
        if (bitmap < 0) issues.add("座位 Bitmap 为负数，禁止自动修复");
        int used = 0;
        int count = 0;
        List<OrderView> previous = new ArrayList<>();
        for (OrderView order : orders) {
            if (!ACTIVE_STATUSES.contains(order.status())) continue;
            count++;
            if (order.seatId() != seatId || order.trainId() != trainId) {
                issues.add("订单 " + order.orderNo() + " 座位/车次关联错误");
                continue;
            }
            int start = order.startStation();
            int end = order.endStation();
            if (start < 0 || start >= end || end > 31) {
                issues.add("订单 " + order.orderNo() + " 乘车区间非法：" + start + " -> " + end);
                continue;
            }
            int calculated = (int) (((1L << end) - 1) ^ ((1L << start) - 1));
            if (order.requestMask() != calculated) {
                issues.add("订单 " + order.orderNo() + " 掩码错误：存储=" + order.requestMask() + "，应为=" + calculated);
                continue;
            }
            int overlap = used & order.requestMask();
            if (overlap != 0) {
                List<String> conflicts = previous.stream().filter(o -> (o.requestMask() & order.requestMask()) != 0)
                        .map(OrderView::orderNo).toList();
                issues.add("订单 " + order.orderNo() + " 与 " + String.join(",", conflicts) + " 区间重叠，掩码=" + overlap);
            }
            previous.add(order);
            used |= order.requestMask();
        }
        return new Report(seatId, seatNo, bitmap, used, used & ~bitmap, bitmap & ~used, count, issues);
    }
}
