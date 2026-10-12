"""RailFlow：订单幂等、座位并发控制和位图占用。"""

import logging
from typing import Optional

from fastapi import HTTPException
from redis.exceptions import LockError, RedisError
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.core.redis_client import redis_client
from app.models.order import Order
from app.models.seat import TrainSeat
from app.models.train import Train  # 确保独立模块加载时 trains 元数据已注册
from app.utils.order_status import OrderStatus

logger = logging.getLogger(__name__)

IDEMPOTENCY_CACHE_TTL = 3600  # Redis 只是加速层，MySQL 唯一索引才是最终幂等保障
SEAT_LOCK_LEASE_SECONDS = 30
SEAT_LOCK_WAIT_SECONDS = 5


def build_order_idempotent_key(user_id: int, request_id: str) -> str:
    return f"order:request:{user_id}:{request_id}"


def _mask(start_station: int, end_station: int) -> int:
    """区间 [start_station,end_station) 的位图，最多 31 个区间。"""
    if not (0 <= start_station < end_station <= 31):
        raise HTTPException(status_code=422, detail="区间必须满足 0 <= 起点 < 终点 <= 31")
    return ((1 << end_station) - 1) ^ ((1 << start_station) - 1)


def _find_by_request(db: Session, user_id: int, request_id: str, *, lock: bool = False) -> Optional[Order]:
    q = db.query(Order).filter(Order.user_id == user_id, Order.request_id == request_id)
    if lock:
        q = q.with_for_update()
    return q.first()


def _validate_idempotent_request(old: Order, request) -> Order:
    """同一个 request_id 只能重放相同业务参数，不能换车次或座位。"""
    if (
        old.train_id != request.train_id
        or old.seat_id != request.seat_id
        or old.start_station != request.start_station
        or old.end_station != request.end_station
    ):
        raise HTTPException(status_code=409, detail="request_id 已被其他下单参数使用")
    return old


def _cache_order(user_id: int, request_id: str, order_no: str) -> None:
    """缓存属于非关键路径：写入失败不应该推翻已经提交的 MySQL 事务。"""
    try:
        redis_client.setex(build_order_idempotent_key(user_id, request_id), IDEMPOTENCY_CACHE_TTL, order_no)
    except RedisError:
        logger.warning("订单已写入数据库，但 Redis 幂等缓存写入失败：%s/%s", user_id, request_id, exc_info=True)


def create_order(db: Session, request):
    """创建待支付订单：Redis 座位互斥锁（可降级）+ MySQL 行锁与唯一索引（最终保障）。"""
    request_mask = _mask(request.start_station, request.end_station)

    # 1. Redis 仅提供查找线索，命中后仍以数据库订单和业务参数为准。
    redis_key = build_order_idempotent_key(request.user_id, request.request_id)
    try:
        cached_order_no = redis_client.get(redis_key)
    except RedisError:
        cached_order_no = None
        logger.warning("Redis 幂等缓存读取失败，直接使用 MySQL", exc_info=True)
    if cached_order_no:
        cached_order = (
            db.query(Order)
            .filter(
                Order.order_no == cached_order_no,
                Order.user_id == request.user_id,
                Order.request_id == request.request_id,
            )
            .first()
        )
        if cached_order is not None:
            return _validate_idempotent_request(cached_order, request)

    # 2. 无论缓存是否存在，都检查数据库，确保缓存过期后幂等仍然成立。
    try:
        existing = _find_by_request(db, request.user_id, request.request_id)
        if existing is not None:
            return _validate_idempotent_request(existing, request)
    except HTTPException:
        db.rollback()
        raise

    # 3. Redis 座位锁是减少热点行锁争用的优化，而不是唯一的防超卖措施。
    #    Redis 不可用时，降级为仅使用 MySQL 行锁。
    lock = None
    acquired = False
    try:
        try:
            lock = redis_client.lock(
                f"lock:railflow:seat:{request.train_id}:{request.seat_id}",
                timeout=SEAT_LOCK_LEASE_SECONDS,
                blocking_timeout=SEAT_LOCK_WAIT_SECONDS,
                sleep=0.02,
            )
            acquired = bool(lock.acquire(blocking=True))
        except RedisError:
            logger.warning("Redis 座位锁不可用，降级为 MySQL 行锁", exc_info=True)
            lock = None

        if lock is not None and not acquired:
            raise HTTPException(status_code=429, detail="座位抢购繁忙，请稍后重试")

        # 4. 必须锁定 seat 行；锁持有期间同时写 Bitmap + Order。
        seat = (
            db.query(TrainSeat)
            .filter(TrainSeat.id == request.seat_id, TrainSeat.train_id == request.train_id)
            .with_for_update()
            .first()
        )
        if seat is None:
            raise HTTPException(status_code=404, detail="指定车次的座位不存在")

        # 5. 拿到 MySQL 行锁后，再做一次当前读；防止两个并发请求都看到“无旧订单”。
        existing = _find_by_request(db, request.user_id, request.request_id, lock=True)
        if existing is not None:
            return _validate_idempotent_request(existing, request)

        if int(seat.bitmap or 0) & request_mask:
            raise HTTPException(status_code=409, detail="该座位当前区间已经占用")

        seat.bitmap = int(seat.bitmap or 0) | request_mask
        order = Order(
            user_id=request.user_id,
            request_id=request.request_id,
            train_id=request.train_id,
            seat_id=request.seat_id,
            start_station=request.start_station,
            end_station=request.end_station,
            request_mask=request_mask,
            amount=0,
            status=OrderStatus.PENDING_PAYMENT.value,
        )
        db.add(order)
        db.commit()  # 一次提交：座位占用与订单创建原子完成
        db.refresh(order)

        _cache_order(request.user_id, request.request_id, order.order_no)
        return order

    except IntegrityError as exc:
        # 可能是并发相同 request_id 触发 orders(user_id,request_id) 唯一索引。
        db.rollback()
        existing = _find_by_request(db, request.user_id, request.request_id)
        if existing is not None:
            return _validate_idempotent_request(existing, request)
        logger.error("订单插入产生数据库完整性错误", exc_info=True)
        raise HTTPException(status_code=409, detail="订单创建冲突，请重试") from exc
    except Exception:
        db.rollback()
        raise
    finally:
        if acquired and lock is not None:
            try:
                lock.release()  # redis-py Lock 使用所有者 token 安全释放
            except (RedisError, LockError):
                logger.warning("Redis 座位锁释放失败或租约已过期", exc_info=True)


def query_order(db: Session, user_id: int, order_no: str):
    order = db.query(Order).filter(Order.user_id == user_id, Order.order_no == order_no).first()
    if order is None:
        raise HTTPException(status_code=404, detail="订单不存在")
    return order
