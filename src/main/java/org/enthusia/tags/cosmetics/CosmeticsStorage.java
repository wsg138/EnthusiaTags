package org.enthusia.tags.cosmetics;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.enthusia.tags.PerformanceMonitor;

public final class CosmeticsStorage {
    private final File databaseFile;
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final PerformanceMonitor performanceMonitor;
    private Connection connection;

    public CosmeticsStorage(File databaseFile, PerformanceMonitor performanceMonitor) {
        this.databaseFile = databaseFile;
        this.performanceMonitor = performanceMonitor;
        this.executor = Executors.newSingleThreadExecutor(new StorageThreadFactory("enthusia-tags-cosmetics-storage"));
    }

    public void init() throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS cosmetics_selected (
                    player_uuid TEXT NOT NULL,
                    category TEXT NOT NULL,
                    cosmetic_id TEXT NOT NULL,
                    PRIMARY KEY (player_uuid, category)
                )
                """);
        }
    }

    public CompletableFuture<Map<String, String>> loadSelectionsAsync(UUID playerId) {
        return submitMeasured("storage.cosmetics.load", () -> loadSelectionsDirect(playerId));
    }

    public Map<String, String> loadSelectionsNow(UUID playerId) throws SQLException {
        return executeBlockingMeasured("storage.cosmetics.load", () -> loadSelectionsDirect(playerId));
    }

    public CompletableFuture<Void> setSelectionAsync(UUID playerId, String category, String cosmeticId) {
        return submitMeasured("storage.cosmetics.save", () -> {
            if (cosmeticId == null) {
                try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM cosmetics_selected WHERE player_uuid = ? AND category = ?")) {
                    statement.setString(1, playerId.toString());
                    statement.setString(2, category);
                    statement.executeUpdate();
                }
                return null;
            }
            try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO cosmetics_selected (player_uuid, category, cosmetic_id) VALUES (?, ?, ?) " +
                    "ON CONFLICT(player_uuid, category) DO UPDATE SET cosmetic_id = excluded.cosmetic_id")) {
                statement.setString(1, playerId.toString());
                statement.setString(2, category);
                statement.setString(3, cosmeticId);
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
            return CompletableFuture.failedFuture(new SQLException("Cosmetics storage is closed"));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return supplier.get();
            } catch (SQLException ex) {
                throw new StorageRuntimeException(ex);
            }
        }, executor);
    }

    private <T> CompletableFuture<T> submitMeasured(String key, SqlSupplier<T> supplier) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new SQLException("Cosmetics storage is closed"));
        }
        return CompletableFuture.supplyAsync(() -> {
            long start = System.nanoTime();
            try {
                T result = supplier.get();
                performanceMonitor.increment(key + ".success");
                performanceMonitor.recordDurationMillis(key, elapsedMillis(start));
                return result;
            } catch (SQLException ex) {
                performanceMonitor.increment(key + ".failure");
                performanceMonitor.recordDurationMillis(key, elapsedMillis(start));
                throw new StorageRuntimeException(ex);
            }
        }, executor);
    }

    private <T> T executeBlocking(SqlSupplier<T> supplier) throws SQLException {
        try {
            return submit(supplier).get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while waiting for cosmetics storage", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof StorageRuntimeException storageRuntimeException
                && storageRuntimeException.getCause() instanceof SQLException sqlException) {
                throw sqlException;
            }
            throw new SQLException("Cosmetics storage execution failed", cause);
        }
    }

    private <T> T executeBlockingMeasured(String key, SqlSupplier<T> supplier) throws SQLException {
        try {
            return submitMeasured(key, supplier).get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while waiting for cosmetics storage", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof StorageRuntimeException storageRuntimeException
                && storageRuntimeException.getCause() instanceof SQLException sqlException) {
                throw sqlException;
            }
            throw new SQLException("Cosmetics storage execution failed", cause);
        }
    }

    private Map<String, String> loadSelectionsDirect(UUID playerId) throws SQLException {
        Map<String, String> selections = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT category, cosmetic_id FROM cosmetics_selected WHERE player_uuid = ?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    selections.put(rs.getString("category"), rs.getString("cosmetic_id"));
                }
            }
        }
        return selections;
    }

    private long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
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
