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

public final class CosmeticsStorage {
    private final File databaseFile;
    private Connection connection;

    public CosmeticsStorage(File databaseFile) {
        this.databaseFile = databaseFile;
    }

    public void init() throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());
        try (Statement statement = connection.createStatement()) {
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

    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
        }
    }

    public synchronized Map<String, String> loadSelections(UUID playerId) throws SQLException {
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

    public synchronized void setSelection(UUID playerId, String category, String cosmeticId) throws SQLException {
        if (cosmeticId == null) {
            try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM cosmetics_selected WHERE player_uuid = ? AND category = ?")) {
                statement.setString(1, playerId.toString());
                statement.setString(2, category);
                statement.executeUpdate();
            }
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO cosmetics_selected (player_uuid, category, cosmetic_id) VALUES (?, ?, ?) " +
                "ON CONFLICT(player_uuid, category) DO UPDATE SET cosmetic_id = excluded.cosmetic_id")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, category);
            statement.setString(3, cosmeticId);
            statement.executeUpdate();
        }
    }
}
