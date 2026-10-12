"""
RailFlow Day7 - 模拟第三方支付回调

功能：
1. 从项目 Settings 读取支付密钥
2. 模拟第三方支付成功通知
3. 生成 HMAC-SHA256 签名
4. 发送支付回调请求
5. 输出服务器响应

使用方式：
python -m scripts.simulate_payment_callback 订单号 --payment-no pay_001
"""

import argparse
import hashlib
import hmac
import json
import time

from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError
from uuid import uuid4

from app.core.config import settings


# =========================================
# 1. 获取项目统一配置
# =========================================

def get_payment_secret():
    """
    直接从 Settings 中读取密钥。

    不使用 os.getenv()
    不需要手动设置 PowerShell 环境变量。
    """

    secret = settings.RAILFLOW_PAYMENT_CALLBACK_SECRET

    if not secret:
        raise RuntimeError(
            "支付回调密钥未配置，请检查 app/core/config.py 和 .env"
        )

    return secret


# =========================================
# 2. 生成 HMAC-SHA256 签名
# =========================================

def generate_signature(raw_body: bytes, secret: str):

    signature = hmac.new(
        secret.encode("utf-8"),
        raw_body,
        hashlib.sha256
    ).hexdigest()

    return "sha256=" + signature


# =========================================
# 3. 模拟支付回调
# =========================================

def main():

    parser = argparse.ArgumentParser(
        description="RailFlow Day7 支付回调模拟"
    )

    parser.add_argument(
        "order_no",
        help="订单编号"
    )

    parser.add_argument(
        "--payment-no",
        default=None,
        help="支付流水号"
    )

    parser.add_argument(
        "--url",
        default="http://127.0.0.1:8011/api/v1/payment/callback",
        help="支付回调地址"
    )

    args = parser.parse_args()

    # 1. 从 Settings 获取密钥
    secret = get_payment_secret()

    # 2. 模拟支付成功消息
    event = {
        "order_no": args.order_no,
        "payment_no": args.payment_no or "pay_" + uuid4().hex,
        "status": "SUCCESS",
        "timestamp": int(time.time())
    }

    # 3. 序列化为原始 JSON 字节
    raw_body = json.dumps(
        event,
        ensure_ascii=False,
        separators=(",", ":")
    ).encode("utf-8")

    # 4. 生成签名
    signature = generate_signature(
        raw_body,
        secret
    )

    # 5. 创建 HTTP 请求
    request = Request(
        url=args.url,
        data=raw_body,
        method="POST",
        headers={
            "Content-Type": "application/json",
            "X-Payment-Signature": signature
        }
    )

    print("========== 模拟支付回调 ==========")
    print("订单号:", args.order_no)
    print("支付流水号:", event["payment_no"])
    print("Settings 密钥读取成功")
    print("发送支付回调请求...")
    print("=================================")

    # 6. 发送请求
    try:
        with urlopen(request, timeout=10) as response:

            response_body = response.read().decode("utf-8")

            print("HTTP 状态码:", response.status)
            print("响应内容:", response_body)

    except HTTPError as exc:

        print("HTTP 错误:", exc.code)
        print("错误详情:", exc.read().decode("utf-8"))

    except URLError as exc:

        print("连接失败:", exc)


if __name__ == "__main__":
    main()