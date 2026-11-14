package org.enthusia.tags.advancements;

import io.github.badgersmc.advancements.pilot.ProjectionService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.enthusia.tags.PerformanceMonitor;
import org.enthusia.tags.advancements.domain.DiaryMilestoneProgress;
import org.enthusia.tags.advancements.domain.DuelMilestoneProgress;
import org.enthusia.tags.advancements.domain.ExpressMilestoneProgress;
import org.enthusia.tags.advancements.domain.ReputationMilestoneProgress;
import org.enthusia.tags.rewards.RewardService;
import org.enthusia.tags.rewards.RewardStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProviderRewardCompletionIntegrationTest {
    @TempDir Path folder;

    @Test void allProviderCompletionsReachDurableRewardsWithoutAutomaticPayout() throws Exception {
        var plugin = mock(JavaPlugin.class);
        var config = new YamlConfiguration();
        config.set("advancements.warzone-duels-enabled", true);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.isEnabled()).thenReturn(true);
        var player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(player.isOnline()).thenReturn(true);
        var scheduler = mock(BukkitScheduler.class);
        List<Runnable> ticks = new ArrayList<>();
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong()))
            .thenAnswer(call -> { ticks.add(call.getArgument(1)); return mock(BukkitTask.class); });
        var manager = mock(PluginManager.class);
        var provider = mock(Plugin.class);
        when(provider.isEnabled()).thenReturn(true);
        when(provider.getDataFolder()).thenReturn(folder.toFile());
        when(manager.getPlugin(anyString())).thenReturn(provider);
        when(manager.isPluginEnabled(anyString())).thenReturn(true);
        var projection = mock(ProjectionService.class);
        when(projection.ready(player)).thenReturn(true);
        var services = mock(ServicesManager.class);
        when(services.load(ProjectionService.class)).thenReturn(projection);
        Map<String, Integer> duel = completed(WarzoneAdvancementBridge.nodes(1));
        Map<String, Integer> commend = completed(CommendAdvancementBridge.nodes(2));
        Map<String, Integer> express = completed(ExpressAdvancementBridge.nodes(3));
        Map<String, Integer> diary = completed(DiaryAdvancementBridge.nodes(4, 815002));
        Map<String, Integer> all = new HashMap<>();
        List.of(duel, commend, express, diary).forEach(all::putAll);
        assertEquals(38, all.size());
        var storage = new RewardStorage(folder.resolve("rewards.db").toFile(), new PerformanceMonitor(null));
        try (var bukkit = mockStatic(Bukkit.class);
             var items = mockConstruction(ItemStack.class, (item, context) -> when(item.getItemMeta()).thenReturn(mock(ItemMeta.class)));
             var a = mockConstruction(WarzoneAdvancementBridge.class, withSettings().defaultAnswer(call -> switch (call.getMethod().getName()) {
                 case "observe" -> new DuelMilestoneProgress.Update(duel, Set.of());
                 case "evidenceAvailable" -> true;
                 default -> RETURNS_DEFAULTS.answer(call);
             }));
             var b = mockConstruction(CommendAdvancementBridge.class, withSettings().defaultAnswer(call -> switch (call.getMethod().getName()) {
                 case "observe" -> new ReputationMilestoneProgress.Update(commend, Set.of());
                 case "evidenceAvailable" -> true;
                 default -> RETURNS_DEFAULTS.answer(call);
             }));
             var c = mockConstruction(ExpressAdvancementBridge.class, withSettings().defaultAnswer(call -> switch (call.getMethod().getName()) {
                 case "observe" -> new ExpressMilestoneProgress.Update(express, Set.of());
                 case "evidenceAvailable" -> true;
                 default -> RETURNS_DEFAULTS.answer(call);
             }));
             var d = mockConstruction(DiaryAdvancementBridge.class, withSettings().defaultAnswer(call -> switch (call.getMethod().getName()) {
                 case "observe" -> new DiaryMilestoneProgress.Update(diary, Set.of());
                 case "evidenceAvailable" -> true;
                 default -> RETURNS_DEFAULTS.answer(call);
             }))) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
            bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
            bukkit.when(Bukkit::getServicesManager).thenReturn(services);
            storage.init();
            var original = new RewardStorage.StoredRewardData(Set.of("existing_claim"), Map.of("unrelated", 42L), Map.of(), 3L);
            storage.saveNow(id, original);
            var rewards = spy(new RewardService(plugin, null, null, new PerformanceMonitor(null)));
            var field = RewardService.class.getDeclaredField("storage");
            field.setAccessible(true);
            field.set(rewards, storage);
            doReturn(true).when(rewards).isAvailable();
            rewards.preloadPlayerBlocking(id);
            try (var controller = new NativeAdvancementController(plugin, rewards)) {
                List.copyOf(ticks).forEach(Runnable::run);
                assertAll(all.keySet().stream().map(key -> () ->
                    assertEquals(1L, rewards.getCounter(id, "advancement_reward:" + key), key)));
                for (String key : all.keySet()) {
                    var criterion = new org.enthusia.tags.rewards.RewardCriterion(
                        org.enthusia.tags.rewards.RewardCriterionType.CUSTOM_COUNTER,
                        org.enthusia.tags.rewards.RewardSourceType.CUSTOM_COUNTER, 1,
                        null, null, null, "advancement_reward:" + key, 0, "Completed", true);
                    assertEquals(1, rewards.getProgress(player, criterion,
                        new RewardService.ProgressSnapshot(0, new HashMap<>())), key);
                }
                List.copyOf(ticks).forEach(Runnable::run);
                verify(rewards, never()).claimAsync(any(), any());
                rewards.unloadPlayer(player);
                var saved = storage.loadNow(id);
                assertEquals(original.claims(), saved.claims());
                assertEquals(42L, saved.counters().get("unrelated"));
                for (String key : all.keySet()) {
                    assertEquals(1L, saved.counters().get("advancement_reward:" + key));
                    assertTrue(storage.loadActionLedgerNow(id, key).isEmpty());
                }
            }
        } finally { storage.close(); }
    }

    private static Map<String, Integer> completed(List<ProjectionService.Node> nodes) {
        Map<String, Integer> result = new HashMap<>();
        nodes.forEach(node -> result.put(node.key(), 1000));
        return result;
    }
}
