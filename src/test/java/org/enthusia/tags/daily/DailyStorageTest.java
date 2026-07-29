package org.enthusia.tags.daily;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DailyStorageTest {
    @Test void ledgerRejectsDuplicatePlayerDateAndPersistsCompletion() throws Exception {
        var file = Files.createTempFile("enthusia-daily-", ".db").toFile();
        UUID player = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 7, 29);
        try (DailyStorage storage = new DailyStorage(file)) {
            assertTrue(storage.reserve(player, date, 5D));
            assertFalse(storage.reserve(player, date, 5D));
            storage.complete(player, date, new DailyState(date, 1, 1, 1, 5D, false));
            assertEquals(1, storage.load(player, true).currentStreak());
            assertEquals(5D, storage.load(player, true).totalAwarded());
        } finally {
            Files.deleteIfExists(file.toPath());
        }
    }

    @Test void restartMarksDepositingTransactionUncertain() throws Exception {
        var file = Files.createTempFile("enthusia-daily-uncertain-", ".db").toFile();
        UUID player = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 7, 29);
        try {
            try (DailyStorage storage = new DailyStorage(file)) {
                assertTrue(storage.reserve(player, date, 50D));
                storage.markDepositing(player, date, 100D);
                assertEquals(DailyStorage.TransactionStatus.DEPOSITING, storage.transaction(player, date).status());
            }
            try (DailyStorage reopened = new DailyStorage(file)) {
                assertEquals(DailyStorage.TransactionStatus.UNCERTAIN, reopened.transaction(player, date).status());
                assertEquals(100D, reopened.transaction(player, date).balanceBefore());
            }
        } finally {
            Files.deleteIfExists(file.toPath());
        }
    }

    @Test void animationPreferencePersistsWithoutChangingStreak() throws Exception {
        var file = Files.createTempFile("enthusia-daily-pref-", ".db").toFile();
        UUID player = UUID.randomUUID();
        try (DailyStorage storage = new DailyStorage(file)) {
            storage.saveAnimationPreference(player, false, true);
            assertFalse(storage.load(player, true).animationEnabled());
            assertEquals(0, storage.load(player, true).currentStreak());
        } finally {
            Files.deleteIfExists(file.toPath());
        }
    }
}
