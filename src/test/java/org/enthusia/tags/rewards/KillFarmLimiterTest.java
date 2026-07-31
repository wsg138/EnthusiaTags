package org.enthusia.tags.rewards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class KillFarmLimiterTest {
    @Test
    void limitsRepeatedVictimCreditsByCooldownAndWindowCap() {
        long hour = 3_600_000L;
        KillFarmLimiter.Decision first = KillFarmLimiter.evaluate(null, 0L,
            30 * 60_000L, 24 * hour, 3);
        KillFarmLimiter.Decision tooSoon = KillFarmLimiter.evaluate(first.nextState(),
            10 * 60_000L, 30 * 60_000L, 24 * hour, 3);
        KillFarmLimiter.Decision second = KillFarmLimiter.evaluate(first.nextState(),
            hour, 30 * 60_000L, 24 * hour, 3);
        KillFarmLimiter.Decision third = KillFarmLimiter.evaluate(second.nextState(),
            2 * hour, 30 * 60_000L, 24 * hour, 3);
        KillFarmLimiter.Decision capped = KillFarmLimiter.evaluate(third.nextState(),
            3 * hour, 30 * 60_000L, 24 * hour, 3);

        assertTrue(first.credited());
        assertFalse(tooSoon.credited());
        assertEquals(KillFarmLimiter.Reason.COOLDOWN, tooSoon.reason());
        assertTrue(second.credited());
        assertTrue(third.credited());
        assertFalse(capped.credited());
        assertEquals(KillFarmLimiter.Reason.VICTIM_LIMIT, capped.reason());
    }

    @Test
    void newWindowAllowsTheVictimAgain() {
        long window = 24 * 3_600_000L;
        KillFarmLimiter.Decision first = KillFarmLimiter.evaluate(null, 100L, 0L, window, 1);
        KillFarmLimiter.Decision nextWindow = KillFarmLimiter.evaluate(first.nextState(),
            100L + window, 0L, window, 1);
        assertTrue(nextWindow.credited());
    }

    @Test
    void malformedStateFailsOpenAsAResetRecord() {
        assertTrue(KillFarmLimiter.evaluate("bad-data", 500L, 0L, 1000L, 1).credited());
    }
}
