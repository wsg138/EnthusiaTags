package org.enthusia.tags.rewards;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.enthusia.tags.PerformanceMonitor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProviderRewardStateTest {
    private static final String MILESTONE = "express/first_class";
    private static final String COUNTER = "advancement_reward:" + MILESTONE;
    @TempDir Path folder;
    @Test void completionPersistsWithoutClaimingAndOutageDoesNotEraseEarnedState() throws Exception {
        UUID id = UUID.randomUUID();
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("provider-state"));
        when(plugin.isEnabled()).thenReturn(true);
        var scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong())).thenReturn(mock(BukkitTask.class));
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.isOnline()).thenReturn(true);
        var criterionConfig = new YamlConfiguration();
        criterionConfig.set("complete.type", "CUSTOM_COUNTER");
        criterionConfig.set("complete.key", COUNTER);
        criterionConfig.set("complete.amount", 1);
        var storage = new RewardStorage(folder.resolve("rewards.db").toFile(), new PerformanceMonitor(null));
        try (var bukkit = mockStatic(Bukkit.class)) {
            storage.init();
            var original = new RewardStorage.StoredRewardData(Set.of("starter_pack"), Map.of("existing", 42L),
                Map.of("reward-delivery:starter_pack", "CLAIMED"), 3L);
            storage.saveNow(id, original);
            bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            var service = spy(new RewardService(plugin, null, null, new PerformanceMonitor(null), storage));
            doReturn(true).when(service).isAvailable();
            service.preloadPlayerBlocking(id);
            var criterion = service.loadCriteria(criterionConfig).getFirst();
            assertEquals(-1, service.getProgress(player, criterion, snapshot()));
            service.observeProviderProgress(player, Map.of(MILESTONE, 0));
            assertEquals(0, service.getProgress(player, criterion, snapshot()));
            service.observeProviderProgress(player, Map.of());
            var unavailable = snapshot();
            assertEquals(0, service.getProgress(player, criterion, unavailable));
            assertFalse(unavailable.unavailableCriteria().isEmpty());
            service.observeProviderProgress(player, Map.of(MILESTONE, 1000));
            assertEquals(1, service.getProgress(player, criterion, snapshot()));
            service.observeProviderProgress(player, Map.of());
            assertEquals(1, service.getProgress(player, criterion, snapshot()));
            service.observeProviderProgress(player, Map.of(MILESTONE, 1000));
            assertEquals(1, service.getCounter(id, COUNTER));
            assertTrue(storage.loadActionLedgerNow(id, "adv_express_first_class").isEmpty());
            service.unloadPlayer(player);
            var saved = storage.loadNow(id);
            assertEquals(1L, saved.counters().get(COUNTER));
            assertEquals(42L, saved.counters().get("existing"));
            assertEquals(original.claims(), saved.claims());
            assertEquals(original.states(), saved.states());
            service.preloadPlayerBlocking(id);
            assertEquals(1, service.getProgress(player, criterion, snapshot()));
            service.observeProviderProgress(player, Map.of(MILESTONE, 0));
            assertEquals(1, service.getCounter(id, COUNTER));
            service.unloadPlayer(player);
            assertEquals(saved, storage.loadNow(id), "Repeated evidence does not create new revisions");
        } finally { storage.close(); }
    }
    @Test void unloadedStaleAndStoppedObservationsCannotLatch() throws Exception {
        UUID id = UUID.randomUUID();
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        Player current = mock(Player.class);
        when(current.getUniqueId()).thenReturn(id);
        when(current.isOnline()).thenReturn(true);
        Player stale = mock(Player.class);
        when(stale.getUniqueId()).thenReturn(id);
        when(stale.isOnline()).thenReturn(true);
        var storage = new RewardStorage(folder.resolve("stale.db").toFile(), new PerformanceMonitor(null));
        try (var bukkit = mockStatic(Bukkit.class)) {
            storage.init();
            bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(current);
            var service = spy(new RewardService(plugin, null, null, new PerformanceMonitor(null), storage));
            doReturn(true).when(service).isAvailable();
            service.observeProviderProgress(current, Map.of(MILESTONE, 1000));
            service.preloadPlayerBlocking(id);
            assertEquals(0, service.getCounter(id, COUNTER));
            service.observeProviderProgress(stale, Map.of(MILESTONE, 1000));
            assertEquals(0, service.getCounter(id, COUNTER));
            doReturn(false).when(service).isAvailable();
            service.observeProviderProgress(current, Map.of(MILESTONE, 1000));
            assertEquals(Map.of(), storage.loadNow(id).counters());
        } finally { storage.close(); }
    }
    private static RewardService.ProgressSnapshot snapshot() { return new RewardService.ProgressSnapshot(0, new HashMap<>()); }
}
