# -*- coding: utf-8 -*-

from sqlalchemy.orm import Session


from app.repositories.seat_repo import (
    get_seats_by_train
)


from app.utils.seat_bitmap import (
    build_mask
)



def search_available_seats(
        db: Session,
        train_id: int,
        start_station: int,
        end_station: int
):
    """
    查询可用座位


    参数:

    train_id:
        车次id


    start_station:
        起始站编号


    end_station:
        终点站编号



    流程:

    1. 根据车次查询座位

    2. 根据区间生成mask

    3. bitmap判断冲突

    """



    # 生成用户购买区间mask

    request_mask = build_mask(
        start_station,
        end_station
    )



    # 查询该车次所有座位

    seats = get_seats_by_train(
        db,
        train_id
    )


    result = []



    # bitmap判断

    for seat in seats:


        # 没有冲突

        if (
            seat.bitmap
            &
            request_mask
        ) == 0:


            result.append(seat)



    return result