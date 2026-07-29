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
import org.enthusia.tags.PerformanceMonitor;

public final class RewardStorage {
    public record ActionLedgerEntry(String actionId, String actionType, String fingerprint, RewardStatus status) {
    }
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
    private final PerformanceMonitor performanceMonitor;
    private Connection connection;

    public RewardStorage(File databaseFile, PerformanceMonitor performanceMonitor) {
        this.databaseFile = databaseFile;
        this.performanceMonitor = performanceMonitor;
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
                CREATE TABLE IF NOT EXISTS reward_ip_claims (
                    reward_id TEXT NOT NULL,
                    ip_address TEXT NOT NULL,
                    player_uuid TEXT NOT NULL,
                    claimed_at INTEGER NOT NULL,
                    PRIMARY KEY (reward_id, ip_address)
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS reward_ip_bypass_pairs (
                    first_uuid TEXT NOT NULL,
                    second_uuid TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    PRIMARY KEY (first_uuid, second_uuid)
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
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS reward_action_ledger (
                    player_uuid TEXT NOT NULL,
                    reward_id TEXT NOT NULL,
                    action_id TEXT NOT NULL,
                    action_type TEXT NOT NULL,
                    fingerprint TEXT NOT NULL,
                    status TEXT NOT NULL,
                    requested_amount REAL,
                    response_amount REAL,
                    response_type TEXT,
                    balance_before REAL,
                    balance_after REAL,
                    error_message TEXT,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY (player_uuid, reward_id, action_id)
                )
                """);
        }
    }

    public CompletableFuture<StoredRewardData> loadAsync(UUID playerId) {
        return submitMeasured("storage.rewards.load", () -> loadDirect(playerId));
    }

    public StoredRewardData loadNow(UUID playerId) throws SQLException {
        return executeBlockingMeasured("storage.rewards.load", () -> loadDirect(playerId));
    }

    public CompletableFuture<Void> saveAsync(UUID playerId, StoredRewardData data) {
        return submitMeasured("storage.rewards.save", () -> {
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

    public void saveNow(UUID playerId, StoredRewardData data) throws SQLException {
        try {
            saveAsync(playerId, data).get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while persisting reward transition", ex);
        } catch (ExecutionException ex) {
            throw new SQLException("Failed to persist reward transition", ex.getCause());
        }
    }

    public Map<String, ActionLedgerEntry> loadActionLedgerNow(UUID playerId, String rewardId) throws SQLException {
        return executeBlockingMeasured("storage.rewards.action-ledger-load", () -> {
            Map<String, ActionLedgerEntry> entries = new HashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT action_id,action_type,fingerprint,status FROM reward_action_ledger"
                    + " WHERE player_uuid=? AND reward_id=?")) {
                statement.setString(1, playerId.toString());
                statement.setString(2, rewardId);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        entries.put(rs.getString("action_id"), new ActionLedgerEntry(
                            rs.getString("action_id"), rs.getString("action_type"), rs.getString("fingerprint"),
                            RewardStatus.valueOf(rs.getString("status"))));
                    }
                }
            }
            return entries;
        });
    }

    public void saveActionLedgerNow(UUID playerId, String rewardId, RewardAction action, String fingerprint,
                                    RewardStatus status, VaultHook.DepositResult result, String error)
        throws SQLException {
        executeBlockingMeasured("storage.rewards.action-ledger-save", () -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO reward_action_ledger(player_uuid,reward_id,action_id,action_type,fingerprint,status,
                  requested_amount,response_amount,response_type,balance_before,balance_after,error_message,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(player_uuid,reward_id,action_id) DO UPDATE SET
                  action_type=excluded.action_type,fingerprint=excluded.fingerprint,status=excluded.status,
                  requested_amount=excluded.requested_amount,response_amount=excluded.response_amount,
                  response_type=excluded.response_type,balance_before=excluded.balance_before,
                  balance_after=excluded.balance_after,error_message=excluded.error_message,updated_at=excluded.updated_at
                """)) {
                statement.setString(1, playerId.toString());
                statement.setString(2, rewardId);
                statement.setString(3, action.getActionId());
                statement.setString(4, action.getType().name());
                statement.setString(5, fingerprint);
                statement.setString(6, status.name());
                if (result == null) {
                    statement.setNull(7, java.sql.Types.REAL);
                    statement.setNull(8, java.sql.Types.REAL);
                    statement.setNull(9, java.sql.Types.VARCHAR);
                    statement.setNull(10, java.sql.Types.REAL);
                    statement.setNull(11, java.sql.Types.REAL);
                } else {
                    statement.setDouble(7, result.requestedAmount());
                    statement.setDouble(8, result.responseAmount());
                    statement.setString(9, result.responseType());
                    statement.setDouble(10, result.balanceBefore());
                    statement.setDouble(11, result.balanceAfter());
                }
                statement.setString(12, error);
                statement.setLong(13, System.currentTimeMillis());
                statement.executeUpdate();
            }
            return null;
        });
    }

    public boolean reserveIpClaimNow(UUID playerId, String rewardId, String ipAddress) throws SQLException {
        if (rewardId == null || rewardId.isBlank() || ipAddress == null || ipAddress.isBlank()) {
            return true;
        }
        return executeBlockingMeasured("storage.rewards.ip-claim", () -> reserveIpClaimDirect(playerId, rewardId, ipAddress));
    }

    public CompletableFuture<Boolean> reserveIpClaimAsync(UUID playerId, String rewardId, String ipAddress) {
        if (rewardId == null || rewardId.isBlank() || ipAddress == null || ipAddress.isBlank()) {
            return CompletableFuture.completedFuture(true);
        }
        return submitMeasured("storage.rewards.ip-claim", () -> reserveIpClaimDirect(playerId, rewardId, ipAddress));
    }

    public CompletableFuture<Void> releaseIpClaimAsync(UUID playerId, String rewardId, String ipAddress) {
        return submitMeasured("storage.rewards.ip-release", () -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM reward_ip_claims WHERE player_uuid = ? AND reward_id = ? AND ip_address = ?")) {
                statement.setString(1, playerId.toString());
                statement.setString(2, rewardId);
                statement.setString(3, ipAddress);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public boolean addIpBypassPairNow(UUID firstPlayerId, UUID secondPlayerId) throws SQLException {
        return executeBlockingMeasured("storage.rewards.ip-bypass.add", () -> addIpBypassPairDirect(firstPlayerId, secondPlayerId));
    }

    public boolean removeIpBypassPairNow(UUID firstPlayerId, UUID secondPlayerId) throws SQLException {
        return executeBlockingMeasured("storage.rewards.ip-bypass.remove", () -> removeIpBypassPairDirect(firstPlayerId, secondPlayerId));
    }

    public Set<UUID> listIpBypassPairsNow(UUID playerId) throws SQLException {
        return executeBlockingMeasured("storage.rewards.ip-bypass.list", () -> listIpBypassPairsDirect(playerId));
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

    private <T> CompletableFuture<T> submitMeasured(String key, SqlSupplier<T> supplier) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new SQLException("Reward storage is closed"));
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

    private <T> T executeBlockingMeasured(String key, SqlSupplier<T> supplier) throws SQLException {
        try {
            return submitMeasured(key, supplier).get();
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

    private StoredRewardData loadDirect(UUID playerId) throws SQLException {
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
    }

    private boolean reserveIpClaimDirect(UUID playerId, String rewardId, String ipAddress) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
            "SELECT player_uuid FROM reward_ip_claims WHERE reward_id = ? AND ip_address = ?")) {
            select.setString(1, rewardId);
            select.setString(2, ipAddress);
            try (ResultSet rs = select.executeQuery()) {
                if (rs.next()) {
                    UUID existingPlayerId = UUID.fromString(rs.getString("player_uuid"));
                    return playerId.equals(existingPlayerId) || isIpBypassPairDirect(playerId, existingPlayerId);
                }
            }
        }

        try (PreparedStatement insert = connection.prepareStatement(
            "INSERT INTO reward_ip_claims (reward_id, ip_address, player_uuid, claimed_at) VALUES (?, ?, ?, ?)")) {
            insert.setString(1, rewardId);
            insert.setString(2, ipAddress);
            insert.setString(3, playerId.toString());
            insert.setLong(4, System.currentTimeMillis());
            insert.executeUpdate();
            return true;
        }
    }

    private boolean addIpBypassPairDirect(UUID firstPlayerId, UUID secondPlayerId) throws SQLException {
        if (firstPlayerId == null || secondPlayerId == null || firstPlayerId.equals(secondPlayerId)) {
            return false;
        }
        IpBypassPair pair = normalizePair(firstPlayerId, secondPlayerId);
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT OR IGNORE INTO reward_ip_bypass_pairs (first_uuid, second_uuid, created_at) VALUES (?, ?, ?)")) {
            statement.setString(1, pair.first().toString());
            statement.setString(2, pair.second().toString());
            statement.setLong(3, System.currentTimeMillis());
            return statement.executeUpdate() > 0;
        }
    }

    private boolean removeIpBypassPairDirect(UUID firstPlayerId, UUID secondPlayerId) throws SQLException {
        if (firstPlayerId == null || secondPlayerId == null || firstPlayerId.equals(secondPlayerId)) {
            return false;
        }
        IpBypassPair pair = normalizePair(firstPlayerId, secondPlayerId);
        try (PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM reward_ip_bypass_pairs WHERE first_uuid = ? AND second_uuid = ?")) {
            statement.setString(1, pair.first().toString());
            statement.setString(2, pair.second().toString());
            return statement.executeUpdate() > 0;
        }
    }

    private Set<UUID> listIpBypassPairsDirect(UUID playerId) throws SQLException {
        if (playerId == null) {
            return Set.of();
        }
        Set<UUID> pairedPlayers = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT first_uuid, second_uuid FROM reward_ip_bypass_pairs WHERE first_uuid = ? OR second_uuid = ?")) {
            String uuid = playerId.toString();
            statement.setString(1, uuid);
            statement.setString(2, uuid);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    UUID first = UUID.fromString(rs.getString("first_uuid"));
                    UUID second = UUID.fromString(rs.getString("second_uuid"));
                    pairedPlayers.add(playerId.equals(first) ? second : first);
                }
            }
        }
        return pairedPlayers;
    }

    private boolean isIpBypassPairDirect(UUID firstPlayerId, UUID secondPlayerId) throws SQLException {
        IpBypassPair pair = normalizePair(firstPlayerId, secondPlayerId);
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT 1 FROM reward_ip_bypass_pairs WHERE first_uuid = ? AND second_uuid = ?")) {
            statement.setString(1, pair.first().toString());
            statement.setString(2, pair.second().toString());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    private IpBypassPair normalizePair(UUID firstPlayerId, UUID secondPlayerId) {
        return firstPlayerId.compareTo(secondPlayerId) <= 0
            ? new IpBypassPair(firstPlayerId, secondPlayerId)
            : new IpBypassPair(secondPlayerId, firstPlayerId);
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

    private record IpBypassPair(UUID first, UUID second) {
    }
}
