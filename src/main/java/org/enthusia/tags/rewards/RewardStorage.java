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
    public enum WriteResult {
        WRITTEN,
        STALE_REJECTED
    }
    public record ActionLedgerEntry(String actionId, String actionType, String fingerprint, RewardStatus status,
                                    Double requestedAmount, Double responseAmount, String responseType,
                                    Double balanceBefore, Double balanceAfter, String errorMessage, long updatedAt) {
    }
    public record ActionHistoryEntry(long historyId, String actionId, RewardStatus oldStatus,
                                     RewardStatus newStatus, String fingerprint, Double requestedAmount,
                                     Double responseAmount, String responseType, Double balanceBefore,
                                     Double balanceAfter, String errorMessage, long createdAt) {
    }
    public record QueuedItem(String rewardId, String actionId, String fingerprint, String material,
                             int amount, String displayName, java.util.List<String> lore, long queuedAt) {
    }
    public record ItemOverflowEntry(String actionId, String fingerprint, String material, int amount,
                                    String status, long queuedAt, Long deliveredAt) {
    }
    public record ReconciliationHistoryEntry(String category, String subject, String oldStatus,
                                             String newStatus, String administrator, String reason,
                                             long createdAt) {
    }
    public record StoredRewardData(Set<String> claims, Map<String, Long> counters, Map<String, String> states,
                                   long revision) {
        public StoredRewardData {
            claims = claims == null ? Set.of() : Set.copyOf(claims);
            counters = counters == null ? Map.of() : Map.copyOf(counters);
            states = states == null ? Map.of() : Map.copyOf(states);
            revision = Math.max(0L, revision);
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
                CREATE TABLE IF NOT EXISTS reward_unlocks (
                    player_uuid TEXT NOT NULL,
                    reward_id TEXT NOT NULL,
                    unlocked_at INTEGER NOT NULL,
                    PRIMARY KEY (player_uuid, reward_id)
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS reward_player_versions (
                    player_uuid TEXT PRIMARY KEY,
                    revision INTEGER NOT NULL
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
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS reward_action_history (
                    history_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    player_uuid TEXT NOT NULL,
                    reward_id TEXT NOT NULL,
                    action_id TEXT NOT NULL,
                    old_status TEXT,
                    new_status TEXT NOT NULL,
                    fingerprint TEXT NOT NULL,
                    requested_amount REAL,
                    response_amount REAL,
                    response_type TEXT,
                    balance_before REAL,
                    balance_after REAL,
                    error_message TEXT,
                    created_at INTEGER NOT NULL
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS reward_legacy_reconciliation_history (
                    history_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    administrator TEXT NOT NULL,
                    player_uuid TEXT NOT NULL,
                    reward_id TEXT NOT NULL,
                    removed_state_keys TEXT NOT NULL,
                    previous_overall_status TEXT,
                    decision TEXT NOT NULL,
                    reason TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS reward_item_overflow (
                    player_uuid TEXT NOT NULL,
                    reward_id TEXT NOT NULL,
                    action_id TEXT NOT NULL,
                    fingerprint TEXT NOT NULL,
                    material TEXT NOT NULL,
                    amount INTEGER NOT NULL,
                    display_name TEXT,
                    lore TEXT NOT NULL,
                    status TEXT NOT NULL,
                    queued_at INTEGER NOT NULL,
                    delivered_at INTEGER,
                    PRIMARY KEY (player_uuid,reward_id,action_id)
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS reward_item_overflow_history (
                    history_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    player_uuid TEXT NOT NULL,
                    reward_id TEXT NOT NULL,
                    action_id TEXT NOT NULL,
                    old_status TEXT,
                    new_status TEXT NOT NULL,
                    reason TEXT NOT NULL,
                    created_at INTEGER NOT NULL
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

    public CompletableFuture<WriteResult> saveAsync(UUID playerId, StoredRewardData data) {
        return submitMeasured("storage.rewards.save", () -> {
            connection.setAutoCommit(false);
            try {
                if (data.revision() <= loadStoredRevision(playerId)) {
                    connection.rollback();
                    return WriteResult.STALE_REJECTED;
                }
                try (PreparedStatement deleteCounters = connection.prepareStatement(
                         "DELETE FROM reward_counters WHERE player_uuid = ?");
                     PreparedStatement deleteStates = connection.prepareStatement(
                         "DELETE FROM reward_states WHERE player_uuid = ?")) {
                    String uuid = playerId.toString();
                    deleteCounters.setString(1, uuid);
                    deleteStates.setString(1, uuid);
                    deleteCounters.executeUpdate();
                    deleteStates.executeUpdate();
                }

                try (PreparedStatement insertClaim = connection.prepareStatement(
                    "INSERT OR IGNORE INTO reward_claims (player_uuid, reward_id, claimed_at) VALUES (?, ?, ?)");
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
                try (PreparedStatement version = connection.prepareStatement("""
                    INSERT INTO reward_player_versions(player_uuid,revision) VALUES(?,?)
                    ON CONFLICT(player_uuid) DO UPDATE SET revision=excluded.revision
                    """)) {
                    version.setString(1, playerId.toString());
                    version.setLong(2, data.revision());
                    version.executeUpdate();
                }

                connection.commit();
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(true);
            }
            return WriteResult.WRITTEN;
        });
    }

    private long loadStoredRevision(UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT revision FROM reward_player_versions WHERE player_uuid=?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong("revision") : 0L;
            }
        }
    }

    public WriteResult saveNow(UUID playerId, StoredRewardData data) throws SQLException {
        try {
            return saveAsync(playerId, data).get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while persisting reward transition", ex);
        } catch (ExecutionException ex) {
            throw new SQLException("Failed to persist reward transition", ex.getCause());
        }
    }

    public void markClaimedNow(UUID playerId, String rewardId) throws SQLException {
        executeBlockingMeasured("storage.rewards.claim-marker", () -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO reward_claims(player_uuid,reward_id,claimed_at) VALUES(?,?,?)")) {
                statement.setString(1, playerId.toString());
                statement.setString(2, rewardId);
                statement.setLong(3, System.currentTimeMillis());
                statement.executeUpdate();
            }
            return null;
        });
    }

    public void markUnlockedNow(UUID playerId, String rewardId) throws SQLException {
        executeBlockingMeasured("storage.rewards.unlock-marker", () -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO reward_unlocks(player_uuid,reward_id,unlocked_at) VALUES(?,?,?)")) {
                statement.setString(1, playerId.toString());
                statement.setString(2, rewardId);
                statement.setLong(3, System.currentTimeMillis());
                statement.executeUpdate();
            }
            return null;
        });
    }

    public Map<String, ActionLedgerEntry> loadActionLedgerNow(UUID playerId, String rewardId) throws SQLException {
        return executeBlockingMeasured("storage.rewards.action-ledger-load", () -> {
            Map<String, ActionLedgerEntry> entries = new HashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT action_id,action_type,fingerprint,status,requested_amount,response_amount,response_type,"
                    + "balance_before,balance_after,error_message,updated_at FROM reward_action_ledger"
                    + " WHERE player_uuid=? AND reward_id=?")) {
                statement.setString(1, playerId.toString());
                statement.setString(2, rewardId);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        entries.put(rs.getString("action_id"), new ActionLedgerEntry(
                            rs.getString("action_id"), rs.getString("action_type"), rs.getString("fingerprint"),
                            RewardStatus.valueOf(rs.getString("status")),
                            nullableDouble(rs, "requested_amount"), nullableDouble(rs, "response_amount"),
                            rs.getString("response_type"), nullableDouble(rs, "balance_before"),
                            nullableDouble(rs, "balance_after"), rs.getString("error_message"),
                            rs.getLong("updated_at")));
                    }
                }
            }
            return entries;
        });
    }

    public java.util.List<ActionHistoryEntry> loadActionHistoryNow(UUID playerId, String rewardId, int limit)
        throws SQLException {
        return executeBlockingMeasured("storage.rewards.action-history-load", () -> {
            java.util.List<ActionHistoryEntry> entries = new java.util.ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT history_id,action_id,old_status,new_status,fingerprint,requested_amount,response_amount,
                  response_type,balance_before,balance_after,error_message,created_at
                FROM reward_action_history WHERE player_uuid=? AND reward_id=?
                ORDER BY history_id DESC LIMIT ?
                """)) {
                statement.setString(1, playerId.toString());
                statement.setString(2, rewardId);
                statement.setInt(3, Math.max(1, limit));
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        String oldStatus = rs.getString("old_status");
                        entries.add(new ActionHistoryEntry(rs.getLong("history_id"), rs.getString("action_id"),
                            oldStatus == null ? null : RewardStatus.valueOf(oldStatus),
                            RewardStatus.valueOf(rs.getString("new_status")), rs.getString("fingerprint"),
                            nullableDouble(rs, "requested_amount"), nullableDouble(rs, "response_amount"),
                            rs.getString("response_type"), nullableDouble(rs, "balance_before"),
                            nullableDouble(rs, "balance_after"), rs.getString("error_message"),
                            rs.getLong("created_at")));
                    }
                }
            }
            return entries;
        });
    }

    public java.util.List<String> listIpClaimsNow(UUID playerId, String rewardId) throws SQLException {
        return executeBlockingMeasured("storage.rewards.ip-claims-inspect", () -> {
            java.util.List<String> claims = new java.util.ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT ip_address,claimed_at FROM reward_ip_claims WHERE player_uuid=? AND reward_id=?")) {
                statement.setString(1, playerId.toString());
                statement.setString(2, rewardId);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        claims.add(rs.getString("ip_address") + " @ " + rs.getLong("claimed_at"));
                    }
                }
            }
            return claims;
        });
    }

    public void recordLegacyReconciliationNow(String administrator, UUID playerId, String rewardId,
                                              java.util.Collection<String> removedKeys, String previousStatus,
                                              String decision, String reason) throws SQLException {
        executeBlockingMeasured("storage.rewards.legacy-reconcile-history", () -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO reward_legacy_reconciliation_history(
                  administrator,player_uuid,reward_id,removed_state_keys,previous_overall_status,
                  decision,reason,created_at) VALUES(?,?,?,?,?,?,?,?)
                """)) {
                statement.setString(1, administrator);
                statement.setString(2, playerId.toString());
                statement.setString(3, rewardId);
                statement.setString(4, String.join("\n", removedKeys));
                statement.setString(5, previousStatus);
                statement.setString(6, decision);
                statement.setString(7, reason);
                statement.setLong(8, System.currentTimeMillis());
                statement.executeUpdate();
            }
            return null;
        });
    }

    public void queueItemNow(UUID playerId, String rewardId, RewardAction action, String fingerprint)
        throws SQLException {
        executeBlockingMeasured("storage.rewards.item-overflow-queue", () -> {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT OR IGNORE INTO reward_item_overflow(
                  player_uuid,reward_id,action_id,fingerprint,material,amount,display_name,lore,status,queued_at)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """)) {
                statement.setString(1, playerId.toString());
                statement.setString(2, rewardId);
                statement.setString(3, action.getActionId());
                statement.setString(4, fingerprint);
                statement.setString(5, action.getMaterial().name());
                statement.setInt(6, action.getItemAmount());
                statement.setString(7, action.getDisplayName());
                statement.setString(8, String.join("\n", action.getLore()));
                statement.setString(9, "QUEUED");
                statement.setLong(10, System.currentTimeMillis());
                if (statement.executeUpdate() == 1) {
                    insertItemOverflowHistory(playerId, rewardId, action.getActionId(), null, "QUEUED",
                        "Inventory capacity was insufficient");
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

    public CompletableFuture<java.util.List<QueuedItem>> loadQueuedItemsAsync(UUID playerId) {
        return submitMeasured("storage.rewards.item-overflow-load", () -> {
            java.util.List<QueuedItem> result = new java.util.ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT reward_id,action_id,fingerprint,material,amount,display_name,lore,queued_at
                FROM reward_item_overflow WHERE player_uuid=? AND status='QUEUED' ORDER BY queued_at
                """)) {
                statement.setString(1, playerId.toString());
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        String lore = rs.getString("lore");
                        result.add(new QueuedItem(rs.getString("reward_id"), rs.getString("action_id"),
                            rs.getString("fingerprint"), rs.getString("material"), rs.getInt("amount"),
                            rs.getString("display_name"), lore == null || lore.isEmpty()
                                ? java.util.List.of() : java.util.List.of(lore.split("\n", -1)),
                            rs.getLong("queued_at")));
                    }
                }
            }
            return result;
        });
    }

    public CompletableFuture<Void> markQueuedItemDeliveredAsync(UUID playerId, QueuedItem item) {
        return submitMeasured("storage.rewards.item-overflow-deliver", () -> {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE reward_item_overflow SET status='DELIVERED',delivered_at=?
                WHERE player_uuid=? AND reward_id=? AND action_id=? AND fingerprint=? AND status='DELIVERY_PENDING'
                """)) {
                statement.setLong(1, System.currentTimeMillis());
                statement.setString(2, playerId.toString());
                statement.setString(3, item.rewardId());
                statement.setString(4, item.actionId());
                statement.setString(5, item.fingerprint());
                if (statement.executeUpdate() != 1) throw new SQLException("Queued item was not deliverable");
                insertItemOverflowHistory(playerId, item.rewardId(), item.actionId(), "DELIVERY_PENDING", "DELIVERED",
                    "Delivered from persistent overflow queue");
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

    public CompletableFuture<Void> markQueuedItemPendingAsync(UUID playerId, QueuedItem item) {
        return transitionQueuedItemAsync(playerId, item, "QUEUED", "DELIVERY_PENDING",
            "Reserved for online inventory delivery");
    }

    public CompletableFuture<Void> returnQueuedItemAsync(UUID playerId, QueuedItem item) {
        return transitionQueuedItemAsync(playerId, item, "DELIVERY_PENDING", "QUEUED",
            "Inventory capacity changed before delivery");
    }

    private CompletableFuture<Void> transitionQueuedItemAsync(UUID playerId, QueuedItem item, String oldStatus,
                                                               String newStatus, String reason) {
        return submitMeasured("storage.rewards.item-overflow-transition", () -> {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE reward_item_overflow SET status=?
                WHERE player_uuid=? AND reward_id=? AND action_id=? AND fingerprint=? AND status=?
                """)) {
                statement.setString(1, newStatus);
                statement.setString(2, playerId.toString());
                statement.setString(3, item.rewardId());
                statement.setString(4, item.actionId());
                statement.setString(5, item.fingerprint());
                statement.setString(6, oldStatus);
                if (statement.executeUpdate() != 1) throw new SQLException("Queued item transition was rejected");
                insertItemOverflowHistory(playerId, item.rewardId(), item.actionId(), oldStatus, newStatus, reason);
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

    private void insertItemOverflowHistory(UUID playerId, String rewardId, String actionId,
                                           String oldStatus, String newStatus, String reason) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO reward_item_overflow_history(
              player_uuid,reward_id,action_id,old_status,new_status,reason,created_at) VALUES(?,?,?,?,?,?,?)
            """)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, rewardId);
            statement.setString(3, actionId);
            statement.setString(4, oldStatus);
            statement.setString(5, newStatus);
            statement.setString(6, reason);
            statement.setLong(7, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    public java.util.List<ItemOverflowEntry> loadItemOverflowNow(UUID playerId, String rewardId)
        throws SQLException {
        return executeBlockingMeasured("storage.rewards.item-overflow-inspect", () -> {
            java.util.List<ItemOverflowEntry> entries = new java.util.ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT action_id,fingerprint,material,amount,status,queued_at,delivered_at
                FROM reward_item_overflow WHERE player_uuid=? AND reward_id=? ORDER BY queued_at
                """)) {
                statement.setString(1, playerId.toString());
                statement.setString(2, rewardId);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        long deliveredAt = rs.getLong("delivered_at");
                        boolean deliveredAtNull = rs.wasNull();
                        entries.add(new ItemOverflowEntry(rs.getString("action_id"), rs.getString("fingerprint"),
                            rs.getString("material"), rs.getInt("amount"), rs.getString("status"),
                            rs.getLong("queued_at"), deliveredAtNull ? null : deliveredAt));
                    }
                }
            }
            return entries;
        });
    }

    public String reconcileItemOverflowNow(UUID playerId, String rewardId, String actionId,
                                           boolean delivered, String auditReason) throws SQLException {
        return executeBlockingMeasured("storage.rewards.item-overflow-reconcile", () -> {
            connection.setAutoCommit(false);
            try {
                String oldStatus;
                try (PreparedStatement select = connection.prepareStatement("""
                    SELECT status FROM reward_item_overflow
                    WHERE player_uuid=? AND reward_id=? AND action_id=?
                    """)) {
                    select.setString(1, playerId.toString());
                    select.setString(2, rewardId);
                    select.setString(3, actionId);
                    try (ResultSet rs = select.executeQuery()) {
                        if (!rs.next()) throw new SQLException("Item overflow entry not found");
                        oldStatus = rs.getString("status");
                    }
                }
                if (!"DELIVERY_PENDING".equals(oldStatus)) {
                    throw new SQLException("Item overflow entry is " + oldStatus + ", not DELIVERY_PENDING");
                }
                String next = delivered ? "DELIVERED" : "QUEUED";
                try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE reward_item_overflow SET status=?,delivered_at=?
                    WHERE player_uuid=? AND reward_id=? AND action_id=? AND status='DELIVERY_PENDING'
                    """)) {
                    update.setString(1, next);
                    if (delivered) update.setLong(2, System.currentTimeMillis());
                    else update.setNull(2, java.sql.Types.BIGINT);
                    update.setString(3, playerId.toString());
                    update.setString(4, rewardId);
                    update.setString(5, actionId);
                    if (update.executeUpdate() != 1) throw new SQLException("Concurrent item reconciliation");
                }
                insertItemOverflowHistory(playerId, rewardId, actionId, oldStatus, next, auditReason);
                connection.commit();
                return next;
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(true);
            }
        });
    }

    public java.util.List<ReconciliationHistoryEntry> loadReconciliationHistoryNow(
        UUID playerId, String rewardId, int limit) throws SQLException {
        return executeBlockingMeasured("storage.rewards.reconciliation-history-load", () -> {
            java.util.List<ReconciliationHistoryEntry> entries = new java.util.ArrayList<>();
            try (PreparedStatement legacy = connection.prepareStatement("""
                SELECT removed_state_keys,previous_overall_status,decision,administrator,reason,created_at
                FROM reward_legacy_reconciliation_history WHERE player_uuid=? AND reward_id=?
                ORDER BY history_id DESC LIMIT ?
                """)) {
                legacy.setString(1, playerId.toString());
                legacy.setString(2, rewardId);
                legacy.setInt(3, Math.max(1, limit));
                try (ResultSet rs = legacy.executeQuery()) {
                    while (rs.next()) {
                        entries.add(new ReconciliationHistoryEntry("legacy", rs.getString("removed_state_keys"),
                            rs.getString("previous_overall_status"), rs.getString("decision"),
                            rs.getString("administrator"), rs.getString("reason"), rs.getLong("created_at")));
                    }
                }
            }
            try (PreparedStatement item = connection.prepareStatement("""
                SELECT action_id,old_status,new_status,reason,created_at
                FROM reward_item_overflow_history WHERE player_uuid=? AND reward_id=?
                ORDER BY history_id DESC LIMIT ?
                """)) {
                item.setString(1, playerId.toString());
                item.setString(2, rewardId);
                item.setInt(3, Math.max(1, limit));
                try (ResultSet rs = item.executeQuery()) {
                    while (rs.next()) {
                        entries.add(new ReconciliationHistoryEntry("item", rs.getString("action_id"),
                            rs.getString("old_status"), rs.getString("new_status"), null,
                            rs.getString("reason"), rs.getLong("created_at")));
                    }
                }
            }
            entries.sort(java.util.Comparator.comparingLong(ReconciliationHistoryEntry::createdAt).reversed());
            return entries.stream().limit(Math.max(1, limit)).toList();
        });
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    public void saveActionLedgerNow(UUID playerId, String rewardId, RewardAction action, String fingerprint,
                                    RewardStatus status, VaultHook.DepositResult result, String error)
        throws SQLException {
        executeBlockingMeasured("storage.rewards.action-ledger-save", () -> {
            connection.setAutoCommit(false);
            try {
                RewardStatus oldStatus = selectActionStatus(playerId, rewardId, action.getActionId());
                if (!allowedTransition(oldStatus, status)) {
                    throw new SQLException("Invalid reward action transition " + oldStatus + " -> " + status);
                }
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
                    bindActionEvidence(statement, playerId, rewardId, action, fingerprint, status, result, error);
                    statement.executeUpdate();
                }
                insertActionHistory(playerId, rewardId, action.getActionId(), oldStatus, status,
                    fingerprint, result, error);
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

    public RewardStatus reconcileActionNow(UUID playerId, String rewardId, String actionId,
                                           RewardStatus newStatus, String auditReason) throws SQLException {
        return executeBlockingMeasured("storage.rewards.action-reconcile", () -> {
            connection.setAutoCommit(false);
            try {
                RewardStatus oldStatus = selectActionStatus(playerId, rewardId, actionId);
                if ((oldStatus != RewardStatus.CLAIM_PENDING
                    && oldStatus != RewardStatus.REQUIRES_RECONCILIATION)
                    || (newStatus != RewardStatus.CLAIMED && newStatus != RewardStatus.DELIVERY_FAILED)) {
                    throw new SQLException("Invalid reconciliation transition " + oldStatus + " -> " + newStatus);
                }
                String fingerprint;
                try (PreparedStatement select = connection.prepareStatement(
                    "SELECT fingerprint FROM reward_action_ledger WHERE player_uuid=? AND reward_id=? AND action_id=?")) {
                    select.setString(1, playerId.toString());
                    select.setString(2, rewardId);
                    select.setString(3, actionId);
                    try (ResultSet rs = select.executeQuery()) {
                        if (!rs.next()) throw new SQLException("Action ledger entry not found");
                        fingerprint = rs.getString("fingerprint");
                    }
                }
                try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE reward_action_ledger SET status=?,error_message=?,updated_at=?"
                        + " WHERE player_uuid=? AND reward_id=? AND action_id=? AND status=?")) {
                    update.setString(1, newStatus.name());
                    update.setString(2, auditReason);
                    update.setLong(3, System.currentTimeMillis());
                    update.setString(4, playerId.toString());
                    update.setString(5, rewardId);
                    update.setString(6, actionId);
                    update.setString(7, oldStatus.name());
                    if (update.executeUpdate() != 1) throw new SQLException("Concurrent reconciliation change");
                }
                insertActionHistory(playerId, rewardId, actionId, oldStatus, newStatus,
                    fingerprint, null, auditReason);
                connection.commit();
                return oldStatus;
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(true);
            }
        });
    }

    private RewardStatus selectActionStatus(UUID playerId, String rewardId, String actionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT status FROM reward_action_ledger WHERE player_uuid=? AND reward_id=? AND action_id=?")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, rewardId);
            statement.setString(3, actionId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? RewardStatus.valueOf(rs.getString("status")) : null;
            }
        }
    }

    private boolean allowedTransition(RewardStatus oldStatus, RewardStatus next) {
        if (next == RewardStatus.CLAIM_PENDING) {
            return oldStatus == null || oldStatus == RewardStatus.DELIVERY_FAILED;
        }
        if (next == RewardStatus.CLAIMED || next == RewardStatus.DELIVERY_FAILED
            || next == RewardStatus.REQUIRES_RECONCILIATION) {
            return oldStatus == RewardStatus.CLAIM_PENDING;
        }
        return false;
    }

    private void bindActionEvidence(PreparedStatement statement, UUID playerId, String rewardId,
                                    RewardAction action, String fingerprint, RewardStatus status,
                                    VaultHook.DepositResult result, String error) throws SQLException {
        statement.setString(1, playerId.toString());
        statement.setString(2, rewardId);
        statement.setString(3, action.getActionId());
        statement.setString(4, action.getType().name());
        statement.setString(5, fingerprint);
        statement.setString(6, status.name());
        bindEvidence(statement, 7, result);
        statement.setString(12, error);
        statement.setLong(13, System.currentTimeMillis());
    }

    private void bindEvidence(PreparedStatement statement, int start, VaultHook.DepositResult result)
        throws SQLException {
        if (result == null) {
            for (int index = start; index < start + 5; index++) statement.setNull(index, java.sql.Types.REAL);
        } else {
            statement.setDouble(start, result.requestedAmount());
            statement.setDouble(start + 1, result.responseAmount());
            statement.setString(start + 2, result.responseType());
            statement.setDouble(start + 3, result.balanceBefore());
            statement.setDouble(start + 4, result.balanceAfter());
        }
    }

    private void insertActionHistory(UUID playerId, String rewardId, String actionId,
                                     RewardStatus oldStatus, RewardStatus newStatus, String fingerprint,
                                     VaultHook.DepositResult result, String error) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO reward_action_history(player_uuid,reward_id,action_id,old_status,new_status,fingerprint,
              requested_amount,response_amount,response_type,balance_before,balance_after,error_message,created_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
            """)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, rewardId);
            statement.setString(3, actionId);
            statement.setString(4, oldStatus == null ? null : oldStatus.name());
            statement.setString(5, newStatus.name());
            statement.setString(6, fingerprint);
            bindEvidence(statement, 7, result);
            statement.setString(12, error);
            statement.setLong(13, System.currentTimeMillis());
            statement.executeUpdate();
        }
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

        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT reward_id FROM reward_unlocks WHERE player_uuid = ?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    states.put("reward-unlocked:" + rs.getString("reward_id"), "true");
                }
            }
        }

        return new StoredRewardData(claims, counters, states, loadStoredRevision(playerId));
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
