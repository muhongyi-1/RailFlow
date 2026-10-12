"""RailFlow 简单并发下单压测：不修改数据库和 Redis 的旧记录。"""

import argparse
import json
import statistics
import time
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from uuid import uuid4


def send(url: str, payload: dict):
    raw = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    request = Request(url, data=raw, method="POST", headers={"Content-Type": "application/json"})
    t0 = time.perf_counter()
    try:
        with urlopen(request, timeout=15) as response:
            result = json.loads(response.read().decode("utf-8"))
            return response.status, result.get("order_no"), time.perf_counter() - t0, ""
    except HTTPError as exc:
        return exc.code, None, time.perf_counter() - t0, exc.read().decode("utf-8", errors="replace")[:150]
    except (URLError, TimeoutError, OSError) as exc:
        return "NETWORK_ERROR", None, time.perf_counter() - t0, str(exc)[:150]


def main():
    p = argparse.ArgumentParser(description="RailFlow 并发下单实验（仅对空闲测试座位使用）")
    p.add_argument("--url", default="http://127.0.0.1:8011/api/v1/orders")
    p.add_argument("--train-id", type=int, default=1)
    p.add_argument("--seat-id", type=int, required=True)
    p.add_argument("--start", type=int, default=0, dest="start_station")
    p.add_argument("--end", type=int, default=2, dest="end_station")
    p.add_argument("--user-id", type=int, default=1)
    p.add_argument("--count", type=int, default=20)
    p.add_argument("--workers", type=int, default=10)
    p.add_argument("--mode", choices=["different", "same"], default="different",
                   help="different：不同请求争同座位；same：同一 request_id 重试")
    args = p.parse_args()
    if not 1 <= args.count <= 500 or not 1 <= args.workers <= 100:
        p.error("count 必须为 1..500，workers 必须为 1..100")
    if not 0 <= args.start_station < args.end_station <= 31:
        p.error("站点区间无效")

    run_id = uuid4().hex[:12]
    payloads = []
    for i in range(args.count):
        payloads.append({
            "train_id": args.train_id,
            "seat_id": args.seat_id,
            "start_station": args.start_station,
            "end_station": args.end_station,
            "user_id": args.user_id,
            "request_id": f"d8_{run_id}_{0 if args.mode == 'same' else i}",
        })

    print(f"开始：模式={args.mode}，请求={args.count}，并发线程={args.workers}")
    print("注意：测试产生真实的 PENDING_PAYMENT 订单，绝不要直接将 Bitmap 清零。")
    begin = time.perf_counter()
    with ThreadPoolExecutor(max_workers=args.workers) as executor:
        futures = [executor.submit(send, args.url, payload) for payload in payloads]
        results = [future.result() for future in as_completed(futures)]
    elapsed = time.perf_counter() - begin

    statuses = Counter(status for status, _, _, _ in results)
    order_nos = {order_no for status, order_no, _, _ in results if status == 200 and order_no}
    latencies = [duration * 1000 for _, _, duration, _ in results]

    print("HTTP 状态码统计：", dict(statuses))
    print("成功响应涉及的不同订单数：", len(order_nos))
    print("订单号：", sorted(order_nos))
    print(f"耗时：{elapsed:.2f} s，吞吐量：{args.count / elapsed:.1f} req/s")
    print(f"响应耗时：均值 {statistics.mean(latencies):.1f} ms，最大 {max(latencies):.1f} ms")
    errors = [(status, info) for status, _, _, info in results if status != 200]
    for status, info in errors[:5]:
        print("失败示例：", status, info)

    if args.mode == "different":
        print("验收标准：测试区间初始空闲时，理论上应只有 1 个成功订单，其余主要为 409/429。")
    else:
        print("验收标准：相同 request_id 的成功响应始终对应同一笔订单，不能生成多个订单。")


if __name__ == "__main__":
    main()
