package org.enthusia.tags.advancements;

import io.github.badgersmc.advancements.pilot.ProjectionService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.enthusia.tags.rewards.RewardService;
import org.enthusia.tags.rewards.RewardDefinition;
import org.bukkit.Material;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProjectionTickTest {
    private final JavaPlugin plugin = mock(JavaPlugin.class);
    private final RewardService rewards = mock(RewardService.class);
    private final ProjectionService projection = mock(ProjectionService.class);
    private final Player player = mock(Player.class);
    private final BukkitScheduler scheduler = mock(BukkitScheduler.class);
    private final PluginManager manager = mock(PluginManager.class);
    private MockedStatic<Bukkit> bukkit;
    private MockedConstruction<ItemStack> items;
    private NativeAdvancementController controller;
    private Runnable tick;

    @BeforeEach void setUp() {
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(rewards.getRewards()).thenReturn(Map.of());
        when(rewards.isAvailable()).thenReturn(true);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(player.isOnline()).thenReturn(true);
        ServicesManager services = mock(ServicesManager.class);
        when(services.load(ProjectionService.class)).thenReturn(projection);
        when(manager.isPluginEnabled("EnthusiaAdvancements")).thenReturn(true);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(20L), eq(20L)))
            .thenReturn(mock(BukkitTask.class));
        bukkit = mockStatic(Bukkit.class);
        items = mockConstruction(ItemStack.class, (item, context) ->
            when(item.getItemMeta()).thenReturn(mock(ItemMeta.class)));
        bukkit.when(Bukkit::getServicesManager).thenReturn(services);
        bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
        bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
        controller = new NativeAdvancementController(plugin, rewards);
        ArgumentCaptor<Runnable> capture = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler, atLeastOnce()).runTaskTimer(eq(plugin), capture.capture(), eq(20L), eq(20L));
        tick = capture.getValue();
    }

    @AfterEach void tearDown() {
        try { if (controller != null) controller.close(); }
        finally { items.close(); bukkit.close(); }
    }

    @Test void linkageFailureIsContainedAndNextTickCanRecover() {
        when(projection.ready(player)).thenThrow(new NoSuchMethodError("companion mismatch")).thenReturn(true);
        assertDoesNotThrow(tick::run);
        assertDoesNotThrow(tick::run);
        verify(projection).project(eq(plugin), eq("enthusia"), eq(player), anyMap());
    }

    @Test void holidaysOwnsMelodyCelebrationButTagsStillProjectsItsClaimProgress() {
        when(manager.isPluginEnabled("EnthusiaHolidays")).thenReturn(true);
        completeRewards("watcher_melody", "first_hour");
        verify(projection, never()).celebrate(plugin, "enthusia", player,
            NativeAdvancementController.key("watcher_melody"));
        verify(projection).celebrate(plugin, "enthusia", player,
            NativeAdvancementController.key("first_hour"));
        verify(projection).project(eq(plugin), eq("enthusia"), eq(player), argThat(progress ->
            progress.get(NativeAdvancementController.key("watcher_melody")) == 1000
                && progress.get(NativeAdvancementController.key("first_hour")) == 1000));
        verify(rewards, never()).claimAsync(any(), any());
    }

    @Test void standaloneTagsStillCelebratesMelody() {
        when(manager.isPluginEnabled("EnthusiaHolidays")).thenReturn(false);
        completeRewards("watcher_melody");
        verify(projection).celebrate(plugin, "enthusia", player,
            NativeAdvancementController.key("watcher_melody"));
    }

    @Test void unrelatedRewardWithSameTitleStillCelebrates() {
        when(manager.isPluginEnabled("EnthusiaHolidays")).thenReturn(true);
        completeRewards("custom_melody");
        verify(projection).celebrate(plugin, "enthusia", player,
            NativeAdvancementController.key("custom_melody"));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void completeRewards(String... ids) {
        Map<String, RewardDefinition> definitions = new java.util.LinkedHashMap<>();
        // Keep the real bundled Melody ancestry valid while exercising its notification.
        for (String ancestor : List.of("lag_was_crazy", "all_rounder", "server_legend", "ultimate_survivor")) {
            definitions.put(ancestor, new RewardDefinition(ancestor, ancestor, List.of(), Material.PAPER,
                List.of(), List.of(), "misc"));
        }
        for (String id : ids) {
            var reward = new RewardDefinition(id, "Forgotten Melody", List.of(), Material.MUSIC_DISC_11,
                List.of(), List.of(), "misc");
            definitions.put(id, reward);
            when(rewards.getAdvancementProgress(player, reward)).thenReturn(1000);
        }
        when(rewards.getRewards()).thenReturn(definitions);
        when(projection.ready(player)).thenReturn(true);
        ArgumentCaptor<BiConsumer<Player, List<RewardDefinition>>> sink = ArgumentCaptor.forClass(BiConsumer.class);
        verify(rewards).setAdvancementNotifications(sink.capture());
        sink.getValue().accept(player, java.util.Arrays.stream(ids).map(definitions::get).toList());
        tick.run();
    }

    @Test void fatalVmErrorsAreNotSwallowed() {
        when(projection.ready(player)).thenThrow(new OutOfMemoryError("test sentinel"));
        assertThrows(OutOfMemoryError.class, tick::run);
    }

    @Test void providerOwnsBothEarlyAndLateMirroredRewardNotifications() {
        controller.close();
        var provider = mock(org.bukkit.plugin.Plugin.class);
        when(manager.getPlugin("DiaryKeeper")).thenReturn(provider);
        when(manager.isPluginEnabled("DiaryKeeper")).thenReturn(true);
        when(provider.isEnabled()).thenReturn(true);
        when(provider.getDataFolder()).thenReturn(new java.io.File("unused-mocked-provider"));
        var reward = new RewardDefinition("diary_reward", "Dear Diary...", List.of(), Material.PAPER,
            List.of(new org.enthusia.tags.rewards.RewardCriterion(
                org.enthusia.tags.rewards.RewardCriterionType.CUSTOM_COUNTER, 1, null,
                "advancement_reward:diary/dear_diary", 0, "Receive diary")), List.of(), "advancements");
        when(rewards.getRewards()).thenReturn(Map.of(reward.getId(), reward));
        when(rewards.getAdvancementProgress(player, reward)).thenReturn(1000);
        when(projection.ready(player)).thenReturn(true);
        var progress = Map.of("diary/dear_diary", 1000);
        try (var bridges = mockConstruction(DiaryAdvancementBridge.class, (bridge, context) -> {
            when(bridge.evidenceAvailable(player.getUniqueId())).thenReturn(true);
            when(bridge.observe(player.getUniqueId())).thenReturn(
                new org.enthusia.tags.advancements.domain.DiaryMilestoneProgress.Update(progress,
                    java.util.Set.of("diary/dear_diary")),
                new org.enthusia.tags.advancements.domain.DiaryMilestoneProgress.Update(progress, java.util.Set.of()));
        })) {
            controller = new NativeAdvancementController(plugin, rewards);
            @SuppressWarnings({"unchecked", "rawtypes"})
            ArgumentCaptor<BiConsumer<Player, List<RewardDefinition>>> sink = ArgumentCaptor.forClass(BiConsumer.class);
            verify(rewards, atLeastOnce()).setAdvancementNotifications(sink.capture());
            var callback = sink.getValue();
            ArgumentCaptor<Runnable> ticks = ArgumentCaptor.forClass(Runnable.class);
            verify(scheduler, atLeastOnce()).runTaskTimer(eq(plugin), ticks.capture(), eq(20L), eq(20L));
            callback.accept(player, List.of(reward));
            ticks.getAllValues().forEach(Runnable::run);
            callback.accept(player, List.of(reward));
            ticks.getAllValues().forEach(Runnable::run);
            verify(projection, times(1)).celebrate(plugin, "enthusia", player, "diary/dear_diary");
            verify(projection, never()).celebrate(plugin, "enthusia", player, NativeAdvancementController.key(reward.getId()));
            verify(projection, times(2)).project(eq(plugin), eq("enthusia"), eq(player), eq(progress));
            verify(rewards, never()).claimAsync(any(), any());
        }
    }

    @Test void linkageFailureDuringRemovalDoesNotAbortCleanup() {
        doThrow(new NoClassDefFoundError("companion unloaded"))
            .when(projection).removeTree(plugin, "enthusia");
        assertDoesNotThrow(controller::close);
        assertDoesNotThrow(controller::close);
        verify(projection).removeTree(plugin, "enthusia");
    }
    @Test void privateRewardNeverRegistersOrProjectsInPublicTree() {
        var hidden = new RewardDefinition("private", "Private name", List.of("Private details"), Material.PAPER,
            List.of(), List.of(), "misc", org.enthusia.tags.rewards.RewardCompletionMode.LATCHED).withHiddenUntilEarned(true);
        when(rewards.getRewards()).thenReturn(Map.of("private", hidden));
        when(projection.ready(player)).thenReturn(true);
        tick.run();
        verify(projection, atLeastOnce()).registerTree(eq(plugin), eq("enthusia"), any(ItemStack.class), argThat(nodes ->
            nodes.stream().noneMatch(node -> node.key().equals(NativeAdvancementController.key("private")) || node.title().contains("Private name"))));
        verify(projection).project(eq(plugin), eq("enthusia"), eq(player), argThat(progress ->
            !progress.containsKey(NativeAdvancementController.key("private"))));
        verify(projection, never()).celebrate(plugin, "enthusia", player, NativeAdvancementController.key("private"));
    }

}
