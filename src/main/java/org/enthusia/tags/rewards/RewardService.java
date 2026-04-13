package org.enthusia.tags.rewards;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.enthusia.tags.Messages;
import org.enthusia.tags.TagDefinition;
import org.enthusia.tags.TagService;

import java.io.File;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RewardService {
    private final JavaPlugin plugin;
    private final TagService tagService;
    private final Messages messages;
    private final VaultHook vaultHook = new VaultHook();
    private final PlaytimeHook playtimeHook = new PlaytimeHook();
    private Object baltopPlugin;
    private Method baltopIsInTop;
    private final Map<String, RewardDefinition> rewards = new LinkedHashMap<>();
    private RewardStorage storage;
    private RewardsConfig config;
    private static final Pattern HOURS_PATTERN = Pattern.compile("(?i)(\\d+)\\s*h");
    private static final Pattern MINUTES_PATTERN = Pattern.compile("(?i)(\\d+)\\s*m");
    private static final Pattern SECONDS_PATTERN = Pattern.compile("(?i)(\\d+)\\s*s");

    public RewardService(JavaPlugin plugin, TagService tagService, Messages messages) {
        this.plugin = plugin;
        this.tagService = tagService;
        this.messages = messages;
    }

    public void enable() {
        ensureDefaults();
        reload();
        vaultHook.setup();
        playtimeHook.setup();
        resolveBaltopHook();
        initStorage();
    }

    public void disable() {
        if (storage != null) {
            storage.close();
        }
    }

    public void reload() {
        ensureDefaults();
        rewards.clear();
        loadConfig();
        vaultHook.setup();
        playtimeHook.setup();
        resolveBaltopHook();
        reopenStorage();
    }

    public Map<String, RewardDefinition> getRewards() {
        return rewards;
    }

    public RewardStorage getStorage() {
        return storage;
    }

    public RewardsConfig getConfig() {
        return config;
    }

    public boolean isClaimed(UUID playerId, String rewardId) {
        try {
            return storage.isClaimed(playerId, rewardId);
        } catch (SQLException ex) {
            plugin.getLogger().warning("Failed to check reward claim: " + ex.getMessage());
            return false;
        }
    }

    public void claim(Player player, RewardDefinition reward) {
        if (isClaimed(player.getUniqueId(), reward.getId())) {
            return;
        }
        if (!isComplete(player, reward)) {
            return;
        }
        for (RewardAction action : reward.getActions()) {
            if (action.getType() == RewardActionType.TAG) {
                tagService.grantTag(player.getUniqueId(), action.getValue());
            } else if (action.getType() == RewardActionType.MONEY) {
                vaultHook.deposit(player, action.getAmount());
            } else if (action.getType() == RewardActionType.COMMAND) {
                String command = action.getValue()
                    .replace("{player}", player.getName())
                    .replace("%player%", player.getName());
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            }
        }
        try {
            storage.setClaimed(player.getUniqueId(), reward.getId());
        } catch (SQLException ex) {
            plugin.getLogger().warning("Failed to store reward claim: " + ex.getMessage());
            handleMovedDb(ex);
        }
    }

    public boolean isComplete(Player player, RewardDefinition reward) {
        for (RewardCriterion criterion : reward.getCriteria()) {
            long current = getProgress(player, criterion);
            if (current < criterion.getAmount()) {
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
            case PLAYTIME_CONSECUTIVE_ACTIVE_MINUTES -> getCounter(player, "consecutive_active");
            case UNDERGROUND_ACTIVE_MINUTES -> getCounter(player, "underground_active");
            case KILLS_TOTAL -> player.getStatistic(Statistic.PLAYER_KILLS);
            case DEATHS_TOTAL -> player.getStatistic(Statistic.DEATHS);
            case BLOCK_MINED -> getBlockStat(player, Statistic.MINE_BLOCK, criterion.getMaterial());
            case KILL_STREAK_CURRENT -> getCounter(player, "kill_streak");
            case DEATH_STREAK_SAME -> getCounter(player, "death_streak_same");
            case QUICK_KILL_COUNT -> getCounter(player, "quick_kill");
            case KILL_FULL_ARMOR_COUNT -> getCounter(player, "kill_full_armor");
            case KILL_LOW_HEALTH_COUNT -> getCounter(player, "kill_low_health");
            case DEATH_CAUSE_COUNT -> getCounter(player, "death_cause:" + criterion.getKey());
            case BALANCE_AT_LEAST -> (long) Math.floor(vaultHook.getBalance(player));
            case BALTOP_TOP3 -> isInBaltopTop3(player) ? 1 : 0;
            case PING_MS_AT_LEAST -> player.getPing();
            case STEPS_WALKED -> player.getStatistic(Statistic.WALK_ONE_CM) / 100;
            case PROJECTILE_HITS -> getCounter(player, "projectile_hits");
            case CUSTOM_COUNTER -> getCounter(player, criterion.getKey());
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

    private long getCounter(Player player, String key) {
        try {
            return storage.getCounter(player.getUniqueId(), key);
        } catch (SQLException ex) {
            plugin.getLogger().warning("Failed to load counter " + key + ": " + ex.getMessage());
            handleMovedDb(ex);
            return 0L;
        }
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
        long fromService = playtimeHook.getMinutes(player.getUniqueId(), type);
        if (fromService >= 0) {
            return fromService;
        }
        return getPlaytimeFromPlaceholder(player, placeholder);
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

    private void reopenStorage() {
        if (storage != null) {
            storage.close();
        }
        initStorage();
    }

    private void handleMovedDb(SQLException ex) {
        if (ex.getMessage() != null && ex.getMessage().contains("SQLITE_READONLY_DBMOVED")) {
            Bukkit.getScheduler().runTask(plugin, this::reopenStorage);
        }
    }

    private boolean isInBaltopTop3(Player player) {
        if (baltopPlugin == null || baltopIsInTop == null) {
            resolveBaltopHook();
        }
        if (baltopPlugin == null || baltopIsInTop == null) {
            return false;
        }
        try {
            Object result = baltopIsInTop.invoke(baltopPlugin, player.getUniqueId(), 3);
            return result instanceof Boolean && (Boolean) result;
        } catch (ReflectiveOperationException ex) {
            return false;
        }
    }

    private void resolveBaltopHook() {
        baltopPlugin = null;
        baltopIsInTop = null;
        var pm = Bukkit.getPluginManager();
        Object plugin = pm.getPlugin("EnthusiaCurrency");
        if (plugin == null) {
            plugin = pm.getPlugin("RivetTokens");
        }
        if (plugin != null && tryBindBaltop(plugin)) {
            return;
        }
        for (var p : pm.getPlugins()) {
            if (tryBindBaltop(p)) {
                return;
            }
        }
    }

    private boolean tryBindBaltop(Object plugin) {
        try {
            Method method = plugin.getClass().getMethod("isInBaltopTop", java.util.UUID.class, int.class);
            baltopPlugin = plugin;
            baltopIsInTop = method;
            return true;
        } catch (NoSuchMethodException ex) {
            return false;
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
            int maxY = entry.getInt("max-y", 56);
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
            case BLOCK_MINED -> "Blocks mined";
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
