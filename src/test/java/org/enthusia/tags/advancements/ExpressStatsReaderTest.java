package org.enthusia.tags.advancements;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ExpressStatsReaderTest {
    @TempDir Path directory;
    private final UUID sender = UUID.randomUUID();
    private final UUID recipient = UUID.randomUUID();

    @Test void readsHistoricalMailAggregatesWithoutWritingDatabase() throws Exception {
        Path database = directory.resolve("mail.db");
        createCurrentSchema(database);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            insert(connection, sender, recipient, "PACKAGE", "CLAIMED", 64, 0, 0);
            insert(connection, sender, recipient, "PACKAGE", "UNCLAIMED", 10, 1, 0);
            insert(connection, sender, recipient, "LETTER", "UNCLAIMED", 0, 0, 0);
            insert(connection, sender, recipient, "PACKAGE", "CLAIMED", 32, 0, 1);
            insert(connection, sender, sender, "PACKAGE", "RETURN_CLAIMED", 5, 0, 0);
        }

        long modified = Files.getLastModifiedTime(database).toMillis();
        var result = ExpressStatsReader.read(database);
        var senderStats = result.get(sender);
        assertEquals(1, senderStats.sentPackages());
        assertEquals(1, senderStats.sentLetters());
        assertEquals(64, senderStats.maxPackedItems());
        assertEquals(1, senderStats.returnedClaims());

        var recipientStats = result.get(recipient);
        assertEquals(1, recipientStats.claimedPackages());
        assertEquals(1, recipientStats.readLetters());
        assertEquals(0, recipientStats.returnedClaims());

        assertEquals(modified, Files.getLastModifiedTime(database).toMillis());
        assertThrows(UnsupportedOperationException.class, result::clear);
    }

    @Test void legacySchemaWithoutDeliveryPendingStillReadsClaimHistory() throws Exception {
        Path database = directory.resolve("legacy.db");
        createLegacySchema(database);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            insertLegacy(connection, sender, recipient, "PACKAGE", "CLAIMED", 12, 0);
        }

        assertEquals(1, ExpressStatsReader.read(database).get(recipient).claimedPackages());
    }

    @Test void returnLifecycleCreditsTheOriginalSenderOnlyAfterDelivery() throws Exception {
        Path database = directory.resolve("return-lifecycle.db");
        createCurrentSchema(database);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            insert(connection, sender, recipient, "PACKAGE", "UNCLAIMED", 8, 1, 0);
            // MailRepository.expire rewrites recipient_uuid to the original sender on return.
            assertEquals(1, statement.executeUpdate(
                "UPDATE mail SET recipient_uuid=COALESCE(sender_uuid,recipient_uuid), status='RETURNED'"
                    + " WHERE type='PACKAGE' AND status='UNCLAIMED'"));
            assertEquals(0, ExpressStatsReader.read(database).get(sender).returnedClaims());
            assertEquals(1, statement.executeUpdate(
                "UPDATE mail SET status='RETURN_CLAIMED', unread=0, delivery_pending=1 WHERE status='RETURNED'"));
            assertEquals(0, ExpressStatsReader.read(database).get(sender).returnedClaims());
            assertEquals(1, statement.executeUpdate(
                "UPDATE mail SET delivery_pending=0 WHERE status='RETURN_CLAIMED'"));
        }
        byte[] before = Files.readAllBytes(database);
        var completed = ExpressStatsReader.read(database);
        assertEquals(1, completed.get(sender).returnedClaims());
        assertEquals(0, completed.get(sender).claimedPackages());
        assertNull(completed.get(recipient), "Original recipient is no longer the owner of the return row");
        assertArrayEquals(before, Files.readAllBytes(database), "Reading evidence must not mutate mail.db");
    }

    @Test void legacyReturnLifecycleAlsoCreditsTheCurrentOwner() throws Exception {
        Path database = directory.resolve("legacy-return.db");
        createLegacySchema(database);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            insertLegacy(connection, sender, recipient, "PACKAGE", "UNCLAIMED", 12, 1);
            statement.executeUpdate("UPDATE mail SET recipient_uuid=sender_uuid, status='RETURNED' WHERE status='UNCLAIMED'");
            assertEquals(0, ExpressStatsReader.read(database).get(sender).returnedClaims());
            statement.executeUpdate("UPDATE mail SET status='RETURN_CLAIMED', unread=0 WHERE status='RETURNED'");
        }
        var completed = ExpressStatsReader.read(database);
        assertEquals(1, completed.get(sender).returnedClaims());
        assertNull(completed.get(recipient));
    }

    @Test void missingOrInvalidDatabaseFailsClosed() throws Exception {
        assertThrows(Exception.class,
            () -> ExpressStatsReader.read(directory.resolve("missing.db")));

        Path invalid = directory.resolve("invalid.db");
        Files.writeString(invalid, "not sqlite");
        assertThrows(Exception.class, () -> ExpressStatsReader.read(invalid));
    }
    @Test void repeatedExchangesCountOnlyDistinctPeoplePerMailType() throws Exception {
        Path database = directory.resolve("unique.db");
        createCurrentSchema(database);
        UUID third = UUID.randomUUID();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            for (int index = 0; index < 50; index++) {
                insert(connection, sender, recipient, "PACKAGE", "CLAIMED", 8, 0, 0);
                insert(connection, recipient, sender, "PACKAGE", "CLAIMED", 8, 0, 0);
                insert(connection, sender, recipient, "LETTER", "UNCLAIMED", 0, 0, 0);
                insert(connection, recipient, sender, "LETTER", "UNCLAIMED", 0, 0, 0);
            }
            insert(connection, sender, third, "PACKAGE", "UNCLAIMED", 64, 1, 0);
            insert(connection, third, sender, "PACKAGE", "CLAIMED", 4, 0, 1);
            insert(connection, third, sender, "LETTER", "UNCLAIMED", 0, 1, 0);
        }
        byte[] before = Files.readAllBytes(database);
        var stats = ExpressStatsReader.read(database, java.util.Set.of(sender)).get(sender);
        assertEquals(2, stats.sentPackages());
        assertEquals(1, stats.sentLetters());
        assertEquals(1, stats.claimedPackages(), "Pending delivery is not received evidence");
        assertEquals(1, stats.readLetters(), "Unread letter is not read evidence");
        assertEquals(64, stats.maxPackedItems());
        assertArrayEquals(before, Files.readAllBytes(database));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE mail SET delivery_pending=0, unread=0");
        }
        var refreshed = ExpressStatsReader.read(database).get(sender);
        assertEquals(2, refreshed.claimedPackages());
        assertEquals(2, refreshed.readLetters());
    }

    @Test void selfMailAndSenderlessMailDoNotEarnNormalMilestones() throws Exception {
        Path database = directory.resolve("self.db");
        createCurrentSchema(database);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            insert(connection, sender, sender, "PACKAGE", "CLAIMED", 128, 0, 0);
            insert(connection, sender, sender, "LETTER", "UNCLAIMED", 0, 0, 0);
            statement.executeUpdate("INSERT INTO mail VALUES(NULL,'" + sender
                + "','PACKAGE','CLAIMED',128,0,0)");
            statement.executeUpdate("INSERT INTO mail VALUES(NULL,'" + sender
                + "','LETTER','UNCLAIMED',0,0,0)");
            insert(connection, sender, sender, "PACKAGE", "RETURN_CLAIMED", 128, 0, 0);
        }
        var stats = ExpressStatsReader.read(database).get(sender);
        assertEquals(0, stats.sentPackages());
        assertEquals(0, stats.sentLetters());
        assertEquals(0, stats.claimedPackages());
        assertEquals(0, stats.readLetters());
        assertEquals(0, stats.maxPackedItems());
        assertEquals(1, stats.returnedClaims(), "Actual returns keep their separate recovery milestone");
    }

    @Test void legacyHistoryAlsoDeduplicatesExchanges() throws Exception {
        Path database = directory.resolve("legacy-unique.db");
        createLegacySchema(database);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            for (int index = 0; index < 10; index++) {
                insertLegacy(connection, sender, recipient, "PACKAGE", "CLAIMED", 12, 0);
                insertLegacy(connection, sender, recipient, "LETTER", "UNCLAIMED", 0, 0);
            }
        }
        var stats = ExpressStatsReader.read(database);
        assertEquals(1, stats.get(sender).sentPackages());
        assertEquals(1, stats.get(sender).sentLetters());
        assertEquals(1, stats.get(recipient).claimedPackages());
        assertEquals(1, stats.get(recipient).readLetters());
    }

    private void createCurrentSchema(Path database) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE mail (
                  sender_uuid TEXT,
                  recipient_uuid TEXT NOT NULL,
                  type TEXT NOT NULL,
                  status TEXT NOT NULL,
                  packed_item_count INTEGER NOT NULL DEFAULT 0,
                  unread INTEGER NOT NULL DEFAULT 1,
                  delivery_pending INTEGER NOT NULL DEFAULT 0
                )
                """);
        }
    }

    private void createLegacySchema(Path database) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE mail (
                  sender_uuid TEXT,
                  recipient_uuid TEXT NOT NULL,
                  type TEXT NOT NULL,
                  status TEXT NOT NULL,
                  packed_item_count INTEGER NOT NULL DEFAULT 0,
                  unread INTEGER NOT NULL DEFAULT 1
                )
                """);
        }
    }
    private void insert(
        java.sql.Connection connection,
        UUID senderId,
        UUID recipientId,
        String type,
        String status,
        int packed,
        int unread,
        int pending
    ) throws Exception {
        try (var statement = connection.prepareStatement(
            "INSERT INTO mail(sender_uuid,recipient_uuid,type,status,packed_item_count,unread,delivery_pending) VALUES(?,?,?,?,?,?,?)"
        )) {
            statement.setString(1, senderId.toString());
            statement.setString(2, recipientId.toString());
            statement.setString(3, type);
            statement.setString(4, status);
            statement.setInt(5, packed);
            statement.setInt(6, unread);
            statement.setInt(7, pending);
            statement.executeUpdate();
        }
    }
    private void insertLegacy(
        java.sql.Connection connection,
        UUID senderId,
        UUID recipientId,
        String type,
        String status,
        int packed,
        int unread
    ) throws Exception {
        try (var statement = connection.prepareStatement(
            "INSERT INTO mail(sender_uuid,recipient_uuid,type,status,packed_item_count,unread) VALUES(?,?,?,?,?,?)"
        )) {
            statement.setString(1, senderId.toString());
            statement.setString(2, recipientId.toString());
            statement.setString(3, type);
            statement.setString(4, status);
            statement.setInt(5, packed);
            statement.setInt(6, unread);
            statement.executeUpdate();
        }
    }
}
