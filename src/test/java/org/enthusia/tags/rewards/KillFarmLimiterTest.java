package org.enthusia.tags.rewards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class KillFarmLimiterTest {
    private static final long HOUR = 3_600_000L;

    @Test
    void allowsFiveKillsThenBlocksTheSixthInsideTheRollingHour() {
        KillFarmLimiter.Decision decision = KillFarmLimiter.evaluate(null, 0L, HOUR, 5);
        for (int minute = 10; minute <= 40; minute += 10) {
            decision = KillFarmLimiter.evaluate(decision.nextState(), minute * 60_000L, HOUR, 5);
            assertTrue(decision.credited());
        }

        KillFarmLimiter.Decision sixth = KillFarmLimiter.evaluate(
            decision.nextState(), 50 * 60_000L, HOUR, 5);

        assertFalse(sixth.credited());
        assertEquals(KillFarmLimiter.Reason.VICTIM_LIMIT, sixth.reason());
    }

    @Test
    void oldestKillDropsOutAtExactlySixtyMinutes() {
        KillFarmLimiter.Decision decision = KillFarmLimiter.evaluate(null, 0L, HOUR, 5);
        for (int minute = 10; minute <= 40; minute += 10) {
            decision = KillFarmLimiter.evaluate(decision.nextState(), minute * 60_000L, HOUR, 5);
        }

        KillFarmLimiter.Decision atBoundary = KillFarmLimiter.evaluate(
            decision.nextState(), HOUR, HOUR, 5);

        assertTrue(atBoundary.credited());
    }

    @Test
    void legacyFixedWindowStateMigratesConservatively() {
        KillFarmLimiter.Decision migrated = KillFarmLimiter.evaluate(
            "0|3|1000", 1000L, HOUR, 5);

        assertTrue(migrated.credited());
        assertTrue(migrated.nextState().startsWith("v2|"));
        KillFarmLimiter.Decision fifth = KillFarmLimiter.evaluate(
            migrated.nextState(), 2000L, HOUR, 5);
        assertTrue(fifth.credited());
        assertFalse(KillFarmLimiter.evaluate(
            fifth.nextState(), 3000L, HOUR, 5).credited());
    }

    @Test
    void expiredRecordsCanBePrunedWithoutRemovingActiveWindows() {
        KillFarmLimiter.Decision first = KillFarmLimiter.evaluate(null, 100L, HOUR, 5);

        assertFalse(KillFarmLimiter.isExpired(first.nextState(), 100L + HOUR - 1L, HOUR));
        assertTrue(KillFarmLimiter.isExpired(first.nextState(), 100L + HOUR, HOUR));
        assertTrue(KillFarmLimiter.isExpired("bad-data", 500L, HOUR));
    }

    @Test
    void malformedStateFailsOpenAsAResetRecord() {
        assertTrue(KillFarmLimiter.evaluate("bad-data", 500L, HOUR, 5).credited());
    }
}
