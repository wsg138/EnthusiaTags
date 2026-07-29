package org.enthusia.tags.daily;

import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class DailyRulesTest {
    private static final List<Double> PAYOUTS = List.of(5D, 10D, 15D, 20D, 30D, 40D, 50D);

    @Test void firstClaimStartsAtOne() {
        assertEquals(1, DailyRules.nextStreak(null, LocalDate.of(2026, 7, 29), 0));
    }

    @Test void sameDayIsRejected() {
        LocalDate date = LocalDate.of(2026, 7, 29);
        assertEquals(0, DailyRules.nextStreak(date, date, 4));
    }

    @Test void consecutiveDayAdvances() {
        assertEquals(5, DailyRules.nextStreak(LocalDate.of(2026, 7, 28), LocalDate.of(2026, 7, 29), 4));
    }

    @Test void missedDayResets() {
        assertEquals(1, DailyRules.nextStreak(LocalDate.of(2026, 7, 27), LocalDate.of(2026, 7, 29), 12));
    }

    @Test void seventhDayAndLaterPlateau() {
        assertAll(() -> assertEquals(50D, DailyRules.payout(7, PAYOUTS)),
            () -> assertEquals(50D, DailyRules.payout(365, PAYOUTS)));
    }
}
