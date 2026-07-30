package org.enthusia.tags.daily;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DailyPayoutsTest {
    @Test
    void acceptsFiniteNonNegativePayouts() {
        assertTrue(DailyPayouts.valid(List.of(0D, 5D, 50D)));
    }

    @Test
    void rejectsUnsafePayoutValues() {
        assertAll(
            () -> assertFalse(DailyPayouts.valid(List.of())),
            () -> assertFalse(DailyPayouts.valid(List.of(5D, -1D))),
            () -> assertFalse(DailyPayouts.valid(List.of(5D, Double.NaN))),
            () -> assertFalse(DailyPayouts.valid(List.of(5D, Double.POSITIVE_INFINITY)))
        );
    }
}
