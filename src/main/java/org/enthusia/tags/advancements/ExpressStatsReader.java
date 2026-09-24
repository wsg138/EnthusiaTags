package org.enthusia.tags.advancements;

import org.enthusia.tags.advancements.domain.ExpressMilestoneProgress;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import org.sqlite.JDBC;
import java.util.Properties;
import java.util.List;
import java.util.ArrayList;
import java.util.StringJoiner;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Read-only aggregate adapter for EnthusiaExpress mail.db. */
final class ExpressStatsReader {
    private static final String SENDER_SQL = """
        SELECT sender_uuid,
               SUM(CASE WHEN type='PACKAGE' THEN 1 ELSE 0 END) AS sent_packages,
               SUM(CASE WHEN type='LETTER' THEN 1 ELSE 0 END) AS sent_letters,
               MAX(CASE WHEN type='PACKAGE' THEN packed_item_count ELSE 0 END) AS max_packed
          FROM mail
         WHERE sender_uuid IS NOT NULL
           AND (? IS NULL OR sender_uuid IN (SELECT value FROM json_each(?)))
         GROUP BY sender_uuid
        """;
    private static final String RECIPIENT_SQL = """
        SELECT recipient_uuid,
               SUM(CASE WHEN type='PACKAGE' AND status='CLAIMED' THEN 1 ELSE 0 END) AS claimed_packages,
               SUM(CASE WHEN type='LETTER' AND unread=0 THEN 1 ELSE 0 END) AS read_letters,
               SUM(CASE WHEN type='PACKAGE' AND status='RETURN_CLAIMED' THEN 1 ELSE 0 END) AS returned_claims
          FROM mail
         WHERE ? IS NULL OR recipient_uuid IN (SELECT value FROM json_each(?))
         GROUP BY recipient_uuid
        """;
    private static final String RECIPIENT_PENDING_SQL = """
        SELECT recipient_uuid,
               SUM(CASE WHEN type='PACKAGE' AND status='CLAIMED' AND delivery_pending=0 THEN 1 ELSE 0 END) AS claimed_packages,
               SUM(CASE WHEN type='LETTER' AND unread=0 THEN 1 ELSE 0 END) AS read_letters,
               SUM(CASE WHEN type='PACKAGE' AND status='RETURN_CLAIMED' AND delivery_pending=0 THEN 1 ELSE 0 END) AS returned_claims
          FROM mail
         WHERE ? IS NULL OR recipient_uuid IN (SELECT value FROM json_each(?))
         GROUP BY recipient_uuid
        """;
    private ExpressStatsReader() {
    }

    static Map<UUID, ExpressMilestoneProgress.Stats> read(Path database) throws Exception {
        return readSelected(database, null);
    }

    static Map<UUID, ExpressMilestoneProgress.Stats> read(Path database, Set<UUID> onlinePlayers) throws Exception {
        List<UUID> selected = new ArrayList<>(Set.copyOf(onlinePlayers));
        if (selected.isEmpty()) return Map.of();
        return readSelected(database, selected);
    }

    private static Map<UUID, ExpressMilestoneProgress.Stats> readSelected(Path database, List<UUID> selected) throws Exception {
        if (database == null || !Files.isRegularFile(database)) {
            throw new IllegalArgumentException("EnthusiaExpress mail.db is unavailable");
        }
        String url = "jdbc:sqlite:" + database.toUri() + "?mode=ro";
        try (Connection connection = JDBC.createConnection(url, new Properties())) {
            if (connection == null) throw new SQLException("SQLite rejected its explicit read-only URL");
            // All aggregates in this refresh share one consistent, read-only snapshot.
            connection.setAutoCommit(false);
            Set<String> columns = columns(connection);
            requireColumns(columns);
            Map<UUID, MutableStats> players = new HashMap<>();
            boolean deliveryPending = columns.contains("delivery_pending");
            if (selected == null) {
                readCounts(connection, players, deliveryPending, null);
            } else {
                for (int from = 0; from < selected.size(); from += 500) {
                    readCounts(connection, players, deliveryPending,
                        selected.subList(from, Math.min(from + 500, selected.size())));
                }
            }
            Map<UUID, ExpressMilestoneProgress.Stats> result = new HashMap<>();
            players.forEach((id, stats) -> result.put(id, stats.freeze()));
            return Map.copyOf(result);
        }
    }

    private static void readCounts(Connection connection, Map<UUID, MutableStats> players,
                                   boolean deliveryPending, List<UUID> selected) throws SQLException {
        readSenderStats(connection, players, selected);
        readRecipientStats(connection, players, deliveryPending, selected);
    }

    private static void bindSelection(PreparedStatement statement, List<UUID> selected) throws SQLException {
        String ids = selected == null ? null : uuidJson(selected);
        statement.setString(1, ids);
        statement.setString(2, ids);
    }

    private static String uuidJson(List<UUID> selected) {
        StringJoiner values = new StringJoiner(",", "[", "]");
        for (UUID id : selected) values.add("\"" + id + "\"");
        return values.toString();
    }

    private static Set<String> columns(Connection connection) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (var statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA table_info(mail)")) {
            while (result.next()) {
                columns.add(result.getString("name"));
            }
        }
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("Missing EnthusiaExpress mail table");
        }
        return Set.copyOf(columns);
    }

    private static void requireColumns(Set<String> columns) {
        for (String required : Set.of(
            "sender_uuid", "recipient_uuid", "type", "status",
            "packed_item_count", "unread"
        )) {
            if (!columns.contains(required)) {
                throw new IllegalArgumentException("Missing EnthusiaExpress column: " + required);
            }
        }
    }
    private static void readSenderStats(
        Connection connection,
        Map<UUID, MutableStats> players,
        List<UUID> selected
    ) throws SQLException {
        try (var statement = connection.prepareStatement(SENDER_SQL)) {
            bindSelection(statement, selected);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    UUID id = uuid(result.getString("sender_uuid"));
                    MutableStats stats = players.computeIfAbsent(id, ignored -> new MutableStats());
                    stats.sentPackages = safeInt(result.getLong("sent_packages"));
                    stats.sentLetters = safeInt(result.getLong("sent_letters"));
                    stats.maxPackedItems = safeInt(result.getLong("max_packed"));
                }
            }
        }
    }
    private static void readRecipientStats(
        Connection connection,
        Map<UUID, MutableStats> players,
        boolean hasDeliveryPending,
        List<UUID> selected
    ) throws SQLException {
        // recipient_uuid is the current mailbox owner. MailRepository.expire rewrites it
        // to sender_uuid before a normal return can become RETURN_CLAIMED; see the
        // pinned provider contract in docs/express-history-contract.md.
        try (var statement = hasDeliveryPending
            ? connection.prepareStatement(RECIPIENT_PENDING_SQL)
            : connection.prepareStatement(RECIPIENT_SQL)) {
            bindSelection(statement, selected);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    UUID id = uuid(result.getString("recipient_uuid"));
                    MutableStats stats = players.computeIfAbsent(id, ignored -> new MutableStats());
                    stats.claimedPackages = safeInt(result.getLong("claimed_packages"));
                    stats.readLetters = safeInt(result.getLong("read_letters"));
                    stats.returnedClaims = safeInt(result.getLong("returned_claims"));
                }
            }
        }
    }
    private static UUID uuid(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Missing player UUID in EnthusiaExpress history");
        }
        UUID id = UUID.fromString(value);
        if (!id.toString().equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("Invalid player UUID in EnthusiaExpress history");
        }
        return id;
    }

    private static int safeInt(long value) {
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Express counter outside supported range");
        }
        return (int) value;
    }

    private static final class MutableStats {
        int sentPackages;
        int sentLetters;
        int claimedPackages;
        int readLetters;
        int maxPackedItems;
        int returnedClaims;

        ExpressMilestoneProgress.Stats freeze() {
            return new ExpressMilestoneProgress.Stats(
                sentPackages, sentLetters, claimedPackages,
                readLetters, maxPackedItems, returnedClaims);
        }
    }
}
