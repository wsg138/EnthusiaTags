package org.enthusia.tags;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class Messages {
    private final JavaPlugin plugin;
    private FileConfiguration config;

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(file);
        InputStream defaultsStream = plugin.getResource("messages.yml");
        if (defaultsStream != null) {
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                new InputStreamReader(defaultsStream, StandardCharsets.UTF_8));
            config.setDefaults(defaults);
            config.options().copyDefaults(true);
            try {
                config.save(file);
            } catch (Exception ignored) {
            }
        }
    }

    public String get(String key) {
        if (config == null) {
            return key;
        }
        return config.getString(key, key);
    }
}
