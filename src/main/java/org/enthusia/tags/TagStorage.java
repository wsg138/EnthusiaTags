package org.enthusia.tags;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TagStorage {
    public record StoredTagData(Set<String> ownedTags, String selectedTag) {
        public StoredTagData {
            ownedTags = ownedTags == null ? Set.of() : Set.copyOf(ownedTags);
        }
    }

    private final File databaseFile;
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private Connection connection;

    public TagStorage(File databaseFile) {
        this.databaseFile = databaseFile;
        this.executor = Executors.newSingleThreadExecutor(new StorageThreadFactory("enthusia-tags-storage"));
    }

    public void init() throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS player_tags (
                    player_uuid TEXT NOT NULL,
                    tag_id TEXT NOT NULL,
                    PRIMARY KEY (player_uuid, tag_id)
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS player_selected (
                    player_uuid TEXT PRIMARY KEY,
                    selected_tag TEXT
                )
                """);
        }
    }

    public CompletableFuture<StoredTagData> loadAsync(UUID playerId) {
        return submit(() -> loadNow(playerId));
    }

    public StoredTagData loadNow(UUID playerId) throws SQLException {
        return executeBlocking(() -> {
            Set<String> ownedTags = new HashSet<>();
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT tag_id FROM player_tags WHERE player_uuid = ?")) {
                statement.setString(1, playerId.toString());
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        ownedTags.add(rs.getString("tag_id"));
                    }
                }
            }

            String selectedTag = null;
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT selected_tag FROM player_selected WHERE player_uuid = ?")) {
                statement.setString(1, playerId.toString());
                try (ResultSet rs = statement.executeQuery()) {
                    if (rs.next()) {
                        selectedTag = rs.getString("selected_tag");
                    }
                }
            }
            return new StoredTagData(ownedTags, selectedTag);
        });
    }

    public CompletableFuture<Void> grantTagAsync(UUID playerId, String tagId) {
        return submit(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO player_tags (player_uuid, tag_id) VALUES (?, ?)")) {
                statement.setString(1, playerId.toString());
                statement.setString(2, tagId);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> revokeTagAsync(UUID playerId, String tagId) {
        return submit(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM player_tags WHERE player_uuid = ? AND tag_id = ?")) {
                statement.setString(1, playerId.toString());
                statement.setString(2, tagId);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> setSelectedTagAsync(UUID playerId, String tagId) {
        return submit(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO player_selected (player_uuid, selected_tag) VALUES (?, ?) " +
                    "ON CONFLICT(player_uuid) DO UPDATE SET selected_tag = excluded.selected_tag")) {
                statement.setString(1, playerId.toString());
                if (tagId == null) {
                    statement.setNull(2, java.sql.Types.VARCHAR);
                } else {
                    statement.setString(2, tagId);
                }
                statement.executeUpdate();
            }
            return null;
        });
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
        }
    }

    private <T> CompletableFuture<T> submit(SqlSupplier<T> supplier) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new SQLException("Tag storage is closed"));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return supplier.get();
            } catch (SQLException ex) {
                throw new StorageRuntimeException(ex);
            }
        }, executor);
    }

    private <T> T executeBlocking(SqlSupplier<T> supplier) throws SQLException {
        try {
            return submit(supplier).get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while waiting for tag storage", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof StorageRuntimeException storageRuntimeException
                && storageRuntimeException.getCause() instanceof SQLException sqlException) {
                throw sqlException;
            }
            throw new SQLException("Tag storage execution failed", cause);
        }
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }

    private static final class StorageRuntimeException extends RuntimeException {
        private StorageRuntimeException(SQLException cause) {
            super(cause);
        }
    }

    private static final class StorageThreadFactory implements ThreadFactory {
        private final String name;

        private StorageThreadFactory(String name) {
            this.name = name;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        }
    }
}
