package org.enthusia.tags.rewards;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.Sound;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.enthusia.tags.IntegrationStatus;
import org.enthusia.tags.Messages;
import org.enthusia.tags.PerformanceMonitor;
import org.enthusia.tags.PlaceholderApiHook;
import org.enthusia.tags.PlayerLookup;
import org.enthusia.tags.TagDefinition;
import org.enthusia.tags.TagService;

import java.io.File;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressWarnings({"PMD.UseConcurrentHashMap", "PMD.NullAssignment", "PMD.AvoidInstantiatingObjectsInLoops"})
public final class RewardService {
    private static final String REWARD_UNLOCK_NOTIFIED_PREFIX = "reward-unlocked:";
    private static final int MAX_UNLOCK_CHECKS_PER_RUN = 25;
    private static final Pattern HOURS_PATTERN = Pattern.compile("(?i)(\\d+)\\s*h");
    private static final Pattern MINUTES_PATTERN = Pattern.compile("(?i)(\\d+)\\s*m");
    private static final Pattern SECONDS_PATTERN = Pattern.compile("(?i)(\\d+)\\s*s");
    private static final Pattern NON_DIGIT_PATTERN = Pattern.compile("[^0-9]");
    private static final String COMMAND_SYNC_ALL = "syncall";
    private static final String COMMAND_SYNC = "sync";
    private static final String COMMAND_DEBUG = "debug";
    private static final String COMMAND_IP_BYPASS = "ipbypass";
    private static final String COMMAND_IP_BYPASS_ALIAS = "bypass";
    private static final String REWARDS_FILE = "rewards.yml";
    private static final String INVALID_CRITERION_PREFIX = "Invalid reward criterion at ";
    private static final String STEPS_WALKED_COUNTER = "steps_walked";
    private static final Map<RewardCriterionType, String> DEFAULT_LABELS = defaultLabels();
    private static final Map<RewardCriterionType, String> DEFAULT_COUNTER_KEYS = defaultCounterKeys();

    private final JavaPlugin plugin;
    private final TagService tagService;
    private final Messages messages;
    private final PerformanceMonitor performanceMonitor;
    private final VaultHook vaultHook = new VaultHook();
    private final PlaytimeHook playtimeHook = new PlaytimeHook();
    private final PlaceholderApiHook placeholderApiHook = new PlaceholderApiHook();
    private final PlayerLookup playerLookup;
    private final Map<String, RewardDefinition> rewards = new LinkedHashMap<>();
    private final Map<UUID, RewardPlayerState> playerStates = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Void>> pendingLoads = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Long>> pendingCounterDeltas = new ConcurrentHashMap<>();
    private final Map<UUID, ProgressSnapshot> progressSnapshots = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<UUID> rewardSyncQueue = new ConcurrentLinkedDeque<>();
    private final java.util.Set<UUID> queuedRewardSyncPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> queuedUnlockChecks = ConcurrentHashMap.newKeySet();
    private final Set<String> inFlightClaims = ConcurrentHashMap.newKeySet();
    private final IntegrationStatus integrationStatus = new IntegrationStatus();

    private RewardStorage storage;
    private RewardsConfig config;
    private BukkitTask flushTask;
    private BukkitTask globalScanTask;
    private BukkitTask syncDrainTask;
    private BukkitTask unlockCheckTask;
    private Plugin baltopPlugin;
    private Method baltopMethod;
    private long progressCacheTicks = 100L;
    private long syncQueuedTotal;
    private long syncProcessedTotal;
    private long syncLastDurationMillis;
    private long syncRepairedStates;
    private long syncStaleStates;

    public RewardService(JavaPlugin plugin, TagService tagService, Messages messages, PerformanceMonitor performanceMonitor) {
        this.plugin = plugin;
        this.tagService = tagService;
        this.messages = messages;
        this.performanceMonitor = performanceMonitor;
        this.playerLookup = new PlayerLookup(plugin);
    }

    public void enable() {
        ensureDefaults();
        initStorage();
        reload();
        startFlushTask();
        startGlobalScanTask();
    }

    public void disable() {
        stopFlushTask();
        stopGlobalScanTask();
        stopSyncDrainTask();
        stopUnlockCheckTask();
        flushAllBlocking();
        pendingLoads.clear();
        pendingCounterDeltas.clear();
        progressSnapshots.clear();
        rewardSyncQueue.clear();
        queuedRewardSyncPlayers.clear();
        queuedUnlockChecks.clear();
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
        progressCacheTicks = Math.max(20L, plugin.getConfig().getLong("performance.reward-progress-cache-ticks", 100L));
        startFlushTask();
        startGlobalScanTask();
        for (Player player : Bukkit.getOnlinePlayers()) {
            preloadPlayer(player.getUniqueId());
            queueProgressRefresh(player);
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
                    applyPendingDeltas(playerId, state);
                    Player player = Bukkit.getPlayer(playerId);
                    if (player != null) {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            reserveExistingIpClaims(player, state);
                            queueProgressRefresh(player);
                        });
                    }
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
        RewardPlayerState state = getLoadedState(player.getUniqueId());
        if (state != null) {
            reserveExistingIpClaims(player, state);
            queueProgressRefresh(player);
        }
    }

    public void unloadPlayer(Player player) {
        UUID playerId = player.getUniqueId();
        flushPlayerBlocking(playerId);
        pendingLoads.remove(playerId);
        pendingCounterDeltas.remove(playerId);
        progressSnapshots.remove(playerId);
        playerStates.remove(playerId);
    }

    public boolean isClaimed(UUID playerId, String rewardId) {
        RewardPlayerState state = getLoadedState(playerId);
        return state != null && state.claimedRewards().contains(rewardId.toLowerCase(Locale.ROOT));
    }

    public RewardClaimResult claim(Player player, RewardDefinition reward) {
        RewardPlayerState state = getLoadedState(player.getUniqueId());
        if (state == null || !state.isLoaded()) {
            performanceMonitor.increment("rewards.claim.skipped-loading");
            return RewardClaimResult.LOADING;
        }
        String rewardId = reward.getId().toLowerCase(Locale.ROOT);
        String claimKey = player.getUniqueId() + ":" + rewardId;
        if (!inFlightClaims.add(claimKey)) {
            return RewardClaimResult.CLAIM_IN_PROGRESS;
        }
        try {
        if (state.claimedRewards().contains(rewardId)) {
            return RewardClaimResult.ALREADY_CLAIMED;
        }
        RewardEvaluation evaluation = evaluate(player, reward);
        if (!evaluation.claimable()) {
            return RewardClaimResult.NOT_READY;
        }
        if (!reserveIpClaim(player, rewardId)) {
            return RewardClaimResult.IP_ALREADY_CLAIMED;
        }

        state.states().put("reward-delivery:" + rewardId, RewardStatus.CLAIM_PENDING.name());
        state.setDirty(true);
        flushPlayerBlocking(player.getUniqueId());
        for (int actionIndex = 0; actionIndex < reward.getActions().size(); actionIndex++) {
            RewardAction action = reward.getActions().get(actionIndex);
            String actionStateKey = "reward-action:" + rewardId + ":" + actionIndex;
            if (RewardStatus.CLAIMED.name().equals(state.states().get(actionStateKey))) {
                continue;
            }
            state.states().put(actionStateKey, RewardStatus.CLAIM_PENDING.name());
            state.setDirty(true);
            flushPlayerBlocking(player.getUniqueId());
            boolean delivered;
            switch (action.getType()) {
                case TAG -> {
                    delivered = tagService.grantTagPersisted(player.getUniqueId(), action.getValue()).join();
                }
                case MONEY -> delivered = vaultHook.deposit(player, action.getAmount());
                case COMMAND -> {
                    String command = action.getValue()
                        .replace("{player}", player.getName())
                        .replace("%player%", player.getName());
                    delivered = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                }
                case ITEM -> delivered = deliverItem(player, action);
                default -> delivered = false;
            }
            if (!delivered) {
                state.states().put(actionStateKey, RewardStatus.DELIVERY_FAILED.name());
                state.states().put("reward-delivery:" + rewardId, RewardStatus.DELIVERY_FAILED.name());
                state.setDirty(true);
                storage.releaseIpClaimAsync(player.getUniqueId(), rewardId, getPlayerIpAddress(player));
                flushPlayerAsync(player.getUniqueId(), state);
                plugin.getLogger().warning("Reward delivery failed player=" + player.getUniqueId()
                    + " name=" + player.getName() + " reward=" + rewardId + " action=" + action.getType());
                return RewardClaimResult.DELIVERY_FAILED;
            }
            state.states().put(actionStateKey, RewardStatus.CLAIMED.name());
            state.setDirty(true);
            flushPlayerBlocking(player.getUniqueId());
        }

        state.claimedRewards().add(rewardId);
        state.states().put("reward-delivery:" + rewardId, RewardStatus.CLAIMED.name());
        state.setDirty(true);
        invalidateProgress(player.getUniqueId());
        flushPlayerBlocking(player.getUniqueId());
        return RewardClaimResult.SUCCESS;
        } finally {
            inFlightClaims.remove(claimKey);
        }
    }

    public boolean isComplete(Player player, RewardDefinition reward) {
        return evaluate(player, reward).claimable();
    }

    public RewardEvaluation evaluate(Player player, RewardDefinition reward) {
        return evaluate(player, reward, getProgressSnapshot(player));
    }

    public RewardEvaluation evaluate(Player player, RewardDefinition reward, ProgressSnapshot snapshot) {
        RewardPlayerState state = getLoadedState(player.getUniqueId());
        Map<String, Long> progress = new LinkedHashMap<>();
        String rewardId = reward.getId().toLowerCase(Locale.ROOT);
        if (state == null || !state.isLoaded()) {
            return new RewardEvaluation(RewardStatus.LOCKED, progress, false, false, "Player state is loading");
        }
        if (state.claimedRewards().contains(rewardId)) {
            return new RewardEvaluation(RewardStatus.CLAIMED, progress, true, false, "Already delivered");
        }
        String delivery = state.states().get("reward-delivery:" + rewardId);
        if (RewardStatus.CLAIM_PENDING.name().equals(delivery)) {
            return new RewardEvaluation(RewardStatus.REQUIRES_RECONCILIATION, progress, true, false,
                "A previous delivery may have been interrupted");
        }
        boolean unlocked = state.states().containsKey(REWARD_UNLOCK_NOTIFIED_PREFIX + rewardId);
        boolean currentlyComplete = true;
        for (RewardCriterion criterion : reward.getCriteria()) {
            long value = getProgress(player, criterion, snapshot);
            progress.put(criterion.getLabel(), value);
            currentlyComplete &= isCriterionAvailable(criterion) && value >= criterion.getAmount();
        }
        if (!areActionsAvailable(reward)) {
            return new RewardEvaluation(RewardStatus.LOCKED, progress, unlocked, false,
                "A required reward integration or action is unavailable");
        }
        boolean complete = currentlyComplete || (unlocked && reward.getCompletionMode() == RewardCompletionMode.LATCHED);
        return new RewardEvaluation(complete ? RewardStatus.UNLOCKED : RewardStatus.LOCKED, progress, unlocked,
            complete, complete ? "Ready to claim" : "Requirements not reached");
    }

    private boolean isComplete(Player player, RewardDefinition reward, ProgressSnapshot snapshot) {
        if (!areActionsAvailable(reward)) {
            return false;
        }
        for (RewardCriterion criterion : reward.getCriteria()) {
            if (!isCriterionAvailable(criterion)) {
                return false;
            }
            if (getProgress(player, criterion, snapshot) < criterion.getAmount()) {
                return false;
            }
        }
        return true;
    }

    public long getProgress(Player player, RewardCriterion criterion) {
        return getProgress(player, criterion, getProgressSnapshot(player));
    }

    public long getProgress(Player player, RewardCriterion criterion, ProgressSnapshot snapshot) {
        performanceMonitor.increment("rewards.progress.calls");
        if (criterion == null || !criterion.isValid()) {
            return 0L;
        }
        String cacheKey = criterionCacheKey(criterion);
        Long cached = snapshot.values().get(cacheKey);
        if (cached != null) {
            return cached;
        }
        long value = computeProgress(player, criterion);
        snapshot.values().put(cacheKey, value);
        return value;
    }

    private long computeProgress(Player player, RewardCriterion criterion) {
        if (criterion.getSourceType() != RewardSourceType.LEGACY) {
            return computeSourceProgress(player, criterion);
        }
        return computeLegacyProgress(player, criterion);
    }

    private long computeSourceProgress(Player player, RewardCriterion criterion) {
        return switch (criterion.getSourceType()) {
            case BUKKIT_STAT -> getBukkitStat(player, criterion.getStatistic());
            case BUKKIT_STAT_MATERIAL -> getBukkitMaterialStat(player, criterion.getStatistic(), criterion.getMaterial());
            case BUKKIT_STAT_ENTITY -> getBukkitEntityStat(player, criterion.getStatistic(), criterion.getEntityType());
            case CUSTOM_COUNTER -> getCounter(player.getUniqueId(), criterion.getKey());
            case PLACEHOLDER -> getPlaytimeFromPlaceholder(player, criterion.getKey());
            case VAULT_BALANCE -> (long) Math.floor(vaultHook.getBalance(player));
            case PLAYTIME -> getPlaytimeMinutes(player, criterion.getType(), criterion.getKey());
            case BALTOP -> isInBaltopTop3(player.getUniqueId()) ? 1 : 0;
            case LEGACY -> 0L;
        };
    }

    private long computeLegacyProgress(Player player, RewardCriterion criterion) {
        return switch (criterion.getType()) {
            case PLAYTIME_ACTIVE_MINUTES -> getPlaytimeMinutes(player, RewardCriterionType.PLAYTIME_ACTIVE_MINUTES,
                config.playtimeActivePlaceholder());
            case PLAYTIME_AFK_MINUTES -> getPlaytimeMinutes(player, RewardCriterionType.PLAYTIME_AFK_MINUTES,
                config.playtimeAfkPlaceholder());
            case PLAYTIME_TOTAL_MINUTES -> getPlaytimeMinutes(player, RewardCriterionType.PLAYTIME_TOTAL_MINUTES,
                config.playtimeTotalPlaceholder());
            case PLAYTIME_CONSECUTIVE_ACTIVE_MINUTES -> getCounter(player.getUniqueId(), "max_consecutive_active");
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
            case PING_MS_AT_LEAST -> getCounter(player.getUniqueId(), "max_ping_ms");
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

    public String formatProgress(long current, RewardCriterion criterion) {
        return current + "/" + criterion.getAmount();
    }

    public ProgressSnapshot getProgressSnapshot(Player player) {
        long nowTick = Bukkit.getCurrentTick();
        ProgressSnapshot snapshot = progressSnapshots.get(player.getUniqueId());
        if (snapshot != null && nowTick - snapshot.createdTick() <= progressCacheTicks) {
            performanceMonitor.increment("rewards.progress.cache-hit");
            return snapshot;
        }
        performanceMonitor.increment("rewards.progress.cache-miss");
        ProgressSnapshot created = new ProgressSnapshot(nowTick, new ConcurrentHashMap<>());
        progressSnapshots.put(player.getUniqueId(), created);
        return created;
    }

    public void invalidateProgress(UUID playerId) {
        progressSnapshots.remove(playerId);
    }

    public void queueProgressRefresh(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        invalidateProgress(player.getUniqueId());
        ProgressSnapshot snapshot = getProgressSnapshot(player);
        notifyUnlockedRewards(player, snapshot);
    }

    public void queueUnlockCheck(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        queueUnlockCheck(player.getUniqueId());
    }

    public String formatAction(RewardAction action) {
        if (action.getType() == RewardActionType.TAG) {
            TagDefinition tag = tagService.getRegistry().get(action.getValue());
            return tag == null ? action.getValue() : tag.getDisplayName();
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
        return placeholderApiHook.apply(player, placeholder);
    }

    public long incrementCounter(UUID playerId, String key, long delta) {
        RewardPlayerState state = getLoadedState(playerId);
        if (state == null || !state.isLoaded()) {
            queueCounterDelta(playerId, key, delta);
            return 0L;
        }
        long next = state.counters().getOrDefault(key, 0L) + delta;
        state.counters().put(key, next);
        state.setDirty(true);
        invalidateProgress(playerId);
        queueUnlockCheck(playerId);
        return next;
    }

    public void setCounter(UUID playerId, String key, long value) {
        RewardPlayerState state = getLoadedState(playerId);
        if (state == null || !state.isLoaded()) {
            performanceMonitor.increment("rewards.state.skipped-unloaded");
            return;
        }
        state.counters().put(key, value);
        state.setDirty(true);
        invalidateProgress(playerId);
        queueUnlockCheck(playerId);
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

    public boolean handleAdminCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sendAdminUsage(sender);
            return true;
        }
        String subcommand = args[0].toLowerCase(Locale.ROOT);
        if (COMMAND_SYNC_ALL.equals(subcommand)) {
            queueSyncAll();
            sender.sendMessage(messages.get("sync-queued") + " " + syncStatus());
            return true;
        }
        if (COMMAND_SYNC.equals(subcommand)) {
            return handleSyncCommand(sender, args);
        }
        if (COMMAND_DEBUG.equals(subcommand)) {
            return handleDebugCommand(sender, args);
        }
        if (COMMAND_IP_BYPASS.equals(subcommand) || COMMAND_IP_BYPASS_ALIAS.equals(subcommand)) {
            return handleIpBypassCommand(sender, args);
        }
        sendAdminUsage(sender);
        return true;
    }

    private boolean handleSyncCommand(CommandSender sender, String[] args) {
        Player target = findCommandTarget(sender, args);
        if (target == null) {
            return true;
        }
        syncPlayer(target);
        sender.sendMessage(messages.get("sync-queued") + " " + syncStatus());
        return true;
    }

    private boolean handleDebugCommand(CommandSender sender, String[] args) {
        Player target = findCommandTarget(sender, args);
        if (target == null) {
            return true;
        }
        sendDebug(sender, target);
        return true;
    }

    private boolean handleIpBypassCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendIpBypassUsage(sender);
            return true;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        return switch (action) {
            case "add" -> handleAddIpBypassCommand(sender, args);
            case "remove", "delete" -> handleRemoveIpBypassCommand(sender, args);
            case "list" -> handleListIpBypassCommand(sender, args);
            default -> {
                sendIpBypassUsage(sender);
                yield true;
            }
        };
    }

    private boolean handleAddIpBypassCommand(CommandSender sender, String[] args) {
        OfflinePlayer first = findOfflineCommandTarget(sender, args, 2);
        OfflinePlayer second = findOfflineCommandTarget(sender, args, 3);
        if (first == null || second == null) {
            return true;
        }
        if (first.getUniqueId().equals(second.getUniqueId())) {
            sender.sendMessage("Players must be two different accounts.");
            return true;
        }
        try {
            boolean added = storage.addIpBypassPairNow(first.getUniqueId(), second.getUniqueId());
            sender.sendMessage((added ? "Added" : "Already exists")
                + " reward IP bypass pair for " + displayName(first) + " and " + displayName(second) + ".");
        } catch (SQLException ex) {
            plugin.getLogger().warning("Failed to add reward IP bypass pair: " + ex.getMessage());
            sender.sendMessage("Failed to add reward IP bypass pair. Check console for details.");
        }
        return true;
    }

    private boolean handleRemoveIpBypassCommand(CommandSender sender, String[] args) {
        OfflinePlayer first = findOfflineCommandTarget(sender, args, 2);
        OfflinePlayer second = findOfflineCommandTarget(sender, args, 3);
        if (first == null || second == null) {
            return true;
        }
        try {
            boolean removed = storage.removeIpBypassPairNow(first.getUniqueId(), second.getUniqueId());
            sender.sendMessage((removed ? "Removed" : "No bypass pair found for")
                + " " + displayName(first) + " and " + displayName(second) + ".");
        } catch (SQLException ex) {
            plugin.getLogger().warning("Failed to remove reward IP bypass pair: " + ex.getMessage());
            sender.sendMessage("Failed to remove reward IP bypass pair. Check console for details.");
        }
        return true;
    }

    private boolean handleListIpBypassCommand(CommandSender sender, String[] args) {
        OfflinePlayer target = findOfflineCommandTarget(sender, args, 2);
        if (target == null) {
            return true;
        }
        try {
            Set<UUID> pairedPlayers = storage.listIpBypassPairsNow(target.getUniqueId());
            if (pairedPlayers.isEmpty()) {
                sender.sendMessage("No reward IP bypass pairs found for " + displayName(target) + ".");
                return true;
            }
            sender.sendMessage("Reward IP bypass pairs for " + displayName(target) + ":");
            for (UUID pairedPlayerId : pairedPlayers) {
                sender.sendMessage("- " + displayName(Bukkit.getOfflinePlayer(pairedPlayerId)));
            }
        } catch (SQLException ex) {
            plugin.getLogger().warning("Failed to list reward IP bypass pairs: " + ex.getMessage());
            sender.sendMessage("Failed to list reward IP bypass pairs. Check console for details.");
        }
        return true;
    }

    private Player findCommandTarget(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendAdminUsage(sender);
            return null;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(messages.get("player-not-found"));
        }
        return target;
    }

    private OfflinePlayer findOfflineCommandTarget(CommandSender sender, String[] args, int index) {
        if (args.length <= index) {
            sendIpBypassUsage(sender);
            return null;
        }
        OfflinePlayer target = playerLookup.findPlayer(args[index]);
        if (target == null) {
            sender.sendMessage(messages.get("player-not-found"));
        }
        return target;
    }

    private void sendAdminUsage(CommandSender sender) {
        sender.sendMessage("Usage: /enthusiatags rewards sync <player>");
        sender.sendMessage("       /enthusiatags rewards syncall");
        sender.sendMessage("       /enthusiatags rewards debug <player>");
        sender.sendMessage("       /enthusiatags rewards ipbypass add <player1> <player2>");
        sender.sendMessage("       /enthusiatags rewards ipbypass remove <player1> <player2>");
        sender.sendMessage("       /enthusiatags rewards ipbypass list <player>");
    }

    private void sendIpBypassUsage(CommandSender sender) {
        sender.sendMessage("Usage: /enthusiatags rewards ipbypass add <player1> <player2>");
        sender.sendMessage("       /enthusiatags rewards ipbypass remove <player1> <player2>");
        sender.sendMessage("       /enthusiatags rewards ipbypass list <player>");
    }

    private String displayName(OfflinePlayer player) {
        String name = player.getName();
        return name == null || name.isBlank() ? player.getUniqueId().toString() : name;
    }

    private void sendDebug(CommandSender sender, Player player) {
        RewardPlayerState state = playerStates.get(player.getUniqueId());
        sender.sendMessage("Reward debug for " + player.getName() + ":");
        sender.sendMessage("  loaded: " + (state != null && state.isLoaded()));
        sender.sendMessage("  loading: " + pendingLoads.containsKey(player.getUniqueId()));
        sender.sendMessage("  sync: " + syncStatus());
        if (state != null && state.isLoaded()) {
            sender.sendMessage("  claimed: " + state.claimedRewards());
            sender.sendMessage("  counters: " + state.counters());
        }
        ProgressSnapshot snapshot = getProgressSnapshot(player);
        for (RewardDefinition reward : rewards.values()) {
            for (RewardCriterion criterion : reward.getCriteria()) {
                sender.sendMessage("  " + reward.getId() + " / " + criterion.getLabel() + " [" + criterion.getSourceType()
                    + "]: " + getProgress(player, criterion, snapshot) + "/" + criterion.getAmount());
            }
        }
    }

    public void setState(UUID playerId, String key, String value) {
        RewardPlayerState state = getLoadedState(playerId);
        if (state == null || !state.isLoaded()) {
            performanceMonitor.increment("rewards.state.skipped-unloaded");
            return;
        }
        if (value == null || value.isBlank()) {
            state.states().remove(key);
        } else {
            state.states().put(key, value);
        }
        state.setDirty(true);
    }

    private RewardPlayerState getOrCreateState(UUID playerId) {
        return playerStates.computeIfAbsent(playerId, ignored -> new RewardPlayerState());
    }

    private RewardPlayerState getOrCreateLoadedState(UUID playerId) {
        RewardPlayerState state = getOrCreateState(playerId);
        if (state.isLoaded()) {
            return state;
        }
        preloadPlayer(playerId);
        performanceMonitor.increment("rewards.state.skipped-unloaded");
        return state;
    }

    private RewardPlayerState getLoadedState(UUID playerId) {
        RewardPlayerState state = playerStates.get(playerId);
        if (state == null || !state.isLoaded()) {
            return null;
        }
        return state;
    }

    private boolean reserveIpClaim(Player player, String rewardId) {
        String ipAddress = getPlayerIpAddress(player);
        if (ipAddress.isBlank()) {
            performanceMonitor.increment("rewards.claim.ip-missing");
            return true;
        }
        try {
            boolean reserved = storage.reserveIpClaimNow(player.getUniqueId(), rewardId, ipAddress);
            if (!reserved) {
                performanceMonitor.increment("rewards.claim.ip-blocked");
            }
            return reserved;
        } catch (SQLException ex) {
            plugin.getLogger().warning("Failed to check reward IP claim for " + player.getUniqueId() + ": " + ex.getMessage());
            performanceMonitor.increment("rewards.claim.ip-check-failed");
            return false;
        }
    }

    private String getPlayerIpAddress(Player player) {
        java.net.InetSocketAddress address = player.getAddress();
        if (address == null) {
            return "";
        }
        if (address.getAddress() != null) {
            return address.getAddress().getHostAddress();
        }
        return address.getHostString() == null ? "" : address.getHostString();
    }

    private void reserveExistingIpClaims(Player player, RewardPlayerState state) {
        if (state == null || !state.isLoaded() || state.claimedRewards().isEmpty()) {
            return;
        }
        String ipAddress = getPlayerIpAddress(player);
        if (ipAddress.isBlank()) {
            return;
        }
        UUID playerId = player.getUniqueId();
        for (String rewardId : state.claimedRewards()) {
            storage.reserveIpClaimAsync(playerId, rewardId, ipAddress).exceptionally(throwable -> {
                plugin.getLogger().warning("Failed to backfill reward IP claim for " + playerId + ": " + throwable.getMessage());
                return false;
            });
        }
    }

    private void notifyUnlockedRewards(Player player, ProgressSnapshot snapshot) {
        RewardPlayerState state = getLoadedState(player.getUniqueId());
        if (state == null || !state.isLoaded()) {
            return;
        }
        List<RewardDefinition> newlyUnlocked = new ArrayList<>();
        for (RewardDefinition reward : rewards.values()) {
            String rewardId = reward.getId().toLowerCase(Locale.ROOT);
            if (state.claimedRewards().contains(rewardId)) {
                continue;
            }
            String notificationKey = REWARD_UNLOCK_NOTIFIED_PREFIX + rewardId;
            if (state.states().containsKey(notificationKey)) {
                continue;
            }
            RewardEvaluation evaluation = evaluate(player, reward, snapshot);
            if (!evaluation.claimable()) {
                continue;
            }
            state.states().put(notificationKey, "true");
            newlyUnlocked.add(reward);
        }
        if (!newlyUnlocked.isEmpty()) {
            state.setDirty(true);
            flushPlayerBlocking(player.getUniqueId());
            int limit = Math.max(1, plugin.getConfig().getInt("rewards.unlock-notifications.individual-limit", 3));
            for (int index = 0; index < Math.min(limit, newlyUnlocked.size()); index++) {
                sendUnlockNotification(player, newlyUnlocked.get(index), false);
                performanceMonitor.increment("rewards.unlock-notified");
            }
            if (newlyUnlocked.size() > limit) {
                Component summary = LegacyComponentSerializer.legacyAmpersand().deserialize(
                    messages.get("rewards-unlocked-summary")
                        .replace("{count}", String.valueOf(newlyUnlocked.size() - limit)))
                    .clickEvent(ClickEvent.runCommand("/rewards"))
                    .hoverEvent(HoverEvent.showText(LegacyComponentSerializer.legacyAmpersand().deserialize(
                        messages.get("rewards-unlocked-summary-hover"))));
                player.sendMessage(summary);
            }
            playUnlockSound(player);
        }
    }

    private void hydrateState(RewardPlayerState state, RewardStorage.StoredRewardData data) {
        state.claimedRewards().clear();
        state.claimedRewards().addAll(data.claims());
        state.counters().clear();
        state.counters().putAll(data.counters());
        state.states().clear();
        state.states().putAll(data.states());
        Long oldPvpDeaths = state.counters().remove("PVP_DEATHS");
        if (oldPvpDeaths != null) {
            state.counters().merge("pvp_deaths", oldPvpDeaths, Math::max);
        }
        state.setLoaded(true);
        state.setDirty(false);
    }

    private void queueCounterDelta(UUID playerId, String key, long delta) {
        pendingCounterDeltas.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>())
            .merge(key, delta, Long::sum);
        performanceMonitor.increment("rewards.counter.deferred");
    }

    private void applyPendingDeltas(UUID playerId, RewardPlayerState state) {
        Map<String, Long> deltas = pendingCounterDeltas.remove(playerId);
        if (deltas == null || deltas.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Long> entry : deltas.entrySet()) {
            state.counters().merge(entry.getKey(), entry.getValue(), Long::sum);
        }
        state.setDirty(true);
        performanceMonitor.add("rewards.counter.deferred-applied", deltas.size());
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

    private long getBukkitStat(Player player, Statistic statistic) {
        if (statistic == null) {
            return 0L;
        }
        try {
            return player.getStatistic(statistic);
        } catch (IllegalArgumentException ex) {
            return 0L;
        }
    }

    private long getBukkitMaterialStat(Player player, Statistic statistic, Material material) {
        if (statistic == null || material == null) {
            return 0L;
        }
        try {
            return player.getStatistic(statistic, material);
        } catch (IllegalArgumentException ex) {
            return 0L;
        }
    }

    private long getBukkitEntityStat(Player player, Statistic statistic, EntityType entityType) {
        if (statistic == null || entityType == null) {
            return 0L;
        }
        try {
            return player.getStatistic(statistic, entityType);
        } catch (IllegalArgumentException ex) {
            return 0L;
        }
    }

    private String criterionCacheKey(RewardCriterion criterion) {
        return criterion.getSourceType() + ":" + criterion.getType() + ":" + criterion.getStatistic()
            + ":" + criterion.getMaterial() + ":" + criterion.getEntityType() + ":" + criterion.getKey();
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
        resolved = placeholderApiHook.apply(player, resolved);
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

        long tokenizedMinutes = parseTokenizedPlaytimeMinutes(raw);
        if (tokenizedMinutes >= 0L) {
            return tokenizedMinutes;
        }
        return parseNumericPlaytimeMinutes(raw, placeholder);
    }

    private void sendUnlockNotification(Player player, RewardDefinition reward, boolean playSound) {
        String description = reward.getDescription().isEmpty() ? "" : reward.getDescription().get(0);
        String labels = reward.getActions().stream().map(action -> {
            String formatted = formatAction(action);
            return formatted == null || formatted.isBlank() ? action.getLabel() : formatted;
        }).filter(value -> value != null && !value.isBlank()).reduce((a, b) -> a + ", " + b).orElse("Reward");
        String template = messages.get("rewards-unlocked-rich")
            .replace("{reward}", reward.getName()).replace("{reward_id}", reward.getId())
            .replace("{description}", description).replace("{category}", reward.getCategory())
            .replace("{rewards}", labels);
        Component body = LegacyComponentSerializer.legacyAmpersand().deserialize(template);
        Component button = LegacyComponentSerializer.legacyAmpersand().deserialize(
            messages.get("rewards-unlocked-button"))
            .clickEvent(ClickEvent.runCommand("/rewards open " + reward.getId()))
            .hoverEvent(HoverEvent.showText(LegacyComponentSerializer.legacyAmpersand().deserialize(
                messages.get("rewards-unlocked-hover"))));
        player.sendMessage(body.append(Component.newline()).append(button));
        if (playSound) playUnlockSound(player);
    }

    private void playUnlockSound(Player player) {
        if (!plugin.getConfig().getBoolean("rewards.unlock-sound.enabled", true)) return;
        String configured = plugin.getConfig().getString("rewards.unlock-sound.sound", "BLOCK_AMETHYST_BLOCK_CHIME");
        Sound sound;
        try {
            sound = Sound.valueOf(configured.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            sound = Sound.BLOCK_AMETHYST_BLOCK_CHIME;
            plugin.getLogger().warning("Invalid rewards.unlock-sound.sound '" + configured
                + "'; using BLOCK_AMETHYST_BLOCK_CHIME");
        }
        player.playSound(player.getLocation(), sound,
            (float) plugin.getConfig().getDouble("rewards.unlock-sound.volume", 0.35D),
            (float) plugin.getConfig().getDouble("rewards.unlock-sound.pitch", 1.15D));
    }

    private long parseTokenizedPlaytimeMinutes(String raw) {
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
        return matched ? minutes : -1L;
    }

    private long parseNumericPlaytimeMinutes(String raw, String placeholder) {
        String digits = NON_DIGIT_PATTERN.matcher(raw).replaceAll("");
        if (digits.isEmpty()) {
            return 0L;
        }

        try {
            long value = Long.parseLong(digits);
            String token = placeholder == null ? "" : placeholder.toLowerCase(Locale.ROOT);
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
        Method method = baltopMethod;
        if (plugin == null || method == null) {
            return false;
        }
        try {
            Object result = method.invoke(plugin, playerId, 3);
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

    private boolean isCriterionAvailable(RewardCriterion criterion) {
        if (criterion == null || !criterion.isValid()) {
            return false;
        }
        return switch (criterion.getSourceType()) {
            case VAULT_BALANCE -> vaultHook.isAvailable();
            case BALTOP -> baltopPlugin != null;
            case PLAYTIME -> playtimeHook.isAvailable() || config.allowPlaceholderPlaytimeFallback();
            default -> isCriterionAvailable(criterion.getType());
        };
    }

    private boolean areActionsAvailable(RewardDefinition reward) {
        if (!vaultHook.isAvailable()) {
            return reward.getActions().stream().noneMatch(action -> action.getType() == RewardActionType.MONEY);
        }
        return reward.getActions().stream().allMatch(action -> action.getType() != RewardActionType.ITEM
            || (action.getMaterial() != null && action.getItemAmount() > 0));
    }

    private boolean deliverItem(Player player, RewardAction action) {
        if (action.getMaterial() == null || action.getItemAmount() <= 0) return false;
        ItemStack item = new ItemStack(action.getMaterial(), action.getItemAmount());
        ItemMeta meta = item.getItemMeta();
        if (action.getDisplayName() != null && !action.getDisplayName().isBlank()) {
            meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize(action.getDisplayName()));
        }
        if (!action.getLore().isEmpty()) {
            meta.lore(action.getLore().stream().map(LegacyComponentSerializer.legacyAmpersand()::deserialize).toList());
        }
        item.setItemMeta(meta);
        if (!hasInventoryCapacity(player, item)) {
            return false;
        }
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
        return leftovers.isEmpty();
    }

    private boolean hasInventoryCapacity(Player player, ItemStack item) {
        int capacity = 0;
        int maxStack = item.getMaxStackSize();
        for (ItemStack existing : player.getInventory().getStorageContents()) {
            if (existing == null || existing.getType().isAir()) {
                capacity += maxStack;
            } else if (existing.isSimilar(item)) {
                capacity += Math.max(0, maxStack - existing.getAmount());
            }
            if (capacity >= item.getAmount()) {
                return true;
            }
        }
        return false;
    }

    private void refreshIntegrations() {
        vaultHook.setup();
        playtimeHook.setup();
        baltopPlugin = findBaltopPlugin();
        baltopMethod = findBaltopMethod(baltopPlugin);
        if (baltopMethod == null) {
            baltopPlugin = null;
        }
        refreshIntegrationWarnings();
    }

    private Plugin findBaltopPlugin() {
        Plugin configured = Bukkit.getPluginManager().getPlugin(config.baltopPluginName());
        if (configured != null) {
            return configured;
        }
        for (Plugin candidate : Bukkit.getPluginManager().getPlugins()) {
            if (findBaltopMethod(candidate) != null) {
                return candidate;
            }
        }
        return null;
    }

    private Method findBaltopMethod(Plugin candidate) {
        if (candidate == null) {
            return null;
        }
        try {
            return candidate.getClass().getMethod("isInBaltopTop", UUID.class, int.class);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private void refreshIntegrationWarnings() {
        if (baltopPlugin != null) {
            baltopMethod = findBaltopMethod(baltopPlugin);
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
        storage = new RewardStorage(new File(dataFolder, "rewards.db"), performanceMonitor);
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

    private void startGlobalScanTask() {
        stopGlobalScanTask();
        if (!plugin.getConfig().getBoolean("global-scan.enabled", true)) {
            return;
        }
        long interval = Math.max(60L, plugin.getConfig().getLong("global-scan.interval-minutes", 10L) * 60L * 20L);
        globalScanTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> enqueueOnlinePlayers(false), interval, interval);
    }

    private void stopGlobalScanTask() {
        if (globalScanTask != null) {
            globalScanTask.cancel();
            globalScanTask = null;
        }
    }

    public void queueSyncAll() {
        enqueueOnlinePlayers(false);
        performanceMonitor.increment("global-scan.syncall-queued");
    }

    public void syncPlayer(Player player) {
        if (player == null) {
            return;
        }
        if (enqueueRewardSync(player.getUniqueId(), true)) {
            syncQueuedTotal++;
            performanceMonitor.increment("global-scan.players-queued");
            startSyncDrainTask();
        }
        performanceMonitor.increment("global-scan.manual-player-sync");
    }

    private void enqueueOnlinePlayers(boolean front) {
        int added = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (enqueueRewardSync(player.getUniqueId(), front)) {
                added++;
            }
        }
        if (added > 0) {
            syncQueuedTotal += added;
            performanceMonitor.add("global-scan.players-queued", added);
            startSyncDrainTask();
        }
    }

    private boolean enqueueRewardSync(UUID playerId, boolean front) {
        if (playerId == null || !queuedRewardSyncPlayers.add(playerId)) {
            return false;
        }
        if (front) {
            rewardSyncQueue.addFirst(playerId);
        } else {
            rewardSyncQueue.addLast(playerId);
        }
        return true;
    }

    private void startSyncDrainTask() {
        if (syncDrainTask != null) {
            return;
        }
        syncDrainTask = Bukkit.getScheduler().runTaskTimer(plugin, this::drainRewardSyncQueue, 1L, 1L);
    }

    private void stopSyncDrainTask() {
        if (syncDrainTask != null) {
            syncDrainTask.cancel();
            syncDrainTask = null;
        }
    }

    private void queueUnlockCheck(UUID playerId) {
        if (playerId == null) {
            return;
        }
        queuedUnlockChecks.add(playerId);
        if (unlockCheckTask == null) {
            unlockCheckTask = Bukkit.getScheduler().runTaskTimer(plugin, this::drainUnlockChecks, 20L, 20L);
        }
    }

    private void stopUnlockCheckTask() {
        if (unlockCheckTask != null) {
            unlockCheckTask.cancel();
            unlockCheckTask = null;
        }
    }

    private void drainUnlockChecks() {
        int processed = 0;
        for (UUID playerId : List.copyOf(queuedUnlockChecks)) {
            if (processed >= MAX_UNLOCK_CHECKS_PER_RUN) {
                break;
            }
            if (!queuedUnlockChecks.remove(playerId)) {
                continue;
            }
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                queueProgressRefresh(player);
            }
            processed++;
        }
        if (queuedUnlockChecks.isEmpty()) {
            stopUnlockCheckTask();
        }
    }

    private void drainRewardSyncQueue() {
        long started = System.nanoTime();
        int max = Math.max(1, plugin.getConfig().getInt("global-scan.max-players-per-tick", 1));
        int processed = 0;
        while (processed < max) {
            UUID playerId = rewardSyncQueue.pollFirst();
            if (playerId == null) {
                break;
            }
            queuedRewardSyncPlayers.remove(playerId);
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                syncStaleStates++;
                performanceMonitor.increment("global-scan.players-stale");
                processed++;
                continue;
            }
            syncPlayerNow(player);
            processed++;
        }
        if (processed > 0) {
            syncProcessedTotal += processed;
            syncLastDurationMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            performanceMonitor.add("global-scan.players-processed", processed);
            performanceMonitor.recordDurationMillis("global-scan.drain", syncLastDurationMillis);
        }
        if (rewardSyncQueue.isEmpty()) {
            stopSyncDrainTask();
        }
    }

    private void syncPlayerNow(Player player) {
        RewardPlayerState state = playerStates.get(player.getUniqueId());
        if (state == null || !state.isLoaded()) {
            syncStaleStates++;
            preloadPlayer(player.getUniqueId());
        } else {
            syncRepairedStates += repairCustomCountersFromBukkit(player, state);
        }
        queueProgressRefresh(player);
    }

    private int repairCustomCountersFromBukkit(Player player, RewardPlayerState state) {
        if (!plugin.getConfig().getBoolean("global-scan.repair-mode", true)
            || plugin.getConfig().getBoolean("global-scan.report-only", false)) {
            return 0;
        }
        int repaired = 0;
        repaired += raiseCounterToAtLeast(player, state, "netherrack_mined",
            getBukkitMaterialStat(player, Statistic.MINE_BLOCK, Material.NETHERRACK));
        repaired += raiseCounterToAtLeast(player, state, "stone_mined",
            getBukkitMaterialStat(player, Statistic.MINE_BLOCK, Material.STONE));
        repaired += raiseCounterToAtLeast(player, state, "iron_ore_mined",
            getBukkitMaterialStat(player, Statistic.MINE_BLOCK, Material.IRON_ORE));
        if (repaired > 0) {
            state.setDirty(true);
            flushPlayerAsync(player.getUniqueId(), state);
        }
        return repaired;
    }

    private int raiseCounterToAtLeast(Player player, RewardPlayerState state, String key, long realValue) {
        long cached = state.counters().getOrDefault(key, 0L);
        if (realValue <= cached) {
            return 0;
        }
        state.counters().put(key, realValue);
        if (plugin.getConfig().getBoolean("global-scan.debug-log-repairs", false)) {
            plugin.getLogger().info("Reward sync repaired " + player.getName() + " " + key + ": " + cached + " -> " + realValue);
        }
        return 1;
    }

    public String syncStatus() {
        return "queued=" + syncQueuedTotal
            + ", processed=" + syncProcessedTotal
            + ", remaining=" + rewardSyncQueue.size()
            + ", last-ms=" + syncLastDurationMillis
            + ", repaired=" + syncRepairedStates
            + ", stale=" + syncStaleStates;
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
        File file = new File(plugin.getDataFolder(), REWARDS_FILE);
        if (!file.exists()) {
            plugin.saveResource(REWARDS_FILE, false);
        }
    }

    private void loadConfig() {
        File file = new File(plugin.getDataFolder(), REWARDS_FILE);
        var configFile = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        config = RewardsConfig.from(configFile);
        ConfigurationSection section = configFile.getConfigurationSection("rewards");
        if (section == null) {
            return;
        }
        for (String id : section.getKeys(false)) {
            String name = section.getString(id + ".name", id);
            List<String> description = section.getStringList(id + ".description");
            Material icon = parseMaterial(section.getString(id + ".icon", "NAME_TAG"), "rewards." + id + ".icon", true);
            List<RewardCriterion> criteria = loadCriteria(section.getConfigurationSection(id + ".criteria"));
            List<RewardAction> actions = loadActions(section.getConfigurationSection(id + ".rewards"));
            String category = section.getString(id + ".category", inferCategory(criteria));
            RewardCompletionMode completionMode;
            try {
                completionMode = RewardCompletionMode.valueOf(section.getString(id + ".completion-mode", "LATCHED")
                    .toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                completionMode = RewardCompletionMode.LATCHED;
                plugin.getLogger().warning("Invalid completion-mode for reward " + id + "; using LATCHED");
            }
            rewards.put(id.toLowerCase(Locale.ROOT),
                new RewardDefinition(id, name, description, icon, criteria, actions, category, completionMode));
        }
    }

    private List<RewardCriterion> loadCriteria(ConfigurationSection section) {
        List<RewardCriterion> criteria = new ArrayList<>();
        if (section == null) {
            return criteria;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(key);
            RewardCriterion criterion = loadCriterion(section, key, entry);
            if (criterion != null) {
                criteria.add(criterion);
            }
        }
        return criteria;
    }

    private RewardCriterion loadCriterion(ConfigurationSection section, String key, ConfigurationSection entry) {
        if (entry == null) {
            return null;
        }
        String path = section.getCurrentPath() + "." + key;
        RewardCriterionType type = parseEnum(RewardCriterionType.class, entry.getString("type", "CUSTOM_COUNTER"),
            path + ".type");
        if (type == null) {
            return null;
        }
        long amount = entry.contains("required") ? entry.getLong("required", 1) : entry.getLong("amount", 1);
        Material material = parseMaterial(entry.getString("material", ""), path + ".material", false);
        String counterKey = criterionCounterKey(entry);
        int maxY = entry.getInt("max-y", config.undergroundMaxY());
        if (type == RewardCriterionType.CUSTOM_COUNTER && STEPS_WALKED_COUNTER.equalsIgnoreCase(counterKey)) {
            type = RewardCriterionType.STEPS_WALKED;
            counterKey = "";
        }
        RewardSourceType sourceType = parseSource(entry.getString("source", ""));
        Statistic statistic = parseStatistic(entry.getString("statistic", ""), path + ".statistic");
        EntityType entityType = parseEnum(EntityType.class, entry.getString("entity", ""), path + ".entity");
        CriterionSource resolved = resolveCriterionSource(type, sourceType, statistic, material, counterKey);
        boolean valid = validateCriterionSource(resolved.sourceType(), resolved.statistic(), resolved.material(), entityType,
            resolved.key(), path);
        String label = entry.getString("label", defaultLabel(type, resolved.material(), resolved.key()));
        return new RewardCriterion(type, resolved.sourceType(), amount, resolved.material(), resolved.statistic(), entityType,
            resolved.key(), maxY, label, valid);
    }

    private String criterionCounterKey(ConfigurationSection entry) {
        String counterKey = entry.getString("key", "");
        return entry.contains("counter") ? entry.getString("counter", counterKey) : counterKey;
    }

    private CriterionSource resolveCriterionSource(RewardCriterionType type,
                                                   RewardSourceType sourceType,
                                                   Statistic statistic,
                                                   Material material,
                                                   String counterKey) {
        if (sourceType != RewardSourceType.LEGACY) {
            return new CriterionSource(sourceType, statistic, material, counterKey);
        }
        SourceDefaults defaults = legacySource(type, counterKey, material);
        return new CriterionSource(
            defaults.sourceType(),
            defaults.statistic() == null ? statistic : defaults.statistic(),
            defaults.material() == null ? material : defaults.material(),
            defaults.key() == null ? counterKey : defaults.key()
        );
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
            RewardActionType type = parseEnum(RewardActionType.class, entry.getString("type", "TAG"),
                section.getCurrentPath() + "." + key + ".type");
            if (type == null) {
                continue;
            }
            String value = entry.getString("id", "");
            double amount = entry.getDouble("amount", 0.0);
            String label = entry.getString("label", "");
            Material itemMaterial = type == RewardActionType.ITEM
                ? parseMaterial(entry.getString("material", value), section.getCurrentPath() + "." + key + ".material", false)
                : null;
            int itemAmount = type == RewardActionType.ITEM ? entry.getInt("amount", 1) : 0;
            String displayName = entry.getString("display-name", "");
            List<String> lore = entry.getStringList("lore");
            actions.add(new RewardAction(type, value, amount, label, itemMaterial, itemAmount, displayName, lore));
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

    private RewardSourceType parseSource(String value) {
        if (value == null || value.isBlank()) {
            return RewardSourceType.LEGACY;
        }
        RewardSourceType parsed = parseEnum(RewardSourceType.class, value, "source");
        return parsed == null ? RewardSourceType.LEGACY : parsed;
    }

    private SourceDefaults legacySource(RewardCriterionType type, String key, Material material) {
        return switch (type) {
            case KILLS_TOTAL -> new SourceDefaults(RewardSourceType.BUKKIT_STAT, Statistic.PLAYER_KILLS, null, null);
            case DEATHS_TOTAL -> new SourceDefaults(RewardSourceType.BUKKIT_STAT, Statistic.DEATHS, null, null);
            case STEPS_WALKED -> new SourceDefaults(RewardSourceType.LEGACY, null, null, null);
            case BLOCK_MINED -> new SourceDefaults(RewardSourceType.BUKKIT_STAT_MATERIAL, Statistic.MINE_BLOCK, material, null);
            case BALANCE_AT_LEAST -> new SourceDefaults(RewardSourceType.VAULT_BALANCE, null, null, null);
            case BALTOP_TOP3 -> new SourceDefaults(RewardSourceType.BALTOP, null, null, null);
            case PLAYTIME_ACTIVE_MINUTES, PLAYTIME_AFK_MINUTES, PLAYTIME_TOTAL_MINUTES ->
                new SourceDefaults(RewardSourceType.PLAYTIME, null, null, key);
            case CUSTOM_COUNTER -> legacyCustomCounterSource(key);
            default -> new SourceDefaults(RewardSourceType.CUSTOM_COUNTER, null, null, keyForType(type, key));
        };
    }

    private SourceDefaults legacyCustomCounterSource(String key) {
        String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "netherrack_mined" -> new SourceDefaults(RewardSourceType.BUKKIT_STAT_MATERIAL, Statistic.MINE_BLOCK, Material.NETHERRACK, key);
            case "stone_mined" -> new SourceDefaults(RewardSourceType.BUKKIT_STAT_MATERIAL, Statistic.MINE_BLOCK, Material.STONE, key);
            case "iron_ore_mined" -> new SourceDefaults(RewardSourceType.BUKKIT_STAT_MATERIAL, Statistic.MINE_BLOCK, Material.IRON_ORE, key);
            default -> new SourceDefaults(RewardSourceType.CUSTOM_COUNTER, null, null, key);
        };
    }

    private String keyForType(RewardCriterionType type, String key) {
        if (key != null && !key.isBlank()) {
            return key;
        }
        return DEFAULT_COUNTER_KEYS.getOrDefault(type, key);
    }

    private boolean validateCriterionSource(RewardSourceType sourceType,
                                            Statistic statistic,
                                            Material material,
                                            EntityType entityType,
                                            String key,
                                            String path) {
        boolean valid = validateStatisticSource(sourceType, statistic, material, entityType, path)
            && validateCounterSource(sourceType, key, path);
        if (!valid) {
            performanceMonitor.increment("config.validation.reward-criterion-invalid");
        }
        return valid;
    }

    private boolean validateStatisticSource(RewardSourceType sourceType,
                                            Statistic statistic,
                                            Material material,
                                            EntityType entityType,
                                            String path) {
        boolean valid = true;
        if (requiresStatistic(sourceType) && statistic == null) {
            logInvalidCriterion(path, "missing/bad Bukkit statistic.");
            valid = false;
        }
        if (sourceType == RewardSourceType.BUKKIT_STAT_MATERIAL && material == null) {
            logInvalidCriterion(path, "missing/bad material.");
            valid = false;
        }
        if (sourceType == RewardSourceType.BUKKIT_STAT_ENTITY && entityType == null) {
            logInvalidCriterion(path, "missing/bad entity.");
            valid = false;
        }
        return valid;
    }

    private boolean validateCounterSource(RewardSourceType sourceType, String key, String path) {
        if (sourceType != RewardSourceType.CUSTOM_COUNTER || (key != null && !key.isBlank())) {
            return true;
        }
        logInvalidCriterion(path, "missing custom counter key.");
        return false;
    }

    private boolean requiresStatistic(RewardSourceType sourceType) {
        return sourceType == RewardSourceType.BUKKIT_STAT
            || sourceType == RewardSourceType.BUKKIT_STAT_MATERIAL
            || sourceType == RewardSourceType.BUKKIT_STAT_ENTITY;
    }

    private void logInvalidCriterion(String path, String reason) {
        plugin.getLogger().warning(INVALID_CRITERION_PREFIX + path + ": " + reason);
    }

    private Material parseMaterial(String value, String path, boolean fallbackNameTag) {
        if (value == null || value.isBlank()) {
            return fallbackNameTag ? Material.NAME_TAG : null;
        }
        Material material = Material.matchMaterial(value);
        if (material == null) {
            plugin.getLogger().warning("Invalid material at " + path + ": " + value);
            performanceMonitor.increment("config.validation.bad-material");
            return fallbackNameTag ? Material.NAME_TAG : null;
        }
        return material;
    }

    private Statistic parseStatistic(String value, String path) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Statistic.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Invalid statistic at " + path + ": " + value);
            performanceMonitor.increment("config.validation.bad-statistic");
            return null;
        }
    }

    private <E extends Enum<E>> E parseEnum(Class<E> type, String value, String path) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Invalid " + type.getSimpleName() + " at " + path + ": " + value);
            performanceMonitor.increment("config.validation.bad-enum");
            return null;
        }
    }

    private String defaultLabel(RewardCriterionType type, Material material, String key) {
        if (type == RewardCriterionType.BLOCK_MINED) {
            return material == null ? "Blocks mined" : material.name();
        }
        if (type == RewardCriterionType.CUSTOM_COUNTER) {
            return key == null || key.isBlank() ? "Progress" : key;
        }
        return DEFAULT_LABELS.getOrDefault(type, "Progress");
    }

    private static Map<RewardCriterionType, String> defaultLabels() {
        Map<RewardCriterionType, String> labels = new EnumMap<>(RewardCriterionType.class);
        labels.put(RewardCriterionType.PLAYTIME_ACTIVE_MINUTES, "Active playtime (min)");
        labels.put(RewardCriterionType.PLAYTIME_AFK_MINUTES, "AFK playtime (min)");
        labels.put(RewardCriterionType.PLAYTIME_TOTAL_MINUTES, "Total playtime (min)");
        labels.put(RewardCriterionType.PLAYTIME_CONSECUTIVE_ACTIVE_MINUTES, "Consecutive active (min)");
        labels.put(RewardCriterionType.UNDERGROUND_ACTIVE_MINUTES, "Underground active (min)");
        labels.put(RewardCriterionType.KILLS_TOTAL, "Player kills");
        labels.put(RewardCriterionType.DEATHS_TOTAL, "Deaths");
        labels.put(RewardCriterionType.KILL_STREAK_CURRENT, "Kill streak");
        labels.put(RewardCriterionType.DEATH_STREAK_SAME, "Deaths to same player");
        labels.put(RewardCriterionType.QUICK_KILL_COUNT, "Quick kills (10s)");
        labels.put(RewardCriterionType.KILL_FULL_ARMOR_COUNT, "Full armor kills");
        labels.put(RewardCriterionType.KILL_LOW_HEALTH_COUNT, "Low health wins");
        labels.put(RewardCriterionType.DEATH_CAUSE_COUNT, "Deaths by cause");
        labels.put(RewardCriterionType.BALANCE_AT_LEAST, "Balance");
        labels.put(RewardCriterionType.BALTOP_TOP3, "Baltop top 3");
        labels.put(RewardCriterionType.PING_MS_AT_LEAST, "Ping (ms)");
        labels.put(RewardCriterionType.STEPS_WALKED, "Blocks walked");
        labels.put(RewardCriterionType.PROJECTILE_HITS, "Projectile hits");
        return labels;
    }

    private static Map<RewardCriterionType, String> defaultCounterKeys() {
        Map<RewardCriterionType, String> keys = new EnumMap<>(RewardCriterionType.class);
        keys.put(RewardCriterionType.KILL_STREAK_CURRENT, "kill_streak");
        keys.put(RewardCriterionType.DEATH_STREAK_SAME, "death_streak_same");
        keys.put(RewardCriterionType.QUICK_KILL_COUNT, "quick_kill");
        keys.put(RewardCriterionType.KILL_FULL_ARMOR_COUNT, "kill_full_armor");
        keys.put(RewardCriterionType.KILL_LOW_HEALTH_COUNT, "kill_low_health");
        keys.put(RewardCriterionType.PROJECTILE_HITS, "projectile_hits");
        return keys;
    }

    public record ProgressSnapshot(long createdTick, Map<String, Long> values) {
    }

    private record SourceDefaults(RewardSourceType sourceType, Statistic statistic, Material material, String key) {
    }

    private record CriterionSource(RewardSourceType sourceType, Statistic statistic, Material material, String key) {
    }
}
