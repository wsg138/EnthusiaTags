package org.enthusia.tags.advancements;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ExpressBridgeTest {
    @TempDir Path directory;
    private final UUID player = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    @Test void historyIsSilentThenNewMailCelebrates() throws Exception {
        Path database = directory.resolve("mail.db");
        createSchema(database);
        insert(database, player, other, "PACKAGE", "UNCLAIMED", 1, 1, 0);

        var bridge = new ExpressAdvancementBridge(database);
        bridge.beginSession(player);
        bridge.refresh();
        var historical = bridge.observe(player);
        assertEquals(1000, historical.progress().get("express/first_class"));
        assertTrue(historical.celebrate().isEmpty());

        for (int i = 0; i < 9; i++) {
            insert(database, player, other, "PACKAGE", "UNCLAIMED", 1, 1, 0);
        }
        bridge.refresh();
        assertEquals(
            java.util.Set.of("express/frequent_shipper"),
            bridge.observe(player).celebrate());
    }
    @Test void idleBridgeDoesNotOpenTheProviderDatabase() {
        var bridge = new ExpressAdvancementBridge(directory.resolve("missing.db"));
        assertDoesNotThrow(bridge::refresh);
        bridge.beginSession(player);
        assertThrows(Exception.class, bridge::refresh);
        bridge.forget(player);
        assertDoesNotThrow(bridge::refresh);
    }

    @Test void nodesAreBranchedAndRewardless() {
        var nodes = ExpressAdvancementBridge.nodes(42);
        assertEquals(11, nodes.size());
        var byKey = nodes.stream().collect(
            java.util.stream.Collectors.toMap(node -> node.key(), node -> node));

        assertEquals("First Class", byKey.get("express/first_class").title());
        assertEquals("express/first_class",
            byKey.get("express/frequent_shipper").parentKey());
        assertEquals("express/frequent_shipper",
            byKey.get("express/postal_legend").parentKey());
        assertEquals("express/youve_got_mail",
            byKey.get("express/parcel_collector").parentKey());
        assertEquals("express/youve_got_mail",
            byKey.get("express/return_to_sender").parentKey());
        assertEquals("express/pen_pal",
            byKey.get("express/correspondent").parentKey());
        assertEquals("express/read_all_about_it",
            byKey.get("express/avid_reader").parentKey());

        for (var node : nodes) {
            assertTrue(node.description().stream().anyMatch(s -> s.contains("Requirements:")));
            assertTrue(node.description().stream().anyMatch(s -> s.contains("Rewards: None")));
        }
    }

    @Test void nativeWiringIsOptionalAsyncAndSoftDependent() throws Exception {
        String config = Files.readString(Path.of("src/main/resources/config.yml"));
        String plugin = Files.readString(Path.of("src/main/resources/plugin.yml"));
        String source = Files.readString(Path.of(
            "src/main/java/org/enthusia/tags/advancements/NativeAdvancementController.java"));
        assertTrue(config.contains("express-enabled: true"));
        assertTrue(plugin.contains("- EnthusiaExpress"));
        assertTrue(source.contains("getPlugin(\"EnthusiaExpress\")"));
        assertTrue(source.contains("ExpressAdvancementBridge"));
        assertTrue(source.contains("express.refresh()"));
        assertTrue(source.contains("express.observe(player.getUniqueId())"));
        assertTrue(source.contains("express.forget(id)"));
        assertTrue(source.contains("expressTask.cancel()"));
    }

    private void createSchema(Path database) throws Exception {
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
    private void insert(
        Path database,
        UUID sender,
        UUID recipient,
        String type,
        String status,
        int packed,
        int unread,
        int pending
    ) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.prepareStatement(
                 "INSERT INTO mail(sender_uuid,recipient_uuid,type,status,packed_item_count,unread,delivery_pending) VALUES(?,?,?,?,?,?,?)"
             )) {
            statement.setString(1, sender.toString());
            statement.setString(2, recipient.toString());
            statement.setString(3, type);
            statement.setString(4, status);
            statement.setInt(5, packed);
            statement.setInt(6, unread);
            statement.setInt(7, pending);
            statement.executeUpdate();
        }
    }
}
