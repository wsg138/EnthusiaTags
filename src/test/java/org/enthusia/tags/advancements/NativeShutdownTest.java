package org.enthusia.tags.advancements;

import io.github.badgersmc.advancements.pilot.ProjectionService;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.enthusia.tags.rewards.RewardService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NativeShutdownTest {
    @Test void providerFailureDoesNotEscapeAndCloseIsIdempotent() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("shutdown-test"));
        RewardService rewards = mock(RewardService.class);
        when(rewards.getRewards()).thenReturn(Map.of());
        ProjectionService projection = mock(ProjectionService.class);
        ServicesManager services = mock(ServicesManager.class);
        when(services.load(ProjectionService.class)).thenReturn(projection);
        PluginManager manager = mock(PluginManager.class);
        when(manager.isPluginEnabled("EnthusiaAdvancements")).thenReturn(true);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask task = mock(BukkitTask.class);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(20L), eq(20L))).thenReturn(task);
        try (var bukkit = mockStatic(Bukkit.class);
             var itemStacks = mockConstruction(ItemStack.class, (item, context) ->
                 when(item.getItemMeta()).thenReturn(mock(ItemMeta.class)))) {
            bukkit.when(Bukkit::getServicesManager).thenReturn(services);
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            NativeAdvancementController controller = new NativeAdvancementController(plugin, rewards);
            verify(projection).registerTree(eq(plugin), eq("enthusia"), any(ItemStack.class), anyList());
            doThrow(new IllegalStateException("provider unavailable"))
                .when(projection).removeTree(plugin, "enthusia");
            assertDoesNotThrow(controller::close);
            assertDoesNotThrow(controller::close);
            verify(projection, times(1)).removeTree(plugin, "enthusia");
            verify(task, atLeastOnce()).cancel();
            verify(rewards, atLeastOnce()).setAdvancementNotifications(isNull());
        }
    }
}
