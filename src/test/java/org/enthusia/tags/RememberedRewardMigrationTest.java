package org.enthusia.tags;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import static org.junit.jupiter.api.Assertions.*;

class RememberedRewardMigrationTest {
    @Test void addsMissingRewardButPreservesExistingAdministratorEntry() {
        var defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(
            Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream("rewards.yml")), StandardCharsets.UTF_8));
        var config = new YamlConfiguration();
        var report = new ConfigMigrator.MigrationReport();
        assertTrue(RememberedRewardMigration.addIfMissing(config, defaults, report));
        assertEquals("last_watch_halloween_2026", config.getString("rewards.last_watch_remembered_2026.criteria.eligibility.key"));
        config.set("rewards.last_watch_remembered_2026.name", "custom");
        assertFalse(RememberedRewardMigration.addIfMissing(config, defaults, report));
        assertEquals("custom", config.getString("rewards.last_watch_remembered_2026.name"));
    }
}
