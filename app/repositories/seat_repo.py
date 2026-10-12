from sqlalchemy.orm import Session

from sqlalchemy import select

from app.models.seat import TrainSeat



def get_seats_by_train(
        db: Session,
        train_id: int
):

    """
    根据车次查询所有座位

    SQL:

    SELECT *
    FROM train_seats
    WHERE train_id = ?
    """


    stmt = (
        select(TrainSeat)
        .where(
            TrainSeat.train_id == train_id
        )
    )


    result = db.execute(stmt)


    seats = result.scalars().all()


    return seats