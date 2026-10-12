from fastapi import FastAPI

from app.api.order import router as order_router
from app.api.payment import router as payment_router
from app.api.trains import router as train_router
from app.api.seat import router as seat_router


app = FastAPI(
    title="RailFlow",
    version="1.0.0"
)


# 注册业务路由
app.include_router(order_router)
app.include_router(payment_router)
app.include_router(train_router)
app.include_router(seat_router)


@app.get("/")
def root():
    return {
        "service": "RailFlow",
        "status": "running"
    }


@app.get("/health")
def health():
    """
    健康检查接口
    用于测试服务是否正常启动
    """
    return {
        "status": "ok"
    }