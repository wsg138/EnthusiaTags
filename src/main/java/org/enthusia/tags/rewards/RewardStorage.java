package org.enthusia.tags.rewards;

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

public final class RewardStorage {
    private final File databaseFile;
    private Connection connection;

    public RewardStorage(File databaseFile) {
        this.databaseFile = databaseFile;
    }

    public void init() throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());
        try (Statement statement = connection.createStatement()) {
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

    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
        }
    }

    public synchronized Set<String> loadClaims(UUID playerId) throws SQLException {
        Set<String> claims = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT reward_id FROM reward_claims WHERE player_uuid = ?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    claims.add(rs.getString("reward_id"));
                }
            }
        }
        return claims;
    }

    public synchronized boolean isClaimed(UUID playerId, String rewardId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT 1 FROM reward_claims WHERE player_uuid = ? AND reward_id = ?")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, rewardId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    public synchronized void setClaimed(UUID playerId, String rewardId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT OR IGNORE INTO reward_claims (player_uuid, reward_id, claimed_at) VALUES (?, ?, ?)")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, rewardId);
            statement.setLong(3, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    public synchronized long getCounter(UUID playerId, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT counter_value FROM reward_counters WHERE player_uuid = ? AND counter_key = ?")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, key);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("counter_value");
                }
            }
        }
        return 0L;
    }

    public synchronized void setCounter(UUID playerId, String key, long value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO reward_counters (player_uuid, counter_key, counter_value) VALUES (?, ?, ?) " +
                "ON CONFLICT(player_uuid, counter_key) DO UPDATE SET counter_value = excluded.counter_value")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, key);
            statement.setLong(3, value);
            statement.executeUpdate();
        }
    }

    public synchronized long incrementCounter(UUID playerId, String key, long delta) throws SQLException {
        long value = getCounter(playerId, key) + delta;
        setCounter(playerId, key, value);
        return value;
    }

    public synchronized String getState(UUID playerId, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT state_value FROM reward_states WHERE player_uuid = ? AND state_key = ?")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, key);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("state_value");
                }
            }
        }
        return null;
    }

    public synchronized void setState(UUID playerId, String key, String value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO reward_states (player_uuid, state_key, state_value) VALUES (?, ?, ?) " +
                "ON CONFLICT(player_uuid, state_key) DO UPDATE SET state_value = excluded.state_value")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, key);
            statement.setString(3, value);
            statement.executeUpdate();
        }
    }
}
