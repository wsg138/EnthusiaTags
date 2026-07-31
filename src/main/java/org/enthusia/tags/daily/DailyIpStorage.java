package org.enthusia.tags.daily;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

final class DailyIpStorage implements AutoCloseable {
    private final Connection connection;

    DailyIpStorage(File file) throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=NORMAL");
                statement.execute("PRAGMA busy_timeout=5000");
                statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS daily_ip_claims (
                      claim_date TEXT NOT NULL,
                      ip_address TEXT NOT NULL,
                      player_uuid TEXT NOT NULL,
                      created_at INTEGER NOT NULL,
                      PRIMARY KEY(claim_date, ip_address, player_uuid)
                    )
                    """);
                statement.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS daily_ip_claim_lookup
                    ON daily_ip_claims(claim_date, ip_address)
                    """);
                statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS daily_ip_siblings (
                      first_uuid TEXT NOT NULL,
                      second_uuid TEXT NOT NULL,
                      administrator TEXT NOT NULL,
                      created_at INTEGER NOT NULL,
                      PRIMARY KEY(first_uuid, second_uuid)
                    )
                    """);
            }
            prune(LocalDate.now().minusDays(45));
        } catch (SQLException ex) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                ex.addSuppressed(closeFailure);
            }
            throw ex;
        }
    }

    synchronized boolean reserve(UUID playerId, LocalDate date, String ipAddress) throws SQLException {
        String normalizedIp = normalizeIp(ipAddress);
        if (normalizedIp.isBlank()) {
            return true;
        }
        connection.setAutoCommit(false);
        try {
            Set<UUID> owners = owners(date, normalizedIp);
            if (owners.contains(playerId)) {
                connection.commit();
                return true;
            }
            if (!owners.isEmpty()) {
                Set<UUID> allowedGroup = siblingGroup(playerId);
                if (!allowedGroup.containsAll(owners)) {
                    connection.rollback();
                    return false;
                }
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                INSERT OR IGNORE INTO daily_ip_claims(claim_date,ip_address,player_uuid,created_at)
                VALUES(?,?,?,?)
                """)) {
                insert.setString(1, date.toString());
                insert.setString(2, normalizedIp);
                insert.setString(3, playerId.toString());
                insert.setLong(4, System.currentTimeMillis());
                insert.executeUpdate();
            }
            connection.commit();
            return true;
        } catch (SQLException ex) {
            connection.rollback();
            throw ex;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    synchronized void release(UUID playerId, LocalDate date, String ipAddress) throws SQLException {
        String normalizedIp = normalizeIp(ipAddress);
        if (normalizedIp.isBlank()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            DELETE FROM daily_ip_claims WHERE claim_date=? AND ip_address=? AND player_uuid=?
            """)) {
            statement.setString(1, date.toString());
            statement.setString(2, normalizedIp);
            statement.setString(3, playerId.toString());
            statement.executeUpdate();
        }
    }

    synchronized boolean addSibling(UUID first, UUID second, String administrator) throws SQLException {
        SiblingPair pair = SiblingPair.of(first, second);
        if (pair == null) {
            return false;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT OR IGNORE INTO daily_ip_siblings(first_uuid,second_uuid,administrator,created_at)
            VALUES(?,?,?,?)
            """)) {
            statement.setString(1, pair.first().toString());
            statement.setString(2, pair.second().toString());
            statement.setString(3, administrator);
            statement.setLong(4, System.currentTimeMillis());
            return statement.executeUpdate() == 1;
        }
    }

    synchronized boolean removeSibling(UUID first, UUID second) throws SQLException {
        SiblingPair pair = SiblingPair.of(first, second);
        if (pair == null) {
            return false;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            DELETE FROM daily_ip_siblings WHERE first_uuid=? AND second_uuid=?
            """)) {
            statement.setString(1, pair.first().toString());
            statement.setString(2, pair.second().toString());
            return statement.executeUpdate() == 1;
        }
    }

    synchronized Set<UUID> siblingGroup(UUID playerId) throws SQLException {
        Set<UUID> visited = new LinkedHashSet<>();
        ArrayDeque<UUID> queue = new ArrayDeque<>();
        visited.add(playerId);
        queue.add(playerId);
        while (!queue.isEmpty()) {
            UUID current = queue.removeFirst();
            for (UUID sibling : directSiblings(current)) {
                if (visited.add(sibling)) {
                    queue.addLast(sibling);
                }
            }
        }
        return Set.copyOf(visited);
    }

    synchronized Set<UUID> owners(LocalDate date, String ipAddress) throws SQLException {
        Set<UUID> owners = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT player_uuid FROM daily_ip_claims WHERE claim_date=? AND ip_address=?
            """)) {
            statement.setString(1, date.toString());
            statement.setString(2, normalizeIp(ipAddress));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    owners.add(UUID.fromString(result.getString("player_uuid")));
                }
            }
        }
        return Set.copyOf(owners);
    }

    private Set<UUID> directSiblings(UUID playerId) throws SQLException {
        Set<UUID> siblings = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT first_uuid,second_uuid FROM daily_ip_siblings
            WHERE first_uuid=? OR second_uuid=?
            """)) {
            String uuid = playerId.toString();
            statement.setString(1, uuid);
            statement.setString(2, uuid);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    UUID first = UUID.fromString(result.getString("first_uuid"));
                    UUID second = UUID.fromString(result.getString("second_uuid"));
                    siblings.add(playerId.equals(first) ? second : first);
                }
            }
        }
        return siblings;
    }

    private void prune(LocalDate oldestDate) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM daily_ip_claims WHERE claim_date<?")) {
            statement.setString(1, oldestDate.toString());
            statement.executeUpdate();
        }
    }

    private String normalizeIp(String ipAddress) {
        return ipAddress == null ? "" : ipAddress.trim().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }

    private record SiblingPair(UUID first, UUID second) {
        private static SiblingPair of(UUID first, UUID second) {
            if (first == null || second == null || first.equals(second)) {
                return null;
            }
            return first.toString().compareTo(second.toString()) <= 0
                ? new SiblingPair(first, second) : new SiblingPair(second, first);
        }
    }
}
