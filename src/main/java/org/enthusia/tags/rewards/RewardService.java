package org.enthusia.tags.rewards;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.enthusia.tags.IntegrationStatus;
import org.enthusia.tags.Messages;
import org.enthusia.tags.TagDefinition;
import org.enthusia.tags.TagService;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RewardService {
    private static final Pattern HOURS_PATTERN = Pattern.compile("(?i)(\\d+)\\s*h");
    private static final Pattern MINUTES_PATTERN = Pattern.compile("(?i)(\\d+)\\s*m");
    private static final Pattern SECONDS_PATTERN = Pattern.compile("(?i)(\\d+)\\s*s");

    private final JavaPlugin plugin;
    private final TagService tagService;
    private final Messages messages;
    private final VaultHook vaultHook = new VaultHook();
    private final PlaytimeHook playtimeHook = new PlaytimeHook();
    private final Map<String, RewardDefinition> rewards = new LinkedHashMap<>();
    private final Map<UUID, RewardPlayerState> playerStates = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Void>> pendingLoads = new ConcurrentHashMap<>();
    private final IntegrationStatus integrationStatus = new IntegrationStatus();

    private RewardStorage storage;
    private RewardsConfig config;
    private BukkitTask flushTask;
    private Plugin baltopPlugin;

    public RewardService(JavaPlugin plugin, TagService tagService, Messages messages) {
        this.plugin = plugin;
        this.tagService = tagService;
        this.messages = messages;
    }

    public void enable() {
        ensureDefaults();
        initStorage();
        reload();
        startFlushTask();
    }

    public void disable() {
        stopFlushTask();
        flushAllBlocking();
        pendingLoads.clear();
        playerStates.clear();
        if (storage != null) {
            storage.close();
        }
    }

    public void reload() {
        ensureDefaults();
        rewards.clear();
        loadConfig();
        refreshIntegrations();
        startFlushTask();
        for (Player player : Bukkit.getOnlinePlayers()) {
            preloadPlayer(player.getUniqueId());
        }
    }

    public RewardsConfig getConfig() {
        return config;
    }

    public Map<String, RewardDefinition> getRewards() {
        return rewards;
    }

    public List<String> getStaffWarnings() {
        return integrationStatus.warnings();
    }

    public void preloadPlayer(UUID playerId) {
        RewardPlayerState existing = playerStates.get(playerId);
        if (existing != null && existing.isLoaded()) {
            return;
        }
        pendingLoads.computeIfAbsent(playerId, ignored ->
            storage.loadAsync(playerId).handle((data, throwable) -> {
                if (throwable != null) {
                    plugin.getLogger().warning("Failed to load rewards for " + playerId + ": " + throwable.getMessage());
                } else {
                    RewardPlayerState state = playerStates.computeIfAbsent(playerId, key -> new RewardPlayerState());
                    hydrateState(state, data);
                }
                return (Void) null;
            }).whenComplete((ignoredResult, ignoredThrowable) -> pendingLoads.remove(playerId)));
    }

    public void preloadPlayerBlocking(UUID playerId) {
        RewardPlayerState existing = playerStates.get(playerId);
        if (existing != null && existing.isLoaded()) {
            return;
        }
        try {
            RewardPlayerState state = playerStates.computeIfAbsent(playerId, key -> new RewardPlayerState());
            hydrateState(state, storage.loadNow(playerId));
        } catch (SQLException ex) {
            plugin.getLogger().warning("Failed to preload rewards for " + playerId + ": " + ex.getMessage());
        }
    }

    public void loadPlayer(Player player) {
        preloadPlayer(player.getUniqueId());
    }

    public void unloadPlayer(Player player) {
        UUID playerId = player.getUniqueId();
        flushPlayerBlocking(playerId);
        pendingLoads.remove(playerId);
        playerStates.remove(playerId);
    }

    public boolean isClaimed(UUID playerId, String rewardId) {
        RewardPlayerState state = getLoadedState(playerId);
        return state != null && state.claimedRewards().contains(rewardId.toLowerCase());
    }

    public void claim(Player player, RewardDefinition reward) {
        RewardPlayerState state = getOrCreateState(player.getUniqueId());
        if (!state.isLoaded()) {
            return;
        }
        String rewardId = reward.getId().toLowerCase();
        if (state.claimedRewards().contains(rewardId) || !isComplete(player, reward)) {
            return;
        }

        for (RewardAction action : reward.getActions()) {
            switch (action.getType()) {
                case TAG -> tagService.grantTag(player.getUniqueId(), action.getValue());
                case MONEY -> vaultHook.deposit(player, action.getAmount());
                case COMMAND -> {
                    String command = action.getValue()
                        .replace("{player}", player.getName())
                        .replace("%player%", player.getName());
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                }
            }
        }

        state.claimedRewards().add(rewardId);
        state.setDirty(true);
        flushPlayerAsync(player.getUniqueId(), state);
    }

    public boolean isComplete(Player player, RewardDefinition reward) {
        if (!areActionsAvailable(reward)) {
            return false;
        }
        for (RewardCriterion criterion : reward.getCriteria()) {
            if (!isCriterionAvailable(criterion.getType())) {
                return false;
            }
            if (getProgress(player, criterion) < criterion.getAmount()) {
                return false;
            }
        }
        return true;
    }

    public long getProgress(Player player, RewardCriterion criterion) {
        return switch (criterion.getType()) {
            case PLAYTIME_ACTIVE_MINUTES -> getPlaytimeMinutes(player, RewardCriterionType.PLAYTIME_ACTIVE_MINUTES,
                config.playtimeActivePlaceholder());
            case PLAYTIME_AFK_MINUTES -> getPlaytimeMinutes(player, RewardCriterionType.PLAYTIME_AFK_MINUTES,
                config.playtimeAfkPlaceholder());
            case PLAYTIME_TOTAL_MINUTES -> getPlaytimeMinutes(player, RewardCriterionType.PLAYTIME_TOTAL_MINUTES,
                config.playtimeTotalPlaceholder());
            case PLAYTIME_CONSECUTIVE_ACTIVE_MINUTES -> getCounter(player.getUniqueId(), "consecutive_active");
            case UNDERGROUND_ACTIVE_MINUTES -> getCounter(player.getUniqueId(), "underground_active");
            case KILLS_TOTAL -> player.getStatistic(Statistic.PLAYER_KILLS);
            case DEATHS_TOTAL -> player.getStatistic(Statistic.DEATHS);
            case BLOCK_MINED -> getBlockStat(player, Statistic.MINE_BLOCK, criterion.getMaterial());
            case KILL_STREAK_CURRENT -> getCounter(player.getUniqueId(), "kill_streak");
            case DEATH_STREAK_SAME -> getCounter(player.getUniqueId(), "death_streak_same");
            case QUICK_KILL_COUNT -> getCounter(player.getUniqueId(), "quick_kill");
            case KILL_FULL_ARMOR_COUNT -> getCounter(player.getUniqueId(), "kill_full_armor");
            case KILL_LOW_HEALTH_COUNT -> getCounter(player.getUniqueId(), "kill_low_health");
            case DEATH_CAUSE_COUNT -> getCounter(player.getUniqueId(), "death_cause:" + criterion.getKey());
            case BALANCE_AT_LEAST -> (long) Math.floor(vaultHook.getBalance(player));
            case BALTOP_TOP3 -> isInBaltopTop3(player.getUniqueId()) ? 1 : 0;
            case PING_MS_AT_LEAST -> player.getPing();
            case STEPS_WALKED -> player.getStatistic(Statistic.WALK_ONE_CM) / 100;
            case PROJECTILE_HITS -> getCounter(player.getUniqueId(), "projectile_hits");
            case CUSTOM_COUNTER -> getCounter(player.getUniqueId(), criterion.getKey());
        };
    }

    public String formatProgress(Player player, RewardCriterion criterion) {
        long current = getProgress(player, criterion);
        long goal = criterion.getAmount();
        return current + "/" + goal;
    }

    public String formatAction(RewardAction action) {
        if (action.getType() == RewardActionType.TAG) {
            TagDefinition tag = tagService.getRegistry().get(action.getValue());
            String name = tag == null ? action.getValue() : tag.getDisplayName();
            return LegacyComponentSerializer.legacyAmpersand().deserialize(name).toString();
        }
        if (action.getType() == RewardActionType.MONEY) {
            return String.valueOf(action.getAmount());
        }
        return "";
    }

    public String getMessage(String key) {
        return messages.get(key);
    }

    public String getLivePlaytimeState(Player player) {
        if (playtimeHook.isAvailable()) {
            return playtimeHook.getLiveState(player.getUniqueId());
        }
        if (!config.allowPlaceholderPlaytimeFallback()) {
            return "";
        }
        String placeholder = config.playtimeStatePlaceholder();
        if (placeholder == null || placeholder.isBlank()) {
            return "";
        }
        return new org.enthusia.tags.PlaceholderApiHook().apply(player, placeholder);
    }

    public long incrementCounter(UUID playerId, String key, long delta) {
        RewardPlayerState state = getOrCreateState(playerId);
        long next = state.counters().getOrDefault(key, 0L) + delta;
        state.counters().put(key, next);
        state.setLoaded(true);
        state.setDirty(true);
        return next;
    }

    public void setCounter(UUID playerId, String key, long value) {
        RewardPlayerState state = getOrCreateState(playerId);
        state.counters().put(key, value);
        state.setLoaded(true);
        state.setDirty(true);
    }

    public long getCounter(UUID playerId, String key) {
        RewardPlayerState state = getLoadedState(playerId);
        if (state == null) {
            return 0L;
        }
        return state.counters().getOrDefault(key, 0L);
    }

    public String getState(UUID playerId, String key) {
        RewardPlayerState state = getLoadedState(playerId);
        if (state == null) {
            return null;
        }
        return state.states().get(key);
    }

    public void setState(UUID playerId, String key, String value) {
        RewardPlayerState state = getOrCreateState(playerId);
        if (value == null || value.isBlank()) {
            state.states().remove(key);
        } else {
            state.states().put(key, value);
        }
        state.setLoaded(true);
        state.setDirty(true);
    }

    private RewardPlayerState getOrCreateState(UUID playerId) {
        return playerStates.computeIfAbsent(playerId, ignored -> new RewardPlayerState());
    }

    private RewardPlayerState getLoadedState(UUID playerId) {
        RewardPlayerState state = playerStates.get(playerId);
        if (state == null || !state.isLoaded()) {
            return null;
        }
        return state;
    }

    private void hydrateState(RewardPlayerState state, RewardStorage.StoredRewardData data) {
        state.claimedRewards().clear();
        state.claimedRewards().addAll(data.claims());
        state.counters().clear();
        state.counters().putAll(data.counters());
        state.states().clear();
        state.states().putAll(data.states());
        state.setLoaded(true);
        state.setDirty(false);
    }

    private RewardStorage.StoredRewardData snapshotState(RewardPlayerState state) {
        return new RewardStorage.StoredRewardData(
            new java.util.HashSet<>(state.claimedRewards()),
            new java.util.HashMap<>(state.counters()),
            new java.util.HashMap<>(state.states()));
    }

    private long getBlockStat(Player player, Statistic stat, Material material) {
        if (material == null) {
            return 0L;
        }
        try {
            return player.getStatistic(stat, material);
        } catch (IllegalArgumentException ex) {
            return 0L;
        }
    }

    private long getPlaytimeMinutes(Player player, RewardCriterionType type, String placeholder) {
        if (!playtimeHook.isAvailable()) {
            return config.allowPlaceholderPlaytimeFallback() ? getPlaytimeFromPlaceholder(player, placeholder) : 0L;
        }
        return playtimeHook.getMinutes(player.getUniqueId(), type);
    }

    private long getPlaytimeFromPlaceholder(Player player, String placeholder) {
        if (placeholder == null || placeholder.isBlank()) {
            return 0L;
        }
        String resolved = tagService.getPlaceholderRegistry().apply(player, placeholder);
        resolved = new org.enthusia.tags.PlaceholderApiHook().apply(player, resolved);
        return parsePlaytimeMinutes(resolved, placeholder);
    }

    private long parsePlaytimeMinutes(String resolved, String placeholder) {
        if (resolved == null) {
            return 0L;
        }
        String raw = resolved.trim();
        if (raw.isEmpty()) {
            return 0L;
        }

        long minutes = 0L;
        boolean matched = false;

        Matcher hours = HOURS_PATTERN.matcher(raw);
        if (hours.find()) {
            minutes += Long.parseLong(hours.group(1)) * 60L;
            matched = true;
        }
        Matcher mins = MINUTES_PATTERN.matcher(raw);
        if (mins.find()) {
            minutes += Long.parseLong(mins.group(1));
            matched = true;
        }
        Matcher secs = SECONDS_PATTERN.matcher(raw);
        if (secs.find()) {
            minutes += Long.parseLong(secs.group(1)) / 60L;
            matched = true;
        }
        if (matched) {
            return minutes;
        }

        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return 0L;
        }

        try {
            long value = Long.parseLong(digits);
            String token = placeholder == null ? "" : placeholder.toLowerCase();
            if (token.contains("%playtime_") && !token.contains("formatted")) {
                return value / 60L;
            }
            return value;
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private boolean isInBaltopTop3(UUID playerId) {
        Plugin plugin = baltopPlugin;
        if (plugin == null) {
            return false;
        }
        try {
            Object result = plugin.getClass().getMethod("isInBaltopTop", UUID.class, int.class)
                .invoke(plugin, playerId, 3);
            return result instanceof Boolean && (Boolean) result;
        } catch (ReflectiveOperationException ex) {
            return false;
        }
    }

    private boolean isCriterionAvailable(RewardCriterionType type) {
        return switch (type) {
            case PLAYTIME_ACTIVE_MINUTES, PLAYTIME_AFK_MINUTES, PLAYTIME_TOTAL_MINUTES -> playtimeHook.isAvailable()
                || config.allowPlaceholderPlaytimeFallback();
            case PLAYTIME_CONSECUTIVE_ACTIVE_MINUTES, UNDERGROUND_ACTIVE_MINUTES ->
                playtimeHook.isAvailable() || config.allowPlaceholderPlaytimeFallback();
            case BALANCE_AT_LEAST -> vaultHook.isAvailable();
            case BALTOP_TOP3 -> baltopPlugin != null;
            default -> true;
        };
    }

    private boolean areActionsAvailable(RewardDefinition reward) {
        if (!vaultHook.isAvailable()) {
            return reward.getActions().stream().noneMatch(action -> action.getType() == RewardActionType.MONEY);
        }
        return true;
    }

    private void refreshIntegrations() {
        vaultHook.setup();
        playtimeHook.setup();
        baltopPlugin = Bukkit.getPluginManager().getPlugin(config.baltopPluginName());
        if (baltopPlugin == null) {
            for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
                try {
                    plugin.getClass().getMethod("isInBaltopTop", UUID.class, int.class);
                    baltopPlugin = plugin;
                    break;
                } catch (NoSuchMethodException ignored) {
                }
            }
        }
        integrationStatus.clear();

        if (usesPlaytimeRewards() && !playtimeHook.isAvailable() && !config.allowPlaceholderPlaytimeFallback()) {
            integrationStatus.addWarning("&cEnthusiaTags warning: PlayTimePlugin API is unavailable. Playtime rewards are blocked.");
        }
        if (usesEconomyRewards() && !vaultHook.isAvailable()) {
            integrationStatus.addWarning("&cEnthusiaTags warning: Vault economy is unavailable. Economy rewards are blocked.");
        }
        if (usesBaltopRewards() && baltopPlugin == null) {
            integrationStatus.addWarning("&cEnthusiaTags warning: Baltop integration plugin '" + config.baltopPluginName()
                + "' is unavailable. Baltop rewards are blocked.");
        }
    }

    private boolean usesPlaytimeRewards() {
        return rewards.values().stream()
            .flatMap(reward -> reward.getCriteria().stream())
            .map(RewardCriterion::getType)
            .anyMatch(type -> switch (type) {
                case PLAYTIME_ACTIVE_MINUTES, PLAYTIME_AFK_MINUTES, PLAYTIME_TOTAL_MINUTES -> true;
                default -> false;
            });
    }

    private boolean usesEconomyRewards() {
        return rewards.values().stream().anyMatch(reward ->
            reward.getCriteria().stream().anyMatch(criterion -> criterion.getType() == RewardCriterionType.BALANCE_AT_LEAST)
                || reward.getActions().stream().anyMatch(action -> action.getType() == RewardActionType.MONEY));
    }

    private boolean usesBaltopRewards() {
        return rewards.values().stream()
            .flatMap(reward -> reward.getCriteria().stream())
            .anyMatch(criterion -> criterion.getType() == RewardCriterionType.BALTOP_TOP3);
    }

    private void initStorage() {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            plugin.getLogger().warning("Failed to create data folder for rewards.");
        }
        storage = new RewardStorage(new File(dataFolder, "rewards.db"));
        try {
            storage.init();
        } catch (SQLException ex) {
            plugin.getLogger().severe("Failed to initialize rewards database: " + ex.getMessage());
        }
    }

    private void startFlushTask() {
        stopFlushTask();
        long interval = Math.max(20L, plugin.getConfig().getLong("performance.reward-save-interval-ticks", 200L));
        flushTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::flushDirtyPlayers, interval, interval);
    }

    private void stopFlushTask() {
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
    }

    private void flushDirtyPlayers() {
        for (Map.Entry<UUID, RewardPlayerState> entry : playerStates.entrySet()) {
            flushPlayerAsync(entry.getKey(), entry.getValue());
        }
    }

    private void flushPlayerAsync(UUID playerId, RewardPlayerState state) {
        if (state == null || !state.isLoaded() || !state.isDirty()) {
            return;
        }
        RewardStorage.StoredRewardData snapshot = snapshotState(state);
        state.setDirty(false);
        storage.saveAsync(playerId, snapshot).exceptionally(throwable -> {
            state.setDirty(true);
            plugin.getLogger().warning("Failed to save reward state for " + playerId + ": " + throwable.getMessage());
            return null;
        });
    }

    private void flushPlayerBlocking(UUID playerId) {
        RewardPlayerState state = playerStates.get(playerId);
        if (state == null || !state.isLoaded() || !state.isDirty()) {
            return;
        }
        try {
            storage.saveAsync(playerId, snapshotState(state)).join();
            state.setDirty(false);
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to flush reward state for " + playerId + " during shutdown: " + ex.getMessage());
        }
    }

    private void flushAllBlocking() {
        for (UUID playerId : List.copyOf(playerStates.keySet())) {
            flushPlayerBlocking(playerId);
        }
    }

    private void ensureDefaults() {
        File file = new File(plugin.getDataFolder(), "rewards.yml");
        if (!file.exists()) {
            plugin.saveResource("rewards.yml", false);
        }
        var configFile = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        try (var stream = plugin.getResource("rewards.yml")) {
            if (stream == null) {
                return;
            }
            var defaults = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                new InputStreamReader(stream, StandardCharsets.UTF_8));
            configFile.setDefaults(defaults);
            configFile.options().copyDefaults(true);
            configFile.save(file);
        } catch (Exception ignored) {
        }
    }

    private void loadConfig() {
        File file = new File(plugin.getDataFolder(), "rewards.yml");
        var configFile = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        config = RewardsConfig.from(configFile);
        ConfigurationSection section = configFile.getConfigurationSection("rewards");
        if (section == null) {
            return;
        }
        for (String id : section.getKeys(false)) {
            String name = section.getString(id + ".name", id);
            List<String> description = section.getStringList(id + ".description");
            Material icon = Material.matchMaterial(section.getString(id + ".icon", "NAME_TAG"));
            List<RewardCriterion> criteria = loadCriteria(section.getConfigurationSection(id + ".criteria"));
            List<RewardAction> actions = loadActions(section.getConfigurationSection(id + ".rewards"));
            String category = section.getString(id + ".category", inferCategory(criteria));
            rewards.put(id.toLowerCase(), new RewardDefinition(id, name, description, icon, criteria, actions, category));
        }
    }

    private List<RewardCriterion> loadCriteria(ConfigurationSection section) {
        List<RewardCriterion> criteria = new ArrayList<>();
        if (section == null) {
            return criteria;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(key);
            if (entry == null) {
                continue;
            }
            RewardCriterionType type = RewardCriterionType.valueOf(entry.getString("type", "CUSTOM_COUNTER"));
            long amount = entry.getLong("amount", 1);
            Material material = Material.matchMaterial(entry.getString("material", ""));
            String counterKey = entry.getString("key", "");
            int maxY = entry.getInt("max-y", config.undergroundMaxY());
            if (type == RewardCriterionType.CUSTOM_COUNTER && "steps_walked".equalsIgnoreCase(counterKey)) {
                type = RewardCriterionType.STEPS_WALKED;
                counterKey = "";
            }
            String label = entry.getString("label", defaultLabel(type, material, counterKey));
            criteria.add(new RewardCriterion(type, amount, material, counterKey, maxY, label));
        }
        return criteria;
    }

    private List<RewardAction> loadActions(ConfigurationSection section) {
        List<RewardAction> actions = new ArrayList<>();
        if (section == null) {
            return actions;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(key);
            if (entry == null) {
                continue;
            }
            RewardActionType type = RewardActionType.valueOf(entry.getString("type", "TAG"));
            String value = entry.getString("id", "");
            double amount = entry.getDouble("amount", 0.0);
            String label = entry.getString("label", "");
            actions.add(new RewardAction(type, value, amount, label));
        }
        return actions;
    }

    private String inferCategory(List<RewardCriterion> criteria) {
        if (criteria == null || criteria.isEmpty()) {
            return "misc";
        }
        RewardCriterionType type = criteria.get(0).getType();
        return switch (type) {
            case PLAYTIME_ACTIVE_MINUTES, PLAYTIME_AFK_MINUTES, PLAYTIME_TOTAL_MINUTES,
                PLAYTIME_CONSECUTIVE_ACTIVE_MINUTES, UNDERGROUND_ACTIVE_MINUTES -> "playtime";
            case BLOCK_MINED -> "mining";
            case KILLS_TOTAL, KILL_STREAK_CURRENT, QUICK_KILL_COUNT, KILL_FULL_ARMOR_COUNT,
                KILL_LOW_HEALTH_COUNT -> "combat";
            case DEATHS_TOTAL, DEATH_STREAK_SAME, DEATH_CAUSE_COUNT -> "deaths";
            case BALANCE_AT_LEAST, BALTOP_TOP3 -> "economy";
            default -> "misc";
        };
    }

    private String defaultLabel(RewardCriterionType type, Material material, String key) {
        return switch (type) {
            case PLAYTIME_ACTIVE_MINUTES -> "Active playtime (min)";
            case PLAYTIME_AFK_MINUTES -> "AFK playtime (min)";
            case PLAYTIME_TOTAL_MINUTES -> "Total playtime (min)";
            case PLAYTIME_CONSECUTIVE_ACTIVE_MINUTES -> "Consecutive active (min)";
            case UNDERGROUND_ACTIVE_MINUTES -> "Underground active (min)";
            case KILLS_TOTAL -> "Player kills";
            case DEATHS_TOTAL -> "Deaths";
            case BLOCK_MINED -> material == null ? "Blocks mined" : material.name();
            case KILL_STREAK_CURRENT -> "Kill streak";
            case DEATH_STREAK_SAME -> "Deaths to same player";
            case QUICK_KILL_COUNT -> "Quick kills (10s)";
            case KILL_FULL_ARMOR_COUNT -> "Full armor kills";
            case KILL_LOW_HEALTH_COUNT -> "Low health wins";
            case DEATH_CAUSE_COUNT -> "Deaths by cause";
            case BALANCE_AT_LEAST -> "Balance";
            case BALTOP_TOP3 -> "Baltop top 3";
            case PING_MS_AT_LEAST -> "Ping (ms)";
            case STEPS_WALKED -> "Blocks walked";
            case PROJECTILE_HITS -> "Projectile hits";
            case CUSTOM_COUNTER -> key.isBlank() ? "Progress" : key;
        };
    }
}
