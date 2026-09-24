package org.enthusia.tags.advancements;

import org.enthusia.tags.advancements.domain.ReputationMilestoneProgress.Stats;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CommendBridgeTest {
    @TempDir Path directory;
    private final UUID player = UUID.randomUUID();

    private void save(Path file, Stats stats) throws Exception {
        Files.writeString(file,
            "advancementEvidence:\n  " + player + ":\n" +
            "    positiveReceived: " + stats.positiveReceived() + "\n" +
            "    maxOverall: " + stats.maxOverall() + "\n" +
            "    minOverall: " + stats.minOverall() + "\n" +
            "    recoveredFromSevere: " + stats.recoveredFromSevere() + "\n" +
            "    categoryMax:\n" +
            "      WAS_KIND: " + stats.kindMax() + "\n" +
            "      GAVE_ITEMS: " + stats.generousMax() + "\n" +
            "      TRUSTWORTHY: " + stats.trustworthyMax() + "\n" +
            "      GOOD_STALL: " + stats.goodStallMax() + "\n");
    }
    @Test void historicalLiveFailureAndReconnectLifecycle() throws Exception {
        Path file = directory.resolve("data.yml");
        save(file, new Stats(true, 10, -5, false, 5, 0, 0, 0));

        var bridge = new CommendAdvancementBridge(file);
        bridge.beginSession(player);
        bridge.refresh();

        var historical = bridge.observe(player);
        assertEquals(1000, historical.progress().get("reputation/a_good_word"));
        assertEquals(1000, historical.progress().get("reputation/well_regarded"));
        assertTrue(historical.celebrate().isEmpty());

        save(file, new Stats(true, 20, -10, false, 5, 5, 0, 0));
        bridge.refresh();
        var live = bridge.observe(player);
        assertTrue(live.celebrate().contains("reputation/pillar_of_the_community"));
        assertTrue(live.celebrate().contains("reputation/bad_reputation"));
        assertTrue(live.celebrate().contains("reputation/generous_spirit"));

        Files.writeString(file, "advancementEvidence: [");
        assertThrows(Exception.class, bridge::refresh);
        assertEquals(live.progress(), bridge.observe(player).progress());

        bridge.forget(player);
        bridge.beginSession(player);
        assertTrue(bridge.observe(player).progress().isEmpty());
        save(file, new Stats(true, 20, -25, true, 5, 5, 5, 5));
        bridge.refresh();
        var reconnect = bridge.observe(player);
        assertEquals(1000, reconnect.progress().get("reputation/public_enemy"));
        assertEquals(1000, reconnect.progress().get("reputation/redemption_arc"));
        assertTrue(reconnect.celebrate().isEmpty());
    }
    @Test void nodesUseRecommendedBranchesAndNoRewards() {
        var nodes = CommendAdvancementBridge.nodes(35);
        assertEquals(10, nodes.size());
        var byKey = nodes.stream().collect(
            java.util.stream.Collectors.toMap(node -> node.key(), node -> node));

        assertEquals("A Good Word", byKey.get("reputation/a_good_word").title());
        assertEquals(35, byKey.get("reputation/a_good_word").y());

        assertEquals("reputation/a_good_word",
            byKey.get("reputation/well_regarded").parentKey());
        assertEquals("reputation/well_regarded",
            byKey.get("reputation/pillar_of_the_community").parentKey());
        assertEquals("reputation/a_good_word",
            byKey.get("reputation/kind_soul").parentKey());
        assertEquals("reputation/a_good_word",
            byKey.get("reputation/generous_spirit").parentKey());
        assertEquals("reputation/a_good_word",
            byKey.get("reputation/trusted_name").parentKey());
        assertEquals("reputation/a_good_word",
            byKey.get("reputation/merchant_of_merit").parentKey());

        assertNull(byKey.get("reputation/bad_reputation").parentKey());
        assertEquals("reputation/bad_reputation",
            byKey.get("reputation/public_enemy").parentKey());
        assertEquals("reputation/bad_reputation",
            byKey.get("reputation/redemption_arc").parentKey());

        for (var node : nodes) {
            assertTrue(node.description().stream().anyMatch(s -> s.contains("Requirements:")));
            assertTrue(node.description().stream().anyMatch(s -> s.contains("Rewards: None")));
        }
    }
    @Test void nativeWiringIsEnabledAsyncAndOptional() throws Exception {
        String config = Files.readString(Path.of("src/main/resources/config.yml"));
        String plugin = Files.readString(Path.of("src/main/resources/plugin.yml"));
        String source = Files.readString(Path.of(
            "src/main/java/org/enthusia/tags/advancements/NativeAdvancementController.java"));

        assertTrue(config.contains("commendation-enabled: true"));
        assertTrue(plugin.contains("- EnthusiaCommend"));
        assertTrue(source.contains("getPlugin(\"EnthusiaCommend\")"));
        assertTrue(source.contains("CommendAdvancementBridge"));
        assertTrue(source.contains("commend.refresh()"));
        assertTrue(source.contains("commend.observe(player.getUniqueId())"));
        assertTrue(source.contains("commend.forget(id)"));
        assertTrue(source.contains("commendTask.cancel()"));
    }

    @Test void correctedHistoryAfterWrongShapedEvidenceRemainsSilent() throws Exception {
        Path file = directory.resolve("shape.yml");
        for (String malformed : new String[]{"dataVersion: 9\nadvancementEvidence: []\n",
                "advancementEvidence:\n  " + player + ":\n    positiveReceived: true\n"
                + "    maxOverall: 20\n    minOverall: -25\n    recoveredFromSevere: true\n    categoryMax: broken\n"}) {
            Files.writeString(file, malformed);
            var bridge = new CommendAdvancementBridge(file);
            bridge.beginSession(player);
            assertThrows(IllegalArgumentException.class, bridge::refresh);
            assertTrue(bridge.observe(player).progress().isEmpty());
            save(file, new Stats(true, 20, -25, true, 5, 5, 5, 5));
            bridge.refresh();
            var restored = bridge.observe(player);
            assertTrue(restored.progress().values().stream().allMatch(value -> value == 1000));
            assertTrue(restored.celebrate().isEmpty(), "Restored evidence is historical, not a live crossing");
        }
    }

    @Test void malformedShapeRetainsAlreadyKnownSessionProgress() throws Exception {
        Path file = directory.resolve("known.yml");
        save(file, new Stats(true, 20, -25, true, 5, 5, 5, 5));
        var bridge = new CommendAdvancementBridge(file);
        bridge.beginSession(player);
        bridge.refresh();
        var known = bridge.observe(player).progress();
        Files.writeString(file, "dataVersion: 9\nadvancementEvidence: false\n");
        assertThrows(IllegalArgumentException.class, bridge::refresh);
        assertEquals(known, bridge.observe(player).progress());
        assertTrue(bridge.observe(player).celebrate().isEmpty());
    }

    @Test void missingProviderEvidenceIsUnavailableNotZero() throws Exception {
        Path file = directory.resolve("data.yml");
        Files.writeString(file, "players: {}\n");
        var bridge = new CommendAdvancementBridge(file);
        bridge.beginSession(player);
        assertThrows(Exception.class, bridge::refresh);
        assertTrue(bridge.observe(player).progress().isEmpty());
    }
}
