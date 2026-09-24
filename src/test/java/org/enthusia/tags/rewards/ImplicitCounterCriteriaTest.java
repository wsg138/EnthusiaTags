package org.enthusia.tags.rewards;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.HashMap;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.enthusia.tags.PerformanceMonitor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImplicitCounterCriteriaTest {
    record Expected(String id, String key, long amount) {}
    private static final List<Expected> EXISTING = List.of(
        new Expected("sleeps_in_minecraft", "max_consecutive_active", 720),
        new Expected("marathon_session", "max_consecutive_active", 360),
        new Expected("yearn_for_mines", "underground_active", 600),
        new Expected("deep_dweller", "underground_active", 1800),
        new Expected("lag_was_crazy", "max_ping_ms", 150));

    private JavaPlugin plugin() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("counter-criteria-test"));
        when(plugin.isEnabled()).thenReturn(true);
        return plugin;
    }

    private RewardService service() {
        return new RewardService(plugin(), null, null, new PerformanceMonitor(null));
    }

    private YamlConfiguration bundled() throws Exception {
        try (var in = getClass().getClassLoader().getResourceAsStream("rewards.yml")) {
            assertNotNull(in);
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    private List<RewardCriterion> parse(RewardService service, ConfigurationSection section) {
        return service.loadCriteria(section);
    }

    @Test void allBundledCriteriaRemainValidIncludingTheFiveMissingMappings() throws Exception {
        var service = service();
        var config = bundled();
        for (var expected : EXISTING) {
            var criteria = parse(service, config.getConfigurationSection("rewards." + expected.id() + ".criteria"));
            assertEquals(1, criteria.size());
            var criterion = criteria.getFirst();
            assertTrue(criterion.isValid(), expected.id());
            assertEquals(RewardSourceType.CUSTOM_COUNTER, criterion.getSourceType());
            assertEquals(expected.key(), criterion.getKey());
            assertEquals(expected.amount(), criterion.getAmount());
        }
        for (var id : config.getConfigurationSection("rewards").getKeys(false)) {
            for (var criterion : parse(service, config.getConfigurationSection("rewards." + id + ".criteria"))) {
                assertTrue(criterion.isValid(), id);
            }
        }
    }

    @Test void administratorOverridesArePreservedAndUnknownCountersStillFailClosed() throws Exception {
        var service = service();
        var config = bundled();
        for (var expected : EXISTING) {
            var section = config.getConfigurationSection("rewards." + expected.id() + ".criteria");
            var entry = section.getConfigurationSection(section.getKeys(false).iterator().next());
            entry.set("key", "admin_counter");
            assertEquals("admin_counter", parse(service, section).getFirst().getKey());
            entry.set("counter", "admin_alias");
            assertEquals("admin_alias", parse(service, section).getFirst().getKey());
            entry.set("source", "CUSTOM_COUNTER");
            assertEquals("admin_alias", parse(service, section).getFirst().getKey());
            entry.set("key", null);
            entry.set("counter", null);
            assertFalse(parse(service, section).getFirst().isValid(), "Explicit custom source requires a key");
            entry.set("source", null);
            entry.set("type", "CUSTOM_COUNTER");
            assertFalse(parse(service, section).getFirst().isValid());
        }
    }

    @Test void persistedCustomCountersDoNotRequirePlaytimeProvider() throws Exception {
        var service = service();
        var config = bundled();
        for (var expected : EXISTING) {
            var criterion = parse(service,
                config.getConfigurationSection("rewards." + expected.id() + ".criteria")).getFirst();
            assertEquals(RewardSourceType.CUSTOM_COUNTER, criterion.getSourceType());
            assertTrue(service.isCriterionAvailable(criterion), expected.id());
        }
    }

    @Test void existingSavedProgressAndClaimsAreReadWithoutMutation(@TempDir Path directory) throws Exception {
        UUID playerId = UUID.randomUUID();
        var counters = Map.of("max_consecutive_active", 721L, "underground_active", 1801L, "max_ping_ms", 151L);
        var saved = new RewardStorage.StoredRewardData(Set.of("sleeps_in_minecraft"), counters, Map.of(), 8L);
        var storage = new RewardStorage(directory.resolve("rewards.db").toFile(), new PerformanceMonitor(null));
        storage.init();
        assertEquals(RewardStorage.WriteResult.WRITTEN, storage.saveAsync(playerId, saved).get());
        storage.close();
        storage = new RewardStorage(directory.resolve("rewards.db").toFile(), new PerformanceMonitor(null));
        storage.init();
        try {
            var service = spy(new RewardService(plugin(), null, null, new PerformanceMonitor(null), storage));
            doReturn(true).when(service).isAvailable();
            service.preloadPlayerBlocking(playerId);
            Player player = mock(Player.class);
            when(player.getUniqueId()).thenReturn(playerId);
            var config = bundled();
            for (var expected : EXISTING) {
                var criterion = parse(service, config.getConfigurationSection("rewards." + expected.id() + ".criteria")).getFirst();
                assertTrue(criterion.isValid(), expected.id());
                assertEquals(counters.get(expected.key()), service.getProgress(player, criterion,
                    new RewardService.ProgressSnapshot(0, new HashMap<>())));
                assertTrue(storage.loadActionLedgerNow(playerId, expected.id()).isEmpty());
            }
            assertEquals(saved, storage.loadNow(playerId));
            for (var entry : counters.entrySet()) assertEquals(entry.getValue(), service.getCounter(playerId, entry.getKey()));
            assertTrue(service.isClaimed(playerId, "sleeps_in_minecraft"));
        } finally { storage.close(); }
    }

}
