package org.enthusia.tags;

import org.bukkit.configuration.file.YamlConfiguration;
import org.enthusia.tags.rewards.RewardService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RememberedStartupIntegrationTest {
    @Test void firstInstallRegistersTagBeforeRewardConfigurationIsLoaded(@TempDir Path dir) throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream("rewards.yml")) {
            Files.copy(java.util.Objects.requireNonNull(input), dir.resolve("rewards.yml"));
        }
        var plugin = mock(EnthusiaTagsPlugin.class);
        var config = new YamlConfiguration();
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(dir.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("remembered-startup-test"));
        assertTrue(HolidayTagCatalog.ensureInstalled(plugin));
        var tags = new TagService(plugin, null, new PerformanceMonitor(null));
        var loadTags = TagService.class.getDeclaredMethod("loadConfigTags");
        loadTags.setAccessible(true);
        loadTags.invoke(tags);
        assertNotNull(tags.getRegistry().get("remembered"));
        var rewards = new RewardService(plugin, tags, null, new PerformanceMonitor(null));
        var loadRewards = RewardService.class.getDeclaredMethod("loadConfig");
        loadRewards.setAccessible(true);
        loadRewards.invoke(rewards);
        var remembered = rewards.getRewards().get("last_watch_remembered_2026");
        assertNotNull(remembered);
        assertTrue(remembered.isHiddenUntilEarned());
        assertTrue(remembered.getActions().getFirst().isValid());
    }
}
