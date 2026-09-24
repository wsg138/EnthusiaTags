package org.enthusia.tags.advancements;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.enthusia.tags.advancements.domain.DuelMilestoneProgress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WarzoneBridgeTest {
    @TempDir Path directory;
    private final UUID player = UUID.randomUUID();
    private void save(Path file, int wins, int streak) throws Exception {
        Files.writeString(file, "players:\n  " + player + ":\n    wins: " + wins + "\n    best-win-streak: " + streak + "\n");
    }
    @Test void historicalLiveFailureAndReconnectLifecycle() throws Exception {
        Path file = directory.resolve("stats.yml");
        save(file, 49, 4);
        String original = Files.readString(file);
        var bridge = new WarzoneAdvancementBridge(file);
        bridge.refresh();
        assertEquals(980, bridge.observe(player).progress().get("warzone_duels/gladiator"));
        assertTrue(bridge.observe(player).celebrate().isEmpty());
        assertEquals(original, Files.readString(file));
        Files.writeString(file, "broken: [");
        assertThrows(Exception.class, bridge::refresh);
        assertEquals(980, bridge.observe(player).progress().get("warzone_duels/gladiator"));
        save(file, 50, 5);
        bridge.refresh();
        assertEquals(2, bridge.observe(player).celebrate().size());
        assertTrue(bridge.observe(player).celebrate().isEmpty());
        bridge.forget(player);
        assertTrue(bridge.observe(player).celebrate().isEmpty());
    }
    @Test void confirmedAbsentPlayerCanEarnFirstWinButMissingFileIsUnknown() throws Exception {
        Path file = directory.resolve("stats.yml");
        var bridge = new WarzoneAdvancementBridge(file);
        assertThrows(Exception.class, bridge::refresh);
        assertTrue(bridge.observe(player).progress().isEmpty());
        Files.writeString(file, "players: {}\n");
        bridge.refresh();
        assertEquals(0, bridge.observe(player).progress().get("warzone_duels/first_blood"));
        save(file, 1, 1);
        bridge.refresh();
        assertTrue(bridge.observe(player).celebrate().contains("warzone_duels/first_blood"));
    }
    @Test void overlappingRefreshAttemptCannotReplaceCurrentSnapshot() throws Exception {
        Path file = directory.resolve("stats.yml");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var reads = new AtomicInteger();
        var bridge = new WarzoneAdvancementBridge(file, source -> {
            assertEquals(file, source);
            if (reads.incrementAndGet() == 1) return Map.of(player, new DuelMilestoneProgress.Stats(1, 1));
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return Map.of(player, new DuelMilestoneProgress.Stats(50, 5));
        });
        bridge.refresh();
        assertEquals(20, bridge.observe(player).progress().get("warzone_duels/gladiator"));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var pending = executor.submit(() -> { bridge.refresh(); return null; });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            bridge.refresh();
            assertEquals(2, reads.get(), "An overlapping refresh must not call the reader again");
            assertEquals(20, bridge.observe(player).progress().get("warzone_duels/gladiator"));
            release.countDown();
            pending.get(5, TimeUnit.SECONDS);
            assertEquals(1000, bridge.observe(player).progress().get("warzone_duels/gladiator"));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test void nodesAreDistinctBranchedAndHaveRequirementsAndNoInventedRewards() {
        var nodes = WarzoneAdvancementBridge.nodes(29);
        assertEquals(9, nodes.size());
        var byKey = nodes.stream().collect(java.util.stream.Collectors.toMap(n -> n.key(), n -> n));

        assertEquals("Welcome to the Thunderdome",
            byKey.get("warzone_duels/welcome_to_thunderdome").title());
        assertEquals("Arena Initiate", byKey.get("warzone_duels/first_blood").title());
        assertEquals(29, byKey.get("warzone_duels/first_blood").y());

        assertEquals(28, byKey.get("warzone_duels/unstoppable").y());
        assertEquals(28, byKey.get("warzone_duels/gladiator").y());
        assertEquals(29, byKey.get("warzone_duels/victor_spoils").y());
        assertEquals(29, byKey.get("warzone_duels/price_for_peace").y());
        assertEquals(30, byKey.get("warzone_duels/my_house_my_rules").y());
        assertEquals(30, byKey.get("warzone_duels/adapt_and_overcome").y());
        assertEquals(30, byKey.get("warzone_duels/not_even_close").y());

        assertEquals("warzone_duels/first_blood",
            byKey.get("warzone_duels/unstoppable").parentKey());
        assertEquals("warzone_duels/first_blood",
            byKey.get("warzone_duels/victor_spoils").parentKey());
        assertEquals("warzone_duels/first_blood",
            byKey.get("warzone_duels/my_house_my_rules").parentKey());

        assertEquals(9, nodes.stream().map(n -> n.key()).distinct().count());
        for (var node : nodes) {
            assertTrue(node.key().startsWith("warzone_duels/"));
            assertTrue(node.description().stream().anyMatch(s -> s.contains("Requirements:")));
            assertTrue(node.description().stream().anyMatch(s -> s.contains("Rewards: None")));
        }
    }
    @Test void joiningMustNotTreatAnOldCachedSnapshotAsLiveBaseline() throws Exception {
        Path file = directory.resolve("stats.yml");
        save(file, 49, 4);
        var bridge = new WarzoneAdvancementBridge(file);
        bridge.refresh();
        bridge.beginSession(player);
        assertTrue(bridge.observe(player).progress().isEmpty(), "Wait for a post-join read");
        save(file, 50, 5);
        bridge.refresh();
        var result = bridge.observe(player);
        assertEquals(1000, result.progress().get("warzone_duels/gladiator"));
        assertTrue(result.celebrate().isEmpty(), "First fresh read is historical, never a replayed toast");
    }
    @Test void malformedSectionCannotSeedAnArtificialLiveBaseline() throws Exception {
        Path file = directory.resolve("shape.yml");
        Files.writeString(file, "players:\n  " + player
            + ":\n    wins: 50\n    best-win-streak: 5\n    advancements: []\n");
        var bridge = new WarzoneAdvancementBridge(file);
        bridge.beginSession(player);
        assertThrows(IllegalArgumentException.class, bridge::refresh);
        assertTrue(bridge.observe(player).progress().isEmpty());
        Files.writeString(file, "players:\n  " + player
            + ":\n    wins: 50\n    best-win-streak: 5\n    advancements:\n      challenges-sent: 1\n");
        bridge.refresh();
        var restored = bridge.observe(player);
        assertEquals(1000, restored.progress().get("warzone_duels/welcome_to_thunderdome"));
        assertTrue(restored.celebrate().isEmpty(), "The first valid snapshot remains historical");
    }

    @Test void nativeWiringIsOptInAsyncAndClosesWithController() throws Exception {
        String config = Files.readString(Path.of("src/main/resources/config.yml"));
        String source = Files.readString(Path.of("src/main/java/org/enthusia/tags/advancements/NativeAdvancementController.java"));
        assertTrue(config.contains("warzone-duels-enabled: false"));
        assertTrue(source.contains("runTaskTimerAsynchronously"));
        assertTrue(source.contains("duels.observe(player.getUniqueId())"));
        assertTrue(source.contains("duels.forget(id)"));
        assertTrue(source.contains("duelTask.cancel()"));
    }
}
