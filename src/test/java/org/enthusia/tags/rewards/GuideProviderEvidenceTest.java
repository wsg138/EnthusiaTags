package org.enthusia.tags.rewards;

import java.nio.file.Path;
import java.util.List;
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
import org.enthusia.tags.advancements.domain.GuideGoalPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GuideProviderEvidenceTest {
    private static final String KEY = "advancement_reward:express/read_all_about_it";
    private static final String MILESTONE = "express/read_all_about_it";
    @TempDir Path folder;

    @Test void verifiedProviderEvidenceEnablesGuideButUnavailableAndInventedKeysDoNot() throws Exception {
        var plugin = mock(JavaPlugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        var scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong())).thenReturn(mock(BukkitTask.class));
        var player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(player.isOnline()).thenReturn(true);
        var storage = new RewardStorage(folder.resolve("guide.db").toFile(), new PerformanceMonitor(null));
        try (var bukkit = mockStatic(Bukkit.class)) {
            storage.init();
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
            var service = spy(new RewardService(plugin, null, null, new PerformanceMonitor(null), storage));
            doReturn(true).when(service).isAvailable();
            service.preloadPlayerBlocking(id);
            var goal = goal(service, KEY);
            assertFalse(service.isGuideGoalReady(player, goal));
            service.observeProviderProgress(player, Map.of(MILESTONE, 0));
            assertTrue(service.isGuideGoalReady(player, goal), "Verified zero is a supported incomplete goal");
            service.observeProviderProgress(player, Map.of());
            assertFalse(service.isGuideGoalReady(player, goal), "Cached zero must not make missing evidence ready");
            service.observeProviderProgress(player, Map.of(MILESTONE, 1000));
            service.observeProviderProgress(player, Map.of());
            assertTrue(service.isGuideGoalReady(player, goal), "Durable earned eligibility survives an outage");
            assertEquals(1L, service.getCounter(id, KEY));
            assertTrue(storage.loadActionLedgerNow(id, goal.getId()).isEmpty());
            assertFalse(service.isGuideGoalReady(player, goal(service, "advancement_reward:express/invented")));
            assertFalse(GuideGoalPolicy.supportsCounter("advancement_reward:invented/goal"));
            assertTrue(storage.listGoldIpClaimsNow(id, goal.getId()).isEmpty());
        } finally { storage.close(); }
    }

    @Test void settledOrInterruptedProviderGoalsAreNotFreshSuggestions() throws Exception {
        var plugin = mock(JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        var storage = new RewardStorage(folder.resolve("status.db").toFile(), new PerformanceMonitor(null));
        try (var bukkit = mockStatic(Bukkit.class)) {
            storage.init();
            var service = spy(new RewardService(plugin, null, null, new PerformanceMonitor(null), storage));
            doReturn(true).when(service).isAvailable();
            var goal = goal(service, KEY);
            for (var status : List.of(RewardStatus.CLAIMED, RewardStatus.ITEM_QUEUED,
                RewardStatus.CLAIM_PENDING, RewardStatus.REQUIRES_RECONCILIATION, RewardStatus.DELIVERY_FAILED)) {
                UUID id = UUID.randomUUID();
                var player = mock(Player.class);
                when(player.getUniqueId()).thenReturn(id);
                var claims = status == RewardStatus.CLAIMED ? Set.of(goal.getId()) : Set.<String>of();
                var original = new RewardStorage.StoredRewardData(claims, Map.of(KEY, 1L),
                    Map.of("reward-delivery:" + goal.getId(), status.name()), 2L);
                storage.saveNow(id, original);
                service.preloadPlayerBlocking(id);
                assertFalse(service.isGuideGoalReady(player, goal), status.name());
                assertEquals(original, storage.loadNow(id), "Guide readiness is read-only");
            }
        } finally { storage.close(); }
    }

    private static RewardDefinition goal(RewardService service, String key) {
        var criteria = new YamlConfiguration();
        criteria.set("complete.type", "CUSTOM_COUNTER");
        criteria.set("complete.key", key);
        criteria.set("complete.amount", 1);
        return new RewardDefinition("provider", "Provider", List.of(), null, service.loadCriteria(criteria),
            List.of(new RewardAction(RewardActionType.TAG, "existing", 0, "Existing tag")), "advancements");
    }
}
