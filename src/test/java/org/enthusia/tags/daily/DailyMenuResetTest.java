package org.enthusia.tags.daily;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class DailyMenuResetTest {
    @Test
    void missedDayResetsDisplayedCurrentStreakBeforeNextClaim() {
        LocalDate today = LocalDate.of(2026, 7, 30);
        DailyState stale = new DailyState(today.minusDays(2), 10, 12, 10, 0D, true);

        DailyMenuModel.View view = DailyMenuModel.build(stale, today,
            List.of(5D, 10D, 15D, 20D, 30D, 40D, 50D), null);

        assertAll(
            () -> assertEquals(0, view.currentStreak()),
            () -> assertEquals(12, view.bestStreak()),
            () -> assertEquals(1, view.activeDay()),
            () -> assertEquals(0, view.claimIndex())
        );
    }
}
