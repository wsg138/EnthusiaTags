package org.enthusia.tags.advancements;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.enthusia.tags.advancements.application.CompletionLedger;

/** Dedicated additive completion ledger, never a provider or reward database. */
final class SqliteCompletionLedger implements CompletionLedger, AutoCloseable {
    private final Path file;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "enthusia-tags-advancement-history");
        thread.setDaemon(true);
        return thread;
    });

    SqliteCompletionLedger(Path file) {
        this.file = file.toAbsolutePath();
    }

    @Override public CompletableFuture<Set<String>> load(UUID player) {
        return submit(connection -> {
            Set<String> completed = new HashSet<>();
            try (var statement = connection.prepareStatement(
                "SELECT advancement_key FROM advancement_completions WHERE player_uuid=?")) {
                statement.setString(1, player.toString());
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) completed.add(rows.getString(1));
                }
            }
            return Set.copyOf(completed);
        });
    }

    @Override public CompletableFuture<Set<String>> record(UUID player, Set<String> completed) {
        Set<String> snapshot = Set.copyOf(completed);
        return submit(connection -> {
            connection.setAutoCommit(false);
            Set<String> inserted = new HashSet<>();
            try (var statement = connection.prepareStatement("""
                INSERT OR IGNORE INTO advancement_completions(player_uuid,advancement_key,completed_at)
                VALUES(?,?,?)
                """)) {
                for (String key : snapshot) {
                    statement.setString(1, player.toString());
                    statement.setString(2, key);
                    statement.setLong(3, System.currentTimeMillis());
                    if (statement.executeUpdate() == 1) inserted.add(key);
                }
            }
            connection.commit();
            return Set.copyOf(inserted);
        });
    }

    private CompletableFuture<Set<String>> submit(Operation operation) {
        if (executor.isShutdown()) return CompletableFuture.failedFuture(new IllegalStateException("Completion ledger closed"));
        return CompletableFuture.supplyAsync(() -> {
            try {
                Files.createDirectories(file.getParent());
                try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file)) {
                    try (var statement = connection.createStatement()) {
                        statement.execute("PRAGMA busy_timeout=5000");
                        statement.execute("PRAGMA synchronous=FULL");
                        statement.executeUpdate("""
                            CREATE TABLE IF NOT EXISTS advancement_completions (
                                player_uuid TEXT NOT NULL,
                                advancement_key TEXT NOT NULL,
                                completed_at INTEGER NOT NULL,
                                PRIMARY KEY(player_uuid,advancement_key)
                            )
                            """);
                    }
                    // On failure, closing the connection rolls back; do not enable auto-commit.
                    return operation.run(connection);
                }
            } catch (java.io.IOException | SQLException ex) {
                throw new CompletionException(ex);
            }
        }, executor);
    }

    @Override public void close() { executor.shutdown(); }

    @FunctionalInterface private interface Operation {
        Set<String> run(Connection connection) throws SQLException;
    }
}
