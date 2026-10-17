package org.enthusia.tags.rewards;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.enthusia.tags.PerformanceMonitor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VerifiedMenuProgressTest {
    @Test void unloadedAndMissingProviderReadingsAreEmptyWithoutChangingSavedData(@TempDir Path folder) throws Exception {
        var plugin = mock(JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        var player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        var storage = new RewardStorage(folder.resolve("menu.db").toFile(), new PerformanceMonitor(null));
        try {
            storage.init();
            var original = new RewardStorage.StoredRewardData(Set.of("claimed"), Map.of("custom_progress", 42L), Map.of(), 3L);
            storage.saveNow(id, original);
            var service = spy(new RewardService(plugin, null, null, new PerformanceMonitor(null), storage));
            doReturn(true).when(service).isAvailable();
            var config = new YamlConfiguration();
            config.set("c.type", "CUSTOM_COUNTER"); config.set("c.key", "custom_progress"); config.set("c.amount", 100);
            var criterion = service.loadCriteria(config).getFirst();
            var snapshot = new RewardService.ProgressSnapshot(0, new HashMap<>());
            assertTrue(service.getVerifiedMenuProgress(player, criterion, snapshot).isEmpty());
            service.preloadPlayerBlocking(id);
            assertEquals(42, service.getVerifiedMenuProgress(player, criterion, snapshot).orElseThrow());
            config.set("c.type", "PLAYTIME_ACTIVE_MINUTES");
            var missingProvider = service.loadCriteria(config).getFirst();
            assertTrue(service.getVerifiedMenuProgress(player, missingProvider, snapshot).isEmpty());
            assertTrue(service.getVerifiedMenuProgress(player, null, snapshot).isEmpty());
            assertEquals(original, storage.loadNow(id));
        } finally { storage.close(); }
    }
}
