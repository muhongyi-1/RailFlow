"""
RailFlow Day7 - 支付回调接口

功能：
1. 接收模拟第三方支付平台回调
2. 验证 HMAC-SHA256 签名
3. 验证回调数据
4. 校验时间戳
5. 将支付成功消息发送到 RabbitMQ
6. 保留 Day6 模拟直付接口（默认禁用）

注意：
此模块只负责接收支付通知，不直接修改订单状态。
真正修改订单状态的是 RabbitMQ 消费者。
"""

import hashlib
import hmac
import logging
import os
import time

from fastapi import (
    APIRouter,
    Depends,
    Header,
    HTTPException,
    Request,
)
from pydantic import ValidationError
from sqlalchemy.orm import Session
from starlette.concurrency import run_in_threadpool

from app.core.config import settings
from app.core.payment_mq import publish_payment_success
from app.db.session import get_db
from app.schemas.payment import (
    PaymentCallbackRequest,
    PaymentCallbackResponse,
)
from app.services.payment_service import (
    PaymentNotApplicable,
    pay_order,
)


logger = logging.getLogger(__name__)

router = APIRouter(
    prefix="/api/v1/payment",
    tags=["支付"],
)


# ==========================================
# 1. 获取统一配置
# ==========================================

def get_payment_secret() -> str:
    """
    从项目 Settings 读取支付回调密钥。

    FastAPI 和模拟脚本都使用相同的配置读取方式。
    """

    secret = settings.RAILFLOW_PAYMENT_CALLBACK_SECRET

    if not secret:
        raise HTTPException(
            status_code=503,
            detail="支付回调密钥尚未配置",
        )

    return secret


# ==========================================
# 2. 生成 HMAC-SHA256 签名
# ==========================================

def generate_signature(
    raw_body: bytes,
    secret: str,
) -> str:
    """
    对原始请求体进行签名。

    注意：
    必须使用原始 JSON 字节。
    不能重新序列化 JSON 后再签名。
    """

    digest = hmac.new(
        secret.encode("utf-8"),
        raw_body,
        hashlib.sha256,
    ).hexdigest()

    return "sha256=" + digest


# ==========================================
# 3. 验证签名
# ==========================================

def verify_callback_signature(
    raw_body: bytes,
    header: str,
) -> bool:
    """
    比较服务端生成的签名与客户端传入的签名。
    """

    secret = get_payment_secret()

    expected_signature = generate_signature(
        raw_body=raw_body,
        secret=secret,
    )

    received_signature = header.strip()

    is_valid = hmac.compare_digest(
        expected_signature,
        received_signature,
    )

    if not is_valid:
        # 不打印密钥，避免泄露敏感配置
        logger.warning(
            "支付回调验签失败，body_sha256=%s",
            hashlib.sha256(raw_body).hexdigest()[:16],
        )

    return is_valid


# ==========================================
# 4. 支付回调接口
# ==========================================

@router.post(
    "/callback",
    response_model=PaymentCallbackResponse,
    status_code=202,
    summary="接收模拟支付回调并发送 RabbitMQ",
)
async def payment_callback(
    request: Request,
    x_payment_signature: str = Header(
        ...,
        alias="X-Payment-Signature",
    ),
):
    """
    Day7 核心接口。

    处理流程：
    请求 -> 验签 -> 校验数据 -> 校验时间戳
         -> RabbitMQ -> 返回202
    """

    # 第一步：读取原始请求体
    raw_body = await request.body()

    # 第二步：验证 HMAC-SHA256 签名
    if not verify_callback_signature(
        raw_body,
        x_payment_signature,
    ):
        raise HTTPException(
            status_code=401,
            detail="支付回调签名不正确",
        )

    # 第三步：校验 JSON 数据
    try:
        payload = PaymentCallbackRequest.model_validate_json(
            raw_body
        )

    except ValidationError as exc:
        raise HTTPException(
            status_code=422,
            detail=exc.errors(),
        ) from exc

    # 第四步：校验时间戳
    current_timestamp = int(time.time())

    if abs(current_timestamp - payload.timestamp) > 300:
        raise HTTPException(
            status_code=400,
            detail="支付回调时间戳已过期",
        )

    # 第五步：发送 RabbitMQ 消息
    try:
        await run_in_threadpool(
            publish_payment_success,
            payload.model_dump(),
        )

    except Exception as exc:
        logger.exception("支付回调发送 RabbitMQ 失败")

        raise HTTPException(
            status_code=503,
            detail="RabbitMQ 暂不可用，请稍后重试",
        ) from exc

    # 第六步：返回接收成功
    logger.info(
        "支付回调消息已入队，order_no=%s，payment_no=%s",
        payload.order_no,
        payload.payment_no,
    )

    return PaymentCallbackResponse(
        accepted=True,
        message="支付通知已入队，请查询订单最终状态",
        payment_no=payload.payment_no,
    )


# ==========================================
# 5. Day6 旧版模拟支付接口
# ==========================================

@router.post(
    "/{order_no}",
    deprecated=True,
    summary="Day6 模拟直接支付（默认禁用）",
)
def legacy_direct_payment(
    order_no: str,
    db: Session = Depends(get_db),
):
    """
    兼容 Day6 的旧接口。

    默认关闭，防止绕过 Day7 支付回调验签。
    仅允许在本地调试时显式开启。
    """

    if os.getenv(
        "RAILFLOW_ENABLE_LEGACY_PAYMENT_API", "0"
    ) != "1":
        raise HTTPException(
            status_code=403,
            detail="Day7 已启用支付回调，旧支付接口默认关闭",
        )

    try:
        order = pay_order(db, order_no)

    except PaymentNotApplicable as exc:
        raise HTTPException(
            status_code=409,
            detail=str(exc),
        ) from exc

    return {
        "message": "Day6 模拟支付成功",
        "order_no": order.order_no,
        "status": order.status,
    }