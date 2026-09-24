package org.enthusia.tags.cosmetics;

import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.PluginManager;
import org.enthusia.tags.EnthusiaTagsPlugin;
import net.kyori.adventure.text.Component;
import org.enthusia.tags.TagService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PresenceLifecycleTest {
    record Fixture(CosmeticsService service, CosmeticsStorage storage) {}

    static Fixture fixture(UUID id, String selection) throws Exception {
        CosmeticsStorage storage = mock(CosmeticsStorage.class);
        when(storage.loadSelectionsNow(id)).thenReturn(Map.of("quit", selection));
        CosmeticsService service = new CosmeticsService(null, null, null, storage);
        service.preloadPlayerBlocking(id);
        return new Fixture(service, storage);
    }

    private CosmeticsListener listener(CosmeticsService service) {
        EnthusiaTagsPlugin plugin = mock(EnthusiaTagsPlugin.class);
        when(plugin.getName()).thenReturn("EnthusiaTags");
        when(plugin.namespace()).thenReturn("enthusiatags");
        TagService tags = mock(TagService.class);
        when(tags.getPlugin()).thenReturn(plugin);
        return new CosmeticsListener(service, tags, null, null);
    }

    @Test void quitSelectionSurvivesUntilRoseChatHighestPriorityDelivery() throws Exception {
        UUID id = UUID.randomUUID();
        Player player = player(id);
        CosmeticsService service = fixture(id, "original_quit").service();
        PluginManager manager = mock(PluginManager.class);
        when(manager.isPluginEnabled("RoseChat")).thenReturn(true);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
            CosmeticsListener listener = listener(service);
            listener.onQuit(new PlayerQuitEvent(player, (Component) null));
            assertEquals("original_quit", service.getSelection(id, "quit"));
            listener.onQuitCleanup(new PlayerQuitEvent(player, (Component) null));
            assertNull(service.getSelection(id, "quit"));
        }
    }

    @Test void roseChatOwnershipAndSuppressedMessagesNeverProduceBukkitDuplicates() throws Exception {
        UUID id = UUID.randomUUID();
        Player player = player(id);
        CosmeticsService service = fixture(id, "custom").service();
        service.getCosmetics().put("custom", new CosmeticDefinition("custom", "custom", "quit",
            CosmeticType.QUIT_MESSAGE, null, null, null, null, "CUSTOM", "test", 0, 0, 0, 0));
        PluginManager manager = mock(PluginManager.class);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
            CosmeticsListener listener = listener(service);
            when(manager.isPluginEnabled("RoseChat")).thenReturn(true);
            PlayerQuitEvent owned = new PlayerQuitEvent(player, Component.text("original"));
            listener.onQuit(owned);
            assertEquals(Component.text("original"), owned.quitMessage());
            when(manager.isPluginEnabled("RoseChat")).thenReturn(false);
            PlayerQuitEvent hidden = new PlayerQuitEvent(player, (Component) null);
            listener.onQuit(hidden);
            assertNull(hidden.quitMessage());
            PlayerQuitEvent standalone = new PlayerQuitEvent(player, Component.text("original"));
            listener.onQuit(standalone);
            assertEquals(Component.text("CUSTOM"), standalone.quitMessage());
        }
    }

    @Test void cleanupRunsAtMonitorAndMessageMutationDoesNot() throws Exception {
        var cleanup = CosmeticsListener.class.getMethod("onQuitCleanup", PlayerQuitEvent.class);
        assertEquals(EventPriority.MONITOR, cleanup.getAnnotation(EventHandler.class).priority());
        var death = CosmeticsListener.class.getMethod("onDeath", org.bukkit.event.entity.PlayerDeathEvent.class);
        assertNotEquals(EventPriority.MONITOR, death.getAnnotation(EventHandler.class).priority());
    }

    static Player player(UUID id) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn("Tester");
        when(player.hasPermission(anyString())).thenReturn(true);
        when(player.isOnline()).thenReturn(true);
        return player;
    }
}
