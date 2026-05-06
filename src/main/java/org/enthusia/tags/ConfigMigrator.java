package org.enthusia.tags;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class ConfigMigrator {
    public static final int CURRENT_CONFIG_VERSION = 2;
    private static final DateTimeFormatter BACKUP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final JavaPlugin plugin;

    public ConfigMigrator(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public MigrationReport migrateAll() {
        MigrationReport report = new MigrationReport();
        migrate("config.yml", report);
        migrate("messages.yml", report);
        migrate("rewards.yml", report);
        migrate("cosmetics.yml", report);
        return report;
    }

    public void migrate(String resourceName, MigrationReport report) {
        try {
            File file = new File(plugin.getDataFolder(), resourceName);
            if (!file.exists()) {
                plugin.saveResource(resourceName, false);
                report.added(resourceName + ": created from bundled defaults");
            }
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            YamlConfiguration defaults;
            try (var stream = plugin.getResource(resourceName)) {
                if (stream == null) {
                    report.warning(resourceName + ": bundled defaults missing");
                    return;
                }
                defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
            }

            int existingVersion = config.getInt("config-version", 0);
            boolean changed = false;
            if (existingVersion < CURRENT_CONFIG_VERSION) {
                backup(file, resourceName, report);
                config.set("config-version", CURRENT_CONFIG_VERSION);
                report.migrated(resourceName + ": config-version " + existingVersion + " -> " + CURRENT_CONFIG_VERSION);
                changed = true;
            }
            changed |= copyMissing(defaults, config, "", resourceName, report);
            if (changed) {
                config.save(file);
            }
        } catch (Exception ex) {
            report.warning(resourceName + ": migration failed: " + ex.getMessage());
        }
    }

    private boolean copyMissing(ConfigurationSection defaults,
                                ConfigurationSection target,
                                String path,
                                String resourceName,
                                MigrationReport report) {
        boolean changed = false;
        for (String key : defaults.getKeys(false)) {
            String childPath = path.isBlank() ? key : path + "." + key;
            if ("config-version".equals(childPath)) {
                continue;
            }
            if (!target.contains(childPath)) {
                target.set(childPath, defaults.get(childPath));
                report.added(resourceName + ": added missing key " + childPath);
                changed = true;
                continue;
            }
            ConfigurationSection defaultChild = defaults.getConfigurationSection(childPath);
            ConfigurationSection targetChild = target.getConfigurationSection(childPath);
            if (defaultChild != null && targetChild != null) {
                changed |= copyMissing(defaults, target, childPath, resourceName, report);
            }
        }
        return changed;
    }

    private void backup(File file, String resourceName, MigrationReport report) throws Exception {
        File backupDir = new File(plugin.getDataFolder(), "backups");
        if (!backupDir.exists() && !backupDir.mkdirs()) {
            report.warning(resourceName + ": failed to create backup directory");
            return;
        }
        String stamp = LocalDateTime.now().format(BACKUP_FORMAT);
        File backup = new File(backupDir, resourceName + "." + stamp + ".bak");
        Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
        report.backup(resourceName + ": backup written to " + backup.getName());
    }

    public static final class MigrationReport {
        private final List<String> backups = new ArrayList<>();
        private final List<String> added = new ArrayList<>();
        private final List<String> migrated = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();

        void backup(String line) {
            backups.add(line);
        }

        void added(String line) {
            added.add(line);
        }

        void migrated(String line) {
            migrated.add(line);
        }

        void warning(String line) {
            warnings.add(line);
        }

        public List<String> summaryLines() {
            List<String> lines = new ArrayList<>();
            lines.add("config backups=" + backups.size() + ", migrated=" + migrated.size()
                + ", added=" + added.size() + ", warnings=" + warnings.size());
            lines.addAll(warnings);
            return lines;
        }
    }
}
