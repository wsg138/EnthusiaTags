package org.enthusia.tags.daily;

import java.io.File;
import java.sql.*;
import java.time.LocalDate;
import java.util.UUID;

public final class DailyStorage implements AutoCloseable {
    public enum TransactionStatus { PREPARED, DEPOSITING, DELIVERED, FAILED, UNCERTAIN, RECONCILED, CANCELLED }
    public record Transaction(UUID playerId, LocalDate date, double amount, TransactionStatus status,
                              Double balanceBefore, Double balanceAfter, Double responseAmount,
                              String responseType, String failure, long createdAt, Long completedAt) { }
    public record Reconciliation(long historyId, String administrator, String decision, String reason,
                                 String oldStatus, String newStatus, long createdAt) { }
    public record Transition(long historyId, String oldStatus, String newStatus, String evidence, long createdAt) { }
    private final Connection connection;

    public DailyStorage(File file) throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS daily_state (
                  player_uuid TEXT PRIMARY KEY, last_claim_date TEXT, current_streak INTEGER NOT NULL,
                  highest_streak INTEGER NOT NULL, total_claims INTEGER NOT NULL, total_awarded REAL NOT NULL,
                  animation_enabled INTEGER NOT NULL)
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS daily_ledger (
                  player_uuid TEXT NOT NULL, claim_date TEXT NOT NULL, amount REAL NOT NULL,
                  status TEXT NOT NULL, created_at INTEGER NOT NULL, completed_at INTEGER, failure TEXT,
                  PRIMARY KEY(player_uuid, claim_date))
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS daily_reconciliation_history (
                  history_id INTEGER PRIMARY KEY AUTOINCREMENT,
                  administrator TEXT NOT NULL, player_uuid TEXT NOT NULL, claim_date TEXT NOT NULL,
                  old_status TEXT NOT NULL, new_status TEXT NOT NULL, decision TEXT NOT NULL,
                  reason TEXT NOT NULL, created_at INTEGER NOT NULL)
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS daily_transition_history (
                  history_id INTEGER PRIMARY KEY AUTOINCREMENT,
                  player_uuid TEXT NOT NULL, claim_date TEXT NOT NULL,
                  old_status TEXT, new_status TEXT NOT NULL, evidence TEXT NOT NULL,
                  created_at INTEGER NOT NULL)
                """);
        }
        ensureColumn("daily_ledger", "balance_before", "REAL");
        ensureColumn("daily_ledger", "balance_after", "REAL");
        ensureColumn("daily_ledger", "response_amount", "REAL");
        ensureColumn("daily_ledger", "response_type", "TEXT");
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE daily_ledger SET status='PREPARED' WHERE status='PENDING'");
        }
        java.util.List<java.util.Map.Entry<UUID, LocalDate>> interrupted = new java.util.ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(
            "SELECT player_uuid,claim_date FROM daily_ledger WHERE status='DEPOSITING'");
             ResultSet rs = select.executeQuery()) {
            while (rs.next()) {
                interrupted.add(java.util.Map.entry(UUID.fromString(rs.getString("player_uuid")),
                    LocalDate.parse(rs.getString("claim_date"))));
            }
        }
        connection.setAutoCommit(false);
        try (PreparedStatement update = connection.prepareStatement(
            "UPDATE daily_ledger SET status='UNCERTAIN',failure=?"
                + " WHERE player_uuid=? AND claim_date=? AND status='DEPOSITING'")) {
            for (java.util.Map.Entry<UUID, LocalDate> entry : interrupted) {
                update.setString(1, "Server restarted while Vault result was unknown");
                update.setString(2, entry.getKey().toString());
                update.setString(3, entry.getValue().toString());
                if (update.executeUpdate() == 1) {
                    insertTransition(entry.getKey(), entry.getValue(), TransactionStatus.DEPOSITING,
                        TransactionStatus.UNCERTAIN, "Server restarted while Vault result was unknown");
                }
            }
            connection.commit();
        } catch (SQLException ex) {
            connection.rollback();
            throw ex;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public synchronized DailyState load(UUID playerId, boolean animationDefault) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM daily_state WHERE player_uuid=?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return DailyState.empty(animationDefault);
                String date = rs.getString("last_claim_date");
                return new DailyState(date == null ? null : LocalDate.parse(date), rs.getInt("current_streak"),
                    rs.getInt("highest_streak"), rs.getLong("total_claims"), rs.getDouble("total_awarded"),
                    rs.getInt("animation_enabled") != 0);
            }
        }
    }

    public synchronized boolean reserve(UUID playerId, LocalDate date, double amount) throws SQLException {
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO daily_ledger(player_uuid,claim_date,amount,status,created_at)"
                    + " VALUES(?,?,?,'PREPARED',?)")) {
                statement.setString(1, playerId.toString());
                statement.setString(2, date.toString());
                statement.setDouble(3, amount);
                statement.setLong(4, System.currentTimeMillis());
                if (statement.executeUpdate() == 1) {
                    insertTransition(playerId, date, null, TransactionStatus.PREPARED,
                        "Daily claim reserved for amount=" + amount);
                    connection.commit();
                    return true;
                }
            }
            try (PreparedStatement retry = connection.prepareStatement("""
                UPDATE daily_ledger SET status='PREPARED',amount=?,created_at=?,completed_at=NULL,failure=NULL,
                  balance_before=NULL,balance_after=NULL,response_amount=NULL,response_type=NULL
                WHERE player_uuid=? AND claim_date=? AND status='FAILED'
                """)) {
                retry.setDouble(1, amount);
                retry.setLong(2, System.currentTimeMillis());
                retry.setString(3, playerId.toString());
                retry.setString(4, date.toString());
                if (retry.executeUpdate() == 1) {
                    insertTransition(playerId, date, TransactionStatus.FAILED, TransactionStatus.PREPARED,
                        "Definite failure retried for amount=" + amount);
                    connection.commit();
                    return true;
                }
            }
            connection.rollback();
            return false;
        } catch (SQLException ex) {
            connection.rollback();
            throw ex;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public synchronized void markDepositing(UUID playerId, LocalDate date, double balanceBefore) throws SQLException {
        connection.setAutoCommit(false);
        try {
            transition(playerId, date, TransactionStatus.PREPARED, TransactionStatus.DEPOSITING,
                "balance_before", balanceBefore, null);
            insertTransition(playerId, date, TransactionStatus.PREPARED, TransactionStatus.DEPOSITING,
                "Vault invocation reserved; balance-before=" + balanceBefore);
            connection.commit();
        } catch (SQLException ex) {
            connection.rollback();
            throw ex;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public synchronized void recordVaultResult(UUID playerId, LocalDate date, TransactionStatus status,
                                               double balanceAfter, double responseAmount,
                                               String responseType, String failure) throws SQLException {
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE daily_ledger SET status=?,balance_after=?,response_amount=?,response_type=?,failure=?
            WHERE player_uuid=? AND claim_date=? AND status='DEPOSITING'
            """)) {
            statement.setString(1, status == TransactionStatus.DELIVERED
                ? TransactionStatus.DEPOSITING.name() : status.name());
            statement.setDouble(2, balanceAfter);
            statement.setDouble(3, responseAmount);
            statement.setString(4, responseType);
            statement.setString(5, failure);
            statement.setString(6, playerId.toString());
            statement.setString(7, date.toString());
            if (statement.executeUpdate() != 1) throw new SQLException("Daily transaction was not DEPOSITING");
            TransactionStatus stored = status == TransactionStatus.DELIVERED
                ? TransactionStatus.DEPOSITING : status;
            insertTransition(playerId, date, TransactionStatus.DEPOSITING, stored,
                "Vault response=" + responseType + " returned=" + responseAmount
                    + " balance-after=" + balanceAfter + " failure=" + failure);
            connection.commit();
        } catch (SQLException ex) {
            connection.rollback();
            throw ex;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public synchronized void complete(UUID playerId, LocalDate date, DailyState state) throws SQLException {
        connection.setAutoCommit(false);
        try (PreparedStatement ledger = connection.prepareStatement(
            "UPDATE daily_ledger SET status='DELIVERED',completed_at=? WHERE player_uuid=? AND claim_date=?"
                + " AND status IN ('DEPOSITING','RECONCILED')");
             PreparedStatement upsert = connection.prepareStatement("""
                 INSERT INTO daily_state VALUES(?,?,?,?,?,?,?) ON CONFLICT(player_uuid) DO UPDATE SET
                 last_claim_date=excluded.last_claim_date,current_streak=excluded.current_streak,
                 highest_streak=excluded.highest_streak,total_claims=excluded.total_claims,
                 total_awarded=excluded.total_awarded,animation_enabled=excluded.animation_enabled
                 """)) {
            ledger.setLong(1, System.currentTimeMillis());
            ledger.setString(2, playerId.toString());
            ledger.setString(3, date.toString());
            if (ledger.executeUpdate() != 1) {
                throw new SQLException("Daily transaction was not ready for completion");
            }
            upsert.setString(1, playerId.toString());
            upsert.setString(2, date.toString());
            upsert.setInt(3, state.currentStreak());
            upsert.setInt(4, state.highestStreak());
            upsert.setLong(5, state.totalClaims());
            upsert.setDouble(6, state.totalAwarded());
            upsert.setInt(7, state.animationEnabled() ? 1 : 0);
            upsert.executeUpdate();
            insertTransition(playerId, date, TransactionStatus.DEPOSITING, TransactionStatus.DELIVERED,
                "Daily state advanced atomically");
            connection.commit();
        } catch (SQLException ex) {
            connection.rollback();
            throw ex;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public synchronized void completeReconciledWithoutStateChange(UUID playerId, LocalDate date)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE daily_ledger SET status='DELIVERED',completed_at=?"
                + " WHERE player_uuid=? AND claim_date=? AND status='RECONCILED'")) {
            statement.setLong(1, System.currentTimeMillis());
            statement.setString(2, playerId.toString());
            statement.setString(3, date.toString());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Daily transaction was not RECONCILED");
            }
        }
    }

    public synchronized void fail(UUID playerId, LocalDate date, String reason) throws SQLException {
        connection.setAutoCommit(false);
        try {
            Transaction current = transaction(playerId, date);
            if (current == null || (current.status() != TransactionStatus.PREPARED
                && current.status() != TransactionStatus.DEPOSITING)) {
                throw new SQLException("Daily transaction is not safely fail-able");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE daily_ledger SET status='FAILED',failure=?"
                    + " WHERE player_uuid=? AND claim_date=? AND status=?")) {
                statement.setString(1, reason);
                statement.setString(2, playerId.toString());
                statement.setString(3, date.toString());
                statement.setString(4, current.status().name());
                if (statement.executeUpdate() != 1) throw new SQLException("Daily failure transition changed");
            }
            insertTransition(playerId, date, current.status(), TransactionStatus.FAILED, reason);
            connection.commit();
        } catch (SQLException ex) {
            connection.rollback();
            throw ex;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public synchronized void saveAnimationPreference(UUID playerId, boolean enabled, boolean defaultValue)
        throws SQLException {
        DailyState current = load(playerId, defaultValue);
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO daily_state VALUES(?,?,?,?,?,?,?) ON CONFLICT(player_uuid) DO UPDATE SET
            animation_enabled=excluded.animation_enabled
            """)) {
            statement.setString(1, playerId.toString());
            if (current.lastClaimDate() == null) statement.setNull(2, Types.VARCHAR);
            else statement.setString(2, current.lastClaimDate().toString());
            statement.setInt(3, current.currentStreak());
            statement.setInt(4, current.highestStreak());
            statement.setLong(5, current.totalClaims());
            statement.setDouble(6, current.totalAwarded());
            statement.setInt(7, enabled ? 1 : 0);
            statement.executeUpdate();
        }
    }

    public synchronized Transaction transaction(UUID playerId, LocalDate date) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT * FROM daily_ledger WHERE player_uuid=? AND claim_date=?")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, date.toString());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return null;
                return new Transaction(playerId, date, rs.getDouble("amount"),
                    TransactionStatus.valueOf(rs.getString("status")), nullableDouble(rs, "balance_before"),
                    nullableDouble(rs, "balance_after"), nullableDouble(rs, "response_amount"),
                    rs.getString("response_type"), rs.getString("failure"), rs.getLong("created_at"),
                    nullableLong(rs, "completed_at"));
            }
        }
    }

    public synchronized Transaction reconcile(UUID playerId, LocalDate date, String administrator,
                                               boolean delivered, String reason) throws SQLException {
        connection.setAutoCommit(false);
        try {
            Transaction current = transaction(playerId, date);
            if (current == null) throw new SQLException("Daily transaction not found");
            if (current.status() != TransactionStatus.UNCERTAIN
                && !(delivered && current.status() == TransactionStatus.RECONCILED)) {
                throw new SQLException("Daily transaction is " + current.status() + ", not UNCERTAIN");
            }
            TransactionStatus next = delivered ? TransactionStatus.RECONCILED : TransactionStatus.FAILED;
            if (current.status() != next) {
                try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE daily_ledger SET status=?,failure=? WHERE player_uuid=? AND claim_date=? AND status=?")) {
                    update.setString(1, next.name());
                    update.setString(2, "Reconciled by " + administrator + ": " + reason);
                    update.setString(3, playerId.toString());
                    update.setString(4, date.toString());
                    update.setString(5, current.status().name());
                    if (update.executeUpdate() != 1) throw new SQLException("Concurrent daily reconciliation");
                }
                try (PreparedStatement history = connection.prepareStatement("""
                    INSERT INTO daily_reconciliation_history(
                      administrator,player_uuid,claim_date,old_status,new_status,decision,reason,created_at)
                    VALUES(?,?,?,?,?,?,?,?)
                    """)) {
                    history.setString(1, administrator);
                    history.setString(2, playerId.toString());
                    history.setString(3, date.toString());
                    history.setString(4, current.status().name());
                    history.setString(5, next.name());
                    history.setString(6, delivered ? "delivered" : "retry");
                    history.setString(7, reason);
                    history.setLong(8, System.currentTimeMillis());
                    history.executeUpdate();
                }
            }
            connection.commit();
            return transaction(playerId, date);
        } catch (SQLException ex) {
            connection.rollback();
            throw ex;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public synchronized java.util.List<Reconciliation> reconciliationHistory(UUID playerId, LocalDate date,
                                                                             int limit) throws SQLException {
        java.util.List<Reconciliation> result = new java.util.ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT history_id,administrator,decision,reason,old_status,new_status,created_at
            FROM daily_reconciliation_history WHERE player_uuid=? AND claim_date=?
            ORDER BY history_id DESC LIMIT ?
            """)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, date.toString());
            statement.setInt(3, Math.max(1, limit));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(new Reconciliation(rs.getLong("history_id"), rs.getString("administrator"),
                        rs.getString("decision"), rs.getString("reason"), rs.getString("old_status"),
                        rs.getString("new_status"), rs.getLong("created_at")));
                }
            }
        }
        return result;
    }

    public synchronized java.util.List<Transition> transitionHistory(UUID playerId, LocalDate date, int limit)
        throws SQLException {
        java.util.List<Transition> result = new java.util.ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT history_id,old_status,new_status,evidence,created_at
            FROM daily_transition_history WHERE player_uuid=? AND claim_date=?
            ORDER BY history_id DESC LIMIT ?
            """)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, date.toString());
            statement.setInt(3, Math.max(1, limit));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(new Transition(rs.getLong("history_id"), rs.getString("old_status"),
                        rs.getString("new_status"), rs.getString("evidence"), rs.getLong("created_at")));
                }
            }
        }
        return result;
    }

    public synchronized Transaction reconcileAtomic(UUID playerId, LocalDate date, String administrator,
                                                    boolean delivered, String reason, DailyState deliveredState,
                                                    boolean stateAlreadyApplied) throws SQLException {
        connection.setAutoCommit(false);
        try {
            Transaction current = transaction(playerId, date);
            if (current == null) throw new SQLException("Daily transaction not found");
            if (current.status() != TransactionStatus.UNCERTAIN
                && !(delivered && current.status() == TransactionStatus.RECONCILED)) {
                throw new SQLException("Daily transaction is " + current.status() + ", not UNCERTAIN");
            }
            TransactionStatus intermediate = delivered ? TransactionStatus.RECONCILED : TransactionStatus.FAILED;
            if (current.status() != intermediate) {
                try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE daily_ledger SET status=?,failure=? WHERE player_uuid=? AND claim_date=? AND status=?
                    """)) {
                    update.setString(1, intermediate.name());
                    update.setString(2, "Reconciled by " + administrator + ": " + reason);
                    update.setString(3, playerId.toString());
                    update.setString(4, date.toString());
                    update.setString(5, current.status().name());
                    if (update.executeUpdate() != 1) throw new SQLException("Concurrent daily reconciliation");
                }
                insertDailyReconciliation(administrator, playerId, date, current.status(), intermediate,
                    delivered ? "delivered" : "retry", reason);
                insertTransition(playerId, date, current.status(), intermediate,
                    "Administrator decision by " + administrator + ": " + reason);
            }
            if (delivered) {
                try (PreparedStatement ledger = connection.prepareStatement(
                    "UPDATE daily_ledger SET status='DELIVERED',completed_at=?"
                        + " WHERE player_uuid=? AND claim_date=? AND status='RECONCILED'")) {
                    ledger.setLong(1, System.currentTimeMillis());
                    ledger.setString(2, playerId.toString());
                    ledger.setString(3, date.toString());
                    if (ledger.executeUpdate() != 1) throw new SQLException("Daily reconciliation was not ready");
                }
                if (!stateAlreadyApplied) {
                    if (deliveredState == null) throw new SQLException("Delivered daily state is required");
                    upsertState(playerId, date, deliveredState);
                }
                insertTransition(playerId, date, TransactionStatus.RECONCILED, TransactionStatus.DELIVERED,
                    stateAlreadyApplied ? "Ledger finalized; daily state was already applied"
                        : "Ledger and daily state finalized atomically");
            }
            connection.commit();
            return transaction(playerId, date);
        } catch (SQLException ex) {
            connection.rollback();
            throw ex;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private void upsertState(UUID playerId, LocalDate date, DailyState state) throws SQLException {
        try (PreparedStatement upsert = connection.prepareStatement("""
            INSERT INTO daily_state VALUES(?,?,?,?,?,?,?) ON CONFLICT(player_uuid) DO UPDATE SET
            last_claim_date=excluded.last_claim_date,current_streak=excluded.current_streak,
            highest_streak=excluded.highest_streak,total_claims=excluded.total_claims,
            total_awarded=excluded.total_awarded,animation_enabled=excluded.animation_enabled
            """)) {
            upsert.setString(1, playerId.toString());
            upsert.setString(2, date.toString());
            upsert.setInt(3, state.currentStreak());
            upsert.setInt(4, state.highestStreak());
            upsert.setLong(5, state.totalClaims());
            upsert.setDouble(6, state.totalAwarded());
            upsert.setInt(7, state.animationEnabled() ? 1 : 0);
            upsert.executeUpdate();
        }
    }

    private void insertDailyReconciliation(String administrator, UUID playerId, LocalDate date,
                                           TransactionStatus oldStatus, TransactionStatus newStatus,
                                           String decision, String reason) throws SQLException {
        try (PreparedStatement history = connection.prepareStatement("""
            INSERT INTO daily_reconciliation_history(
              administrator,player_uuid,claim_date,old_status,new_status,decision,reason,created_at)
            VALUES(?,?,?,?,?,?,?,?)
            """)) {
            history.setString(1, administrator);
            history.setString(2, playerId.toString());
            history.setString(3, date.toString());
            history.setString(4, oldStatus.name());
            history.setString(5, newStatus.name());
            history.setString(6, decision);
            history.setString(7, reason);
            history.setLong(8, System.currentTimeMillis());
            history.executeUpdate();
        }
    }

    private void insertTransition(UUID playerId, LocalDate date, TransactionStatus oldStatus,
                                  TransactionStatus newStatus, String evidence) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO daily_transition_history(
              player_uuid,claim_date,old_status,new_status,evidence,created_at) VALUES(?,?,?,?,?,?)
            """)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, date.toString());
            if (oldStatus == null) statement.setNull(3, Types.VARCHAR);
            else statement.setString(3, oldStatus.name());
            statement.setString(4, newStatus.name());
            statement.setString(5, evidence == null ? "" : evidence);
            statement.setLong(6, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    private void transition(UUID playerId, LocalDate date, TransactionStatus from, TransactionStatus to,
                            String column, double value, String failure) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE daily_ledger SET status=?,"
            + column + "=?,failure=? WHERE player_uuid=? AND claim_date=? AND status=?")) {
            statement.setString(1, to.name());
            statement.setDouble(2, value);
            statement.setString(3, failure);
            statement.setString(4, playerId.toString());
            statement.setString(5, date.toString());
            statement.setString(6, from.name());
            if (statement.executeUpdate() != 1) throw new SQLException("Invalid daily transition " + from + " -> " + to);
        }
    }

    private Double nullableDouble(ResultSet rs, String name) throws SQLException {
        double value = rs.getDouble(name);
        return rs.wasNull() ? null : value;
    }

    private Long nullableLong(ResultSet rs, String name) throws SQLException {
        long value = rs.getLong(name);
        return rs.wasNull() ? null : value;
    }

    private void ensureColumn(String table, String column, String type) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            try {
                statement.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
            } catch (SQLException ex) {
                if (!ex.getMessage().toLowerCase(java.util.Locale.ROOT).contains("duplicate column")) throw ex;
            }
        }
    }

    @Override public void close() throws SQLException { connection.close(); }
}
