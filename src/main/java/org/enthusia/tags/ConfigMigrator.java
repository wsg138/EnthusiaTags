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
    public static final int CURRENT_CONFIG_VERSION = 4;
    private static final int REWARDS_CONFIG_VERSION = 4;
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
            int targetVersion = targetVersion(resourceName, defaults);
            boolean changed = false;
            if (existingVersion < targetVersion) {
                backup(file, resourceName, report);
                changed |= migrateKnownValues(resourceName, config, existingVersion, report);
                config.set("config-version", targetVersion);
                report.migrated(resourceName + ": config-version " + existingVersion + " -> " + targetVersion);
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

    private int targetVersion(String resourceName, YamlConfiguration defaults) {
        if ("config.yml".equals(resourceName)) {
            return CURRENT_CONFIG_VERSION;
        }
        if ("rewards.yml".equals(resourceName)) {
            return REWARDS_CONFIG_VERSION;
        }
        return defaults.getInt("config-version", CURRENT_CONFIG_VERSION);
    }

    private boolean migrateKnownValues(String resourceName,
                                       YamlConfiguration config,
                                       int existingVersion,
                                       MigrationReport report) {
        boolean changed = false;
        if ("config.yml".equals(resourceName)) {
            if (existingVersion < 3) {
                changed |= migrateInvisibilityDefault(config, report);
            }
            if (existingVersion < 4) {
                changed |= removeDeprecated(config, "daily.animation.default-player-preference", report);
                changed |= removeDeprecated(config, "daily.animation.duration-ticks", report);
            }
        }
        if ("rewards.yml".equals(resourceName) && existingVersion < REWARDS_CONFIG_VERSION) {
            changed |= replaceRewardMoneyAmount(config, "rewards.payday.rewards.r1.amount", 500.0, 200.0, report);
            changed |= replaceRewardMoneyAmount(config, "rewards.diamond_hands.rewards.r2.amount", 500.0, 200.0, report);
            changed |= replaceExact(config, "rewards.diamond_hands.description",
                List.of("&7Obtain 64 diamonds."), List.of("&7Mine 64 diamond or deepslate diamond ore."), report);
            changed |= replaceExact(config, "rewards.diamond_hands.criteria.c1.key",
                "diamonds_obtained", "diamond_ore_mined", report);
            changed |= replaceExact(config, "rewards.fear_me.criteria.c1.type",
                "DEATHS_TOTAL", "CUSTOM_COUNTER", report);
            if ("CUSTOM_COUNTER".equals(config.getString("rewards.fear_me.criteria.c1.type"))
                && !config.contains("rewards.fear_me.criteria.c1.key")) {
                config.set("rewards.fear_me.criteria.c1.key", "pvp_deaths");
                report.migrated("rewards.yml: corrected default fear_me counter");
                changed = true;
            }
            changed |= replaceExact(config, "rewards.i_swear_it_worked.description",
                List.of("&7Die to your own explosion."), List.of("&7Die to an explosion."), report);
            if ("COMMAND".equalsIgnoreCase(config.getString("rewards.starter_pack.rewards.r1.type"))
                && "give {player} golden_apple 2".equals(config.getString("rewards.starter_pack.rewards.r1.id"))
                && "Starter Pack (2 Golden Apples)".equals(config.getString("rewards.starter_pack.rewards.r1.label"))) {
                config.set("rewards.starter_pack.rewards.r1.type", "ITEM");
                config.set("rewards.starter_pack.rewards.r1.id", null);
                config.set("rewards.starter_pack.rewards.r1.material", "GOLDEN_APPLE");
                config.set("rewards.starter_pack.rewards.r1.amount", 2);
                report.migrated("rewards.yml: migrated unchanged starter_pack command to ITEM");
                changed = true;
            }
        }
        return changed;
    }

    private boolean replaceExact(YamlConfiguration config, String path, Object oldValue,
                                 Object newValue, MigrationReport report) {
        if (!java.util.Objects.equals(config.get(path), oldValue)) return false;
        config.set(path, newValue);
        report.migrated("rewards.yml: corrected unchanged bundled value " + path);
        return true;
    }

    private boolean migrateInvisibilityDefault(YamlConfiguration config, MigrationReport report) {
        String path = "vanish.treat-invisibility-effect-as-vanish";
        if (!config.contains(path) || config.getBoolean(path)) {
            return false;
        }
        config.set(path, true);
        report.migrated("config.yml: " + path + " false -> true");
        return true;
    }

    private boolean removeDeprecated(YamlConfiguration config, String path, MigrationReport report) {
        if (!config.contains(path)) return false;
        config.set(path, null);
        report.migrated("config.yml: removed deprecated key " + path);
        return true;
    }

    private boolean replaceRewardMoneyAmount(YamlConfiguration config,
                                             String path,
                                             double oldAmount,
                                             double newAmount,
                                             MigrationReport report) {
        if (!config.contains(path) || Double.compare(config.getDouble(path), oldAmount) != 0) {
            return false;
        }
        config.set(path, newAmount);
        report.migrated("rewards.yml: " + path + " " + oldAmount + " -> " + newAmount);
        return true;
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
                if (isAdministratorCollection(resourceName, childPath)) {
                    continue;
                }
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

    private boolean isAdministratorCollection(String resourceName, String path) {
        if ("rewards.yml".equals(resourceName) && path.startsWith("rewards.")) return true;
        return "config.yml".equals(resourceName) && path.startsWith("tags.");
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

        public List<String> backups() {
            return List.copyOf(backups);
        }

        public List<String> added() {
            return List.copyOf(added);
        }

        public List<String> migrated() {
            return List.copyOf(migrated);
        }

        public List<String> warnings() {
            return List.copyOf(warnings);
        }

        public boolean hasChanges() {
            return !backups.isEmpty() || !added.isEmpty() || !migrated.isEmpty();
        }
    }
}
