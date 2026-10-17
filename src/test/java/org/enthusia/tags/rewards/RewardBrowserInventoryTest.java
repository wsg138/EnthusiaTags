package org.enthusia.tags.rewards;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.enthusia.tags.EnthusiaTagsPlugin;
import org.enthusia.tags.PerformanceMonitor;
import org.enthusia.tags.TagService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RewardBrowserInventoryTest {
    @Test void focusedNavigationAndRefreshKeepSlotsAndNeverClaim() {
        var plugin = mock(EnthusiaTagsPlugin.class);
        when(plugin.getName()).thenReturn("EnthusiaTags");
        when(plugin.namespace()).thenReturn("enthusiatags");
        when(plugin.getPerformanceMonitor()).thenReturn(new PerformanceMonitor(null));
        var tags = mock(TagService.class);
        when(tags.getPlugin()).thenReturn(plugin);
        var service = mock(RewardService.class);
        var config = mock(RewardsConfig.class);
        when(service.getConfig()).thenReturn(config);
        when(config.categories()).thenReturn(Map.of("misc", new RewardCategory("misc", "General", Material.PAPER)));
        var player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        var rewards = new LinkedHashMap<String, RewardDefinition>();
        for (int n = 0; n < 25; n++) {
            var criterion = new RewardCriterion(RewardCriterionType.CUSTOM_COUNTER, 100, null, "stone_mined", 0, "Stone");
            var reward = new RewardDefinition("goal" + n, "Goal " + n, List.of(), Material.STONE, List.of(criterion), List.of(), "misc");
            rewards.put(reward.getId(), reward);
        }
        when(service.getRewards()).thenReturn(rewards);
        var progress = new RewardService.ProgressSnapshot(0, Map.of());
        when(service.getProgressSnapshot(player)).thenReturn(progress);
        when(service.evaluate(eq(player), any(RewardDefinition.class), eq(progress)))
            .thenReturn(new RewardEvaluation(RewardStatus.LOCKED, Map.of(), false, false, "Requirements not reached"));
        when(service.getVerifiedMenuProgress(eq(player), any(RewardCriterion.class), eq(progress))).thenReturn(OptionalLong.of(0));
        Inventory inventory = mock(Inventory.class);
        when(inventory.getSize()).thenReturn(54);
        RewardMenuHolder[] captured = new RewardMenuHolder[1];
        try (var bukkit = mockStatic(Bukkit.class); var items = mockConstruction(ItemStack.class, (item, context) -> {
            var meta = mock(ItemMeta.class);
            when(meta.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
            when(item.getItemMeta()).thenReturn(meta);
        })) {
            bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(54), any(net.kyori.adventure.text.Component.class)))
                .thenAnswer(call -> { captured[0] = call.getArgument(0); return inventory; });
            var menu = new RewardMenu(service, tags);
            assertSame(inventory, menu.createFocused(player, rewards.get("goal23")));
            var holder = captured[0];
            assertEquals(1, holder.getPage());
            assertEquals("goal23", holder.state().focusedReward());
            assertEquals(List.of("goal21", "goal22", "goal23", "goal24"), holder.visibleRewards());
            var before = holder.visibleRewards();
            when(service.evaluate(eq(player), any(RewardDefinition.class), eq(progress)))
                .thenReturn(new RewardEvaluation(RewardStatus.UNLOCKED, Map.of(), true, true, "Ready to claim"));
            menu.refresh(player, holder, true);
            assertEquals(before, holder.visibleRewards());
            assertEquals(new RewardMenuAction(RewardMenuAction.Type.CLAIM, "goal23"), holder.action(21));
            verify(service, never()).claimAsync(any(Player.class), any(RewardDefinition.class));
            verify(player, never()).openInventory(any(Inventory.class));
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            BukkitTask task = mock(BukkitTask.class);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(40L), eq(40L))).thenReturn(task);
            menu.startRefresh(); menu.startRefresh();
            assertTrue(menu.beginClaim(id, "GOAL23"));
            assertFalse(menu.beginClaim(id, "goal23"));
            menu.close(); menu.close(); menu.startRefresh();
            assertFalse(menu.claiming(id, "goal23"));
            verify(scheduler).runTaskTimer(eq(plugin), any(Runnable.class), eq(40L), eq(40L));
            verify(task).cancel();
        }
    }
}
