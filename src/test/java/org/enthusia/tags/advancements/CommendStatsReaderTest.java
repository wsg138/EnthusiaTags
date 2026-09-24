package org.enthusia.tags.advancements;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CommendStatsReaderTest {
    private static final UUID PLAYER =
        UUID.fromString("00000000-0000-0000-0000-000000000001");

    private String record(String body) {
        return "advancementEvidence:\n  " + PLAYER + ":\n" + body;
    }

    @Test void readsProviderOwnedEvidence() throws Exception {
        var stats = CommendStatsReader.parse(record(
            "    positiveReceived: true\n" +
            "    maxOverall: 23\n" +
            "    minOverall: -27\n" +
            "    recoveredFromSevere: true\n" +
            "    categoryMax:\n" +
            "      WAS_KIND: 5\n" +
            "      GAVE_ITEMS: 6\n" +
            "      TRUSTWORTHY: 7\n" +
            "      GOOD_STALL: 8\n"
        )).get(PLAYER);

        assertTrue(stats.positiveReceived());
        assertEquals(23, stats.maxOverall());
        assertEquals(-27, stats.minOverall());
        assertTrue(stats.recoveredFromSevere());
        assertEquals(5, stats.kindMax());
        assertEquals(6, stats.generousMax());
        assertEquals(7, stats.trustworthyMax());
        assertEquals(8, stats.goodStallMax());
    }
    @Test void missingCategoryMaxDefaultsToZero() throws Exception {
        var stats = CommendStatsReader.parse(record(
            "    positiveReceived: false\n" +
            "    maxOverall: 0\n" +
            "    minOverall: 0\n" +
            "    recoveredFromSevere: false\n"
        )).get(PLAYER);
        assertEquals(0, stats.kindMax());
        assertEquals(0, stats.generousMax());
        assertEquals(0, stats.trustworthyMax());
        assertEquals(0, stats.goodStallMax());
    }

    @Test void versionNineWithoutEvidenceMeansNoRepHistoryYet() throws Exception {
        assertTrue(CommendStatsReader.parse("dataVersion: 9\nplayers: {}\n").isEmpty());
    }

    @Test void emptyEvidenceSectionIsValidAndImmutable() throws Exception {
        var result = CommendStatsReader.parse("advancementEvidence: {}\n");
        assertTrue(result.isEmpty());
        assertThrows(UnsupportedOperationException.class,
            () -> result.put(PLAYER, null));
    }

    @Test void versionNineCannotHidePresentWrongShapedEvidence() {
        for (String value : new String[]{"[]", "[item]", "42", "broken", "false"}) {
            assertThrows(IllegalArgumentException.class, () -> CommendStatsReader.parse(
                "dataVersion: 9\nadvancementEvidence: " + value + "\n"), value);
        }
    }

    @Test void presentWrongShapedCategoryMaxIsNotMissingHistory() {
        for (String value : new String[]{"[]", "[item]", "42", "broken", "false"}) {
            String yaml = record("    positiveReceived: true\n    maxOverall: 20\n"
                + "    minOverall: -25\n    recoveredFromSevere: true\n    categoryMax: " + value + "\n");
            assertThrows(IllegalArgumentException.class, () -> CommendStatsReader.parse(yaml), value);
        }
    }

    @Test void emptyCategoryMaxIsStillAValidZeroHistory() throws Exception {
        var stats = CommendStatsReader.parse(record("    positiveReceived: false\n    maxOverall: 0\n"
            + "    minOverall: 0\n    recoveredFromSevere: false\n    categoryMax: {}\n")).get(PLAYER);
        assertEquals(0, stats.kindMax());
        assertEquals(0, stats.goodStallMax());
    }

    @Test void mixedCaseDuplicateUuidRecordsAreRejected() {
        String uuid = "abcdefab-cdef-abcd-efab-cdefabcdefab";
        String fields = "    positiveReceived: true\n    maxOverall: 20\n"
            + "    minOverall: -25\n    recoveredFromSevere: true\n";
        String yaml = "advancementEvidence:\n  " + uuid + ":\n" + fields
            + "  " + uuid.toUpperCase(java.util.Locale.ROOT) + ":\n" + fields;
        assertThrows(IllegalArgumentException.class, () -> CommendStatsReader.parse(yaml));
    }

    @Test void missingOrMalformedEvidenceFailsClosed() {
        for (String input : new String[]{
            "",
            "players: {}\n",
            "advancementEvidence: [\n",
            record("    positiveReceived: maybe\n    maxOverall: 0\n    minOverall: 0\n    recoveredFromSevere: false\n"),
            record("    positiveReceived: false\n    maxOverall: -1\n    minOverall: 0\n    recoveredFromSevere: false\n"),
            record("    positiveReceived: false\n    maxOverall: 0\n    minOverall: 1\n    recoveredFromSevere: false\n"),
            record("    positiveReceived: false\n    maxOverall: 0\n    minOverall: 0\n    recoveredFromSevere: false\n    categoryMax:\n      WAS_KIND: -1\n"),
            "advancementEvidence:\n  not-a-uuid:\n    positiveReceived: false\n    maxOverall: 0\n    minOverall: 0\n    recoveredFromSevere: false\n"
        }) {
            assertThrows(Exception.class, () -> CommendStatsReader.parse(input), input);
        }
    }
}
