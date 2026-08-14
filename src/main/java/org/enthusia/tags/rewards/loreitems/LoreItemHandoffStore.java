package org.enthusia.tags.rewards.loreitems;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

// This store owns one SQLite connection. Serializing each complete JDBC operation on the
// store instance is intentional so result sets and transaction-visible state never overlap.
@SuppressWarnings("PMD.AvoidSynchronizedAtMethodLevel")
public final class LoreItemHandoffStore implements AutoCloseable {
    private static final String PARAM_PLAYER_ID = "playerId";
    private static final String PARAM_REWARD_ID = "rewardId";
    private static final String PARAM_ACTION_ID = "actionId";
    private static final String SELECT_PREFIX = "SELECT ";
    private static final String FROM_HANDOFFS = " FROM lore_item_handoffs ";
    private static final int EXPECTED_SINGLE_ROW = 1;
    private static final String SELECT_COLUMNS = """
        player_uuid, reward_id, action_id, definition_key, external_operation_id,
        state, last_outcome, attempts, last_error, next_attempt_at, reward_finalized,
        created_at, updated_at
        """;

    private final Connection connection;

    public LoreItemHandoffStore(Path databasePath) throws SQLException {
        Objects.requireNonNull(databasePath, "databasePath");
        connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath());
        configure();
        createSchema();
    }

    public synchronized LoreItemHandoffRecord prepare(
        UUID playerId,
        String rewardId,
        String actionId,
        String definitionKey,
        long nowEpochMillis) throws SQLException {
        Objects.requireNonNull(playerId, PARAM_PLAYER_ID);
        String reward = canonicalId(rewardId, PARAM_REWARD_ID);
        String action = canonicalId(actionId, PARAM_ACTION_ID);
        String definition = requiredText(definitionKey, "definitionKey");
        String operationId = LoreItemOperationKey.forRewardAction(playerId, reward, action);

        LoreItemHandoffRecord existing = load(playerId, reward, action);
        if (existing != null) {
            verifyIdentity(existing, definition, operationId);
            return existing;
        }

        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO lore_item_handoffs (
                player_uuid, reward_id, action_id, definition_key, external_operation_id,
                state, last_outcome, attempts, last_error, next_attempt_at,
                reward_finalized, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, '', 0, '', 0, 0, ?, ?)
            """)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, reward);
            statement.setString(3, action);
            statement.setString(4, definition);
            statement.setString(5, operationId);
            statement.setString(6, LoreItemHandoffState.PENDING.name());
            statement.setLong(7, nowEpochMillis);
            statement.setLong(8, nowEpochMillis);
            statement.executeUpdate();
        }
        LoreItemHandoffRecord inserted = load(playerId, reward, action);
        if (inserted == null) {
            throw new SQLException("Lore-item handoff disappeared after insert");
        }
        return inserted;
    }

    public synchronized LoreItemHandoffRecord recordOutcome(
        String externalOperationId,
        LoreItemHandoffState state,
        String outcome,
        String error,
        long nextAttemptAtEpochMillis,
        long nowEpochMillis) throws SQLException {
        String operationId = requiredText(externalOperationId, "externalOperationId");
        Objects.requireNonNull(state, "state");
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE lore_item_handoffs
               SET state = ?, last_outcome = ?, attempts = attempts + 1, last_error = ?,
                   next_attempt_at = ?, updated_at = ?
             WHERE external_operation_id = ?
            """)) {
            statement.setString(1, state.name());
            statement.setString(2, outcome == null ? "" : outcome);
            statement.setString(3, error == null ? "" : error);
            statement.setLong(4, nextAttemptAtEpochMillis);
            statement.setLong(5, nowEpochMillis);
            statement.setString(6, operationId);
            if (statement.executeUpdate() != EXPECTED_SINGLE_ROW) {
                throw new SQLException("Lore-item handoff operation was not found: " + operationId);
            }
        }
        LoreItemHandoffRecord updated = loadByOperationId(operationId);
        if (updated == null) {
            throw new SQLException("Lore-item handoff disappeared after outcome update");
        }
        return updated;
    }

    public synchronized LoreItemHandoffRecord markReview(
        String externalOperationId,
        String outcome,
        String detail,
        long nowEpochMillis) throws SQLException {
        String operationId = requiredText(externalOperationId, "externalOperationId");
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE lore_item_handoffs
               SET state = 'REVIEW', last_outcome = ?, last_error = ?, next_attempt_at = 0, updated_at = ?
             WHERE external_operation_id = ? AND reward_finalized = 0
            """)) {
            statement.setString(1, outcome == null ? "" : outcome);
            statement.setString(2, detail == null ? "" : detail);
            statement.setLong(3, nowEpochMillis);
            statement.setString(4, operationId);
            if (statement.executeUpdate() != EXPECTED_SINGLE_ROW) {
                throw new SQLException("Lore-item handoff could not be moved to review: " + operationId);
            }
        }
        LoreItemHandoffRecord updated = loadByOperationId(operationId);
        if (updated == null) {
            throw new SQLException("Lore-item handoff disappeared after review transition");
        }
        return updated;
    }

    public synchronized LoreItemHandoffRecord requestRetry(
        UUID playerId,
        String rewardId,
        String actionId,
        long nowEpochMillis) throws SQLException {
        LoreItemHandoffRecord existing = load(playerId, rewardId, actionId);
        if (existing == null || existing.state() == LoreItemHandoffState.ACCEPTED) {
            return existing;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE lore_item_handoffs
               SET state = ?, last_outcome = 'STAFF_RETRY_REQUESTED', last_error = '',
                   next_attempt_at = ?, reward_finalized = 0, updated_at = ?
             WHERE external_operation_id = ?
            """)) {
            statement.setString(1, LoreItemHandoffState.RETRY.name());
            statement.setLong(2, nowEpochMillis);
            statement.setLong(3, nowEpochMillis);
            statement.setString(4, existing.externalOperationId());
            if (statement.executeUpdate() != EXPECTED_SINGLE_ROW) {
                throw new SQLException("Lore-item handoff disappeared while requesting retry");
            }
        }
        LoreItemHandoffRecord updated = loadByOperationId(existing.externalOperationId());
        if (updated == null) {
            throw new SQLException("Lore-item handoff disappeared after retry request");
        }
        return updated;
    }

    public synchronized LoreItemHandoffRecord load(UUID playerId, String rewardId, String actionId)
        throws SQLException {
        Objects.requireNonNull(playerId, PARAM_PLAYER_ID);
        String reward = canonicalId(rewardId, PARAM_REWARD_ID);
        String action = canonicalId(actionId, PARAM_ACTION_ID);
        try (PreparedStatement statement = connection.prepareStatement(
            SELECT_PREFIX + SELECT_COLUMNS + FROM_HANDOFFS
                + "WHERE player_uuid = ? AND reward_id = ? AND action_id = ?")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, reward);
            statement.setString(3, action);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? read(result) : null;
            }
        }
    }

    public synchronized LoreItemHandoffRecord loadByOperationId(String externalOperationId) throws SQLException {
        String operationId = requiredText(externalOperationId, "externalOperationId");
        try (PreparedStatement statement = connection.prepareStatement(
            SELECT_PREFIX + SELECT_COLUMNS + FROM_HANDOFFS + "WHERE external_operation_id = ?")) {
            statement.setString(1, operationId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? read(result) : null;
            }
        }
    }

    public synchronized List<LoreItemHandoffRecord> listDue(long nowEpochMillis, int limit) throws SQLException {
        if (limit <= 0) {
            return List.of();
        }
        List<LoreItemHandoffRecord> records = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
            SELECT_PREFIX + SELECT_COLUMNS + FROM_HANDOFFS
                + "WHERE state IN ('PENDING', 'RETRY') AND next_attempt_at <= ? "
                + "ORDER BY next_attempt_at ASC, created_at ASC LIMIT ?")) {
            statement.setLong(1, nowEpochMillis);
            statement.setInt(2, limit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    records.add(read(result));
                }
            }
        }
        return List.copyOf(records);
    }

    public synchronized List<LoreItemHandoffRecord> listAcceptedPendingFinalization(int limit) throws SQLException {
        if (limit <= 0) {
            return List.of();
        }
        List<LoreItemHandoffRecord> records = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
            SELECT_PREFIX + SELECT_COLUMNS + FROM_HANDOFFS
                + "WHERE state = 'ACCEPTED' AND reward_finalized = 0 "
                + "ORDER BY updated_at ASC, created_at ASC LIMIT ?")) {
            statement.setInt(1, limit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    records.add(read(result));
                }
            }
        }
        return List.copyOf(records);
    }

    public synchronized void markRewardFinalized(
        String externalOperationId,
        long nowEpochMillis) throws SQLException {
        String operationId = requiredText(externalOperationId, "externalOperationId");
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE lore_item_handoffs
               SET reward_finalized = 1, updated_at = ?
             WHERE external_operation_id = ? AND state = 'ACCEPTED' AND reward_finalized = 0
            """)) {
            statement.setLong(1, nowEpochMillis);
            statement.setString(2, operationId);
            int updated = statement.executeUpdate();
            if (updated == EXPECTED_SINGLE_ROW) {
                return;
            }
        }
        LoreItemHandoffRecord existing = loadByOperationId(operationId);
        if (existing == null) {
            throw new SQLException("Lore-item handoff operation was not found: " + operationId);
        }
        if (existing.state() != LoreItemHandoffState.ACCEPTED) {
            throw new SQLException("Lore-item handoff is not accepted: " + operationId);
        }
        if (!existing.rewardFinalized()) {
            throw new SQLException("Lore-item handoff finalization marker was not persisted: " + operationId);
        }
    }

    public synchronized List<LoreItemHandoffRecord> listForReward(UUID playerId, String rewardId)
        throws SQLException {
        Objects.requireNonNull(playerId, PARAM_PLAYER_ID);
        String reward = canonicalId(rewardId, PARAM_REWARD_ID);
        List<LoreItemHandoffRecord> records = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
            SELECT_PREFIX + SELECT_COLUMNS + FROM_HANDOFFS
                + "WHERE player_uuid = ? AND reward_id = ? ORDER BY action_id ASC")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, reward);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    records.add(read(result));
                }
            }
        }
        return List.copyOf(records);
    }

    private void configure() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = FULL");
        }
    }

    private void createSchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE IF NOT EXISTS lore_item_handoffs (
                    player_uuid TEXT NOT NULL,
                    reward_id TEXT NOT NULL,
                    action_id TEXT NOT NULL,
                    definition_key TEXT NOT NULL,
                    external_operation_id TEXT NOT NULL UNIQUE,
                    state TEXT NOT NULL,
                    last_outcome TEXT NOT NULL DEFAULT '',
                    attempts INTEGER NOT NULL DEFAULT 0,
                    last_error TEXT NOT NULL DEFAULT '',
                    next_attempt_at INTEGER NOT NULL DEFAULT 0,
                    reward_finalized INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY (player_uuid, reward_id, action_id)
                )
                """);
        }
        ensureRewardFinalizedColumn();
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_lore_item_handoffs_due
                    ON lore_item_handoffs (state, next_attempt_at)
                """);
            statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_lore_item_handoffs_finalize
                    ON lore_item_handoffs (state, reward_finalized, updated_at)
                """);
        }
    }

    private void ensureRewardFinalizedColumn() throws SQLException {
        boolean present = false;
        try (Statement statement = connection.createStatement();
             ResultSet columns = statement.executeQuery("PRAGMA table_info(lore_item_handoffs)")) {
            while (columns.next()) {
                if ("reward_finalized".equalsIgnoreCase(columns.getString("name"))) {
                    present = true;
                    break;
                }
            }
        }
        if (!present) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                    ALTER TABLE lore_item_handoffs
                    ADD COLUMN reward_finalized INTEGER NOT NULL DEFAULT 0
                    """);
            }
        }
    }

    private static LoreItemHandoffRecord read(ResultSet result) throws SQLException {
        return new LoreItemHandoffRecord(
            UUID.fromString(result.getString("player_uuid")),
            result.getString("reward_id"),
            result.getString("action_id"),
            result.getString("definition_key"),
            result.getString("external_operation_id"),
            LoreItemHandoffState.valueOf(result.getString("state")),
            result.getString("last_outcome"),
            result.getInt("attempts"),
            result.getString("last_error"),
            result.getLong("next_attempt_at"),
            result.getInt("reward_finalized") != 0,
            result.getLong("created_at"),
            result.getLong("updated_at"));
    }

    private static void verifyIdentity(
        LoreItemHandoffRecord existing,
        String definitionKey,
        String externalOperationId) throws SQLException {
        if (!existing.definitionKey().equals(definitionKey)) {
            throw new SQLException("Lore-item definition changed for an existing reward action; staff review is required");
        }
        if (!existing.externalOperationId().equals(externalOperationId)) {
            throw new SQLException("Lore-item external operation identity changed for an existing reward action");
        }
    }

    private static String canonicalId(String value, String name) {
        return requiredText(value, name).toLowerCase(Locale.ROOT);
    }

    private static String requiredText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
