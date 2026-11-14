package org.enthusia.tags;

import org.bukkit.configuration.file.YamlConfiguration;

/** Adds only the new event reward, leaving existing administrator entries untouched. */
final class RememberedRewardMigration {
    private static final String PATH = "rewards.last_watch_remembered_2026";
    private RememberedRewardMigration() { }

    static boolean addIfMissing(YamlConfiguration config, YamlConfiguration defaults, ConfigMigrator.MigrationReport report) {
        if (config.contains(PATH) || !defaults.isConfigurationSection(PATH)) return false;
        config.set(PATH, defaults.get(PATH));
        report.added("rewards.yml: added Last Watch reward");
        return true;
    }
}
