package org.enthusia.tags;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.enthusia.tags.api.TagVisibilityService;

import java.io.File;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class TagService implements TagVisibilityService {
    private final EnthusiaTagsPlugin plugin;
    private final Messages messages;
    private final PerformanceMonitor performanceMonitor;
    private final TagRegistry registry = new TagRegistry();
    private final PlaceholderRegistry placeholderRegistry = new PlaceholderRegistry();
    private final PlaceholderApiHook placeholderApiHook = new PlaceholderApiHook();
    private final VanishHook vanishHook = new VanishHook();
    private final Map<UUID, PlayerTagData> cache = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Void>> pendingLoads = new ConcurrentHashMap<>();
    private final Map<UUID, Set<Object>> suppressionOwners = new ConcurrentHashMap<>();

    private TagStorage storage;
    private volatile String lineFormat;
    private volatile String guiTitle;
    private volatile String clearItemName;
    private volatile String noTagsItemName;
    private BukkitTask vanishTask;
    private NametagRefreshCoordinator refreshCoordinator;
    private AutoCloseable placeholderExpansion = () -> { };

    public TagService(EnthusiaTagsPlugin plugin, Messages messages, PerformanceMonitor performanceMonitor) {
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
        refreshCoordinator = new NametagRefreshCoordinator(NametagRefreshBridgeFactory.create(plugin));
        placeholderExpansion = PlaceholderExpansionRegistrar.register(plugin, this);
        startVanishWatcher();
        for (Player player : Bukkit.getOnlinePlayers()) {
            preloadPlayer(player.getUniqueId());
            loadPlayer(player);
        }
    }

    public void disable() {
        stopVanishWatcher();
        closePlaceholderExpansion();
        if (refreshCoordinator != null) {
            refreshCoordinator.close();
            refreshCoordinator = null;
        }
        pendingLoads.clear();
        suppressionOwners.clear();
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
                    cache.put(playerId, toPlayerTagData(data));
                }
                return (Void) null;
            }).whenComplete((ignoredResult, ignoredThrowable) -> {
                pendingLoads.remove(playerId);
                scheduleRefresh(playerId, TagRefreshReason.PLAYER_DATA_LOADED);
            }));
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
            requestNametagRefresh(player, TagRefreshReason.PLAYER_DATA_LOADED);
            return;
        }
        pending.thenRun(() -> scheduleRefresh(playerId, TagRefreshReason.PLAYER_DATA_LOADED));
    }

    public void unloadPlayer(Player player) {
        cache.remove(player.getUniqueId());
        pendingLoads.remove(player.getUniqueId());
    }

    public void reloadAll() {
        ensureConfigDefaults();
        reloadConfigValues();
        registry.clear();
        loadConfigTags();
        vanishHook.reload(plugin);
        startVanishWatcher();
        for (Player player : Bukkit.getOnlinePlayers()) {
            requestNametagRefresh(player, TagRefreshReason.RELOADED);
        }
    }

    public void reloadConfigValues() {
        plugin.reloadConfig();
        lineFormat = TagTextFormat.canonicalMiniMessage(
            plugin.getConfig().getString("line-format", "<gray>[{tag}<gray>]"));
        guiTitle = plugin.getConfig().getString("gui-title", "Your Tags");
        clearItemName = plugin.getConfig().getString("clear-item-name", "&cClear Tag");
        noTagsItemName = plugin.getConfig().getString("no-tags-item-name", "&7No tags yet");
    }

    /**
     * Retained for command compatibility. UnlimitedNametags now owns nametag positioning.
     */
    @Deprecated(forRemoval = false)
    public void setDisplayOffset(double ignoredOffset) {
        plugin.getLogger().warning("Ignored legacy display offset " + ignoredOffset
            + "; configure positioning in UnlimitedNametags.");
    }

    public TagRegistry getRegistry() {
        return registry;
    }

    public EnthusiaTagsPlugin getPlugin() {
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
        TagDefinition canonical = new TagDefinition(
            tag.getId(),
            TagTextFormat.canonicalMiniMessage(tag.getDisplayName()),
            TagTextFormat.canonicalMiniMessage(tag.getTagText()),
            tag.getIcon(),
            tag.getDescription()
        );
        registry.register(canonical);
        refreshSelectedForTag(canonical.getId());
    }

    public void unregisterTag(String tagId) {
        registry.unregister(tagId);
        refreshSelectedForTag(tagId);
    }

    public boolean createTag(String id, String displayName, String tagText) {
        String key = id.toLowerCase(Locale.ROOT);
        if (registry.get(key) != null) {
            return false;
        }
        String canonicalDisplay = TagTextFormat.canonicalMiniMessage(displayName);
        String canonicalText = TagTextFormat.canonicalMiniMessage(tagText);
        plugin.getConfig().set("tags." + key + ".display-name", canonicalDisplay);
        plugin.getConfig().set("tags." + key + ".tag-text", canonicalText);
        plugin.getConfig().set("tags." + key + ".icon", "NAME_TAG");
        plugin.saveConfig();
        registerTag(new TagDefinition(key, canonicalDisplay, canonicalText, Material.NAME_TAG, List.of()));
        return true;
    }

    public boolean updateTagText(String id, String tagText) {
        String key = id.toLowerCase(Locale.ROOT);
        TagDefinition existing = registry.get(key);
        if (existing == null) {
            return false;
        }
        String canonicalText = TagTextFormat.canonicalMiniMessage(tagText);
        plugin.getConfig().set("tags." + key + ".tag-text", canonicalText);
        plugin.saveConfig();
        registerTag(new TagDefinition(key, existing.getDisplayName(), canonicalText,
            existing.getIcon(), existing.getDescription()));
        return true;
    }

    public void grantTag(UUID playerId, String tagId) {
        grantTagPersisted(playerId, tagId);
    }

    public CompletableFuture<Boolean> grantTagPersisted(UUID playerId, String tagId) {
        String lowered = tagId.toLowerCase(Locale.ROOT);
        if (registry.get(lowered) == null) {
            return CompletableFuture.completedFuture(false);
        }
        PlayerTagData data = cache.computeIfAbsent(playerId, ignored -> new PlayerTagData());
        boolean alreadyOwned = data.getOwnedTags().contains(lowered);
        data.getOwnedTags().add(lowered);
        scheduleRefresh(playerId, TagRefreshReason.GRANTED);
        return storage.grantTagAsync(playerId, lowered).handle((ignored, throwable) -> {
            if (throwable == null) {
                return true;
            }
            if (!alreadyOwned) {
                data.getOwnedTags().remove(lowered);
                scheduleRefresh(playerId, TagRefreshReason.REVOKED);
            }
            plugin.getLogger().warning("Failed to grant tag " + lowered + " to " + playerId + ": "
                + throwable.getMessage());
            return false;
        });
    }

    public CompletableFuture<TagAdminResult> grantTagAsync(OfflinePlayer player, String tagId) {
        if (player == null) {
            return CompletableFuture.completedFuture(TagAdminResult.PLAYER_NOT_FOUND);
        }
        String lowered = tagId.toLowerCase(Locale.ROOT);
        if (registry.get(lowered) == null) {
            return CompletableFuture.completedFuture(TagAdminResult.UNKNOWN_TAG);
        }
        return loadDataAsync(player.getUniqueId()).thenCompose(data -> {
            data.getOwnedTags().add(lowered);
            cache.put(player.getUniqueId(), data);
            scheduleRefresh(player.getUniqueId(), TagRefreshReason.GRANTED);
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
        String lowered = tagId.toLowerCase(Locale.ROOT);
        PlayerTagData data = cache.computeIfAbsent(playerId, ignored -> new PlayerTagData());
        data.getOwnedTags().remove(lowered);
        if (lowered.equalsIgnoreCase(data.getSelectedTag())) {
            data.setSelectedTag(null);
        }
        scheduleRefresh(playerId, TagRefreshReason.REVOKED);
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
        String lowered = tagId.toLowerCase(Locale.ROOT);
        if (registry.get(lowered) == null) {
            return CompletableFuture.completedFuture(TagAdminResult.UNKNOWN_TAG);
        }
        return loadDataAsync(player.getUniqueId()).thenApply(data -> {
            cache.put(player.getUniqueId(), data);
            revokeTag(player.getUniqueId(), lowered);
            return TagAdminResult.SUCCESS;
        });
    }

    public boolean setSelectedTag(Player player, String tagId) {
        String normalized = tagId == null ? null : tagId.toLowerCase(Locale.ROOT);
        PlayerTagData data = cache.computeIfAbsent(player.getUniqueId(), ignored -> new PlayerTagData());
        if (normalized != null && (registry.get(normalized) == null || !data.getOwnedTags().contains(normalized))) {
            return false;
        }
        data.setSelectedTag(normalized);
        requestNametagRefresh(player, normalized == null ? TagRefreshReason.CLEARED : TagRefreshReason.SELECTED);
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
        String normalized = tagId == null ? null : tagId.toLowerCase(Locale.ROOT);
        if (normalized != null && registry.get(normalized) == null) {
            return CompletableFuture.completedFuture(TagAdminResult.UNKNOWN_TAG);
        }
        return loadDataAsync(player.getUniqueId()).thenCompose(data -> {
            if (normalized != null && !data.getOwnedTags().contains(normalized)) {
                return CompletableFuture.completedFuture(TagAdminResult.TAG_NOT_OWNED);
            }
            data.setSelectedTag(normalized);
            cache.put(player.getUniqueId(), data);
            scheduleRefresh(player.getUniqueId(), normalized == null ? TagRefreshReason.CLEARED : TagRefreshReason.SELECTED);
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

    public TagPlaceholderOutput getPlaceholderOutput(UUID playerId, Player player) {
        return getPlaceholderOutput(playerId, player, player == null ? "" : player.getName());
    }

    TagPlaceholderOutput getPlaceholderOutput(UUID playerId, Player player, String playerName) {
        PlayerTagData data = cache.get(playerId);
        return CachedTagPlaceholderResolver.resolve(data, registry, isSuppressed(playerId),
            lineFormat, playerName, line -> {
                String internal = player == null ? line : placeholderRegistry.apply(player, line);
                return player == null ? internal : placeholderApiHook.apply(player, internal);
            });
    }

    public void requestNametagRefresh(Player player) {
        requestNametagRefresh(player, TagRefreshReason.PLAYER_LIFECYCLE);
    }

    void requestNametagRefresh(Player player, TagRefreshReason reason) {
        if (player == null || !player.isOnline()) {
            return;
        }
        PlayerTagData data = cache.get(player.getUniqueId());
        if (data != null) {
            data.setVanished(vanishHook.isVanished(player));
        }
        if (refreshCoordinator != null) {
            performanceMonitor.increment("tags.refresh");
            refreshCoordinator.request(player.getUniqueId(), reason);
        }
    }

    @Override
    public void suppress(UUID playerId, Object owner) {
        if (playerId == null || owner == null) {
            return;
        }
        suppressionOwners.computeIfAbsent(playerId, unused -> ConcurrentHashMap.newKeySet()).add(owner);
        scheduleRefresh(playerId, TagRefreshReason.SUPPRESSED);
    }

    @Override
    public void unsuppress(UUID playerId, Object owner) {
        if (playerId == null || owner == null) {
            return;
        }
        Set<Object> owners = suppressionOwners.get(playerId);
        if (owners == null) {
            return;
        }
        owners.remove(owner);
        if (!owners.isEmpty()) {
            return;
        }
        suppressionOwners.remove(playerId, owners);
        scheduleRefresh(playerId, TagRefreshReason.UNSUPPRESSED);
    }

    @Override
    public boolean isSuppressed(UUID playerId) {
        Set<Object> owners = suppressionOwners.get(playerId);
        return owners != null && !owners.isEmpty();
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
        state.setSelectedTag(data.selectedTag() == null ? null : data.selectedTag().toLowerCase(Locale.ROOT));
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
            registerTag(new TagDefinition(id, displayName, tagText,
                icon == null ? Material.NAME_TAG : icon, description));
        }
    }

    private void refreshSelectedForTag(String tagId) {
        String lowered = tagId.toLowerCase(Locale.ROOT);
        for (Map.Entry<UUID, PlayerTagData> entry : cache.entrySet()) {
            if (lowered.equals(entry.getValue().getSelectedTag())) {
                scheduleRefresh(entry.getKey(), TagRefreshReason.TAG_DEFINITION_CHANGED);
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
                requestNametagRefresh(player, TagRefreshReason.VISIBILITY_CHANGED);
            }
        }
    }

    private void scheduleRefresh(UUID playerId, TagRefreshReason reason) {
        if (!plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                requestNametagRefresh(player, reason);
            }
        });
    }

    private void closePlaceholderExpansion() {
        try {
            placeholderExpansion.close();
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to unregister the EnthusiaTags placeholder expansion: " + ex.getMessage());
        } finally {
            placeholderExpansion = () -> { };
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
