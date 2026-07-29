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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
    private volatile Map<String, RewardDefinition> rewards = Map.of();
    private final Map<UUID, RewardPlayerState> playerStates = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Void>> pendingLoads = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Long>> pendingCounterDeltas = new ConcurrentHashMap<>();
    private final Map<UUID, ProgressSnapshot> progressSnapshots = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<UUID> rewardSyncQueue = new ConcurrentLinkedDeque<>();
    private final java.util.Set<UUID> queuedRewardSyncPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> queuedUnlockChecks = ConcurrentHashMap.newKeySet();
    private final Set<String> inFlightClaims = ConcurrentHashMap.newKeySet();
    private final Set<CompletableFuture<?>> activeOperations = ConcurrentHashMap.newKeySet();
    private final IntegrationStatus integrationStatus = new IntegrationStatus();
    private final AtomicReference<ServiceLifecycle> lifecycle =
        new AtomicReference<>(ServiceLifecycle.STOPPED);
    private final AtomicBoolean reloadQueued = new AtomicBoolean(false);

    private RewardStorage storage;
    private ExecutorService claimExecutor;
    private volatile RewardsConfig config;
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
        if (!initStorage()) {
            lifecycle.set(ServiceLifecycle.STOPPED);
            rewards = Map.of();
            plugin.getLogger().severe("Reward functionality is disabled because durable reward storage is unavailable.");
            return;
        }
        claimExecutor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "enthusia-tags-reward-claim");
            thread.setDaemon(true);
            return thread;
        });
        lifecycle.set(ServiceLifecycle.RELOADING);
        reloadNow();
    }

    public void disable() {
        lifecycle.set(ServiceLifecycle.STOPPING);
        stopFlushTask();
        stopGlobalScanTask();
        stopSyncDrainTask();
        stopUnlockCheckTask();
        if (claimExecutor != null) {
            claimExecutor.shutdownNow();
            try {
                if (!claimExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    plugin.getLogger().severe("Reward claim workers did not stop; storage will remain open.");
                    inFlightClaims.clear();
                    lifecycle.set(ServiceLifecycle.STOPPED);
                    return;
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                plugin.getLogger().severe("Interrupted while stopping reward claim workers; storage will remain open.");
                inFlightClaims.clear();
                lifecycle.set(ServiceLifecycle.STOPPED);
                return;
            }
        }
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
        inFlightClaims.clear();
        activeOperations.clear();
        lifecycle.set(ServiceLifecycle.STOPPED);
    }

    public void reload() {
        ServiceLifecycle current = lifecycle.get();
        if (current == ServiceLifecycle.STOPPING || current == ServiceLifecycle.STOPPED) {
            return;
        }
        lifecycle.set(ServiceLifecycle.RELOADING);
        if (!activeOperations.isEmpty()) {
            if (reloadQueued.compareAndSet(false, true)) {
                CompletableFuture<?>[] claims = activeOperations.toArray(CompletableFuture[]::new);
                CompletableFuture.allOf(claims).whenComplete((ignored, throwable) ->
                    Bukkit.getScheduler().runTask(plugin, this::reloadNow));
            }
            return;
        }
        reloadNow();
    }

    private void reloadNow() {
        if (lifecycle.get() == ServiceLifecycle.STOPPING || lifecycle.get() == ServiceLifecycle.STOPPED) {
            return;
        }
        ensureDefaults();
        loadConfig();
        refreshIntegrations();
        progressCacheTicks = Math.max(20L, plugin.getConfig().getLong("performance.reward-progress-cache-ticks", 100L));
        startFlushTask();
        startGlobalScanTask();
        for (Player player : Bukkit.getOnlinePlayers()) {
            preloadPlayer(player.getUniqueId());
            queueProgressRefresh(player);
        }
        reloadQueued.set(false);
        lifecycle.set(ServiceLifecycle.RUNNING);
    }

    public RewardsConfig getConfig() {
        return config;
    }

    public Map<String, RewardDefinition> getRewards() {
        return rewards;
    }

    public boolean isAvailable() {
        return plugin.isEnabled() && lifecycle.get() == ServiceLifecycle.RUNNING
            && storage != null && claimExecutor != null && !claimExecutor.isShutdown();
    }

    public void runForOnlinePlayer(UUID playerId, java.util.function.Consumer<Player> action) {
        if (!isAvailable()) return;
        scheduleMain(() -> {
            if (!isAvailable()) return;
            Player player = onlinePlayer(playerId);
            if (player != null) action.accept(player);
        });
    }

    public List<String> getStaffWarnings() {
        return integrationStatus.warnings();
    }

    public void preloadPlayer(UUID playerId) {
        if (!isAvailable()) return;
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
                            drainItemOverflow(player);
                            recoverPendingRewards(player.getUniqueId());
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
            drainItemOverflow(player);
            recoverPendingRewards(player.getUniqueId());
            queueProgressRefresh(player);
        }
    }

    public void unloadPlayer(Player player) {
        UUID playerId = player.getUniqueId();
        RewardPlayerState state = playerStates.get(playerId);
        if (state != null) {
            flushPlayerAsync(playerId, state);
        }
        pendingLoads.remove(playerId);
        pendingCounterDeltas.remove(playerId);
        progressSnapshots.remove(playerId);
        playerStates.remove(playerId);
    }

    public boolean isClaimed(UUID playerId, String rewardId) {
        RewardPlayerState state = getLoadedState(playerId);
        return state != null && state.isClaimed(rewardId.toLowerCase(Locale.ROOT));
    }

    public CompletableFuture<RewardClaimResult> claimAsync(Player player, RewardDefinition reward) {
        if (lifecycle.get() != ServiceLifecycle.RUNNING || claimExecutor == null
            || claimExecutor.isShutdown()) {
            return CompletableFuture.completedFuture(RewardClaimResult.SERVICE_UNAVAILABLE);
        }
        RewardPlayerState state = getLoadedState(player.getUniqueId());
        if (state == null || !state.isLoaded()) {
            performanceMonitor.increment("rewards.claim.skipped-loading");
            return CompletableFuture.completedFuture(RewardClaimResult.LOADING);
        }
        String rewardId = reward.getId().toLowerCase(Locale.ROOT);
        if (state.isClaimed(rewardId)) {
            return CompletableFuture.completedFuture(RewardClaimResult.ALREADY_CLAIMED);
        }
        RewardEvaluation evaluation = evaluate(player, reward);
        if (evaluation.status() == RewardStatus.ITEM_QUEUED) {
            retryQueuedItems(player);
            return CompletableFuture.completedFuture(RewardClaimResult.ITEM_QUEUED);
        }
        if (evaluation.status() == RewardStatus.REQUIRES_RECONCILIATION) {
            return CompletableFuture.completedFuture(RewardClaimResult.RECONCILIATION_REQUIRED);
        }
        if (evaluation.status() == RewardStatus.CLAIM_PENDING) {
            return CompletableFuture.completedFuture(RewardClaimResult.CLAIM_IN_PROGRESS);
        }
        if (!evaluation.claimable()) {
            return CompletableFuture.completedFuture(RewardClaimResult.NOT_READY);
        }
        String claimKey = player.getUniqueId() + ":" + rewardId;
        if (!inFlightClaims.add(claimKey)) {
            return CompletableFuture.completedFuture(RewardClaimResult.CLAIM_IN_PROGRESS);
        }
        String ipAddress = getPlayerIpAddress(player);
        UUID playerId = player.getUniqueId();
        String playerName = player.getName();
        CompletableFuture<RewardClaimResult> future = new CompletableFuture<>();
        activeOperations.add(future);
        try {
            claimExecutor.execute(() -> {
                try {
                    future.complete(claimInternal(playerId, playerName, reward, ipAddress));
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            });
        } catch (RuntimeException ex) {
            activeOperations.remove(future);
            inFlightClaims.remove(claimKey);
            return CompletableFuture.completedFuture(RewardClaimResult.SERVICE_UNAVAILABLE);
        }
        future.whenComplete((ignored, throwable) -> {
            activeOperations.remove(future);
            if (lifecycle.get() == ServiceLifecycle.RELOADING && activeOperations.isEmpty()
                && reloadQueued.compareAndSet(false, true)) {
                Bukkit.getScheduler().runTask(plugin, this::reloadNow);
            }
        });
        return future;
    }

    private void resumeClaimAfterItem(UUID playerId, String rewardId) {
        if (lifecycle.get() != ServiceLifecycle.RUNNING || claimExecutor == null || claimExecutor.isShutdown()) {
            return;
        }
        RewardDefinition reward = rewards.get(rewardId);
        if (reward == null) {
            return;
        }
        String claimKey = playerId + ":" + rewardId;
        if (!inFlightClaims.add(claimKey)) {
            return;
        }
        CompletableFuture<RewardClaimResult> future = new CompletableFuture<>();
        activeOperations.add(future);
        try {
            claimExecutor.execute(() -> {
                try {
                    PlayerIdentity identity = callOnMain(() -> {
                        Player live = onlinePlayer(playerId);
                        return live == null ? null
                            : new PlayerIdentity(live.getName(), getPlayerIpAddress(live));
                    });
                    if (identity == null) {
                        inFlightClaims.remove(claimKey);
                        future.complete(RewardClaimResult.ITEM_QUEUED);
                    } else {
                        future.complete(claimInternal(playerId, identity.name(), reward, identity.ipAddress()));
                    }
                } catch (Throwable throwable) {
                    inFlightClaims.remove(claimKey);
                    future.completeExceptionally(throwable);
                }
            });
        } catch (RuntimeException ex) {
            activeOperations.remove(future);
            inFlightClaims.remove(claimKey);
            return;
        }
        future.whenComplete((result, throwable) -> activeOperations.remove(future));
    }

    private RewardClaimResult claimInternal(UUID playerId, String playerName, RewardDefinition reward,
                                            String ipAddress) {
        RewardPlayerState state = getLoadedState(playerId);
        if (state == null || !state.isLoaded()) return RewardClaimResult.LOADING;
        String rewardId = reward.getId().toLowerCase(Locale.ROOT);
        String claimKey = playerId + ":" + rewardId;
        try {
        if (state.isClaimed(rewardId)) {
            return RewardClaimResult.ALREADY_CLAIMED;
        }
        String legacyPrefix = "reward-action:" + rewardId + ":";
        boolean unresolvedLegacy = !state.stateKeysWithPrefix(legacyPrefix).isEmpty();
        if (unresolvedLegacy) {
            state.setOverall(rewardId, RewardStatus.REQUIRES_RECONCILIATION);
            persistStateBarrier(playerId, state);
            plugin.getLogger().warning("Reward " + rewardId + " for " + playerId
                + " has unresolved pre-ledger action state; automatic migration was refused.");
            return RewardClaimResult.RECONCILIATION_REQUIRED;
        }
        Map<String, RewardStorage.ActionLedgerEntry> ledger =
            storage.loadActionLedgerNow(playerId, rewardId);
        boolean unresolvedHistoricalAction = ledger.values().stream().anyMatch(entry ->
            entry.status() == RewardStatus.CLAIM_PENDING
                || entry.status() == RewardStatus.REQUIRES_RECONCILIATION);
        if (unresolvedHistoricalAction) {
            state.setOverall(rewardId, RewardStatus.REQUIRES_RECONCILIATION);
            persistStateBarrier(playerId, state);
            return RewardClaimResult.RECONCILIATION_REQUIRED;
        }
        if (!reserveIpClaim(playerId, rewardId, ipAddress)) {
            return RewardClaimResult.IP_ALREADY_CLAIMED;
        }

        state.setOverall(rewardId, RewardStatus.CLAIM_PENDING);
        if (!persistStateBarrier(playerId, state)) {
            storage.releaseIpClaimAsync(playerId, rewardId, ipAddress);
            return RewardClaimResult.DELIVERY_FAILED;
        }
        boolean anyActionDelivered = ledger.values().stream()
            .anyMatch(entry -> entry.status() == RewardStatus.CLAIMED
                || entry.status() == RewardStatus.REQUIRES_RECONCILIATION);
        for (RewardAction action : reward.getActions()) {
            String fingerprint = actionFingerprint(action);
            RewardStorage.ActionLedgerEntry existing = ledger.get(action.getActionId());
            if (existing != null && existing.status() == RewardStatus.CLAIMED
                && existing.fingerprint().equals(fingerprint)) {
                anyActionDelivered = true;
                continue;
            }
            if (existing != null && (existing.status() == RewardStatus.CLAIM_PENDING
                || existing.status() == RewardStatus.REQUIRES_RECONCILIATION
                || (existing.status() == RewardStatus.CLAIMED && !existing.fingerprint().equals(fingerprint)))) {
                state.setOverall(rewardId, RewardStatus.REQUIRES_RECONCILIATION);
                persistStateBarrier(playerId, state);
                return RewardClaimResult.RECONCILIATION_REQUIRED;
            }
            storage.saveActionLedgerNow(playerId, rewardId, action, fingerprint,
                RewardStatus.CLAIM_PENDING, null, null);
            boolean delivered;
            boolean ambiguous = false;
            VaultHook.DepositResult vaultResult = null;
            String evidence = null;
            switch (action.getType()) {
                case TAG -> {
                    delivered = waitForTagPersistence(playerId, action.getValue());
                }
                case MONEY -> {
                    Player livePlayer = callOnMain(() -> onlinePlayer(playerId));
                    if (livePlayer == null) {
                        delivered = false;
                        vaultResult = null;
                        evidence = "Player disconnected before Vault was invoked";
                        break;
                    }
                    vaultResult = callOnMain(() -> vaultHook.depositDetailed(livePlayer, action.getAmount()));
                    delivered = vaultResult.success()
                        && Double.compare(vaultResult.responseAmount(), action.getAmount()) == 0;
                    ambiguous = !delivered && (!"UNAVAILABLE".equals(vaultResult.responseType())
                        && (!"FAILURE".equals(vaultResult.responseType())
                            || vaultResult.balanceAfter() > vaultResult.balanceBefore()));
                    evidence = (vaultResult.errorMessage() == null ? "" : vaultResult.errorMessage())
                        + (ambiguous ? " [classified uncertain]" : " [classified definite]");
                }
                case COMMAND -> {
                    String command = action.getValue()
                        .replace("{player}", playerName)
                        .replace("%player%", playerName);
                    Player livePlayer = callOnMain(() -> onlinePlayer(playerId));
                    if (livePlayer == null) {
                        delivered = false;
                        evidence = "Player disconnected before command invocation";
                    } else {
                        delivered = callOnMain(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
                        evidence = delivered ? "dispatchCommand returned true"
                            : "dispatchCommand returned false after invocation";
                        ambiguous = !delivered;
                    }
                }
                case ITEM -> {
                    ItemDeliveryResult itemResult = deliverItem(playerId, rewardId, action, fingerprint);
                    if (itemResult == ItemDeliveryResult.QUEUED) {
                        state.setOverall(rewardId, RewardStatus.ITEM_QUEUED);
                        persistStateBarrier(playerId, state);
                        return RewardClaimResult.ITEM_QUEUED;
                    }
                    delivered = itemResult == ItemDeliveryResult.DELIVERED;
                    evidence = itemResult == ItemDeliveryResult.DELIVERED
                        ? "Inserted into the live player inventory" : "Item delivery failed before insertion";
                }
                default -> delivered = false;
            }
            if (!delivered) {
                RewardStatus failureStatus = ambiguous
                    ? RewardStatus.REQUIRES_RECONCILIATION : RewardStatus.DELIVERY_FAILED;
                storage.saveActionLedgerNow(playerId, rewardId, action, fingerprint,
                    failureStatus, vaultResult, evidence);
                state.setOverall(rewardId, failureStatus);
                if (!anyActionDelivered && !ambiguous) {
                    storage.releaseIpClaimAsync(playerId, rewardId, ipAddress);
                }
                persistStateBarrier(playerId, state);
                plugin.getLogger().warning("Reward delivery failed player=" + playerId
                    + " name=" + playerName + " reward=" + rewardId + " action=" + action.getType());
                return RewardClaimResult.DELIVERY_FAILED;
            }
            anyActionDelivered = true;
            storage.saveActionLedgerNow(playerId, rewardId, action, fingerprint,
                RewardStatus.CLAIMED, vaultResult, evidence);
        }

        long finalizedRevision = storage.finalizeRewardNow(playerId, rewardId);
        state.applyDurableFinalization(rewardId, finalizedRevision);
        invalidateProgress(playerId);
        return RewardClaimResult.SUCCESS;
        } catch (SQLException ex) {
            plugin.getLogger().severe("Reward claim ledger failed for " + playerId + ": " + ex.getMessage());
            return RewardClaimResult.DELIVERY_FAILED;
        } finally {
            inFlightClaims.remove(claimKey);
        }
    }

    private <T> T callOnMain(java.util.concurrent.Callable<T> callable) throws SQLException {
        if (lifecycle.get() == ServiceLifecycle.STOPPING || lifecycle.get() == ServiceLifecycle.STOPPED) {
            throw new SQLException("Reward service is stopping");
        }
        if (Bukkit.isPrimaryThread()) {
            try {
                return callable.call();
            } catch (Exception ex) {
                throw new SQLException("Main-thread reward action failed", ex);
            }
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (lifecycle.get() == ServiceLifecycle.STOPPING
                    || lifecycle.get() == ServiceLifecycle.STOPPED) {
                    result.completeExceptionally(new IllegalStateException("Reward service stopped"));
                    return;
                }
                try {
                    result.complete(callable.call());
                } catch (Exception ex) {
                    result.completeExceptionally(ex);
                }
            });
        } catch (RuntimeException ex) {
            throw new SQLException("Could not schedule main-thread reward action", ex);
        }
        try {
            return result.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted waiting for main-thread reward action", ex);
        } catch (ExecutionException ex) {
            throw new SQLException("Main-thread reward action failed", ex.getCause());
        } catch (TimeoutException ex) {
            throw new SQLException("Timed out waiting for main-thread reward action", ex);
        }
    }

    private boolean waitForTagPersistence(UUID playerId, String tagId) throws SQLException {
        try {
            return tagService.grantTagPersisted(playerId, tagId).get(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted waiting for tag persistence", ex);
        } catch (ExecutionException | TimeoutException ex) {
            throw new SQLException("Tag persistence did not complete safely", ex);
        }
    }

    private Player onlinePlayer(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        return player != null && player.isOnline() ? player : null;
    }

    private String actionFingerprint(RewardAction action) {
        return action.getType() + "|" + action.getValue() + "|" + action.getAmount() + "|"
            + action.getMaterial() + "|" + action.getItemAmount() + "|" + action.getDisplayName()
            + "|" + String.join("\\n", action.getLore());
    }

    private boolean persistStateBarrier(UUID playerId, RewardPlayerState state) {
        synchronized (state) {
            try {
                RewardStorage.StoredRewardData snapshot = state.barrierSnapshot();
                RewardStorage.WriteResult result = storage.saveNow(playerId, snapshot);
                if (result != RewardStorage.WriteResult.WRITTEN) {
                    plugin.getLogger().severe("Reward transition snapshot was rejected as stale for " + playerId
                        + " revision=" + snapshot.revision());
                    return false;
                }
                state.markClean(snapshot.revision());
                return true;
            } catch (SQLException ex) {
                plugin.getLogger().severe("Reward transition was not persisted for " + playerId + ": "
                    + ex.getMessage());
                return false;
            }
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
        if (state.isClaimed(rewardId)) {
            return new RewardEvaluation(RewardStatus.CLAIMED, progress, true, false, "Already delivered");
        }
        String delivery = state.getState("reward-delivery:" + rewardId);
        if (RewardStatus.REQUIRES_RECONCILIATION.name().equals(delivery)) {
            return new RewardEvaluation(RewardStatus.REQUIRES_RECONCILIATION, progress, true, false,
                "A previous delivery may have been interrupted");
        }
        if (RewardStatus.ITEM_QUEUED.name().equals(delivery)) {
            return new RewardEvaluation(RewardStatus.ITEM_QUEUED, progress, true, false,
                "A required item is safely queued");
        }
        if (RewardStatus.CLAIM_PENDING.name().equals(delivery)) {
            return new RewardEvaluation(RewardStatus.CLAIM_PENDING, progress, true, false,
                "Partial reward delivery is pending");
        }
        if (RewardStatus.DELIVERY_FAILED.name().equals(delivery)) {
            return new RewardEvaluation(RewardStatus.DELIVERY_FAILED, progress, true, true,
                "A definite failure may be retried safely");
        }
        boolean unlocked = state.hasState(REWARD_UNLOCK_NOTIFIED_PREFIX + rewardId);
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
        long next = state.incrementCounter(key, delta);
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
        state.setCounter(key, value);
        invalidateProgress(playerId);
        queueUnlockCheck(playerId);
    }

    public long getCounter(UUID playerId, String key) {
        RewardPlayerState state = getLoadedState(playerId);
        if (state == null) {
            return 0L;
        }
        return state.getCounter(key);
    }

    public String getState(UUID playerId, String key) {
        RewardPlayerState state = getLoadedState(playerId);
        if (state == null) {
            return null;
        }
        return state.getState(key);
    }

    public boolean handleAdminCommand(CommandSender sender, String[] args) {
        if (!isAvailable()) {
            sender.sendMessage("Reward administration is unavailable because durable reward storage is not active.");
            return true;
        }
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
        if ("reconcile".equals(subcommand)) {
            return handleReconcileCommand(sender, args);
        }
        if ("inspect".equals(subcommand)) {
            return handleInspectCommand(sender, args);
        }
        if ("items".equals(subcommand)) {
            return handleQueuedItemsCommand(sender, args);
        }
        if ("ip".equals(subcommand)) {
            return handleIpReservationCommand(sender, args);
        }
        sendAdminUsage(sender);
        return true;
    }

    private boolean handleReconcileCommand(CommandSender sender, String[] args) {
        if (args.length < 6) {
            sender.sendMessage("Usage: /enthusiatags rewards reconcile <player> <reward>"
                + " <action-id|item:action-id|legacy|whole>"
                + " <delivered|retry|force-delivered|force-retry> <reason>");
            return true;
        }
        OfflinePlayer target = findOfflineCommandTarget(sender, args, 1);
        if (target == null) return true;
        String rewardId = args[2].toLowerCase(Locale.ROOT);
        String actionId = args[3].toLowerCase(Locale.ROOT);
        String outcome = args[4].toLowerCase(Locale.ROOT);
        String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 5, args.length)).trim();
        if (reason.isBlank()) {
            sender.sendMessage("A nonblank reconciliation reason is required.");
            return true;
        }
        UUID playerId = target.getUniqueId();
        Administrator actor = administrator(sender);
        submitAdmin(sender, () -> reconcileReward(playerId, rewardId, actionId, outcome, reason, actor));
        return true;
    }

    private List<String> reconcileReward(UUID playerId, String rewardId, String actionId, String outcome,
                                         String reason, Administrator actor) throws SQLException {
        if (inFlightClaims.contains(playerId + ":" + rewardId)) {
            throw new SQLException("A claim is active for this player and reward");
        }
        RewardStorage.StoredRewardData stored = storage.loadNow(playerId);
        Map<String, RewardStorage.ActionLedgerEntry> ledger = storage.loadActionLedgerNow(playerId, rewardId);
        List<RewardStorage.ItemOverflowEntry> items = storage.loadItemOverflowNow(playerId, rewardId);
        RewardDefinition reward = rewards.get(rewardId);
        RewardStorage.AtomicReconcileResult result;
        Set<String> removedKeys = Set.of();
        String forceEvidence = null;
        boolean legacyUnmapped = stored.states().containsKey("reward-legacy-unmapped:" + rewardId);
        if ("legacy".equals(actionId)) {
            if (!"delivered".equals(outcome) && !"retry".equals(outcome)) {
                throw new SQLException("Legacy outcome must be delivered or retry");
            }
            removedKeys = stored.states().keySet().stream()
                .filter(key -> key.startsWith("reward-action:" + rewardId + ":"))
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
            result = storage.reconcileLegacyAtomicNow(playerId, rewardId, removedKeys,
                "delivered".equals(outcome), actor.name(), actor.uuid(), reason);
            legacyUnmapped = "delivered".equals(outcome);
        } else if ("whole".equals(actionId)) {
            if (!"force-delivered".equals(outcome) && !"force-retry".equals(outcome)) {
                throw new SQLException("Whole resolution requires force-delivered or force-retry");
            }
            String unresolved = unresolvedEvidence(ledger, items);
            forceEvidence = unresolved;
            result = storage.forceWholeAtomicNow(playerId, rewardId, "force-delivered".equals(outcome),
                actor.name(), actor.uuid(), reason, unresolved);
            legacyUnmapped = false;
        } else if (actionId.startsWith("item:")) {
            if (!"delivered".equals(outcome) && !"retry".equals(outcome)) {
                throw new SQLException("Item outcome must be delivered or retry");
            }
            String itemActionId = actionId.substring("item:".length());
            RewardStorage.ActionLedgerEntry entry = ledger.get(itemActionId);
            if (entry == null) throw new SQLException("Item action ledger entry not found");
            ReconciliationPlan plan = planOverall(reward, ledger, items, itemActionId,
                "delivered".equals(outcome) ? RewardStatus.CLAIMED : RewardStatus.ITEM_QUEUED,
                "delivered".equals(outcome) ? "DELIVERED" : "QUEUED", legacyUnmapped);
            result = storage.reconcileItemAtomicNow(playerId, rewardId, itemActionId, entry.fingerprint(),
                "delivered".equals(outcome), plan.overall(), plan.finalizeReward(),
                actor.name(), actor.uuid(), reason, "Explicit staff item decision");
        } else {
            if (!"delivered".equals(outcome) && !"retry".equals(outcome)) {
                throw new SQLException("Action outcome must be delivered or retry");
            }
            RewardStorage.ActionLedgerEntry entry = ledger.get(actionId);
            if (entry == null) throw new SQLException("Action ledger entry not found");
            RewardStatus next = "delivered".equals(outcome)
                ? RewardStatus.CLAIMED : RewardStatus.DELIVERY_FAILED;
            ReconciliationPlan plan = planOverall(reward, ledger, items, actionId, next, null, legacyUnmapped);
            result = storage.reconcileActionAtomicNow(playerId, rewardId, actionId, entry.fingerprint(),
                next, plan.overall(), plan.finalizeReward(), actor.name(), actor.uuid(), outcome, reason,
                "Explicit staff action decision");
        }
        RewardPlayerState state = getLoadedState(playerId);
        if (state != null) {
            state.applyReconciliation(rewardId, RewardStatus.valueOf(result.newOverallStatus()),
                result.claimed(), removedKeys, legacyUnmapped, result.revision());
        }
        invalidateProgress(playerId);
        if (actionId.startsWith("item:") && "retry".equals(outcome)) {
            drainItemOverflow(playerId);
        }
        List<String> messages = new ArrayList<>(List.of(
            "Reconciliation committed for " + playerId + " reward=" + rewardId + " subject=" + actionId + ".",
            "Subject: " + result.oldSubjectStatus() + " -> " + result.newSubjectStatus()
                + "; overall: " + result.oldOverallStatus() + " -> " + result.newOverallStatus() + ".",
            "Decision=" + outcome + " reason=" + reason));
        if (forceEvidence != null) {
            messages.add("FORCE RESOLUTION recorded: target=" + playerId + " reward=" + rewardId
                + " unresolved=[" + forceEvidence + "] reason=" + reason);
        }
        return messages;
    }

    private RewardStatus refreshOverallAfterReconciliation(UUID playerId, String rewardId) throws SQLException {
        RewardDefinition reward = rewards.get(rewardId);
        Map<String, RewardStorage.ActionLedgerEntry> ledger = storage.loadActionLedgerNow(playerId, rewardId);
        List<RewardStorage.ItemOverflowEntry> items = storage.loadItemOverflowNow(playerId, rewardId);
        RewardStorage.StoredRewardData stored = storage.loadNow(playerId);
        ReconciliationPlan plan = planOverall(reward, ledger, items, null, null, null,
            stored.states().containsKey("reward-legacy-unmapped:" + rewardId));
        RewardPlayerState state = getLoadedState(playerId);
        if (plan.finalizeReward()) {
            long revision = storage.finalizeRewardNow(playerId, rewardId);
            if (state != null) state.applyDurableFinalization(rewardId, revision);
        } else if (state != null) {
            state.setOverall(rewardId, plan.overall());
        }
        if (state != null && !persistStateBarrier(playerId, state)) {
            throw new SQLException("Failed to persist refreshed overall reward state");
        }
        return plan.overall() == RewardStatus.CLAIM_PENDING ? null : plan.overall();
    }

    private ReconciliationPlan planOverall(
        RewardDefinition reward, Map<String, RewardStorage.ActionLedgerEntry> ledger,
        List<RewardStorage.ItemOverflowEntry> items, String overrideActionId,
        RewardStatus overrideActionStatus, String overrideItemStatus, boolean legacyUnmapped) {
        Map<String, RewardStatus> statuses = new java.util.HashMap<>();
        ledger.forEach((id, entry) -> statuses.put(id, entry.status()));
        if (overrideActionId != null && overrideActionStatus != null) {
            statuses.put(overrideActionId, overrideActionStatus);
        }
        boolean unresolved = legacyUnmapped || statuses.values().stream().anyMatch(status ->
            status == RewardStatus.CLAIM_PENDING || status == RewardStatus.REQUIRES_RECONCILIATION);
        for (RewardStorage.ItemOverflowEntry item : items) {
            String status = item.actionId().equals(overrideActionId) && overrideItemStatus != null
                ? overrideItemStatus : item.status();
            unresolved |= "DELIVERY_PENDING".equals(status);
        }
        if (unresolved) return new ReconciliationPlan(RewardStatus.REQUIRES_RECONCILIATION, false);
        boolean pendingItem = statuses.values().stream().anyMatch(status -> status == RewardStatus.ITEM_QUEUED);
        for (RewardStorage.ItemOverflowEntry item : items) {
            String status = item.actionId().equals(overrideActionId) && overrideItemStatus != null
                ? overrideItemStatus : item.status();
            pendingItem |= "QUEUED".equals(status);
        }
        if (pendingItem) return new ReconciliationPlan(RewardStatus.ITEM_QUEUED, false);
        if (reward == null) {
            if (statuses.values().stream().anyMatch(status -> status == RewardStatus.DELIVERY_FAILED)) {
                return new ReconciliationPlan(RewardStatus.DELIVERY_FAILED, false);
            }
            boolean allHistoricalDelivered = !statuses.isEmpty()
                && statuses.values().stream().allMatch(status -> status == RewardStatus.CLAIMED);
            return new ReconciliationPlan(allHistoricalDelivered ? RewardStatus.CLAIMED
                : RewardStatus.CLAIM_PENDING, allHistoricalDelivered);
        }
        Set<String> currentIds = reward.getActions().stream().map(RewardAction::getActionId)
            .collect(java.util.stream.Collectors.toSet());
        if (statuses.entrySet().stream().anyMatch(entry -> currentIds.contains(entry.getKey())
            && entry.getValue() == RewardStatus.DELIVERY_FAILED)) {
            return new ReconciliationPlan(RewardStatus.DELIVERY_FAILED, false);
        }
        boolean allCurrentDelivered = reward.getActions().stream().allMatch(action -> {
            RewardStorage.ActionLedgerEntry entry = ledger.get(action.getActionId());
            RewardStatus status = action.getActionId().equals(overrideActionId) && overrideActionStatus != null
                ? overrideActionStatus : entry == null ? null : entry.status();
            return status == RewardStatus.CLAIMED && entry != null
                && actionFingerprint(action).equals(entry.fingerprint());
        });
        return new ReconciliationPlan(allCurrentDelivered ? RewardStatus.CLAIMED : RewardStatus.CLAIM_PENDING,
            allCurrentDelivered);
    }

    private String unresolvedEvidence(Map<String, RewardStorage.ActionLedgerEntry> ledger,
                                      List<RewardStorage.ItemOverflowEntry> items) {
        List<String> unresolved = new ArrayList<>();
        ledger.values().stream().filter(entry -> entry.status() != RewardStatus.CLAIMED)
            .forEach(entry -> unresolved.add("action " + entry.actionId() + "=" + entry.status()));
        items.stream().filter(item -> !"DELIVERED".equals(item.status()))
            .forEach(item -> unresolved.add("item " + item.actionId() + "=" + item.status()));
        return unresolved.isEmpty() ? "No unresolved ledger rows" : String.join("; ", unresolved);
    }

    private Administrator administrator(CommandSender sender) {
        return sender instanceof Player player
            ? new Administrator(player.getName(), player.getUniqueId())
            : new Administrator("console", null);
    }

    private void submitAdmin(CommandSender sender, java.util.concurrent.Callable<List<String>> operation) {
        if (claimExecutor == null || claimExecutor.isShutdown()
            || lifecycle.get() == ServiceLifecycle.STOPPING || lifecycle.get() == ServiceLifecycle.STOPPED) {
            sender.sendMessage("Reward administration is unavailable while the service is stopping.");
            return;
        }
        CompletableFuture<List<String>> future = CompletableFuture.supplyAsync(() -> {
            try {
                return operation.call();
            } catch (Exception ex) {
                throw new java.util.concurrent.CompletionException(ex);
            }
        }, claimExecutor);
        activeOperations.add(future);
        future.whenComplete((lines, throwable) -> {
            activeOperations.remove(future);
            scheduleMain(() -> {
            if (throwable != null) {
                Throwable cause = throwable instanceof java.util.concurrent.CompletionException
                    ? throwable.getCause() : throwable;
                sender.sendMessage("Operation failed: " + (cause == null ? throwable.getMessage() : cause.getMessage()));
                return;
            }
            lines.forEach(sender::sendMessage);
            });
        });
    }

    private boolean handleInspectCommand(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("Usage: /enthusiatags rewards inspect <player> <reward|unresolved>");
            return true;
        }
        OfflinePlayer target = findOfflineCommandTarget(sender, args, 1);
        if (target == null) return true;
        String rewardId = args[2].toLowerCase(Locale.ROOT);
        UUID playerId = target.getUniqueId();
        Administrator actor = administrator(sender);
        if ("unresolved".equals(rewardId)) {
            submitAdmin(sender, () -> inspectUnresolvedRewards(playerId));
        } else {
            submitAdmin(sender, () -> inspectReward(playerId, rewardId, actor));
        }
        return true;
    }

    private List<String> inspectUnresolvedRewards(UUID playerId) throws SQLException {
        List<String> lines = new ArrayList<>();
        lines.add("Unresolved rewards for " + playerId + ":");
        RewardStorage.StoredRewardData stored = storage.loadNow(playerId);
        for (String rewardId : storage.listKnownRewardIdsNow(playerId)) {
            String overall = stored.states().get("reward-delivery:" + rewardId);
            boolean claimed = stored.claims().contains(rewardId);
            boolean pendingItems = storage.loadItemOverflowNow(playerId, rewardId).stream()
                .anyMatch(item -> !"DELIVERED".equals(item.status()));
            if (!claimed || pendingItems || RewardStatus.REQUIRES_RECONCILIATION.name().equals(overall)
                || RewardStatus.ITEM_QUEUED.name().equals(overall)
                || RewardStatus.DELIVERY_FAILED.name().equals(overall)) {
                lines.add("- " + rewardId + " overall=" + overall + " claimed=" + claimed
                    + " pending-items=" + pendingItems + " configured=" + rewards.containsKey(rewardId));
            }
        }
        if (lines.size() == 1) lines.add("- none");
        return lines;
    }

    private List<String> inspectReward(UUID playerId, String rewardId, Administrator actor) throws SQLException {
        storage.recordInspectionNow(playerId, rewardId, actor.name(), actor.uuid());
        RewardDefinition reward = rewards.get(rewardId);
        RewardStorage.StoredRewardData stored = storage.loadNow(playerId);
        Map<String, RewardStorage.ActionLedgerEntry> ledger = storage.loadActionLedgerNow(playerId, rewardId);
        List<String> lines = new ArrayList<>();
        lines.add("Reward inspection: " + playerId + " / " + rewardId
            + (reward == null ? " [REMOVED/HISTORICAL]" : " [CURRENT]"));
        lines.add("  overall: " + stored.states().getOrDefault("reward-delivery:" + rewardId, "none"));
        lines.add("  claimed marker: " + stored.claims().contains(rewardId));
        lines.add("  unlock marker: " + storage.isUnlockedNow(playerId, rewardId));
        List<String> legacy = stored.states().keySet().stream()
            .filter(key -> key.startsWith("reward-action:" + rewardId + ":")).sorted().toList();
        lines.add("  legacy keys: " + (legacy.isEmpty() ? "none" : legacy));
        lines.add("  legacy unmapped: "
            + stored.states().getOrDefault("reward-legacy-unmapped:" + rewardId, "none"));
        Set<String> currentIds = reward == null ? Set.of() : reward.getActions().stream()
            .map(RewardAction::getActionId).collect(java.util.stream.Collectors.toSet());
        if (reward != null) {
            for (RewardAction action : reward.getActions()) {
                addActionInspection(lines, "current", action.getActionId(), action.getType().name(),
                    actionFingerprint(action), ledger.get(action.getActionId()));
            }
        }
        ledger.values().stream().filter(entry -> !currentIds.contains(entry.actionId()))
            .sorted(java.util.Comparator.comparing(RewardStorage.ActionLedgerEntry::actionId))
            .forEach(entry -> addActionInspection(lines, "historical/removed", entry.actionId(),
                entry.actionType(), "-", entry));
        lines.add("  IP reservations: " + storage.listIpClaimsNow(playerId, rewardId));
        lines.add("  recent IP reconciliation history:");
        for (String entry : storage.loadIpHistoryNow(playerId, rewardId, 12)) {
            lines.add("    " + entry);
        }
        lines.add("  item overflow:");
        for (RewardStorage.ItemOverflowEntry entry : storage.loadItemOverflowNow(playerId, rewardId)) {
            lines.add("    action=" + entry.actionId() + " status=" + entry.status()
                + " item=" + entry.material() + "x" + entry.amount() + " fingerprint=" + entry.fingerprint()
                + " queued=" + entry.queuedAt() + " delivered=" + entry.deliveredAt());
        }
        lines.add("  recent action history:");
        for (RewardStorage.ActionHistoryEntry entry : storage.loadActionHistoryNow(playerId, rewardId, 20)) {
            lines.add("    #" + entry.historyId() + " " + entry.actionId() + " "
                + entry.oldStatus() + " -> " + entry.newStatus() + " at " + entry.createdAt()
                + (entry.errorMessage() == null ? "" : " evidence=" + entry.errorMessage()));
        }
        lines.add("  recent item and legacy transition history:");
        for (RewardStorage.ReconciliationHistoryEntry entry
            : storage.loadReconciliationHistoryNow(playerId, rewardId, 20)) {
            lines.add("    " + entry.category() + "/" + entry.subject() + " "
                + entry.oldStatus() + " -> " + entry.newStatus() + " at=" + entry.createdAt()
                + (entry.administrator() == null ? "" : " by=" + entry.administrator())
                + " reason=" + entry.reason());
        }
        lines.add("  recent immutable staff reconciliation history:");
        for (RewardStorage.AdminHistoryEntry entry : storage.loadAdminHistoryNow(playerId, rewardId, 20)) {
            lines.add("    #" + entry.historyId() + " " + entry.category() + "/" + entry.subjectId()
                + " " + entry.oldSubjectStatus() + " -> " + entry.newSubjectStatus()
                + " overall " + entry.oldOverallStatus() + " -> " + entry.newOverallStatus()
                + " decision=" + entry.decision() + " by=" + entry.administratorName()
                + (entry.administratorUuid() == null ? "" : "/" + entry.administratorUuid())
                + " at=" + entry.createdAt() + " reason=" + entry.reason()
                + " evidence=" + entry.evidence());
        }
        return lines;
    }

    private void addActionInspection(List<String> lines, String kind, String actionId, String actionType,
                                      String configuredFingerprint,
                                      RewardStorage.ActionLedgerEntry entry) {
        lines.add("  [" + kind + "] id=" + actionId + " type=" + actionType);
        lines.add("    configured-fingerprint=" + configuredFingerprint);
        if (entry == null) {
            lines.add("    stored=none status=not-started");
            return;
        }
        lines.add("    stored-fingerprint=" + entry.fingerprint() + " status=" + entry.status()
            + " updated=" + entry.updatedAt());
        lines.add("    vault requested=" + entry.requestedAmount() + " returned=" + entry.responseAmount()
            + " before=" + entry.balanceBefore() + " after=" + entry.balanceAfter());
        lines.add("    provider/command-evidence=" + entry.responseType() + " error=" + entry.errorMessage());
    }

    private boolean handleQueuedItemsCommand(CommandSender sender, String[] args) {
        OfflinePlayer target = findOfflineCommandTarget(sender, args, 1);
        if (target == null) return true;
        UUID playerId = target.getUniqueId();
        submitAdmin(sender, () -> {
            List<String> lines = new ArrayList<>();
            lines.add("Queued/ambiguous reward items for " + playerId + ":");
            for (String rewardId : storage.listKnownRewardIdsNow(playerId)) {
                for (RewardStorage.ItemOverflowEntry item : storage.loadItemOverflowNow(playerId, rewardId)) {
                    if (!"DELIVERED".equals(item.status())) {
                        lines.add("- reward=" + rewardId + " action=" + item.actionId()
                            + " status=" + item.status() + " item=" + item.material() + "x" + item.amount()
                            + " fingerprint=" + item.fingerprint());
                    }
                }
            }
            if (lines.size() == 1) lines.add("- none");
            return lines;
        });
        return true;
    }

    private boolean handleIpReservationCommand(CommandSender sender, String[] args) {
        if (args.length < 6) {
            sender.sendMessage("Usage: /enthusiatags rewards ip <player> <reward> <ip>"
                + " <retain|release|repair> <reason>");
            return true;
        }
        OfflinePlayer target = findOfflineCommandTarget(sender, args, 1);
        if (target == null) return true;
        String rewardId = args[2].toLowerCase(Locale.ROOT);
        String ipAddress = args[3];
        String decision = args[4].toLowerCase(Locale.ROOT);
        String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 5, args.length)).trim();
        if (!Set.of("retain", "release", "repair").contains(decision) || reason.isBlank()) {
            sender.sendMessage("Decision must be retain, release, or repair and include a reason.");
            return true;
        }
        Administrator actor = administrator(sender);
        submitAdmin(sender, () -> {
            RewardStorage.AtomicReconcileResult result = storage.reconcileIpReservationNow(
                target.getUniqueId(), rewardId, ipAddress, decision, actor.name(), actor.uuid(), reason);
            return List.of("IP reservation " + decision + " committed for target=" + target.getUniqueId()
                + " reward=" + rewardId + " ip=" + ipAddress + " owner "
                + result.oldSubjectStatus() + " -> " + result.newSubjectStatus() + ".");
        });
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
        submitAdmin(sender, () -> {
            boolean added = storage.addIpBypassPairNow(first.getUniqueId(), second.getUniqueId());
            return List.of((added ? "Added" : "Already exists")
                + " reward IP bypass pair for " + displayName(first) + " and " + displayName(second) + ".");
        });
        return true;
    }

    private boolean handleRemoveIpBypassCommand(CommandSender sender, String[] args) {
        OfflinePlayer first = findOfflineCommandTarget(sender, args, 2);
        OfflinePlayer second = findOfflineCommandTarget(sender, args, 3);
        if (first == null || second == null) {
            return true;
        }
        submitAdmin(sender, () -> {
            boolean removed = storage.removeIpBypassPairNow(first.getUniqueId(), second.getUniqueId());
            return List.of((removed ? "Removed" : "No bypass pair found for")
                + " " + displayName(first) + " and " + displayName(second) + ".");
        });
        return true;
    }

    private boolean handleListIpBypassCommand(CommandSender sender, String[] args) {
        OfflinePlayer target = findOfflineCommandTarget(sender, args, 2);
        if (target == null) {
            return true;
        }
        submitAdmin(sender, () -> {
            Set<UUID> pairedPlayers = storage.listIpBypassPairsNow(target.getUniqueId());
            if (pairedPlayers.isEmpty()) {
                return List.of("No reward IP bypass pairs found for " + displayName(target) + ".");
            }
            List<String> lines = new ArrayList<>();
            lines.add("Reward IP bypass pairs for " + displayName(target) + ":");
            for (UUID pairedPlayerId : pairedPlayers) {
                lines.add("- " + displayName(Bukkit.getOfflinePlayer(pairedPlayerId)));
            }
            return lines;
        });
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
        sender.sendMessage("       /enthusiatags rewards inspect <player> <reward|unresolved>");
        sender.sendMessage("       /enthusiatags rewards items <player>");
        sender.sendMessage("       /enthusiatags rewards ip <player> <reward> <ip>"
            + " <retain|release|repair> <reason>");
        sender.sendMessage("       /enthusiatags rewards reconcile <player> <reward>"
            + " <action-id|item:action-id|legacy|whole>"
            + " <delivered|retry|force-delivered|force-retry> <reason>");
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
            sender.sendMessage("  claimed: " + state.claimedRewardsSnapshot());
            sender.sendMessage("  counters: " + state.countersSnapshot());
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
            state.removeState(key);
        } else {
            state.putState(key, value);
        }
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
        return reserveIpClaim(player.getUniqueId(), rewardId, getPlayerIpAddress(player));
    }

    private boolean reserveIpClaim(UUID playerId, String rewardId, String ipAddress) {
        if (ipAddress.isBlank()) {
            performanceMonitor.increment("rewards.claim.ip-missing");
            return true;
        }
        try {
            boolean reserved = storage.reserveIpClaimNow(playerId, rewardId, ipAddress);
            if (!reserved) {
                performanceMonitor.increment("rewards.claim.ip-blocked");
            }
            return reserved;
        } catch (SQLException ex) {
            plugin.getLogger().warning("Failed to check reward IP claim for " + playerId + ": " + ex.getMessage());
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
        if (state == null || !state.isLoaded() || state.claimedRewardsSnapshot().isEmpty()) {
            return;
        }
        String ipAddress = getPlayerIpAddress(player);
        if (ipAddress.isBlank()) {
            return;
        }
        UUID playerId = player.getUniqueId();
        for (String rewardId : state.claimedRewardsSnapshot()) {
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
            if (state.isClaimed(rewardId)) {
                continue;
            }
            String notificationKey = REWARD_UNLOCK_NOTIFIED_PREFIX + rewardId;
            if (state.hasState(notificationKey)) {
                continue;
            }
            RewardEvaluation evaluation = evaluate(player, reward, snapshot);
            if (!evaluation.claimable()) {
                continue;
            }
            newlyUnlocked.add(reward);
        }
        if (!newlyUnlocked.isEmpty()) {
            if (claimExecutor == null || claimExecutor.isShutdown()
                || lifecycle.get() == ServiceLifecycle.STOPPING) {
                return;
            }
            UUID playerId = player.getUniqueId();
            CompletableFuture<List<RewardDefinition>> future =
                CompletableFuture.supplyAsync(() -> persistUnlocks(playerId, state, newlyUnlocked), claimExecutor);
            activeOperations.add(future);
            future.whenComplete((persistedUnlocks, throwable) -> {
                activeOperations.remove(future);
                scheduleMain(() -> {
                    Player live = onlinePlayer(playerId);
                    if (live == null || throwable != null || persistedUnlocks.isEmpty()) return;
                    sendPersistedUnlocks(live, persistedUnlocks);
                });
            });
        }
    }

    private List<RewardDefinition> persistUnlocks(UUID playerId, RewardPlayerState state,
                                                   List<RewardDefinition> newlyUnlocked) {
        List<RewardDefinition> persisted = new ArrayList<>();
        for (RewardDefinition reward : newlyUnlocked) {
            String rewardId = reward.getId().toLowerCase(Locale.ROOT);
            try {
                storage.markUnlockedNow(playerId, rewardId);
                state.putState(REWARD_UNLOCK_NOTIFIED_PREFIX + rewardId, "true");
                persisted.add(reward);
            } catch (SQLException ex) {
                plugin.getLogger().warning("Failed to persist unlock marker for " + playerId
                    + " reward=" + rewardId + ": " + ex.getMessage());
            }
        }
        return persisted;
    }

    private void sendPersistedUnlocks(Player player, List<RewardDefinition> persistedUnlocks) {
            int limit = Math.max(1, plugin.getConfig().getInt("rewards.unlock-notifications.individual-limit", 3));
            for (int index = 0; index < Math.min(limit, persistedUnlocks.size()); index++) {
                sendUnlockNotification(player, persistedUnlocks.get(index), false);
                performanceMonitor.increment("rewards.unlock-notified");
            }
            if (persistedUnlocks.size() > limit) {
                Component summary = LegacyComponentSerializer.legacyAmpersand().deserialize(
                    messages.get("rewards-unlocked-summary")
                        .replace("{count}", String.valueOf(persistedUnlocks.size() - limit)))
                    .clickEvent(ClickEvent.runCommand("/rewards"))
                    .hoverEvent(HoverEvent.showText(LegacyComponentSerializer.legacyAmpersand().deserialize(
                        messages.get("rewards-unlocked-summary-hover"))));
                player.sendMessage(summary);
            }
            playUnlockSound(player);
    }

    private void hydrateState(RewardPlayerState state, RewardStorage.StoredRewardData data) {
        state.hydrate(data);
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
        state.mergeCounters(deltas);
        performanceMonitor.add("rewards.counter.deferred-applied", deltas.size());
    }

    private RewardStorage.StoredRewardData snapshotState(RewardPlayerState state) {
        return state.snapshot();
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
        if (reward.getActions().stream().anyMatch(action -> !action.isValid())) {
            return false;
        }
        if (!vaultHook.isAvailable()) {
            return reward.getActions().stream().noneMatch(action -> action.getType() == RewardActionType.MONEY);
        }
        return reward.getActions().stream().allMatch(action -> action.getType() != RewardActionType.ITEM
            || (action.getMaterial() != null && action.getItemAmount() > 0));
    }

    private ItemDeliveryResult deliverItem(UUID playerId, String rewardId, RewardAction action, String fingerprint)
        throws SQLException {
        if (action.getMaterial() == null || action.getItemAmount() <= 0) return ItemDeliveryResult.FAILED;
        boolean inserted = callOnMain(() -> {
            Player player = onlinePlayer(playerId);
            if (player == null) {
                return false;
            }
            List<ItemStack> items = createRewardItems(action.getMaterial(), action.getItemAmount(),
                action.getDisplayName(), action.getLore());
            return hasInventoryCapacity(player, items.get(0), action.getItemAmount())
                && player.getInventory().addItem(items.toArray(ItemStack[]::new)).isEmpty();
        });
        if (!inserted) {
            storage.queueItemNow(playerId, rewardId, action, fingerprint);
            return ItemDeliveryResult.QUEUED;
        }
        return ItemDeliveryResult.DELIVERED;
    }

    private List<ItemStack> createRewardItems(Material material, int amount, String displayName, List<String> lore) {
        ItemStack template = new ItemStack(material, 1);
        ItemMeta meta = template.getItemMeta();
        if (displayName != null && !displayName.isBlank()) {
            meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize(displayName));
        }
        if (!lore.isEmpty()) {
            meta.lore(lore.stream().map(LegacyComponentSerializer.legacyAmpersand()::deserialize).toList());
        }
        template.setItemMeta(meta);
        List<ItemStack> items = new ArrayList<>();
        int remaining = amount;
        while (remaining > 0) {
            ItemStack item = template.clone();
            item.setAmount(Math.min(template.getMaxStackSize(), remaining));
            remaining -= item.getAmount();
            items.add(item);
        }
        return items;
    }

    private void drainItemOverflow(Player player) {
        drainItemOverflow(player.getUniqueId());
    }

    public void retryQueuedItems(Player player) {
        drainItemOverflow(player.getUniqueId());
    }

    private void drainItemOverflow(UUID playerId) {
        if (lifecycle.get() == ServiceLifecycle.STOPPING || lifecycle.get() == ServiceLifecycle.STOPPED) {
            return;
        }
        storage.loadQueuedItemsAsync(playerId).whenComplete((items, throwable) -> {
            if (throwable != null) {
                plugin.getLogger().warning("Failed to load queued reward items for " + playerId
                    + ": " + throwable.getMessage());
                return;
            }
            scheduleMain(() -> drainItemOverflowOnMain(playerId, items, 0));
        });
    }

    private void drainItemOverflowOnMain(UUID playerId, List<RewardStorage.QueuedItem> items, int index) {
        Player player = onlinePlayer(playerId);
        if (player == null || index >= items.size()) return;
        RewardStorage.QueuedItem queued = items.get(index);
        Material material = Material.matchMaterial(queued.material());
        if (material == null) {
            plugin.getLogger().warning("Queued reward item has invalid material: " + queued.material());
            return;
        }
        List<ItemStack> itemStacks = createRewardItems(material, queued.amount(),
            queued.displayName(), queued.lore());
        if (!hasInventoryCapacity(player, itemStacks.get(0), queued.amount())) return;
        storage.markQueuedItemPendingAsync(playerId, queued).whenComplete((ignoredPending, pendingError) -> {
            if (pendingError != null) {
                plugin.getLogger().warning("Failed to reserve queued item delivery for " + playerId
                    + ": " + pendingError.getMessage());
                return;
            }
            scheduleMain(() -> insertReservedQueuedItem(playerId, items, index, queued, itemStacks));
        });
    }

    private void insertReservedQueuedItem(UUID playerId, List<RewardStorage.QueuedItem> items, int index,
                                          RewardStorage.QueuedItem queued, List<ItemStack> itemStacks) {
        Player player = onlinePlayer(playerId);
        if (player == null || !hasInventoryCapacity(player, itemStacks.get(0), queued.amount())) {
            storage.returnQueuedItemAsync(playerId, queued);
            return;
        }
        Map<Integer, ItemStack> leftovers =
            player.getInventory().addItem(itemStacks.toArray(ItemStack[]::new));
        if (!leftovers.isEmpty()) {
            markAmbiguousItemDelivery(playerId, queued,
                "Inventory addItem returned leftovers after DELIVERY_PENDING reservation");
            return;
        }
        storage.completeQueuedItemAsync(playerId, queued).whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                markAmbiguousItemDelivery(playerId, queued,
                    "Inventory insertion completed but delivered marker failed: " + throwable.getMessage());
                return;
            }
            onQueuedItemDelivered(playerId, queued);
            scheduleMain(() -> {
                Player live = onlinePlayer(playerId);
                if (live != null) {
                    live.sendMessage(messages.get("rewards-queued-item-delivered"));
                }
                drainItemOverflowOnMain(playerId, items, index + 1);
            });
        });
    }

    private void markAmbiguousItemDelivery(UUID playerId, RewardStorage.QueuedItem queued, String evidence) {
        plugin.getLogger().severe("Queued reward item delivery is uncertain for " + playerId + "/"
            + queued.rewardId() + "/" + queued.actionId() + ": " + evidence);
        storage.markQueuedItemReconciliationAsync(playerId, queued, evidence).whenComplete((revision, throwable) -> {
            if (throwable != null) {
                plugin.getLogger().severe("Failed to persist item reconciliation requirement for " + playerId
                    + "/" + queued.rewardId() + "/" + queued.actionId() + ": " + throwable.getMessage());
            } else {
                RewardPlayerState state = playerStates.get(playerId);
                if (state != null && state.isLoaded()) {
                    state.applyReconciliation(queued.rewardId(), RewardStatus.REQUIRES_RECONCILIATION,
                        false, Set.of(), false, revision);
                }
            }
            runForOnlinePlayer(playerId, player ->
                player.sendMessage(messages.get("rewards-reconciliation-required")));
        });
    }

    private void onQueuedItemDelivered(UUID playerId, RewardStorage.QueuedItem queued) {
        try {
            RewardStatus overall = refreshOverallAfterReconciliation(playerId, queued.rewardId());
            if (overall == null) {
                resumeClaimAfterItem(playerId, queued.rewardId());
            }
        } catch (SQLException ex) {
            plugin.getLogger().severe("Queued item delivered but reward finalization needs staff review for "
                + playerId + "/" + queued.rewardId() + ": " + ex.getMessage());
        }
    }

    private void recoverPendingRewards(UUID playerId) {
        if (claimExecutor == null || claimExecutor.isShutdown()) return;
        CompletableFuture.runAsync(() -> {
            try {
                RewardStorage.StoredRewardData stored = storage.loadNow(playerId);
                for (String rewardId : storage.listKnownRewardIdsNow(playerId)) {
                    String overall = stored.states().get("reward-delivery:" + rewardId);
                    if (!RewardStatus.ITEM_QUEUED.name().equals(overall)
                        && !RewardStatus.CLAIM_PENDING.name().equals(overall)) {
                        continue;
                    }
                    RewardStatus refreshed = refreshOverallAfterReconciliation(playerId, rewardId);
                    if (refreshed == null) {
                        resumeClaimAfterItem(playerId, rewardId);
                    }
                }
            } catch (SQLException ex) {
                plugin.getLogger().warning("Failed to recover pending rewards for " + playerId + ": "
                    + ex.getMessage());
            }
        }, claimExecutor);
    }

    private void scheduleMain(Runnable runnable) {
        if (lifecycle.get() == ServiceLifecycle.STOPPING || lifecycle.get() == ServiceLifecycle.STOPPED) {
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, runnable);
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Could not schedule reward lifecycle callback: " + ex.getMessage());
        }
    }

    private boolean hasInventoryCapacity(Player player, ItemStack item, int requiredAmount) {
        int capacity = 0;
        int maxStack = item.getMaxStackSize();
        for (ItemStack existing : player.getInventory().getStorageContents()) {
            if (existing == null || existing.getType().isAir()) {
                capacity += maxStack;
            } else if (existing.isSimilar(item)) {
                capacity += Math.max(0, maxStack - existing.getAmount());
            }
            if (capacity >= requiredAmount) {
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

    private boolean initStorage() {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            plugin.getLogger().severe("Failed to create data folder for rewards.");
            return false;
        }
        storage = new RewardStorage(new File(dataFolder, "rewards.db"), performanceMonitor);
        try {
            storage.init();
            return true;
        } catch (SQLException ex) {
            plugin.getLogger().severe("Failed to initialize rewards database: " + ex.getMessage());
            storage.close();
            storage = null;
            return false;
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
            flushPlayerAsync(player.getUniqueId(), state);
        }
        return repaired;
    }

    private int raiseCounterToAtLeast(Player player, RewardPlayerState state, String key, long realValue) {
        long cached = state.getCounter(key);
        if (realValue <= cached) {
            return 0;
        }
        state.raiseCounter(key, realValue);
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
        storage.saveAsync(playerId, snapshot).whenComplete((result, throwable) -> {
            if (throwable != null) {
                plugin.getLogger().warning("Failed to save reward state for " + playerId + ": "
                    + throwable.getMessage());
            } else if (result == RewardStorage.WriteResult.WRITTEN) {
                state.markClean(snapshot.revision());
            }
        });
    }

    private void flushPlayerBlocking(UUID playerId) {
        RewardPlayerState state = playerStates.get(playerId);
        if (state == null || !state.isLoaded() || !state.isDirty()) {
            return;
        }
        try {
            RewardStorage.StoredRewardData snapshot = snapshotState(state);
            RewardStorage.WriteResult result = storage.saveAsync(playerId, snapshot).get(5, TimeUnit.SECONDS);
            if (result == RewardStorage.WriteResult.WRITTEN) {
                state.markClean(snapshot.revision());
            }
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
        Map<String, RewardDefinition> loadedRewards = new LinkedHashMap<>();
        if (section == null) {
            rewards = Map.of();
            return;
        }
        for (String id : section.getKeys(false)) {
            String name = section.getString(id + ".name", id);
            List<String> description = section.getStringList(id + ".description");
            Material icon = parseMaterial(section.getString(id + ".icon", "NAME_TAG"), "rewards." + id + ".icon", true);
            List<RewardCriterion> criteria = loadCriteria(section.getConfigurationSection(id + ".criteria"));
            List<RewardAction> actions = loadActions(section.getConfigurationSection(id + ".rewards"), id);
            String category = section.getString(id + ".category", inferCategory(criteria));
            RewardCompletionMode completionMode;
            try {
                completionMode = RewardCompletionMode.valueOf(section.getString(id + ".completion-mode", "LATCHED")
                    .toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                completionMode = RewardCompletionMode.LATCHED;
                plugin.getLogger().warning("Invalid completion-mode for reward " + id + "; using LATCHED");
            }
            loadedRewards.put(id.toLowerCase(Locale.ROOT),
                new RewardDefinition(id, name, description, icon, criteria, actions, category, completionMode));
        }
        rewards = java.util.Collections.unmodifiableMap(loadedRewards);
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

    private List<RewardAction> loadActions(ConfigurationSection section, String rewardId) {
        List<RewardAction> actions = new ArrayList<>();
        Set<String> actionIds = new java.util.HashSet<>();
        if (section == null) {
            return actions;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(key);
            if (entry == null) {
                plugin.getLogger().warning("Malformed reward action at " + section.getCurrentPath() + "." + key
                    + "; reward " + rewardId + " will be unavailable.");
                actions.add(new RewardAction(key.toLowerCase(Locale.ROOT), RewardActionType.TAG, "", 0D,
                    "Malformed action", null, 0, "", List.of(), false));
                continue;
            }
            String actionId = entry.getString("action-id", key).toLowerCase(Locale.ROOT);
            RewardActionType type = parseEnum(RewardActionType.class, entry.getString("type", "TAG"),
                section.getCurrentPath() + "." + key + ".type");
            if (type == null) {
                actions.add(new RewardAction(actionId, RewardActionType.TAG, "", 0D,
                    "Invalid action type", null, 0, "", List.of(), false));
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
            boolean validId = actionId.matches("[a-z0-9][a-z0-9._-]{0,63}") && actionIds.add(actionId);
            String invalidReason = validateActionDefinition(type, value, amount, itemMaterial, itemAmount);
            boolean valid = validId && invalidReason == null;
            if (!validId) {
                plugin.getLogger().warning("Invalid or duplicate action-id '" + actionId
                    + "' for reward " + rewardId + "; reward will be unavailable.");
            }
            if (invalidReason != null) {
                plugin.getLogger().warning("Invalid action " + rewardId + "/" + actionId + ": "
                    + invalidReason + "; reward will be unavailable.");
            }
            actions.add(new RewardAction(actionId, type, value, amount, label,
                itemMaterial, itemAmount, displayName, lore, valid));
        }
        return actions;
    }

    private String validateActionDefinition(RewardActionType type, String value, double amount,
                                            Material itemMaterial, int itemAmount) {
        return switch (type) {
            case TAG -> {
                if (value == null || value.isBlank()) yield "tag id is blank";
                if (tagService.getRegistry().get(value.toLowerCase(Locale.ROOT)) == null) {
                    yield "tag '" + value + "' is not configured";
                }
                yield null;
            }
            case MONEY -> !Double.isFinite(amount) || amount <= 0D
                ? "money amount must be finite and greater than zero" : null;
            case COMMAND -> value == null || value.isBlank() ? "command is blank" : null;
            case ITEM -> {
                if (itemMaterial == null) yield "item material is missing or invalid";
                if (itemAmount <= 0) yield "item amount must be greater than zero";
                if (itemAmount > 2304) yield "item amount exceeds the 36-slot inventory safety limit";
                yield null;
            }
        };
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

    private enum ServiceLifecycle {
        RUNNING,
        RELOADING,
        STOPPING,
        STOPPED
    }

    private enum ItemDeliveryResult {
        DELIVERED,
        QUEUED,
        FAILED
    }

    private record PlayerIdentity(String name, String ipAddress) {
    }

    private record Administrator(String name, UUID uuid) {
    }

    private record ReconciliationPlan(RewardStatus overall, boolean finalizeReward) {
    }
}
