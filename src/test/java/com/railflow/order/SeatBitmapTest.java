package com.railflow.order;

import com.railflow.common.ApiException;
import com.railflow.common.SeatBitmap;
import com.railflow.domain.OrderStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.*;

class SeatBitmapTest {
    @ParameterizedTest
    @CsvSource({"0,2,3", "1,3,6", "0,31,2147483647", "30,31,1073741824"})
    void buildsExactSegmentMaskWithoutSignedOverflow(int start, int end, int expected) {
        assertThat(SeatBitmap.mask(start, end)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"-1,2", "1,1", "2,1", "0,32", "31,32"})
    void rejectsInvalidAndOutOfRangeIntervals(int start, int end) {
        assertThatThrownBy(() -> SeatBitmap.mask(start, end)).isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(422);
    }

    @Test
    void allowsAdjacentIntervalsAndRejectsOverlap() {
        assertThat(SeatBitmap.available(3, SeatBitmap.mask(2, 4))).isTrue();
        assertThat(SeatBitmap.available(3, SeatBitmap.mask(1, 3))).isFalse();
    }

    @Test
    void finalOrderStatesCannotTransition() {
        for (OrderStatus state : OrderStatus.values()) {
            for (OrderStatus target : OrderStatus.values()) {
                assertThat(state.canTransitionTo(target)).isEqualTo(
                        state == OrderStatus.PENDING_PAYMENT && target != OrderStatus.PENDING_PAYMENT);
            }
        }
    }
}
