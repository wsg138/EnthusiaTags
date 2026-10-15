package org.enthusia.tags.advancements;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.enthusia.tags.advancements.application.CompletionHistory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SqliteCompletionLedgerTest {
    @TempDir Path directory;

    @Test void restartAndProviderRegressionRestoreSilentlyWithIndependentPlayerAndKey() throws Exception {
        Path file = directory.resolve("history.db");
        UUID player = UUID.randomUUID();
        try (var first = new SqliteCompletionLedger(file)) {
            assertEquals(Set.of("express/first_class"), first.record(player, Set.of("express/first_class")).get(5, TimeUnit.SECONDS));
        }
        try (var restarted = new SqliteCompletionLedger(file)) {
            assertEquals(Set.of("express/first_class"), restarted.load(player).get(5, TimeUnit.SECONDS));
            assertTrue(restarted.record(player, Set.of("express/first_class")).get(5, TimeUnit.SECONDS).isEmpty());
            assertEquals(Set.of("diary/dear_diary"), restarted.record(player, Set.of("diary/dear_diary")).get(5, TimeUnit.SECONDS));
            assertEquals(Set.of("express/first_class"), restarted.record(UUID.randomUUID(), Set.of("express/first_class")).get(5, TimeUnit.SECONDS));
            var history = new CompletionHistory(restarted, player);
            var progress = new java.util.HashMap<>(java.util.Map.of("express/first_class", 0));
            var pending = new java.util.HashSet<>(Set.of("express/first_class"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!history.reconcile(progress, pending, Set.of("express/first_class"))) {
                assertTrue(System.nanoTime() < deadline);
                Thread.sleep(5);
            }
            assertEquals(1000, progress.get("express/first_class"));
            assertTrue(pending.isEmpty());
        }
    }

    @Test void competingConnectionsHaveOneWinnerAndClosureRejectsNewWork() throws Exception {
        Path file = directory.resolve("history.db");
        UUID player = UUID.randomUUID();
        try (var one = new SqliteCompletionLedger(file); var two = new SqliteCompletionLedger(file)) {
            one.load(player).get(5, TimeUnit.SECONDS);
            var a = one.record(player, Set.of("express/first_class"));
            var b = two.record(player, Set.of("express/first_class"));
            assertEquals(1, a.get(5, TimeUnit.SECONDS).size() + b.get(5, TimeUnit.SECONDS).size());
            one.close();
            assertThrows(Exception.class, () -> one.load(player).join());
        }
    }

    @Test void inaccessibleDatabaseIsAnErrorNotEmptyHistory() throws Exception {
        Path blocker = directory.resolve("not-a-directory");
        Files.createFile(blocker);
        try (var ledger = new SqliteCompletionLedger(blocker.resolve("history.db"))) {
            assertThrows(Exception.class, () -> ledger.load(UUID.randomUUID()).get(5, TimeUnit.SECONDS));
        }
    }

    @Test void failedMultiKeyTransactionRollsBackEveryInsert() throws Exception {
        Path file = directory.resolve("history.db");
        UUID player = UUID.randomUUID();
        try (var ledger = new SqliteCompletionLedger(file)) {
            ledger.load(player).get(5, TimeUnit.SECONDS);
            try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + file);
                 var statement = connection.createStatement()) {
                statement.executeUpdate("""
                    CREATE TRIGGER reject_second BEFORE INSERT ON advancement_completions
                    WHEN (SELECT COUNT(*) FROM advancement_completions) = 1
                    BEGIN SELECT RAISE(ABORT, 'injected failure'); END
                    """);
            }
            assertThrows(Exception.class, () -> ledger.record(player, Set.of("one", "two")).get(5, TimeUnit.SECONDS));
            assertTrue(ledger.load(player).get(5, TimeUnit.SECONDS).isEmpty());
        }
    }
}
