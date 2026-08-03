package org.enthusia.tags;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

final class TagConfigV5Migration {
    private TagConfigV5Migration() {
    }

    static boolean migrate(YamlConfiguration config, ConfigMigrator.MigrationReport report) {
        boolean changed = migrateText(config, "line-format", report);
        ConfigurationSection tags = config.getConfigurationSection("tags");
        if (tags != null) {
            for (String id : tags.getKeys(false)) {
                changed |= migrateText(config, "tags." + id + ".display-name", report);
                changed |= migrateText(config, "tags." + id + ".tag-text", report);
            }
        }
        if (config.contains("display-offset")) {
            config.set("display-offset", null);
            report.migrated("config.yml: removed deprecated display-offset; UnlimitedNametags owns positioning");
            changed = true;
        }
        return changed;
    }

    private static boolean migrateText(YamlConfiguration config,
                                       String path,
                                       ConfigMigrator.MigrationReport report) {
        if (!config.isString(path)) {
            return false;
        }
        String current = config.getString(path, "");
        String migrated = TagTextFormat.canonicalMiniMessage(current);
        if (current.equals(migrated)) {
            return false;
        }
        config.set(path, migrated);
        report.migrated("config.yml: converted " + path + " to MiniMessage");
        return true;
    }
}
