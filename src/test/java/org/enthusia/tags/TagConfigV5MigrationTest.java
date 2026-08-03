package org.enthusia.tags;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TagConfigV5MigrationTest {
    @Test
    void migratesOnlyTagFormattingAndPreservesCustomTags() {
        YamlConfiguration config = legacyConfig();
        ConfigMigrator.MigrationReport report = new ConfigMigrator.MigrationReport();

        assertTrue(TagConfigV5Migration.migrate(config, report));

        assertTrue(config.getString("line-format").contains("<gray>"));
        assertTrue(config.getString("tags.custom.display-name").contains("39c5ff"));
        assertTrue(config.getString("tags.custom.tag-text").contains("<bold>"));
        assertEquals("CUSTOM_MATERIAL", config.getString("tags.custom.private-admin-field"));
        assertEquals(List.of("&7Keep this legacy description"), config.getStringList("tags.custom.description"));
        assertEquals(17, config.getInt("rewards.untouched"));
        assertFalse(config.contains("display-offset"));
    }

    @Test
    void migrationIsIdempotent() {
        YamlConfiguration config = legacyConfig();
        ConfigMigrator.MigrationReport report = new ConfigMigrator.MigrationReport();
        assertTrue(TagConfigV5Migration.migrate(config, report));
        String once = config.saveToString();

        assertFalse(TagConfigV5Migration.migrate(config, new ConfigMigrator.MigrationReport()));
        assertEquals(once, config.saveToString());
    }

    private static YamlConfiguration legacyConfig() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("line-format", "&7[{tag}&7]");
        config.set("display-offset", 0.06D);
        config.set("tags.custom.display-name", "&#39c5ffCustom");
        config.set("tags.custom.tag-text", "&#39c5ff&lCustom");
        config.set("tags.custom.icon", "NAME_TAG");
        config.set("tags.custom.private-admin-field", "CUSTOM_MATERIAL");
        config.set("tags.custom.description", List.of("&7Keep this legacy description"));
        config.set("rewards.untouched", 17);
        return config;
    }
}
