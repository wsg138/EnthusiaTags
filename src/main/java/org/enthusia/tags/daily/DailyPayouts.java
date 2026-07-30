package org.enthusia.tags.daily;

import java.util.List;

final class DailyPayouts {
    private DailyPayouts() {
    }

    static boolean valid(List<Double> payouts) {
        if (payouts.isEmpty()) {
            return false;
        }
        for (double payout : payouts) {
            if (!Double.isFinite(payout) || payout < 0D) {
                return false;
            }
        }
        return true;
    }
}
