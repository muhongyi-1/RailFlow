"""座位区间位图工具。"""


def build_mask(
    start_station: int,
    end_station: int,
) -> int:
    if start_station < 0 or end_station <= start_station:
        raise ValueError(
            "区间必须满足 0 <= start_station < end_station"
        )

    # 例如 0 → 2，得到 0b11，也就是十进制 3。
    return (
        ((1 << (end_station - start_station)) - 1)
        << start_station
    )


def build_segment_mask(
    start_station: int,
    end_station: int,
) -> int:
    # 兼容现有 seat_service.py 中使用的函数名。
    return build_mask(start_station, end_station)


def check_available(
    seat_bitmap: int,
    request_mask: int,
) -> bool:
    # 没有重叠的占用区间，座位就可用。
    return (seat_bitmap & request_mask) == 0