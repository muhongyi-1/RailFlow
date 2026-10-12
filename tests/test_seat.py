from app.utils.seat_bitmap import (
    build_segment_mask
)



def test_bitmap():


    mask = build_segment_mask(
        0,
        2
    )


    assert mask == 3



def test_conflict():


    old = 3

    request = 2


    assert (
        old & request
    ) != 0