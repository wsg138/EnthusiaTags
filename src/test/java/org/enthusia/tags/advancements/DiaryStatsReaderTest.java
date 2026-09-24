package org.enthusia.tags.advancements;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DiaryStatsReaderTest {
    private static final UUID PLAYER =
        UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test void readsProviderOwnedEvidence() throws Exception {
        String yaml = """
            players:
              00000000-0000-0000-0000-000000000001:
                id: diary-id
                issuedAt: 123
                advancements:
                  received: true
                  edits: 25
                  destructionAttempts: 10
                  voidReturns: 1
                  containerAttempts: 1
                  groundPickups: 2
            """;
        var stats = DiaryStatsReader.parse(yaml).get(PLAYER);
        assertTrue(stats.received());
        assertEquals(25, stats.edits());
        assertEquals(10, stats.destructionAttempts());
        assertEquals(1, stats.voidReturns());
        assertEquals(1, stats.containerAttempts());
        assertEquals(2, stats.groundPickups());
    }

    @Test void legacyIssuedPlayerReceivesOnlyProvableHistoricalCredit() throws Exception {
        String yaml = """
            players:
              00000000-0000-0000-0000-000000000001:
                id: diary-id
                issuedAt: 123
            """;
        var stats = DiaryStatsReader.parse(yaml).get(PLAYER);
        assertTrue(stats.received());
        assertEquals(0, stats.edits());
        assertEquals(0, stats.destructionAttempts());
        assertEquals(0, stats.voidReturns());
        assertEquals(0, stats.containerAttempts());
        assertEquals(0, stats.groundPickups());
    }

    @Test void emptyStoreIsValid() throws Exception {
        assertTrue(DiaryStatsReader.parse("lastWorldUid: abc\n").isEmpty());
    }
    @Test void wrongShapedTopLevelPlayersAreRejected() {
        for (String value : new String[]{"[]", "broken", "true", "3"}) {
            assertThrows(IllegalArgumentException.class, () -> DiaryStatsReader.parse("players: " + value + "\n"), value);
        }
    }

    @Test void wrongShapedPlayerFieldsCannotBecomeEmptyEvidence() {
        for (String field : new String[]{"issuedAt: broken", "issuedAt: 1.5", "issuedAt: true",
                "issuedAt: []", "issuedAt: -1", "advancements: []", "advancements: broken"}) {
            String yaml = "players:\n  " + PLAYER + ":\n    id: diary-id\n    " + field + "\n";
            assertThrows(IllegalArgumentException.class, () -> DiaryStatsReader.parse(yaml), field);
        }
    }

    @Test void duplicateCanonicalPlayerIdentityIsRejected() {
        String id = "abcdefab-cdef-abcd-efab-cdefabcdefab";
        String body = ":\n    id: diary-id\n    issuedAt: 123\n";
        String yaml = "players:\n  " + id + body + "  " + id.toUpperCase(java.util.Locale.ROOT) + body;
        assertThrows(IllegalArgumentException.class, () -> DiaryStatsReader.parse(yaml));
    }

    @Test void longIssuanceTimestampAndEmptySectionsRemainValid() throws Exception {
        String yaml = "players:\n  " + PLAYER + ":\n    issuedAt: 2147483648\n    advancements: {}\n";
        assertTrue(DiaryStatsReader.parse(yaml).get(PLAYER).received());
        assertTrue(DiaryStatsReader.parse("players: {}\n").isEmpty());
    }

    @Test void pathReaderUsesTheSameStrictParser(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        java.nio.file.Path file = directory.resolve("diaries.yml");
        String valid = "players:\n  " + PLAYER + ":\n    issuedAt: 123\n";
        java.nio.file.Files.writeString(file, valid);
        assertEquals(DiaryStatsReader.parse(valid), DiaryStatsReader.read(file));
        java.nio.file.Files.writeString(file, "players: [\n");
        assertThrows(Exception.class, () -> DiaryStatsReader.read(file));
        java.nio.file.Files.writeString(file, "players: []\n");
        assertThrows(IllegalArgumentException.class, () -> DiaryStatsReader.read(file));
    }

    @Test void malformedEvidenceFailsClosed() {
        String negative = """
            players:
              00000000-0000-0000-0000-000000000001:
                id: diary-id
                issuedAt: 123
                advancements:
                  edits: -1
            """;
        assertThrows(Exception.class, () -> DiaryStatsReader.parse(negative));

        String badUuid = """
            players:
              not-a-uuid:
                id: diary-id
                issuedAt: 123
            """;
        assertThrows(Exception.class, () -> DiaryStatsReader.parse(badUuid));
    }
}
