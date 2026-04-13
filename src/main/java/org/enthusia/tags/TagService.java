package org.enthusia.tags;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TagService {
    private final JavaPlugin plugin;
    private final Messages messages;
    private final TagRegistry registry = new TagRegistry();
    private final PlaceholderRegistry placeholderRegistry = new PlaceholderRegistry();
    private final PlaceholderApiHook placeholderApiHook = new PlaceholderApiHook();
    private final TagDisplayManager displayManager = new TagDisplayManager();
    private final VanishHook vanishHook = new VanishHook();
    private final Map<UUID, PlayerTagData> cache = new ConcurrentHashMap<>();
    private TagStorage storage;
    private String lineFormat;
    private String guiTitle;
    private String clearItemName;
    private String noTagsItemName;
    private double displayOffset;
    private org.bukkit.scheduler.BukkitTask vanishTask;

    public TagService(JavaPlugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    public void enable() {
        ensureConfigDefaults();
        reloadConfigValues();
        placeholderRegistry.register("player", Player::getName);
        initStorage();
        displayManager.start(plugin);
        startVanishWatcher();
        loadConfigTags();
        for (Player player : Bukkit.getOnlinePlayers()) {
            loadPlayer(player);
        }
    }

    public void disable() {
        displayManager.stop();
        stopVanishWatcher();
        cache.clear();
        if (storage != null) {
            storage.close();
        }
    }

    public void reloadConfigValues() {
        plugin.reloadConfig();
        lineFormat = plugin.getConfig().getString("line-format", "&7[{tag}&7]");
        guiTitle = plugin.getConfig().getString("gui-title", "Your Tags");
        clearItemName = plugin.getConfig().getString("clear-item-name", "&cClear Tag");
        noTagsItemName = plugin.getConfig().getString("no-tags-item-name", "&7No tags yet");
        displayOffset = plugin.getConfig().getDouble("display-offset", 2.1);
    }

    public void reloadAll() {
        ensureConfigDefaults();
        messages.reload();
        reloadConfigValues();
        registry.clear();
        loadConfigTags();
        reopenStorage();
        displayManager.clearAll();
        for (Player player : Bukkit.getOnlinePlayers()) {
            updateDisplay(player);
        }
    }

    public void setDisplayOffset(double offset) {
        displayOffset = offset;
        plugin.getConfig().set("display-offset", offset);
        plugin.saveConfig();
        displayManager.clearAll();
        for (Player player : Bukkit.getOnlinePlayers()) {
            updateDisplay(player);
        }
    }

    public TagRegistry getRegistry() {
        return registry;
    }

    public JavaPlugin getPlugin() {
        return plugin;
    }

    public Messages getMessages() {
        return messages;
    }

    public PlaceholderRegistry getPlaceholderRegistry() {
        return placeholderRegistry;
    }

    public String getGuiTitle() {
        return guiTitle;
    }

    public String getClearItemName() {
        return clearItemName;
    }

    public String getNoTagsItemName() {
        return noTagsItemName;
    }

    public void registerTag(TagDefinition tag) {
        registry.register(tag);
        refreshSelectedForTag(tag.getId());
    }

    public void unregisterTag(String tagId) {
        registry.unregister(tagId);
        refreshSelectedForTag(tagId);
    }

    public boolean createTag(String id, String displayName, String tagText) {
        String key = id.toLowerCase();
        if (registry.get(key) != null) {
            return false;
        }
        plugin.getConfig().set("tags." + key + ".display-name", displayName);
        plugin.getConfig().set("tags." + key + ".tag-text", tagText);
        plugin.getConfig().set("tags." + key + ".icon", "NAME_TAG");
        plugin.saveConfig();
        registerTag(new TagDefinition(key, displayName, tagText, org.bukkit.Material.NAME_TAG, java.util.List.of()));
        return true;
    }

    public boolean updateTagText(String id, String tagText) {
        String key = id.toLowerCase();
        TagDefinition existing = registry.get(key);
        if (existing == null) {
            return false;
        }
        plugin.getConfig().set("tags." + key + ".tag-text", tagText);
        plugin.saveConfig();
        registerTag(new TagDefinition(key, existing.getDisplayName(), tagText, existing.getIcon(), existing.getDescription()));
        return true;
    }

    public void grantTag(UUID playerId, String tagId) {
        PlayerTagData data = cache.computeIfAbsent(playerId, ignored -> new PlayerTagData());
        data.getOwnedTags().add(tagId.toLowerCase());
        runAsync(() -> {
            try {
                storage.grantTag(playerId, tagId.toLowerCase());
            } catch (SQLException ex) {
                plugin.getLogger().warning("Failed to grant tag " + tagId + " to " + playerId + ": " + ex.getMessage());
                handleMovedDb(ex);
            }
        });
    }

    public void revokeTag(UUID playerId, String tagId) {
        PlayerTagData data = cache.computeIfAbsent(playerId, ignored -> new PlayerTagData());
        data.getOwnedTags().remove(tagId.toLowerCase());
        if (tagId.equalsIgnoreCase(data.getSelectedTag())) {
            data.setSelectedTag(null);
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                updateDisplay(player);
            }
        }
        runAsync(() -> {
            try {
                storage.revokeTag(playerId, tagId.toLowerCase());
            } catch (SQLException ex) {
                plugin.getLogger().warning("Failed to revoke tag " + tagId + " from " + playerId + ": " + ex.getMessage());
                handleMovedDb(ex);
            }
        });
    }

    public boolean setSelectedTag(Player player, String tagId) {
        PlayerTagData data = cache.computeIfAbsent(player.getUniqueId(), ignored -> new PlayerTagData());
        if (tagId != null && !data.getOwnedTags().contains(tagId.toLowerCase())) {
            return false;
        }
        data.setSelectedTag(tagId == null ? null : tagId.toLowerCase());
        updateDisplay(player);
        runAsync(() -> {
            try {
                storage.setSelectedTag(player.getUniqueId(), data.getSelectedTag());
            } catch (SQLException ex) {
                plugin.getLogger().warning("Failed to set selected tag for " + player.getUniqueId() + ": " + ex.getMessage());
                handleMovedDb(ex);
            }
        });
        return true;
    }

    public PlayerTagData getPlayerData(UUID playerId) {
        return cache.computeIfAbsent(playerId, ignored -> new PlayerTagData());
    }

    public void loadPlayer(Player player) {
        runAsync(() -> {
            try {
                Set<String> owned = storage.loadOwnedTags(player.getUniqueId());
                String selected = storage.loadSelectedTag(player.getUniqueId());
                PlayerTagData data = new PlayerTagData();
                data.getOwnedTags().addAll(owned);
                data.setSelectedTag(selected == null ? null : selected.toLowerCase());
                cache.put(player.getUniqueId(), data);
                runSync(() -> updateDisplay(player));
            } catch (SQLException ex) {
                plugin.getLogger().warning("Failed to load tags for " + player.getUniqueId() + ": " + ex.getMessage());
            }
        });
    }

    public void unloadPlayer(Player player) {
        cache.remove(player.getUniqueId());
        displayManager.remove(player);
    }

    public void updateDisplay(Player player) {
        PlayerTagData data = cache.get(player.getUniqueId());
        if (data == null) {
            displayManager.update(player, null, displayOffset);
            return;
        }
        boolean vanished = isVanished(player);
        data.setVanished(vanished);
        if (vanished || data.getSelectedTag() == null) {
            displayManager.update(player, null, displayOffset);
            return;
        }
        TagDefinition tag = registry.get(data.getSelectedTag());
        if (tag == null) {
            displayManager.update(player, null, displayOffset);
            return;
        }
        String line = lineFormat.replace("{tag}", tag.getTagText());
        line = placeholderRegistry.apply(player, line);
        line = placeholderApiHook.apply(player, line);
        Component component = LegacyComponentSerializer.legacyAmpersand().deserialize(line);
        displayManager.update(player, component, displayOffset);
    }

    public void removeDisplay(Player player) {
        displayManager.remove(player);
    }

    private boolean isVanished(Player player) {
        return vanishHook.isVanished(player);
    }

    private void initStorage() {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            plugin.getLogger().warning("Failed to create data folder.");
        }
        storage = new TagStorage(new File(dataFolder, "tags.db"));
        try {
            storage.init();
        } catch (SQLException ex) {
            plugin.getLogger().severe("Failed to initialize database: " + ex.getMessage());
        }
    }

    private void reopenStorage() {
        if (storage != null) {
            storage.close();
        }
        initStorage();
    }

    private void loadConfigTags() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("tags");
        if (section == null) {
            return;
        }
        for (String id : section.getKeys(false)) {
            String displayName = section.getString(id + ".display-name", id);
            String tagText = section.getString(id + ".tag-text", displayName);
            String iconName = section.getString(id + ".icon", "NAME_TAG");
            Material icon = Material.matchMaterial(iconName);
            List<String> description = section.getStringList(id + ".description");
            registerTag(new TagDefinition(id, displayName, tagText, icon == null ? Material.NAME_TAG : icon, description));
        }
    }

    private void refreshSelectedForTag(String tagId) {
        String lowered = tagId.toLowerCase();
        for (Map.Entry<UUID, PlayerTagData> entry : cache.entrySet()) {
            if (lowered.equals(entry.getValue().getSelectedTag())) {
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player != null) {
                    updateDisplay(player);
                }
            }
        }
    }

    private void runAsync(Runnable task) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    private void runSync(Runnable task) {
        Bukkit.getScheduler().runTask(plugin, task);
    }

    private void startVanishWatcher() {
        if (vanishTask != null) {
            return;
        }
        vanishTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshVanishStates, 20L, 20L);
    }

    private void stopVanishWatcher() {
        if (vanishTask == null) {
            return;
        }
        vanishTask.cancel();
        vanishTask = null;
    }

    private void refreshVanishStates() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerTagData data = cache.get(player.getUniqueId());
            if (data == null) {
                continue;
            }
            boolean vanished = isVanished(player);
            if (data.isVanished() != vanished) {
                data.setVanished(vanished);
                updateDisplay(player);
            }
        }
    }

    private void handleMovedDb(SQLException ex) {
        if (ex.getMessage() != null && ex.getMessage().contains("SQLITE_READONLY_DBMOVED")) {
            Bukkit.getScheduler().runTask(plugin, this::reopenStorage);
        }
    }

    private void ensureConfigDefaults() {
        plugin.saveDefaultConfig();
        var config = plugin.getConfig();
        try (var stream = plugin.getResource("config.yml")) {
            if (stream == null) {
                return;
            }
            var defaults = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8));
            config.setDefaults(defaults);
            config.options().copyDefaults(true);
            plugin.saveConfig();
        } catch (Exception ignored) {
        }
    }
}
