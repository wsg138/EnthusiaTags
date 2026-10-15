package org.enthusia.tags.advancements;

import io.github.badgersmc.advancements.pilot.ProjectionService;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.enthusia.tags.rewards.RewardService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DuplicateAnnouncementTest {
    @TempDir Path directory;

    @Test void previouslyAnnouncedCompletionIsNotAnnouncedAfterSessionReset() throws Exception {
        scenario(false, false);
    }

    @Test void previouslyAnnouncedCompletionIsNotAnnouncedAfterControllerRestart() throws Exception {
        scenario(true, false);
    }

    @Test void partiallyDeliveredProviderNotificationIsNotRetried() throws Exception {
        scenario(false, true);
    }

    private void scenario(boolean restart, boolean partialFailure) throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("duplicate-test"));
        RewardService rewards = mock(RewardService.class);
        when(rewards.getRewards()).thenReturn(Map.of());
        ProjectionService projection = mock(ProjectionService.class);
        if (partialFailure) {
            doThrow(new IllegalStateException("sent toast, then viewer delivery failed"))
                .doNothing().when(projection).celebrate(eq(plugin), eq("enthusia"), any(Player.class), eq("express/first_class"));
        }
        ServicesManager services = mock(ServicesManager.class);
        when(services.load(ProjectionService.class)).thenReturn(projection);
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        try (var bukkit = mockStatic(Bukkit.class);
             var items = mockConstruction(ItemStack.class, (item, context) ->
                 when(item.getItemMeta()).thenReturn(mock(ItemMeta.class)))) {
            bukkit.when(Bukkit::getServicesManager).thenReturn(services);
            bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
            bukkit.when(Bukkit::getScheduler).thenReturn(mock(BukkitScheduler.class));
            var controller = new NativeAdvancementController(plugin, rewards);
            try {
                queue(controller, id);
                drain(controller, player);
                // Even a second queue in the same session cannot reuse authorization.
                queue(controller, id);
                drain(controller, player);
                controller.onQuit(new PlayerQuitEvent(player, (net.kyori.adventure.text.Component) null));
                if (restart) {
                    controller.close();
                    controller = new NativeAdvancementController(plugin, rewards);
                }
                queue(controller, id);
                drain(controller, player);
                verify(projection, times(1)).celebrate(plugin, "enthusia", player, "express/first_class");
            } finally {
                controller.close();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void queue(NativeAdvancementController controller, UUID id) throws Exception {
        var registered = NativeAdvancementController.class.getDeclaredField("registeredKeys");
        registered.setAccessible(true);
        registered.set(controller, Set.of("express/first_class"));
        var field = NativeAdvancementController.class.getDeclaredField("pendingCelebrations");
        field.setAccessible(true);
        ((Map<UUID, Set<String>>) field.get(controller)).put(id, new HashSet<>(Set.of("express/first_class")));
    }

    private void drain(NativeAdvancementController controller, Player player) throws Exception {
        var method = NativeAdvancementController.class.getDeclaredMethod("celebratePending", Player.class, Map.class);
        method.setAccessible(true);
        // Allow background SQLite work; every invocation is a main-thread projection poll.
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        var field = NativeAdvancementController.class.getDeclaredField("pendingCelebrations");
        field.setAccessible(true);
        while (((Map<?, ?>) field.get(controller)).containsKey(player.getUniqueId())) {
            try {
                method.invoke(controller, player, new java.util.HashMap<>(Map.of("express/first_class", 1000)));
            } catch (java.lang.reflect.InvocationTargetException ex) {
                if (!(ex.getCause() instanceof IllegalStateException)
                    || !"sent toast, then viewer delivery failed".equals(ex.getCause().getMessage())) throw ex;
                // Simulates the tick boundary containing a recoverable renderer exception.
            }
            org.junit.jupiter.api.Assertions.assertTrue(System.nanoTime() < deadline, "Completion did not settle");
            Thread.sleep(5);
        }
    }
}
