# -*- coding: utf-8 -*-
# -*- coding: utf-8 -*-
"""
@File    : test_bitmap.py
@Author  : 你的名字
@Time    : 2026/10/11 16:07
@Desc    : 
"""
from app.utils.seat_bitmap import *


def test_bitmap():


    # 北京->南京

    mask = build_mask(
        0,
        2
    )


    print(
        bin(mask)
    )


    assert check_available(
        0b000,
        mask
    )


    assert not check_available(
        0b001,
        mask
    )