"""Register all SQLAlchemy models for metadata and foreign keys."""

from app.models.train import Station, Train, TrainStop
from app.models.seat import TrainSeat
from app.models.order import Order

__all__ = ["Station", "Train", "TrainStop", "TrainSeat", "Order"]
