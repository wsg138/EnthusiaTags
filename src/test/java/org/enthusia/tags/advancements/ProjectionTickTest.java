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
        ServicesManager services = mock(ServicesManager.class);
        when(services.load(ProjectionService.class)).thenReturn(projection);
        PluginManager manager = mock(PluginManager.class);
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
        verify(scheduler).runTaskTimer(eq(plugin), capture.capture(), eq(20L), eq(20L));
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

    @Test void fatalVmErrorsAreNotSwallowed() {
        when(projection.ready(player)).thenThrow(new OutOfMemoryError("test sentinel"));
        assertThrows(OutOfMemoryError.class, tick::run);
    }

    @Test void linkageFailureDuringRemovalDoesNotAbortCleanup() {
        doThrow(new NoClassDefFoundError("companion unloaded"))
            .when(projection).removeTree(plugin, "enthusia");
        assertDoesNotThrow(controller::close);
        assertDoesNotThrow(controller::close);
        verify(projection).removeTree(plugin, "enthusia");
    }
}
