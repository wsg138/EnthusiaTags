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

public final class TagStorage {
    private final File databaseFile;
    private Connection connection;

    public TagStorage(File databaseFile) {
        this.databaseFile = databaseFile;
    }

    public void init() throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());
        try (Statement statement = connection.createStatement()) {
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

    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
        }
    }

    public synchronized Set<String> loadOwnedTags(UUID playerId) throws SQLException {
        Set<String> tags = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT tag_id FROM player_tags WHERE player_uuid = ?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    tags.add(rs.getString("tag_id"));
                }
            }
        }
        return tags;
    }

    public synchronized String loadSelectedTag(UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT selected_tag FROM player_selected WHERE player_uuid = ?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("selected_tag");
                }
            }
        }
        return null;
    }

    public synchronized void grantTag(UUID playerId, String tagId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT OR IGNORE INTO player_tags (player_uuid, tag_id) VALUES (?, ?)")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, tagId);
            statement.executeUpdate();
        }
    }

    public synchronized void revokeTag(UUID playerId, String tagId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM player_tags WHERE player_uuid = ? AND tag_id = ?")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, tagId);
            statement.executeUpdate();
        }
    }

    public synchronized void setSelectedTag(UUID playerId, String tagId) throws SQLException {
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
    }
}
