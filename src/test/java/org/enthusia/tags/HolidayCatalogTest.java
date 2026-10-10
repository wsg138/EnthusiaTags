package org.enthusia.tags;

import org.bukkit.configuration.file.YamlConfiguration;
import org.enthusia.tags.rewards.RewardsConfig;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class HolidayCatalogTest {
    @Test void defaultsNestHolidaysAndIncludeExistingEventTags() {
        var config = resource("rewards.yml");
        var categories = RewardsConfig.from(config).categories();
        assertNull(categories.get("holidays").parent());
        assertEquals("holidays", categories.get("halloween").parent());
        assertEquals("holidays", categories.get("christmas").parent());
        assertTrue(categories.get("halloween").tags().contains("pumpkin_king"));
        assertTrue(categories.get("christmas").tags().contains("advent_keeper"));
        var ids = HolidayTagCatalog.tags().stream().map(HolidayTagCatalog.HolidayTag::id).toList();
        for (var category : categories.values()) {
            for (String id : category.tags()) assertTrue(ids.contains(id), id);
        }
    }
    @Test void migrationMakesHunterBoldAndPreservesCustomFields() {
        var config = new YamlConfiguration();
        config.set("tags.pumpkin_hunter.tag-text", "<gradient:#ff7b00:#ffb347>Pumpkin Hunter</gradient>");
        config.set("tags.pumpkin_hunter.display-name", "<red>My Hunter");
        config.set("tags.pumpkin_king.tag-text", "custom crown");
        assertTrue(HolidayTagMigration.migrate(config, new ConfigMigrator.MigrationReport()));
        assertEquals("<bold><red>My Hunter</bold>", config.getString("tags.pumpkin_hunter.display-name"));
        assertEquals("custom crown", config.getString("tags.pumpkin_king.tag-text"));
        assertFalse(HolidayTagMigration.migrate(config, new ConfigMigrator.MigrationReport()));
    }
    @Test void cyclesAndMissingParentsDoNotHideCategories() {
        var config = new YamlConfiguration();
        config.set("categories.a.parent", "b");
        config.set("categories.b.parent", "a");
        assertThrows(IllegalArgumentException.class, () -> RewardsConfig.from(config));
        config.set("categories.b.parent", "missing");
        assertThrows(IllegalArgumentException.class, () -> RewardsConfig.from(config));
    }
    private static YamlConfiguration resource(String name) {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
            java.util.Objects.requireNonNull(HolidayCatalogTest.class.getClassLoader().getResourceAsStream(name)), StandardCharsets.UTF_8));
    }
}
