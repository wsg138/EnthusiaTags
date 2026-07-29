package org.enthusia.tags.daily;

import java.time.LocalDate;
import java.util.List;

public final class DailyRules {
    private DailyRules() {
    }

    public static int nextStreak(LocalDate lastClaim, LocalDate today, int currentStreak) {
        if (lastClaim == null) return 1;
        if (lastClaim.equals(today)) return 0;
        return lastClaim.plusDays(1).equals(today) ? Math.max(1, currentStreak + 1) : 1;
    }

    public static double payout(int streak, List<Double> schedule) {
        if (schedule == null || schedule.isEmpty() || streak < 1) return 0D;
        return schedule.get(Math.min(streak, schedule.size()) - 1);
    }
}
