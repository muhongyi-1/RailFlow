package com.railflow.common;

/** Each bit represents one adjacent station segment; MySQL INT permits 31 nonnegative bits. */
public final class SeatBitmap {
    private SeatBitmap() {}

    public static int mask(int startStation, int endStation) {
        if (startStation < 0 || startStation >= endStation || endStation > 31) {
            throw new ApiException(422, "区间必须满足 0 <= 起点 < 终点 <= 31");
        }
        // Shift a long so the boundary at bit 31 never overflows a signed int.
        return (int) (((1L << endStation) - 1L) ^ ((1L << startStation) - 1L));
    }

    public static boolean available(int seatBitmap, int requestMask) {
        return (seatBitmap & requestMask) == 0;
    }
}
