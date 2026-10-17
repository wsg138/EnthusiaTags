package org.enthusia.tags;

import org.bukkit.configuration.file.YamlConfiguration;

/** The owner requested bold Pumpkin Hunter; retain the configured name, colors and other fields. */
final class HolidayTagMigration {
    private HolidayTagMigration() { }
    static boolean migrate(YamlConfiguration config, ConfigMigrator.MigrationReport report) {
        boolean changed = false;
        for (String field : java.util.List.of("display-name", "tag-text")) {
            String path = "tags.pumpkin_hunter." + field;
            String value = config.getString(path);
            if (value == null || value.isBlank()) continue;
            String canonical = TagTextFormat.canonicalMiniMessage(value);
            if (canonical.toLowerCase(java.util.Locale.ROOT).contains("<bold>")) continue;
            config.set(path, "<bold>" + canonical + "</bold>");
            report.migrated("config.yml: made Pumpkin Hunter " + field + " bold");
            changed = true;
        }
        return changed;
    }
}
