package org.enthusia.tags.rewards;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RewardStorage {
    public record StoredRewardData(Set<String> claims, Map<String, Long> counters, Map<String, String> states) {
        public StoredRewardData {
            claims = claims == null ? Set.of() : Set.copyOf(claims);
            counters = counters == null ? Map.of() : Map.copyOf(counters);
            states = states == null ? Map.of() : Map.copyOf(states);
        }
    }

    private final File databaseFile;
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private Connection connection;

    public RewardStorage(File databaseFile) {
        this.databaseFile = databaseFile;
        this.executor = Executors.newSingleThreadExecutor(new StorageThreadFactory("enthusia-tags-rewards-storage"));
    }

    public void init() throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS reward_claims (
                    player_uuid TEXT NOT NULL,
                    reward_id TEXT NOT NULL,
                    claimed_at INTEGER NOT NULL,
                    PRIMARY KEY (player_uuid, reward_id)
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS reward_counters (
                    player_uuid TEXT NOT NULL,
                    counter_key TEXT NOT NULL,
                    counter_value INTEGER NOT NULL,
                    PRIMARY KEY (player_uuid, counter_key)
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS reward_states (
                    player_uuid TEXT NOT NULL,
                    state_key TEXT NOT NULL,
                    state_value TEXT NOT NULL,
                    PRIMARY KEY (player_uuid, state_key)
                )
                """);
        }
    }

    public CompletableFuture<StoredRewardData> loadAsync(UUID playerId) {
        return submit(() -> loadNow(playerId));
    }

    public StoredRewardData loadNow(UUID playerId) throws SQLException {
        return executeBlocking(() -> {
            Set<String> claims = new HashSet<>();
            Map<String, Long> counters = new HashMap<>();
            Map<String, String> states = new HashMap<>();

            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT reward_id FROM reward_claims WHERE player_uuid = ?")) {
                statement.setString(1, playerId.toString());
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        claims.add(rs.getString("reward_id"));
                    }
                }
            }

            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT counter_key, counter_value FROM reward_counters WHERE player_uuid = ?")) {
                statement.setString(1, playerId.toString());
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        counters.put(rs.getString("counter_key"), rs.getLong("counter_value"));
                    }
                }
            }

            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT state_key, state_value FROM reward_states WHERE player_uuid = ?")) {
                statement.setString(1, playerId.toString());
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        states.put(rs.getString("state_key"), rs.getString("state_value"));
                    }
                }
            }

            return new StoredRewardData(claims, counters, states);
        });
    }

    public CompletableFuture<Void> saveAsync(UUID playerId, StoredRewardData data) {
        return submit(() -> {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement deleteClaims = connection.prepareStatement(
                    "DELETE FROM reward_claims WHERE player_uuid = ?");
                     PreparedStatement deleteCounters = connection.prepareStatement(
                         "DELETE FROM reward_counters WHERE player_uuid = ?");
                     PreparedStatement deleteStates = connection.prepareStatement(
                         "DELETE FROM reward_states WHERE player_uuid = ?")) {
                    String uuid = playerId.toString();
                    deleteClaims.setString(1, uuid);
                    deleteCounters.setString(1, uuid);
                    deleteStates.setString(1, uuid);
                    deleteClaims.executeUpdate();
                    deleteCounters.executeUpdate();
                    deleteStates.executeUpdate();
                }

                try (PreparedStatement insertClaim = connection.prepareStatement(
                    "INSERT INTO reward_claims (player_uuid, reward_id, claimed_at) VALUES (?, ?, ?)");
                     PreparedStatement insertCounter = connection.prepareStatement(
                         "INSERT INTO reward_counters (player_uuid, counter_key, counter_value) VALUES (?, ?, ?)");
                     PreparedStatement insertState = connection.prepareStatement(
                         "INSERT INTO reward_states (player_uuid, state_key, state_value) VALUES (?, ?, ?)")) {
                    String uuid = playerId.toString();
                    long now = System.currentTimeMillis();
                    for (String claim : data.claims()) {
                        insertClaim.setString(1, uuid);
                        insertClaim.setString(2, claim);
                        insertClaim.setLong(3, now);
                        insertClaim.addBatch();
                    }
                    for (Map.Entry<String, Long> entry : data.counters().entrySet()) {
                        insertCounter.setString(1, uuid);
                        insertCounter.setString(2, entry.getKey());
                        insertCounter.setLong(3, entry.getValue());
                        insertCounter.addBatch();
                    }
                    for (Map.Entry<String, String> entry : data.states().entrySet()) {
                        insertState.setString(1, uuid);
                        insertState.setString(2, entry.getKey());
                        insertState.setString(3, entry.getValue());
                        insertState.addBatch();
                    }
                    insertClaim.executeBatch();
                    insertCounter.executeBatch();
                    insertState.executeBatch();
                }

                connection.commit();
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(true);
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
            return CompletableFuture.failedFuture(new SQLException("Reward storage is closed"));
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
            throw new SQLException("Interrupted while waiting for reward storage", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof StorageRuntimeException storageRuntimeException
                && storageRuntimeException.getCause() instanceof SQLException sqlException) {
                throw sqlException;
            }
            throw new SQLException("Reward storage execution failed", cause);
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
