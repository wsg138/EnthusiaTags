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
    private static final int EXPECTED_SINGLE_ROW = 1;
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
    public record AtomicReconcileResult(String oldSubjectStatus, String newSubjectStatus,
                                        String oldOverallStatus, String newOverallStatus,
                                        boolean claimed, long revision) {
    }
    public record IpReservation(String rewardId, String ipAddress, UUID owner, long claimedAt) {
    }
    public record AdminHistoryEntry(long historyId, String category, String administratorName,
                                    String administratorUuid, String subjectId, String oldSubjectStatus,
                                    String newSubjectStatus, String oldOverallStatus, String newOverallStatus,
                                    String decision, String reason, String evidence, long createdAt) {
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
        this.executor = new ReentrantSingleThreadExecutor("enthusia-tags-rewards-storage");
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
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS reward_reconciliation_history (
                    history_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    category TEXT NOT NULL,
                    administrator_name TEXT NOT NULL,
                    administrator_uuid TEXT,
                    player_uuid TEXT NOT NULL,
                    reward_id TEXT NOT NULL,
                    subject_id TEXT NOT NULL,
                    old_subject_status TEXT,
                    new_subject_status TEXT,
                    old_overall_status TEXT,
                    new_overall_status TEXT,
                    decision TEXT NOT NULL,
                    reason TEXT NOT NULL,
                    evidence TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS reward_ip_reconciliation_history (
                    history_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    administrator_name TEXT NOT NULL,
                    administrator_uuid TEXT,
                    player_uuid TEXT NOT NULL,
                    reward_id TEXT NOT NULL,
                    ip_address TEXT NOT NULL,
                    decision TEXT NOT NULL,
                    previous_owner TEXT,
                    new_owner TEXT,
                    reason TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS reward_inspection_history (
                    history_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    administrator_name TEXT NOT NULL,
                    administrator_uuid TEXT,
                    player_uuid TEXT NOT NULL,
                    reward_id TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                )
                """);
        }
        migratePreviousItemQueueSemantics();
    }

    private void migratePreviousItemQueueSemantics() throws SQLException {
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                UPDATE reward_action_ledger SET status='ITEM_QUEUED',
                  error_message='Migrated from pre-pending item queue semantics',updated_at=strftime('%s','now')*1000
                WHERE status='CLAIMED' AND EXISTS (
                  SELECT 1 FROM reward_item_overflow q
                  WHERE q.player_uuid=reward_action_ledger.player_uuid
                    AND q.reward_id=reward_action_ledger.reward_id
                    AND q.action_id=reward_action_ledger.action_id
                    AND q.fingerprint=reward_action_ledger.fingerprint
                    AND q.status IN ('QUEUED','DELIVERY_PENDING'))
                """);
            statement.executeUpdate("""
                DELETE FROM reward_claims WHERE EXISTS (
                  SELECT 1 FROM reward_item_overflow q
                  WHERE q.player_uuid=reward_claims.player_uuid AND q.reward_id=reward_claims.reward_id
                    AND q.status IN ('QUEUED','DELIVERY_PENDING'))
                """);
            statement.executeUpdate("""
                INSERT INTO reward_states(player_uuid,state_key,state_value)
                SELECT player_uuid,'reward-delivery:'||reward_id,
                  CASE WHEN SUM(CASE WHEN status='DELIVERY_PENDING' THEN 1 ELSE 0 END)>0
                    THEN 'REQUIRES_RECONCILIATION' ELSE 'ITEM_QUEUED' END
                FROM reward_item_overflow WHERE status IN ('QUEUED','DELIVERY_PENDING')
                GROUP BY player_uuid,reward_id
                ON CONFLICT(player_uuid,state_key) DO UPDATE SET state_value=excluded.state_value
                """);
            connection.commit();
        } catch (SQLException ex) {
            connection.rollback();
            throw ex;
        } finally {
            connection.setAutoCommit(true);
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

    public long finalizeRewardNow(UUID playerId, String rewardId) throws SQLException {
        return executeBlockingMeasured("storage.rewards.finalize", () -> {
            connection.setAutoCommit(false);
            try {
                writeOverallAndClaim(playerId, rewardId, RewardStatus.CLAIMED, true);
                long revision = bumpPlayerRevision(playerId);
                connection.commit();
                return revision;
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(true);
            }
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

    public boolean isUnlockedNow(UUID playerId, String rewardId) throws SQLException {
        return executeBlockingMeasured("storage.rewards.unlock-inspect", () -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM reward_unlocks WHERE player_uuid=? AND reward_id=?")) {
                statement.setString(1, playerId.toString());
                statement.setString(2, rewardId);
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    public void recordInspectionNow(UUID playerId, String rewardId, String administratorName,
                                    UUID administratorId) throws SQLException {
        executeBlockingMeasured("storage.rewards.inspection-history", () -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO reward_inspection_history(
                  administrator_name,administrator_uuid,player_uuid,reward_id,created_at) VALUES(?,?,?,?,?)
                """)) {
                statement.setString(1, administratorName);
                if (administratorId == null) statement.setNull(2, java.sql.Types.VARCHAR);
                else statement.setString(2, administratorId.toString());
                statement.setString(3, playerId.toString());
                statement.setString(4, rewardId);
                statement.setLong(5, System.currentTimeMillis());
                statement.executeUpdate();
            }
            return null;
        });
    }

    public Set<String> listKnownRewardIdsNow(UUID playerId) throws SQLException {
        return executeBlockingMeasured("storage.rewards.known-rewards", () -> {
            Set<String> result = new java.util.TreeSet<>();
            String uuid = playerId.toString();
            for (String sql : java.util.List.of(
                "SELECT reward_id FROM reward_claims WHERE player_uuid=?",
                "SELECT reward_id FROM reward_unlocks WHERE player_uuid=?",
                "SELECT reward_id FROM reward_action_ledger WHERE player_uuid=?",
                "SELECT reward_id FROM reward_item_overflow WHERE player_uuid=?",
                "SELECT reward_id FROM reward_ip_claims WHERE player_uuid=?")) {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, uuid);
                    try (ResultSet rs = statement.executeQuery()) {
                        while (rs.next()) result.add(rs.getString("reward_id"));
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT state_key FROM reward_states WHERE player_uuid=? AND state_key LIKE 'reward-%:%'")) {
                statement.setString(1, uuid);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        String key = rs.getString("state_key");
                        int colon = key.indexOf(':');
                        if (colon >= 0 && colon + 1 < key.length()) {
                            String suffix = key.substring(colon + 1);
                            int next = suffix.indexOf(':');
                            result.add(next < 0 ? suffix : suffix.substring(0, next));
                        }
                    }
                }
            }
            return result;
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
                RewardStatus oldStatus = selectActionStatus(playerId, rewardId, action.getActionId());
                if (oldStatus != RewardStatus.CLAIM_PENDING) {
                    throw new SQLException("Item action was not CLAIM_PENDING");
                }
                try (PreparedStatement ledger = connection.prepareStatement("""
                    UPDATE reward_action_ledger SET status='ITEM_QUEUED',error_message=?,updated_at=?
                    WHERE player_uuid=? AND reward_id=? AND action_id=? AND fingerprint=? AND status='CLAIM_PENDING'
                    """)) {
                    ledger.setString(1, "Durable item entitlement queued");
                    ledger.setLong(2, System.currentTimeMillis());
                    ledger.setString(3, playerId.toString());
                    ledger.setString(4, rewardId);
                    ledger.setString(5, action.getActionId());
                    ledger.setString(6, fingerprint);
                    if (ledger.executeUpdate() != 1) throw new SQLException("Item action queue transition failed");
                }
                insertActionHistory(playerId, rewardId, action.getActionId(), oldStatus,
                    RewardStatus.ITEM_QUEUED, fingerprint, null, "Durable item entitlement queued");
                writeOverallAndClaim(playerId, rewardId, RewardStatus.ITEM_QUEUED, false);
                bumpPlayerRevision(playerId);
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

    public CompletableFuture<Void> completeQueuedItemAsync(UUID playerId, QueuedItem item) {
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
                try (PreparedStatement ledger = connection.prepareStatement("""
                    UPDATE reward_action_ledger SET status='CLAIMED',error_message=?,updated_at=?
                    WHERE player_uuid=? AND reward_id=? AND action_id=? AND fingerprint=? AND status='ITEM_QUEUED'
                    """)) {
                    ledger.setString(1, "Delivered from persistent overflow queue");
                    ledger.setLong(2, System.currentTimeMillis());
                    ledger.setString(3, playerId.toString());
                    ledger.setString(4, item.rewardId());
                    ledger.setString(5, item.actionId());
                    ledger.setString(6, item.fingerprint());
                    if (ledger.executeUpdate() != 1) throw new SQLException("Queued item action was not ITEM_QUEUED");
                }
                insertActionHistory(playerId, item.rewardId(), item.actionId(), RewardStatus.ITEM_QUEUED,
                    RewardStatus.CLAIMED, item.fingerprint(), null,
                    "Delivered from persistent overflow queue");
                writeOverallAndClaim(playerId, item.rewardId(), RewardStatus.CLAIM_PENDING, false);
                bumpPlayerRevision(playerId);
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

    public CompletableFuture<Long> markQueuedItemReconciliationAsync(
        UUID playerId, QueuedItem item, String evidence) {
        return submitMeasured("storage.rewards.item-overflow-ambiguous", () -> {
            connection.setAutoCommit(false);
            try {
                ActionLedgerEntry ledger = selectActionEntry(playerId, item.rewardId(), item.actionId());
                if (ledger == null || ledger.status() != RewardStatus.ITEM_QUEUED
                    || !item.fingerprint().equals(ledger.fingerprint())) {
                    throw new SQLException("Ambiguous item does not match its unresolved action ledger entry");
                }
                try (PreparedStatement pending = connection.prepareStatement("""
                    SELECT 1 FROM reward_item_overflow
                    WHERE player_uuid=? AND reward_id=? AND action_id=? AND fingerprint=?
                      AND status='DELIVERY_PENDING'
                    """)) {
                    pending.setString(1, playerId.toString());
                    pending.setString(2, item.rewardId());
                    pending.setString(3, item.actionId());
                    pending.setString(4, item.fingerprint());
                    try (ResultSet rs = pending.executeQuery()) {
                        if (!rs.next()) throw new SQLException("Item overflow entry is not DELIVERY_PENDING");
                    }
                }
                try (PreparedStatement action = connection.prepareStatement("""
                    UPDATE reward_action_ledger
                    SET status='REQUIRES_RECONCILIATION',error_message=?,updated_at=?
                    WHERE player_uuid=? AND reward_id=? AND action_id=? AND fingerprint=?
                      AND status='ITEM_QUEUED'
                    """)) {
                    action.setString(1, evidence);
                    action.setLong(2, System.currentTimeMillis());
                    action.setString(3, playerId.toString());
                    action.setString(4, item.rewardId());
                    action.setString(5, item.actionId());
                    action.setString(6, item.fingerprint());
                    if (action.executeUpdate() != 1) {
                        throw new SQLException("Ambiguous item action transition was rejected");
                    }
                }
                insertItemOverflowHistory(playerId, item.rewardId(), item.actionId(),
                    "DELIVERY_PENDING", "DELIVERY_PENDING", evidence);
                insertActionHistory(playerId, item.rewardId(), item.actionId(), RewardStatus.ITEM_QUEUED,
                    RewardStatus.REQUIRES_RECONCILIATION, item.fingerprint(), null, evidence);
                writeOverallAndClaim(playerId, item.rewardId(), RewardStatus.REQUIRES_RECONCILIATION, false);
                long revision = bumpPlayerRevision(playerId);
                connection.commit();
                return revision;
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(true);
            }
        });
    }

    private CompletableFuture<Void> transitionQueuedItemAsync(UUID playerId, QueuedItem item, String oldStatus,
                                                               String newStatus, String reason) {
        return submitMeasured("storage.rewards.item-overflow-transition", () -> {
            connection.setAutoCommit(false);
            try {
                if ("QUEUED".equals(oldStatus)) {
                    ActionLedgerEntry ledger = selectActionEntry(playerId, item.rewardId(), item.actionId());
                    if (ledger == null || ledger.status() != RewardStatus.ITEM_QUEUED
                        || !item.fingerprint().equals(ledger.fingerprint())) {
                        throw new SQLException("Queued item does not match an ITEM_QUEUED action ledger entry");
                    }
                }
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

    public java.util.List<AdminHistoryEntry> loadAdminHistoryNow(UUID playerId, String rewardId, int limit)
        throws SQLException {
        return executeBlockingMeasured("storage.rewards.admin-history-load", () -> {
            java.util.List<AdminHistoryEntry> entries = new java.util.ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT history_id,category,administrator_name,administrator_uuid,subject_id,
                  old_subject_status,new_subject_status,old_overall_status,new_overall_status,
                  decision,reason,evidence,created_at
                FROM reward_reconciliation_history WHERE player_uuid=? AND reward_id=?
                ORDER BY history_id DESC LIMIT ?
                """)) {
                statement.setString(1, playerId.toString());
                statement.setString(2, rewardId);
                statement.setInt(3, Math.max(1, limit));
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        entries.add(new AdminHistoryEntry(rs.getLong("history_id"), rs.getString("category"),
                            rs.getString("administrator_name"), rs.getString("administrator_uuid"),
                            rs.getString("subject_id"), rs.getString("old_subject_status"),
                            rs.getString("new_subject_status"), rs.getString("old_overall_status"),
                            rs.getString("new_overall_status"), rs.getString("decision"),
                            rs.getString("reason"), rs.getString("evidence"), rs.getLong("created_at")));
                    }
                }
            }
            return entries;
        });
    }

    public AtomicReconcileResult reconcileIpReservationNow(
        UUID playerId, String rewardId, String ipAddress, String decision,
        String administratorName, UUID administratorId, String reason) throws SQLException {
        return executeBlockingMeasured("storage.rewards.ip-reconcile", () -> {
            connection.setAutoCommit(false);
            try {
                String oldOwner = null;
                try (PreparedStatement select = connection.prepareStatement(
                    "SELECT player_uuid FROM reward_ip_claims WHERE reward_id=? AND ip_address=?")) {
                    select.setString(1, rewardId);
                    select.setString(2, ipAddress);
                    try (ResultSet rs = select.executeQuery()) {
                        if (rs.next()) oldOwner = rs.getString("player_uuid");
                    }
                }
                String newOwner;
                switch (decision) {
                    case "retain" -> {
                        if (!playerId.toString().equals(oldOwner)) {
                            throw new SQLException("Reservation is not owned by the target player");
                        }
                        newOwner = oldOwner;
                    }
                    case "release" -> {
                        if (!playerId.toString().equals(oldOwner)) {
                            throw new SQLException("Reservation is not owned by the target player");
                        }
                        try (PreparedStatement delete = connection.prepareStatement(
                            "DELETE FROM reward_ip_claims WHERE reward_id=? AND ip_address=? AND player_uuid=?")) {
                            delete.setString(1, rewardId);
                            delete.setString(2, ipAddress);
                            delete.setString(3, playerId.toString());
                            if (delete.executeUpdate() != 1) throw new SQLException("IP reservation changed");
                        }
                        newOwner = null;
                    }
                    case "repair" -> {
                        try (PreparedStatement upsert = connection.prepareStatement("""
                            INSERT INTO reward_ip_claims(reward_id,ip_address,player_uuid,claimed_at)
                            VALUES(?,?,?,?) ON CONFLICT(reward_id,ip_address) DO UPDATE SET
                              player_uuid=excluded.player_uuid,claimed_at=excluded.claimed_at
                            """)) {
                            upsert.setString(1, rewardId);
                            upsert.setString(2, ipAddress);
                            upsert.setString(3, playerId.toString());
                            upsert.setLong(4, System.currentTimeMillis());
                            upsert.executeUpdate();
                        }
                        newOwner = playerId.toString();
                    }
                    default -> throw new SQLException("Unknown IP reconciliation decision");
                }
                try (PreparedStatement history = connection.prepareStatement("""
                    INSERT INTO reward_ip_reconciliation_history(
                      administrator_name,administrator_uuid,player_uuid,reward_id,ip_address,
                      decision,previous_owner,new_owner,reason,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)
                    """)) {
                    history.setString(1, administratorName);
                    if (administratorId == null) history.setNull(2, java.sql.Types.VARCHAR);
                    else history.setString(2, administratorId.toString());
                    history.setString(3, playerId.toString());
                    history.setString(4, rewardId);
                    history.setString(5, ipAddress);
                    history.setString(6, decision);
                    history.setString(7, oldOwner);
                    history.setString(8, newOwner);
                    history.setString(9, reason);
                    history.setLong(10, System.currentTimeMillis());
                    history.executeUpdate();
                }
                connection.commit();
                return new AtomicReconcileResult(oldOwner, newOwner, null, null, false,
                    loadStoredRevision(playerId));
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(true);
            }
        });
    }

    public java.util.List<String> loadIpHistoryNow(UUID playerId, String rewardId, int limit)
        throws SQLException {
        return executeBlockingMeasured("storage.rewards.ip-history-load", () -> {
            java.util.List<String> result = new java.util.ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT history_id,administrator_name,administrator_uuid,ip_address,decision,
                  previous_owner,new_owner,reason,created_at
                FROM reward_ip_reconciliation_history WHERE player_uuid=? AND reward_id=?
                ORDER BY history_id DESC LIMIT ?
                """)) {
                statement.setString(1, playerId.toString());
                statement.setString(2, rewardId);
                statement.setInt(3, Math.max(1, limit));
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        result.add("#" + rs.getLong("history_id") + " ip=" + rs.getString("ip_address")
                            + " decision=" + rs.getString("decision") + " "
                            + rs.getString("previous_owner") + " -> " + rs.getString("new_owner")
                            + " by=" + rs.getString("administrator_name")
                            + (rs.getString("administrator_uuid") == null ? ""
                                : "/" + rs.getString("administrator_uuid"))
                            + " at=" + rs.getLong("created_at") + " reason=" + rs.getString("reason"));
                    }
                }
            }
            return result;
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

    public boolean acceptLoreItemHandoffNow(
        UUID playerId,
        String rewardId,
        RewardAction action,
        String expectedFingerprint,
        String evidence) throws SQLException {
        requireLoreItemAction(action);
        return executeBlockingMeasured(
            "storage.rewards.loreitems-accepted",
            () -> acceptLoreItemHandoffDirect(playerId, rewardId, action, expectedFingerprint, evidence));
    }

    private boolean acceptLoreItemHandoffDirect(
        UUID playerId,
        String rewardId,
        RewardAction action,
        String expectedFingerprint,
        String evidence) throws SQLException {
        connection.setAutoCommit(false);
        try {
            ActionLedgerEntry current = selectActionEntry(playerId, rewardId, action.getActionId());
            if (!matchesLoreItemRecoveryIdentity(current, expectedFingerprint)) {
                connection.rollback();
                return false;
            }
            if (current.status() == RewardStatus.CLAIMED) {
                connection.rollback();
                return true;
            }
            if (!isRecoverableLoreItemStatus(current.status())) {
                connection.rollback();
                return false;
            }
            updateAcceptedLoreItemAction(playerId, rewardId, action, expectedFingerprint, evidence, current);
            connection.commit();
            return true;
        } catch (SQLException ex) {
            connection.rollback();
            throw ex;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private void updateAcceptedLoreItemAction(
        UUID playerId,
        String rewardId,
        RewardAction action,
        String expectedFingerprint,
        String evidence,
        ActionLedgerEntry current) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
            UPDATE reward_action_ledger
               SET status='CLAIMED', error_message=?, updated_at=?
             WHERE player_uuid=? AND reward_id=? AND action_id=?
               AND action_type='LORE_ITEM' AND fingerprint=? AND status=?
            """)) {
            update.setString(1, evidence);
            update.setLong(2, System.currentTimeMillis());
            update.setString(3, playerId.toString());
            update.setString(4, rewardId);
            update.setString(5, action.getActionId());
            update.setString(6, expectedFingerprint);
            update.setString(7, current.status().name());
            if (update.executeUpdate() != EXPECTED_SINGLE_ROW) {
                throw new SQLException("Concurrent LoreItems reward recovery change");
            }
        }
        insertActionHistory(playerId, rewardId, action.getActionId(), current.status(),
            RewardStatus.CLAIMED, expectedFingerprint, null, evidence);
    }

    private static void requireLoreItemAction(RewardAction action) {
        if (action == null || action.getType() != RewardActionType.LORE_ITEM) {
            throw new IllegalArgumentException("action must be a LORE_ITEM reward action");
        }
    }

    private static boolean matchesLoreItemRecoveryIdentity(
        ActionLedgerEntry current,
        String expectedFingerprint) {
        return current != null
            && RewardActionType.LORE_ITEM.name().equals(current.actionType())
            && expectedFingerprint.equals(current.fingerprint());
    }

    private static boolean isRecoverableLoreItemStatus(RewardStatus status) {
        return status == RewardStatus.CLAIM_PENDING || status == RewardStatus.DELIVERY_FAILED;
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

    public AtomicReconcileResult reconcileActionAtomicNow(
        UUID playerId, String rewardId, String actionId, String expectedFingerprint,
        RewardStatus newStatus, RewardStatus newOverall, boolean finalize,
        String administratorName, UUID administratorId, String decision, String reason, String evidence)
        throws SQLException {
        return executeBlockingMeasured("storage.rewards.action-reconcile-atomic", () -> {
            connection.setAutoCommit(false);
            try {
                ActionLedgerEntry current = selectActionEntry(playerId, rewardId, actionId);
                if (current == null) throw new SQLException("Action ledger entry not found");
                if (!current.fingerprint().equals(expectedFingerprint)) {
                    throw new SQLException("Action fingerprint changed during reconciliation");
                }
                if ((current.status() != RewardStatus.CLAIM_PENDING
                    && current.status() != RewardStatus.REQUIRES_RECONCILIATION)
                    || (newStatus != RewardStatus.CLAIMED && newStatus != RewardStatus.DELIVERY_FAILED)) {
                    throw new SQLException("Invalid reconciliation transition " + current.status()
                        + " -> " + newStatus);
                }
                String oldOverall = selectOverallStatus(playerId, rewardId);
                try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE reward_action_ledger SET status=?,error_message=?,updated_at=?
                    WHERE player_uuid=? AND reward_id=? AND action_id=? AND fingerprint=? AND status=?
                    """)) {
                    update.setString(1, newStatus.name());
                    update.setString(2, evidence);
                    update.setLong(3, System.currentTimeMillis());
                    update.setString(4, playerId.toString());
                    update.setString(5, rewardId);
                    update.setString(6, actionId);
                    update.setString(7, expectedFingerprint);
                    update.setString(8, current.status().name());
                    if (update.executeUpdate() != 1) throw new SQLException("Concurrent reconciliation change");
                }
                insertActionHistory(playerId, rewardId, actionId, current.status(), newStatus,
                    expectedFingerprint, null, evidence);
                writeOverallAndClaim(playerId, rewardId, newOverall, finalize);
                insertReconciliationHistory("action", administratorName, administratorId, playerId, rewardId,
                    actionId, current.status().name(), newStatus.name(), oldOverall, newOverall.name(),
                    decision, reason, evidence);
                long revision = bumpPlayerRevision(playerId);
                connection.commit();
                return new AtomicReconcileResult(current.status().name(), newStatus.name(), oldOverall,
                    newOverall.name(), finalize, revision);
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(true);
            }
        });
    }

    public AtomicReconcileResult reconcileItemAtomicNow(
        UUID playerId, String rewardId, String actionId, String expectedFingerprint,
        boolean delivered, RewardStatus newOverall, boolean finalize,
        String administratorName, UUID administratorId, String reason, String evidence) throws SQLException {
        return executeBlockingMeasured("storage.rewards.item-reconcile-atomic", () -> {
            connection.setAutoCommit(false);
            try {
                ActionLedgerEntry action = selectActionEntry(playerId, rewardId, actionId);
                if (action == null || !expectedFingerprint.equals(action.fingerprint())) {
                    throw new SQLException("Matching item action ledger entry not found");
                }
                String oldItem;
                try (PreparedStatement select = connection.prepareStatement("""
                    SELECT status,fingerprint FROM reward_item_overflow
                    WHERE player_uuid=? AND reward_id=? AND action_id=?
                    """)) {
                    select.setString(1, playerId.toString());
                    select.setString(2, rewardId);
                    select.setString(3, actionId);
                    try (ResultSet rs = select.executeQuery()) {
                        if (!rs.next()) throw new SQLException("Item overflow entry not found");
                        oldItem = rs.getString("status");
                        if (!expectedFingerprint.equals(rs.getString("fingerprint"))) {
                            throw new SQLException("Item overflow fingerprint does not match the action ledger");
                        }
                    }
                }
                if (!"DELIVERY_PENDING".equals(oldItem)) {
                    throw new SQLException("Item delivery is " + oldItem
                        + "; only ambiguous DELIVERY_PENDING records require staff reconciliation");
                }
                String newItem = delivered ? "DELIVERED" : "QUEUED";
                String oldOverall = selectOverallStatus(playerId, rewardId);
                try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE reward_item_overflow SET status=?,delivered_at=?
                    WHERE player_uuid=? AND reward_id=? AND action_id=? AND fingerprint=?
                      AND status='DELIVERY_PENDING'
                    """)) {
                    update.setString(1, newItem);
                    if (delivered) update.setLong(2, System.currentTimeMillis());
                    else update.setNull(2, java.sql.Types.BIGINT);
                    update.setString(3, playerId.toString());
                    update.setString(4, rewardId);
                    update.setString(5, actionId);
                    update.setString(6, expectedFingerprint);
                    if (update.executeUpdate() != 1) throw new SQLException("Concurrent item reconciliation");
                }
                insertItemOverflowHistory(playerId, rewardId, actionId, oldItem, newItem, evidence);
                if (action.status() != RewardStatus.ITEM_QUEUED
                    && action.status() != RewardStatus.REQUIRES_RECONCILIATION) {
                    throw new SQLException("Item action is " + action.status() + ", not unresolved");
                }
                RewardStatus nextAction = delivered ? RewardStatus.CLAIMED : RewardStatus.ITEM_QUEUED;
                try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE reward_action_ledger SET status=?,error_message=?,updated_at=?
                    WHERE player_uuid=? AND reward_id=? AND action_id=? AND fingerprint=? AND status=?
                    """)) {
                    update.setString(1, nextAction.name());
                    update.setString(2, evidence);
                    update.setLong(3, System.currentTimeMillis());
                    update.setString(4, playerId.toString());
                    update.setString(5, rewardId);
                    update.setString(6, actionId);
                    update.setString(7, expectedFingerprint);
                    update.setString(8, action.status().name());
                    if (update.executeUpdate() != 1) throw new SQLException("Item action reconciliation failed");
                }
                insertActionHistory(playerId, rewardId, actionId, action.status(),
                    nextAction, expectedFingerprint, null, evidence);
                String newAction = nextAction.name();
                writeOverallAndClaim(playerId, rewardId, newOverall, finalize);
                insertReconciliationHistory("item", administratorName, administratorId, playerId, rewardId,
                    actionId, oldItem + "/" + action.status(), newItem + "/" + newAction,
                    oldOverall, newOverall.name(), delivered ? "delivered" : "retry", reason, evidence);
                long revision = bumpPlayerRevision(playerId);
                connection.commit();
                return new AtomicReconcileResult(oldItem + "/" + action.status(), newItem + "/" + newAction,
                    oldOverall, newOverall.name(), finalize, revision);
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(true);
            }
        });
    }

    private ActionLedgerEntry selectActionEntry(UUID playerId, String rewardId, String actionId)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT action_id,action_type,fingerprint,status,requested_amount,response_amount,response_type,
              balance_before,balance_after,error_message,updated_at
            FROM reward_action_ledger WHERE player_uuid=? AND reward_id=? AND action_id=?
            """)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, rewardId);
            statement.setString(3, actionId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return null;
                return new ActionLedgerEntry(rs.getString("action_id"), rs.getString("action_type"),
                    rs.getString("fingerprint"), RewardStatus.valueOf(rs.getString("status")),
                    nullableDouble(rs, "requested_amount"), nullableDouble(rs, "response_amount"),
                    rs.getString("response_type"), nullableDouble(rs, "balance_before"),
                    nullableDouble(rs, "balance_after"), rs.getString("error_message"),
                    rs.getLong("updated_at"));
            }
        }
    }

    private String selectOverallStatus(UUID playerId, String rewardId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT state_value FROM reward_states WHERE player_uuid=? AND state_key=?")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, "reward-delivery:" + rewardId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString("state_value") : null;
            }
        }
    }

    private void writeOverallAndClaim(UUID playerId, String rewardId, RewardStatus overall, boolean finalize)
        throws SQLException {
        try (PreparedStatement state = connection.prepareStatement("""
            INSERT INTO reward_states(player_uuid,state_key,state_value) VALUES(?,?,?)
            ON CONFLICT(player_uuid,state_key) DO UPDATE SET state_value=excluded.state_value
            """)) {
            state.setString(1, playerId.toString());
            state.setString(2, "reward-delivery:" + rewardId);
            state.setString(3, overall.name());
            state.executeUpdate();
        }
        if (finalize) {
            try (PreparedStatement claim = connection.prepareStatement(
                "INSERT OR IGNORE INTO reward_claims(player_uuid,reward_id,claimed_at) VALUES(?,?,?)")) {
                claim.setString(1, playerId.toString());
                claim.setString(2, rewardId);
                claim.setLong(3, System.currentTimeMillis());
                claim.executeUpdate();
            }
        } else {
            try (PreparedStatement claim = connection.prepareStatement(
                "DELETE FROM reward_claims WHERE player_uuid=? AND reward_id=?")) {
                claim.setString(1, playerId.toString());
                claim.setString(2, rewardId);
                claim.executeUpdate();
            }
        }
    }

    private void insertReconciliationHistory(
        String category, String administratorName, UUID administratorId, UUID playerId, String rewardId,
        String subjectId, String oldSubject, String newSubject, String oldOverall, String newOverall,
        String decision, String reason, String evidence) throws SQLException {
        try (PreparedStatement history = connection.prepareStatement("""
            INSERT INTO reward_reconciliation_history(
              category,administrator_name,administrator_uuid,player_uuid,reward_id,subject_id,
              old_subject_status,new_subject_status,old_overall_status,new_overall_status,
              decision,reason,evidence,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """)) {
            history.setString(1, category);
            history.setString(2, administratorName);
            if (administratorId == null) history.setNull(3, java.sql.Types.VARCHAR);
            else history.setString(3, administratorId.toString());
            history.setString(4, playerId.toString());
            history.setString(5, rewardId);
            history.setString(6, subjectId);
            history.setString(7, oldSubject);
            history.setString(8, newSubject);
            history.setString(9, oldOverall);
            history.setString(10, newOverall);
            history.setString(11, decision);
            history.setString(12, reason);
            history.setString(13, evidence == null ? "" : evidence);
            history.setLong(14, System.currentTimeMillis());
            history.executeUpdate();
        }
    }

    private long bumpPlayerRevision(UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO reward_player_versions(player_uuid,revision) VALUES(?,1)
            ON CONFLICT(player_uuid) DO UPDATE SET revision=revision+1
            """)) {
            statement.setString(1, playerId.toString());
            statement.executeUpdate();
        }
        return loadStoredRevision(playerId);
    }

    public AtomicReconcileResult reconcileLegacyAtomicNow(
        UUID playerId, String rewardId, java.util.Collection<String> expectedKeys, boolean delivered,
        String administratorName, UUID administratorId, String reason) throws SQLException {
        return executeBlockingMeasured("storage.rewards.legacy-reconcile-atomic", () -> {
            connection.setAutoCommit(false);
            try {
                java.util.List<String> actualKeys = new java.util.ArrayList<>();
                try (PreparedStatement select = connection.prepareStatement(
                    "SELECT state_key FROM reward_states WHERE player_uuid=? AND state_key LIKE ? ORDER BY state_key")) {
                    select.setString(1, playerId.toString());
                    select.setString(2, "reward-action:" + rewardId + ":%");
                    try (ResultSet rs = select.executeQuery()) {
                        while (rs.next()) actualKeys.add(rs.getString("state_key"));
                    }
                }
                java.util.List<String> expected = expectedKeys.stream().sorted().toList();
                if (actualKeys.isEmpty() || !actualKeys.equals(expected)) {
                    throw new SQLException("Legacy state changed since inspection");
                }
                String oldOverall = selectOverallStatus(playerId, rewardId);
                try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM reward_states WHERE player_uuid=? AND state_key=?")) {
                    for (String key : actualKeys) {
                        delete.setString(1, playerId.toString());
                        delete.setString(2, key);
                        delete.addBatch();
                    }
                    delete.executeBatch();
                }
                RewardStatus newOverall = delivered
                    ? RewardStatus.REQUIRES_RECONCILIATION : RewardStatus.DELIVERY_FAILED;
                if (delivered) {
                    try (PreparedStatement marker = connection.prepareStatement("""
                        INSERT INTO reward_states(player_uuid,state_key,state_value) VALUES(?,?,?)
                        ON CONFLICT(player_uuid,state_key) DO UPDATE SET state_value=excluded.state_value
                        """)) {
                        marker.setString(1, playerId.toString());
                        marker.setString(2, "reward-legacy-unmapped:" + rewardId);
                        marker.setString(3, "requires-force-resolution");
                        marker.executeUpdate();
                    }
                } else {
                    try (PreparedStatement marker = connection.prepareStatement(
                        "DELETE FROM reward_states WHERE player_uuid=? AND state_key=?")) {
                        marker.setString(1, playerId.toString());
                        marker.setString(2, "reward-legacy-unmapped:" + rewardId);
                        marker.executeUpdate();
                    }
                }
                writeOverallAndClaim(playerId, rewardId, newOverall, false);
                try (PreparedStatement history = connection.prepareStatement("""
                    INSERT INTO reward_legacy_reconciliation_history(
                      administrator,player_uuid,reward_id,removed_state_keys,previous_overall_status,
                      decision,reason,created_at) VALUES(?,?,?,?,?,?,?,?)
                    """)) {
                    history.setString(1, administratorId == null ? administratorName
                        : administratorName + "/" + administratorId);
                    history.setString(2, playerId.toString());
                    history.setString(3, rewardId);
                    history.setString(4, String.join("\n", actualKeys));
                    history.setString(5, oldOverall);
                    history.setString(6, delivered ? "delivered-unmapped" : "retry");
                    history.setString(7, reason);
                    history.setLong(8, System.currentTimeMillis());
                    history.executeUpdate();
                }
                insertReconciliationHistory("legacy", administratorName, administratorId, playerId, rewardId,
                    "legacy", String.join(",", actualKeys), delivered ? "legacy-unmapped" : "retryable",
                    oldOverall, newOverall.name(), delivered ? "delivered" : "retry", reason,
                    "Removed exact keys: " + actualKeys);
                long revision = bumpPlayerRevision(playerId);
                connection.commit();
                return new AtomicReconcileResult(String.join(",", actualKeys),
                    delivered ? "legacy-unmapped" : "retryable", oldOverall, newOverall.name(), false, revision);
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(true);
            }
        });
    }

    public AtomicReconcileResult forceWholeAtomicNow(
        UUID playerId, String rewardId, boolean delivered, String administratorName, UUID administratorId,
        String reason, String evidence) throws SQLException {
        return executeBlockingMeasured("storage.rewards.whole-reconcile-atomic", () -> {
            connection.setAutoCommit(false);
            try {
                String marker;
                try (PreparedStatement select = connection.prepareStatement(
                    "SELECT state_value FROM reward_states WHERE player_uuid=? AND state_key=?")) {
                    select.setString(1, playerId.toString());
                    select.setString(2, "reward-legacy-unmapped:" + rewardId);
                    try (ResultSet rs = select.executeQuery()) {
                        marker = rs.next() ? rs.getString("state_value") : null;
                    }
                }
                if (marker == null) {
                    throw new SQLException("No persisted legacy-unmapped condition permits whole force-resolution");
                }
                try (PreparedStatement inspected = connection.prepareStatement("""
                    SELECT 1 FROM reward_inspection_history
                    WHERE player_uuid=? AND reward_id=? AND administrator_name=?
                      AND ((administrator_uuid IS NULL AND ? IS NULL) OR administrator_uuid=?)
                    ORDER BY history_id DESC LIMIT 1
                    """)) {
                    inspected.setString(1, playerId.toString());
                    inspected.setString(2, rewardId);
                    inspected.setString(3, administratorName);
                    if (administratorId == null) inspected.setNull(4, java.sql.Types.VARCHAR);
                    else inspected.setString(4, administratorId.toString());
                    if (administratorId == null) inspected.setNull(5, java.sql.Types.VARCHAR);
                    else inspected.setString(5, administratorId.toString());
                    try (ResultSet rs = inspected.executeQuery()) {
                        if (!rs.next()) {
                            throw new SQLException("This administrator must inspect the reward before force-resolution");
                        }
                    }
                }
                try (PreparedStatement pending = connection.prepareStatement("""
                    SELECT action_id,status FROM reward_item_overflow
                    WHERE player_uuid=? AND reward_id=? AND status IN ('QUEUED','DELIVERY_PENDING')
                    """)) {
                    pending.setString(1, playerId.toString());
                    pending.setString(2, rewardId);
                    try (ResultSet rs = pending.executeQuery()) {
                        if (rs.next()) {
                            throw new SQLException("Pending item " + rs.getString("action_id")
                                + " must be delivered or reconciled first");
                        }
                    }
                }
                String oldOverall = selectOverallStatus(playerId, rewardId);
                RewardStatus next = delivered ? RewardStatus.CLAIMED : RewardStatus.DELIVERY_FAILED;
                try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM reward_states WHERE player_uuid=? AND state_key=?")) {
                    delete.setString(1, playerId.toString());
                    delete.setString(2, "reward-legacy-unmapped:" + rewardId);
                    delete.executeUpdate();
                }
                writeOverallAndClaim(playerId, rewardId, next, delivered);
                insertReconciliationHistory("whole-force", administratorName, administratorId, playerId, rewardId,
                    "whole", marker, delivered ? "force-delivered" : "force-retry",
                    oldOverall, next.name(), delivered ? "force-delivered" : "force-retry", reason, evidence);
                long revision = bumpPlayerRevision(playerId);
                connection.commit();
                return new AtomicReconcileResult(marker, delivered ? "force-delivered" : "force-retry",
                    oldOverall, next.name(), delivered, revision);
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
            || next == RewardStatus.REQUIRES_RECONCILIATION || next == RewardStatus.ITEM_QUEUED) {
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
