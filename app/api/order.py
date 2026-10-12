from fastapi import APIRouter, Depends, Query
from sqlalchemy.orm import Session


from app.db.session import get_db

from app.schemas.order_booking import (
    OrderCreateRequest,
    OrderResult,
)

from app.services.order_service import (
    create_order as create_order_service,
    query_order as query_order_service,
)



router = APIRouter(
    prefix="/api/v1/orders",
    tags=["订单"],
)



@router.post(
    "",
    response_model=OrderResult
)
def create_order(
    request: OrderCreateRequest,
    db: Session = Depends(get_db),
):

    return create_order_service(
        db,
        request
    )



@router.get(
    "/{order_no}",
    response_model=OrderResult
)
def get_order(
    order_no: str,
    user_id: int = Query(..., gt=0),
    db: Session = Depends(get_db),
):

    return query_order_service(
        db,
        user_id,
        order_no
    )