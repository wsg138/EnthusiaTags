package org.enthusia.tags;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class TagService {
    private final JavaPlugin plugin;
    private final Messages messages;
    private final PerformanceMonitor performanceMonitor;
    private final TagRegistry registry = new TagRegistry();
    private final PlaceholderRegistry placeholderRegistry = new PlaceholderRegistry();
    private final PlaceholderApiHook placeholderApiHook = new PlaceholderApiHook();
    private final TagDisplayManager displayManager = new TagDisplayManager();
    private final VanishHook vanishHook = new VanishHook();
    private final Map<UUID, PlayerTagData> cache = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Void>> pendingLoads = new ConcurrentHashMap<>();

    private TagStorage storage;
    private String lineFormat;
    private String guiTitle;
    private String clearItemName;
    private String noTagsItemName;
    private double displayOffset;
    private BukkitTask vanishTask;

    public TagService(JavaPlugin plugin, Messages messages, PerformanceMonitor performanceMonitor) {
        this.plugin = plugin;
        this.messages = messages;
        this.performanceMonitor = performanceMonitor;
    }

    public void enable() {
        ensureConfigDefaults();
        reloadConfigValues();
        placeholderRegistry.register("player", Player::getName);
        initStorage();
        loadConfigTags();
        vanishHook.reload(plugin);
        displayManager.start(plugin);
        startVanishWatcher();
        for (Player player : Bukkit.getOnlinePlayers()) {
            preloadPlayer(player.getUniqueId());
            loadPlayer(player);
        }
    }

    public void disable() {
        stopVanishWatcher();
        displayManager.stop();
        pendingLoads.clear();
        cache.clear();
        if (storage != null) {
            storage.close();
        }
    }

    public void preloadPlayer(UUID playerId) {
        if (cache.containsKey(playerId)) {
            return;
        }
        pendingLoads.computeIfAbsent(playerId, ignored ->
            storage.loadAsync(playerId).handle((data, throwable) -> {
                if (throwable != null) {
                    plugin.getLogger().warning("Failed to load tags for " + playerId + ": " + throwable.getMessage());
                } else {
                    PlayerTagData state = toPlayerTagData(data);
                    cache.put(playerId, state);
                }
                return (Void) null;
            }).whenComplete((ignoredResult, ignoredThrowable) -> pendingLoads.remove(playerId)));
    }

    public void preloadPlayerBlocking(UUID playerId) {
        if (cache.containsKey(playerId)) {
            return;
        }
        try {
            cache.put(playerId, toPlayerTagData(storage.loadNow(playerId)));
        } catch (SQLException ex) {
            plugin.getLogger().warning("Failed to preload tags for " + playerId + ": " + ex.getMessage());
        }
    }

    public void loadPlayer(Player player) {
        UUID playerId = player.getUniqueId();
        preloadPlayer(playerId);
        CompletableFuture<Void> pending = pendingLoads.get(playerId);
        if (pending == null) {
            updateDisplay(player);
            return;
        }
        pending.thenRun(() -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                updateDisplay(player);
            }
        }));
    }

    public void unloadPlayer(Player player) {
        cache.remove(player.getUniqueId());
        pendingLoads.remove(player.getUniqueId());
        displayManager.remove(player);
    }

    public void reloadAll() {
        ensureConfigDefaults();
        reloadConfigValues();
        registry.clear();
        loadConfigTags();
        vanishHook.reload(plugin);
        startVanishWatcher();
        for (Player player : Bukkit.getOnlinePlayers()) {
            updateDisplay(player);
        }
    }

    public void reloadConfigValues() {
        plugin.reloadConfig();
        lineFormat = plugin.getConfig().getString("line-format", "&7[{tag}&7]");
        guiTitle = plugin.getConfig().getString("gui-title", "Your Tags");
        clearItemName = plugin.getConfig().getString("clear-item-name", "&cClear Tag");
        noTagsItemName = plugin.getConfig().getString("no-tags-item-name", "&7No tags yet");
        displayOffset = plugin.getConfig().getDouble("display-offset", 0.06D);
    }

    public void setDisplayOffset(double offset) {
        displayOffset = offset;
        plugin.getConfig().set("display-offset", offset);
        plugin.saveConfig();
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
        registerTag(new TagDefinition(key, displayName, tagText, Material.NAME_TAG, List.of()));
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
        String lowered = tagId.toLowerCase();
        PlayerTagData data = cache.computeIfAbsent(playerId, ignored -> new PlayerTagData());
        data.getOwnedTags().add(lowered);
        storage.grantTagAsync(playerId, lowered).exceptionally(throwable -> {
            plugin.getLogger().warning("Failed to grant tag " + lowered + " to " + playerId + ": " + throwable.getMessage());
            return null;
        });
    }

    public CompletableFuture<TagAdminResult> grantTagAsync(OfflinePlayer player, String tagId) {
        if (player == null) {
            return CompletableFuture.completedFuture(TagAdminResult.PLAYER_NOT_FOUND);
        }
        if (registry.get(tagId) == null) {
            return CompletableFuture.completedFuture(TagAdminResult.UNKNOWN_TAG);
        }
        String lowered = tagId.toLowerCase();
        return loadDataAsync(player.getUniqueId()).thenCompose(data -> {
            data.getOwnedTags().add(lowered);
            cache.put(player.getUniqueId(), data);
            Player online = player.getPlayer();
            if (online != null) {
                Bukkit.getScheduler().runTask(plugin, () -> updateDisplay(online));
            }
            return storage.grantTagAsync(player.getUniqueId(), lowered).handle((ignored, throwable) -> {
                if (throwable != null) {
                    plugin.getLogger().warning("Failed to grant tag " + lowered + " to " + player.getUniqueId() + ": "
                        + throwable.getMessage());
                }
                return TagAdminResult.SUCCESS;
            });
        });
    }

    public void revokeTag(UUID playerId, String tagId) {
        String lowered = tagId.toLowerCase();
        PlayerTagData data = cache.computeIfAbsent(playerId, ignored -> new PlayerTagData());
        data.getOwnedTags().remove(lowered);
        if (lowered.equalsIgnoreCase(data.getSelectedTag())) {
            data.setSelectedTag(null);
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                updateDisplay(player);
            }
        }
        storage.revokeTagAsync(playerId, lowered).exceptionally(throwable -> {
            plugin.getLogger().warning("Failed to revoke tag " + lowered + " from " + playerId + ": " + throwable.getMessage());
            return null;
        });
        storage.setSelectedTagAsync(playerId, data.getSelectedTag()).exceptionally(throwable -> null);
    }

    public CompletableFuture<TagAdminResult> revokeTagAsync(OfflinePlayer player, String tagId) {
        if (player == null) {
            return CompletableFuture.completedFuture(TagAdminResult.PLAYER_NOT_FOUND);
        }
        String lowered = tagId.toLowerCase();
        if (registry.get(lowered) == null) {
            return CompletableFuture.completedFuture(TagAdminResult.UNKNOWN_TAG);
        }
        return loadDataAsync(player.getUniqueId()).thenApply(data -> {
            data.getOwnedTags().remove(lowered);
            if (lowered.equalsIgnoreCase(data.getSelectedTag())) {
                data.setSelectedTag(null);
            }
            cache.put(player.getUniqueId(), data);
            revokeTag(player.getUniqueId(), lowered);
            return TagAdminResult.SUCCESS;
        });
    }

    public boolean setSelectedTag(Player player, String tagId) {
        String normalized = tagId == null ? null : tagId.toLowerCase();
        PlayerTagData data = cache.computeIfAbsent(player.getUniqueId(), ignored -> new PlayerTagData());
        if (normalized != null && !data.getOwnedTags().contains(normalized)) {
            return false;
        }
        data.setSelectedTag(normalized);
        updateDisplay(player);
        storage.setSelectedTagAsync(player.getUniqueId(), normalized).exceptionally(throwable -> {
            plugin.getLogger().warning("Failed to set selected tag for " + player.getUniqueId() + ": " + throwable.getMessage());
            return null;
        });
        return true;
    }

    public CompletableFuture<TagAdminResult> setSelectedTagAsync(OfflinePlayer player, String tagId) {
        if (player == null) {
            return CompletableFuture.completedFuture(TagAdminResult.PLAYER_NOT_FOUND);
        }
        String normalized = tagId == null ? null : tagId.toLowerCase();
        if (normalized != null && registry.get(normalized) == null) {
            return CompletableFuture.completedFuture(TagAdminResult.UNKNOWN_TAG);
        }
        return loadDataAsync(player.getUniqueId()).thenCompose(data -> {
            if (normalized != null && !data.getOwnedTags().contains(normalized)) {
                return CompletableFuture.completedFuture(TagAdminResult.TAG_NOT_OWNED);
            }
            data.setSelectedTag(normalized);
            cache.put(player.getUniqueId(), data);
            Player online = player.getPlayer();
            if (online != null) {
                Bukkit.getScheduler().runTask(plugin, () -> updateDisplay(online));
            }
            return storage.setSelectedTagAsync(player.getUniqueId(), normalized).handle((ignored, throwable) -> {
                if (throwable != null) {
                    plugin.getLogger().warning("Failed to set selected tag for " + player.getUniqueId() + ": "
                        + throwable.getMessage());
                }
                return TagAdminResult.SUCCESS;
            });
        });
    }

    public CompletableFuture<PlayerTagData> getPlayerDataAsync(UUID playerId) {
        return loadDataAsync(playerId);
    }

    public PlayerTagData getPlayerData(UUID playerId) {
        return cache.computeIfAbsent(playerId, ignored -> new PlayerTagData());
    }

    public void updateDisplay(Player player) {
        if (!player.isOnline()) {
            displayManager.remove(player);
            return;
        }
        PlayerTagData data = cache.get(player.getUniqueId());
        if (data == null) {
            displayManager.update(player, null, displayOffset);
            return;
        }
        boolean vanished = vanishHook.isVanished(player);
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
        performanceMonitor.increment("tags.refresh");
        displayManager.update(player, component, displayOffset);
    }

    public void removeDisplay(Player player) {
        displayManager.remove(player);
    }

    private CompletableFuture<PlayerTagData> loadDataAsync(UUID playerId) {
        PlayerTagData cachedData = cache.get(playerId);
        if (cachedData != null) {
            return CompletableFuture.completedFuture(cachedData);
        }
        return storage.loadAsync(playerId).thenApply(this::toPlayerTagData);
    }

    private PlayerTagData toPlayerTagData(TagStorage.StoredTagData data) {
        PlayerTagData state = new PlayerTagData();
        state.getOwnedTags().addAll(data.ownedTags());
        state.setSelectedTag(data.selectedTag() == null ? null : data.selectedTag().toLowerCase());
        return state;
    }

    private void initStorage() {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            plugin.getLogger().warning("Failed to create data folder.");
        }
        storage = new TagStorage(new File(dataFolder, "tags.db"), performanceMonitor);
        try {
            storage.init();
        } catch (SQLException ex) {
            plugin.getLogger().severe("Failed to initialize tag database: " + ex.getMessage());
        }
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

    private void startVanishWatcher() {
        stopVanishWatcher();
        long interval = Math.max(20L, plugin.getConfig().getLong("performance.tag-visibility-refresh-ticks", 100L));
        vanishTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshVanishStates, interval, interval);
    }

    private void stopVanishWatcher() {
        if (vanishTask != null) {
            vanishTask.cancel();
            vanishTask = null;
        }
    }

    private void refreshVanishStates() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerTagData data = cache.get(player.getUniqueId());
            if (data == null) {
                continue;
            }
            boolean vanished = vanishHook.isVanished(player);
            if (data.isVanished() != vanished) {
                data.setVanished(vanished);
                updateDisplay(player);
            }
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
