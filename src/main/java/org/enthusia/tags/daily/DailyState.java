package org.enthusia.tags.daily;

import java.time.LocalDate;

public record DailyState(LocalDate lastClaimDate, int currentStreak, int highestStreak,
                         long totalClaims, double totalAwarded, boolean animationEnabled) {
    public static DailyState empty(boolean animationDefault) {
        return new DailyState(null, 0, 0, 0, 0D, animationDefault);
    }
}
