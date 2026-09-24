package org.enthusia.tags.advancements;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WarzoneStatsReaderTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private String record(String values) { return "players:\n  " + PLAYER + ":\n" + values; }

    @Test void readsVerifiedPersistedFieldsWithoutChangingSource() throws Exception {
        var result = WarzoneStatsReader.parse(record(
            "    wins: 50\n" +
            "    best-win-streak: 5\n" +
            "    advancements:\n" +
            "      challenges-sent: 2\n" +
            "      spoils-claims: 3\n" +
            "      mutual-draws: 4\n" +
            "      custom-rules-wins: 5\n" +
            "      restricted-mobility-wins: 6\n" +
            "      low-health-wins: 7\n"
        ));
        var stats = result.get(PLAYER);
        assertEquals(50, stats.wins());
        assertEquals(5, stats.bestStreak());
        assertEquals(2, stats.challengesSent());
        assertEquals(3, stats.spoilsClaims());
        assertEquals(4, stats.mutualDraws());
        assertEquals(5, stats.customRulesWins());
        assertEquals(6, stats.restrictedMobilityWins());
        assertEquals(7, stats.lowHealthWins());
        assertThrows(UnsupportedOperationException.class, () -> result.clear());
    }
    @Test void legacyProviderRowsTreatNewEvidenceAsUnknownZero() throws Exception {
        var stats = WarzoneStatsReader.parse(record(
            "    wins: 1\n    best-win-streak: 1\n"
        )).get(PLAYER);
        assertEquals(0, stats.challengesSent());
        assertEquals(0, stats.spoilsClaims());
        assertEquals(0, stats.mutualDraws());
        assertEquals(0, stats.customRulesWins());
        assertEquals(0, stats.restrictedMobilityWins());
        assertEquals(0, stats.lowHealthWins());
    }

    @Test void missingMalformedAndIncompleteAreNotZero() {
        for (String input : new String[]{"", "players: [", "other: 1", "players: 7",
                record("    wins: 1\n"), record("    wins: -1\n    best-win-streak: 0\n"),
                record("    wins: 1.5\n    best-win-streak: 0\n"),
                record("    wins: 2147483648\n    best-win-streak: 0\n"),
                record("    wins: 2\n    best-win-streak: 3\n"),
                "players:\n  bad-uuid:\n    wins: 1\n    best-win-streak: 1\n"}) {
            assertThrows(Exception.class, () -> WarzoneStatsReader.parse(input), input);
        }
    }

    @Test void malformedNewEvidenceIsRejected() {
        assertThrows(Exception.class, () -> WarzoneStatsReader.parse(record(
            "    wins: 1\n    best-win-streak: 1\n    advancements:\n      spoils-claims: -1\n"
        )));
    }

    @Test void presentNonSectionAdvancementEvidenceFailsClosed() {
        for (String value : new String[]{"7", "broken", "[]", "[one, two]", "true"}) {
            String yaml = record("    wins: 50\n    best-win-streak: 5\n    advancements: " + value + "\n");
            assertThrows(IllegalArgumentException.class, () -> WarzoneStatsReader.parse(yaml), value);
        }
    }

    @Test void emptyAdvancementSectionRetainsLegacyZeroCounters() throws Exception {
        var stats = WarzoneStatsReader.parse(record(
            "    wins: 1\n    best-win-streak: 1\n    advancements: {}\n")).get(PLAYER);
        assertEquals(0, stats.challengesSent());
        assertEquals(0, stats.lowHealthWins());
    }

    @Test void lossyNumericCoercionsStayRejected() {
        for (String value : new String[]{"1.0", "2147483648", "4294967296", "-1"}) {
            assertThrows(IllegalArgumentException.class, () -> WarzoneStatsReader.parse(record(
                "    wins: " + value + "\n    best-win-streak: 1\n")), value);
        }
    }

    @Test void caseVariantUuidRecordsCannotOverwriteHistory() {
        String uuid = "abcdefab-cdef-abcd-efab-cdefabcdefab";
        String body = "    wins: 1\n    best-win-streak: 1\n";
        String yaml = "players:\n  " + uuid + ":\n" + body + "  "
            + uuid.toUpperCase(java.util.Locale.ROOT) + ":\n" + body;
        assertThrows(IllegalArgumentException.class, () -> WarzoneStatsReader.parse(yaml));
    }

    @Test void absentPlayerIsNotManufactured() throws Exception {
        assertTrue(WarzoneStatsReader.parse("players: {}\n").isEmpty());
    }
}
