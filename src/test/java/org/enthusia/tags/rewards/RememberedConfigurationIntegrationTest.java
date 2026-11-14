package org.enthusia.tags.rewards;

import org.bukkit.plugin.java.JavaPlugin;
import org.enthusia.tags.PerformanceMonitor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RememberedConfigurationIntegrationTest {
    @Test void actualBundledConfigurationLoadsAndWrongSourceFailsClosed(@TempDir Path dir) throws Exception {
        var file = dir.resolve("rewards.yml");
        try (var input = getClass().getClassLoader().getResourceAsStream("rewards.yml")) {
            Files.copy(java.util.Objects.requireNonNull(input), file);
        }
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(dir.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("remembered-test"));
        var tags = mock(org.enthusia.tags.TagService.class);
        var registry = mock(org.enthusia.tags.TagRegistry.class);
        when(tags.getRegistry()).thenReturn(registry);
        when(registry.get(anyString())).thenReturn(mock(org.enthusia.tags.TagDefinition.class));
        var service = new RewardService(plugin, tags, null, new PerformanceMonitor(null));
        var load = RewardService.class.getDeclaredMethod("loadConfig");
        load.setAccessible(true);
        load.invoke(service);
        var reward = service.getRewards().get("last_watch_remembered_2026");
        assertNotNull(reward);
        assertTrue(reward.isHiddenUntilEarned());
        assertTrue(RewardService.validRememberedReward(reward.getCriteria(), reward.getActions()));
        var yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file.toFile());
        yaml.set("rewards.last_watch_remembered_2026.criteria.eligibility.source", "PLACEHOLDER");
        yaml.save(file.toFile());
        load.invoke(service);
        assertFalse(service.getRewards().containsKey("last_watch_remembered_2026"));
    }
}
