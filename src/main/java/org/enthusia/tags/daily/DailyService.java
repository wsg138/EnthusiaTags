package org.enthusia.tags.daily;

import java.io.File;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.enthusia.tags.rewards.VaultHook;

public final class DailyService implements CommandExecutor, Listener {
    private static final String DAILY_UNAVAILABLE = "Daily rewards are temporarily unavailable.";
    private static final String DEFAULT_TIMEZONE = "America/Indiana/Indianapolis";
    private static final String DISCONNECTED_BEFORE_VAULT =
        "Player disconnected before Vault invocation";
    private static final List<Double> DEFAULT_PAYOUTS =
        List.of(5D, 10D, 15D, 20D, 30D, 40D, 50D);
    private static final boolean LEGACY_ANIMATION_DEFAULT = true;
    private static final int MAX_RELOAD_RETRY_ATTEMPTS = 40;
    private static final long RELOAD_RETRY_DELAY_TICKS = 10L;

    private final JavaPlugin plugin;
    private final VaultHook vault = new VaultHook();
    private final DailyMenuRenderer menuRenderer;
    private final DailyAnimationRenderer animationRenderer;
    private final Set<UUID> claims = ConcurrentHashMap.newKeySet();
    private final Map<UUID, ActiveAnimation> animations = new ConcurrentHashMap<>();
    private final Set<CompletableFuture<?>> activeWork = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean accepting = new AtomicBoolean(false);
    private final AtomicBoolean acceptingClaims = new AtomicBoolean(false);

    private DailyStorage storage;
    private ExecutorService executor;
    private volatile ZoneId zone;
    private volatile List<Double> payouts = DEFAULT_PAYOUTS;
    private int reloadRetryAttempts;
    private BukkitTask reloadRetryTask;

    public DailyService(JavaPlugin plugin) {
        this.plugin = plugin;
        menuRenderer = new DailyMenuRenderer(plugin);
        animationRenderer = new DailyAnimationRenderer(plugin, menuRenderer);
    }

    public void enable() throws SQLException {
        zone = configuredZone();
        payouts = configuredPayouts();
        storage = new DailyStorage(new File(plugin.getDataFolder(), "daily.db"));
        vault.setup();
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "enthusia-tags-daily");
            thread.setDaemon(true);
            return thread;
        });
        accepting.set(true);
        acceptingClaims.set(true);
    }

    public void disable() {
        accepting.set(false);
        acceptingClaims.set(false);
        cancelReloadRetry();
        animations.values().forEach(active -> active.task().cancel());
        animations.clear();

        boolean workersStopped = stopExecutor();
        activeWork.clear();
        claims.clear();
        if (!workersStopped) {
            plugin.getLogger().severe("Daily workers did not stop; daily storage will remain open.");
            return;
        }
        closeStorage();
    }

    public void reload() {
        acceptingClaims.set(false);
        if (!claims.isEmpty()) {
            scheduleReloadRetry();
            return;
        }
        cancelReloadRetry();
        reloadRetryAttempts = 0;
        zone = configuredZone();
        payouts = configuredPayouts();
        vault.setup();
        acceptingClaims.set(true);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use /daily.");
            return true;
        }
        open(player);
        return true;
    }

    public boolean handleAdminCommand(CommandSender sender, String[] args) {
        if (!isValidAdminOperation(args)) {
            sendAdminUsage(sender);
            return true;
        }

        Optional<OfflinePlayer> target = resolveTarget(sender, args[1]);
        Optional<ParsedAdminDate> parsedDate = parseAdminDate(sender, args);
        if (target.isEmpty() || parsedDate.isEmpty()) {
            return true;
        }

        UUID playerId = target.orElseThrow().getUniqueId();
        ParsedAdminDate selection = parsedDate.orElseThrow();
        if (args[0].equalsIgnoreCase("inspect")) {
            completeToSender(sender, submit(() -> inspect(playerId, selection.date())));
            return true;
        }
        return handleReconciliation(sender, args, selection.reasonEnd(), playerId, selection.date());
    }

    public void open(Player player) {
        openLoaded(player.getUniqueId(), true);
    }

    @EventHandler
    public void click(InventoryClickEvent event) {
        InventoryHolder topHolder = event.getView().getTopInventory().getHolder();
        if (!(topHolder instanceof DailyInventoryHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || holder.isAnimation()) {
            return;
        }
        if (holder.claimSlot() >= 0 && event.getRawSlot() == holder.claimSlot()) {
            claim(player);
        }
    }

    @EventHandler
    public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof DailyInventoryHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        cancelAnimation(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void close(InventoryCloseEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof DailyInventoryHolder dailyHolder && dailyHolder.isAnimation()
            && event.getPlayer() instanceof Player player) {
            cancelAnimation(player.getUniqueId(), dailyHolder.sessionId());
        }
    }

    private boolean isValidAdminOperation(String[] args) {
        return args.length >= 2 && (args[0].equalsIgnoreCase("inspect")
            || args[0].equalsIgnoreCase("reconcile"));
    }

    private boolean stopExecutor() {
        if (executor == null) {
            return true;
        }
        executor.shutdownNow();
        try {
            return executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void closeStorage() {
        if (storage == null) {
            return;
        }
        try {
            storage.close();
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.WARNING, "Failed to close daily storage", ex);
        }
    }

    private void scheduleReloadRetry() {
        if (reloadRetryTask != null) {
            return;
        }
        if (reloadRetryAttempts >= MAX_RELOAD_RETRY_ATTEMPTS) {
            plugin.getLogger().warning("Daily reload remained blocked by an active claim for "
                + "20 seconds; claims remain paused until reload is run again.");
            return;
        }
        reloadRetryAttempts++;
        try {
            reloadRetryTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                reloadRetryTask = null;
                reload();
            }, RELOAD_RETRY_DELAY_TICKS);
        } catch (IllegalArgumentException | IllegalPluginAccessException ex) {
            reloadRetryTask = null;
            plugin.getLogger().log(Level.WARNING, "Could not reschedule daily reload", ex);
        }
    }

    private void cancelReloadRetry() {
        BukkitTask task = reloadRetryTask;
        reloadRetryTask = null;
        if (task != null) {
            task.cancel();
        }
    }

    private Optional<OfflinePlayer> resolveTarget(CommandSender sender, String supplied) {
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(supplied);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            return Optional.of(Bukkit.getOfflinePlayer(UUID.fromString(supplied)));
        } catch (IllegalArgumentException ex) {
            sender.sendMessage("Player must be cached or supplied as a UUID.");
            return Optional.empty();
        }
    }

    private Optional<ParsedAdminDate> parseAdminDate(CommandSender sender, String[] args) {
        LocalDate date = today();
        int reasonEnd = args.length;
        if (args[0].equalsIgnoreCase("inspect") && args.length > 2) {
            try {
                date = LocalDate.parse(args[2]);
            } catch (DateTimeException ex) {
                sender.sendMessage("Date must use YYYY-MM-DD.");
                return Optional.empty();
            }
        } else if (args[0].equalsIgnoreCase("reconcile") && args.length > 4) {
            try {
                date = LocalDate.parse(args[args.length - 1]);
                reasonEnd--;
            } catch (DateTimeException ignored) {
                // The final argument is part of the required free-form reason.
            }
        }
        return Optional.of(new ParsedAdminDate(date, reasonEnd));
    }

    private boolean handleReconciliation(CommandSender sender, String[] args, int reasonEnd,
                                         UUID playerId, LocalDate selectedDate) {
        if (args.length < 5) {
            sendAdminUsage(sender);
            return true;
        }
        String decision = args[2].toLowerCase(Locale.ROOT);
        if (!decision.equals("delivered") && !decision.equals("retry")) {
            sender.sendMessage("Decision must be delivered or retry.");
            return true;
        }
        String reason = String.join(" ", Arrays.copyOfRange(args, 3, reasonEnd));
        if (reason.isBlank()) {
            sender.sendMessage("A reconciliation reason is required.");
            return true;
        }
        String administrator = sender instanceof Player player
            ? player.getName() + "/" + player.getUniqueId() : "console";
        completeToSender(sender, submit(() -> reconcileDaily(playerId, selectedDate,
            decision, administrator, reason)));
        return true;
    }

    private List<String> reconcileDaily(UUID playerId, LocalDate date, String decision,
                                        String administrator, String reason) throws SQLException {
        DailyState old = storage.load(playerId, LEGACY_ANIMATION_DEFAULT);
        DailyStorage.Transaction transaction = storage.transaction(playerId, date);
        if (transaction == null) {
            throw new SQLException("Daily transaction not found");
        }
        boolean delivered = decision.equals("delivered");
        if (delivered && old.lastClaimDate() != null && date.isBefore(old.lastClaimDate())) {
            throw new SQLException("Cannot finalize a transaction older than the current daily state");
        }
        boolean stateAlreadyApplied = delivered && date.equals(old.lastClaimDate());
        DailyState deliveredState = reconciledState(old, transaction, date,
            delivered, stateAlreadyApplied);
        DailyStorage.Transaction result = storage.reconcileAtomic(playerId, date, administrator,
            delivered, reason, deliveredState, stateAlreadyApplied);
        return List.of("Daily transaction reconciled atomically as " + decision + " for " + date
            + "; final status=" + result.status() + ".");
    }

    private DailyState reconciledState(DailyState old, DailyStorage.Transaction transaction,
                                       LocalDate date, boolean delivered,
                                       boolean stateAlreadyApplied) throws SQLException {
        if (!delivered || stateAlreadyApplied) {
            return old;
        }
        int streak = DailyRules.nextStreak(old.lastClaimDate(), date, old.currentStreak());
        if (streak <= 0) {
            throw new SQLException("Daily date ordering cannot be applied safely");
        }
        return new DailyState(date, streak, Math.max(old.highestStreak(), streak),
            old.totalClaims() + 1, old.totalAwarded() + transaction.amount(), true);
    }

    private List<String> inspect(UUID playerId, LocalDate date) throws SQLException {
        List<String> lines = new ArrayList<>();
        DailyState state = storage.load(playerId, LEGACY_ANIMATION_DEFAULT);
        Optional<DailyStorage.Transaction> transaction =
            Optional.ofNullable(storage.transaction(playerId, date));
        lines.add("Daily inspection: " + playerId + " / " + date);
        lines.add("  state last=" + state.lastClaimDate() + " streak=" + state.currentStreak()
            + " highest=" + state.highestStreak() + " claims=" + state.totalClaims()
            + " awarded=" + state.totalAwarded());
        appendTransactionInspection(lines, transaction);
        appendHistoryInspection(lines, playerId, date);
        return lines;
    }

    private void appendTransactionInspection(List<String> lines,
                                             Optional<DailyStorage.Transaction> transaction) {
        if (transaction.isEmpty()) {
            lines.add("  transaction: none");
            return;
        }
        DailyStorage.Transaction value = transaction.orElseThrow();
        lines.add("  transaction status=" + value.status() + " amount=" + value.amount()
            + " created=" + value.createdAt() + " completed=" + value.completedAt());
        lines.add("  vault before=" + value.balanceBefore() + " after=" + value.balanceAfter()
            + " requested=" + value.amount() + " returned=" + value.responseAmount()
            + " provider=" + value.responseType() + " failure=" + value.failure());
    }

    private void appendHistoryInspection(List<String> lines, UUID playerId,
                                         LocalDate date) throws SQLException {
        lines.add("  ordinary transition history:");
        for (DailyStorage.Transition entry : storage.transitionHistory(playerId, date, 20)) {
            lines.add("    #" + entry.historyId() + " " + entry.oldStatus() + " -> "
                + entry.newStatus() + " at " + entry.createdAt() + " evidence=" + entry.evidence());
        }
        lines.add("  administrator reconciliation history:");
        for (DailyStorage.Reconciliation entry : storage.reconciliationHistory(playerId, date, 20)) {
            lines.add("    #" + entry.historyId() + " " + entry.oldStatus() + " -> "
                + entry.newStatus() + " " + entry.decision() + " by " + entry.administrator()
                + " at " + entry.createdAt() + " reason=" + entry.reason());
        }
    }

    private void sendAdminUsage(CommandSender sender) {
        sender.sendMessage("Usage: /enthusiatags daily inspect <player> [YYYY-MM-DD]");
        sender.sendMessage(
            "       /enthusiatags daily reconcile <player> <delivered|retry> <reason> [YYYY-MM-DD]");
    }

    private void openLoaded(UUID playerId, boolean allowAnimation) {
        LocalDate currentDate = today();
        List<Double> payoutSnapshot = payouts;
        submit(() -> loadView(playerId, currentDate, payoutSnapshot)).whenComplete((view, throwable) ->
            runMain(() -> finishOpen(playerId, allowAnimation, view, throwable)));
    }

    private DailyMenuModel.View loadView(UUID playerId, LocalDate currentDate,
                                         List<Double> payoutSnapshot) throws SQLException {
        DailyState state = storage.load(playerId, LEGACY_ANIMATION_DEFAULT);
        DailyStorage.Transaction transaction = storage.transaction(playerId, currentDate);
        return DailyMenuModel.build(state, currentDate, payoutSnapshot,
            DailyMenuModel.ledgerState(transaction));
    }

    private void finishOpen(UUID playerId, boolean allowAnimation, DailyMenuModel.View view,
                            Throwable throwable) {
        Optional<Player> online = onlinePlayer(playerId);
        if (online.isEmpty()) {
            return;
        }
        Player player = online.orElseThrow();
        if (throwable != null) {
            plugin.getLogger().log(Level.WARNING, "Could not load daily menu for " + playerId,
                unwrapCompletionException(throwable));
            player.sendMessage(Component.text(DAILY_UNAVAILABLE));
            return;
        }
        if (allowAnimation && animationRenderer.enabled()) {
            startAnimation(player, view);
        } else {
            player.openInventory(menuRenderer.createMenu(view));
        }
    }

    private void startAnimation(Player player, DailyMenuModel.View view) {
        UUID playerId = player.getUniqueId();
        UUID sessionId = UUID.randomUUID();
        cancelAnimation(playerId);
        Inventory inventory = animationRenderer.createInventory(sessionId);
        DailyAnimationRenderer.Presentation presentation = animationRenderer.prepare(view);
        player.openInventory(inventory);

        int frames = animationRenderer.frameCount();
        BukkitRunnable animation = animationTask(playerId, sessionId, inventory, presentation, frames);
        try {
            BukkitTask task = animation.runTaskTimer(plugin, 0L, animationRenderer.frameTicks());
            animations.put(playerId, new ActiveAnimation(sessionId, task));
        } catch (IllegalArgumentException | IllegalPluginAccessException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not start daily animation", ex);
            player.openInventory(menuRenderer.createMenu(view));
        }
    }

    private BukkitRunnable animationTask(UUID playerId, UUID sessionId, Inventory inventory,
                                         DailyAnimationRenderer.Presentation presentation, int frames) {
        return new BukkitRunnable() {
            private int frame;

            @Override
            public void run() {
                Optional<Player> online = onlinePlayer(playerId);
                if (online.isEmpty() || !isCurrentAnimation(playerId, sessionId)
                    || !hasAnimationSession(online.orElseThrow(), sessionId)) {
                    stopAnimation(playerId, sessionId, this);
                    return;
                }

                Player player = online.orElseThrow();
                DailyAnimationPlan.Frame plan = DailyAnimationPlan.frame(frame, frames);
                animationRenderer.renderFrame(inventory, presentation, plan);
                animationRenderer.playFrameSound(player, plan);
                frame++;
                if (frame >= frames) {
                    finishAnimation(playerId, sessionId, this);
                }
            }
        };
    }

    private void finishAnimation(UUID playerId, UUID sessionId, BukkitRunnable animation) {
        stopAnimation(playerId, sessionId, animation);
        try {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Optional<Player> online = onlinePlayer(playerId);
                if (online.isPresent() && hasAnimationSession(online.orElseThrow(), sessionId)) {
                    openLoaded(playerId, false);
                }
            }, 1L);
        } catch (IllegalArgumentException | IllegalPluginAccessException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not finish daily animation", ex);
            openLoaded(playerId, false);
        }
    }

    private void stopAnimation(UUID playerId, UUID sessionId, BukkitRunnable animation) {
        ActiveAnimation current = animations.get(playerId);
        if (current != null && sessionId.equals(current.sessionId())) {
            animations.remove(playerId, current);
        }
        animation.cancel();
    }

    private boolean isCurrentAnimation(UUID playerId, UUID sessionId) {
        ActiveAnimation current = animations.get(playerId);
        return current != null && sessionId.equals(current.sessionId());
    }

    private boolean hasAnimationSession(Player player, UUID sessionId) {
        InventoryHolder holder = player.getOpenInventory().getTopInventory().getHolder();
        return holder instanceof DailyInventoryHolder dailyHolder
            && dailyHolder.isAnimation()
            && sessionId.equals(dailyHolder.sessionId());
    }

    private void cancelAnimation(UUID playerId) {
        ActiveAnimation animation = animations.remove(playerId);
        if (animation != null) {
            animation.task().cancel();
        }
    }

    private void cancelAnimation(UUID playerId, UUID sessionId) {
        ActiveAnimation animation = animations.get(playerId);
        if (animation != null && sessionId.equals(animation.sessionId())
            && animations.remove(playerId, animation)) {
            animation.task().cancel();
        }
    }

    private void claim(Player player) {
        UUID playerId = player.getUniqueId();
        if (!accepting.get() || !acceptingClaims.get()) {
            player.sendMessage(Component.text(DAILY_UNAVAILABLE));
            return;
        }
        if (!claims.add(playerId)) {
            player.sendMessage(Component.text("Your daily claim is already being processed."));
            return;
        }

        LocalDate currentDate = today();
        List<Double> payoutSnapshot = payouts;
        submit(() -> claimOffThread(playerId, currentDate, payoutSnapshot))
            .whenComplete((outcome, throwable) -> {
                claims.remove(playerId);
                runMain(() -> finishClaim(playerId, outcome, throwable));
            });
    }

    private void finishClaim(UUID playerId, DailyClaimOutcome outcome, Throwable throwable) {
        Optional<Player> online = onlinePlayer(playerId);
        if (online.isEmpty()) {
            return;
        }
        Player player = online.orElseThrow();
        if (throwable != null) {
            plugin.getLogger().log(Level.WARNING, "Daily claim failed for " + playerId,
                unwrapCompletionException(throwable));
            player.sendMessage(Component.text(DAILY_UNAVAILABLE));
            return;
        }

        String message = outcome.claimed()
            ? "Daily reward claimed: " + menuRenderer.rewardText(outcome.amount())
            : outcome.message();
        player.sendMessage(Component.text(message));
        if (outcome.claimed()) {
            animationRenderer.playClaimSound(player);
        }
        openLoaded(playerId, false);
    }

    private DailyClaimOutcome claimOffThread(UUID playerId, LocalDate currentDate,
                                             List<Double> payoutSnapshot) throws SQLException {
        DailyState old = storage.load(playerId, LEGACY_ANIMATION_DEFAULT);
        int streak = DailyRules.nextStreak(old.lastClaimDate(), currentDate, old.currentStreak());
        if (streak == 0) {
            return DailyClaimOutcome.failure("You already claimed today's reward.");
        }

        double amount = DailyRules.payout(streak, payoutSnapshot);
        if (!storage.reserve(playerId, currentDate, amount)) {
            return reservationConflict(playerId, currentDate);
        }

        BalanceLookup balance = lookupBalance(playerId);
        if (!balance.available()) {
            failReservationSafely(playerId, currentDate, balance.failureMessage());
            return DailyClaimOutcome.failure(balance.failureMessage());
        }
        if (!markDepositing(playerId, currentDate, balance.amount())) {
            return DailyClaimOutcome.failure(
                "The daily claim could not be prepared safely and may be retried.");
        }

        DepositLookup deposit = invokeDeposit(playerId, currentDate, amount, balance.amount());
        if (!deposit.completed()) {
            return DailyClaimOutcome.failure(deposit.failureMessage());
        }
        return finalizeDeposit(playerId, currentDate, old, streak, amount, deposit.result());
    }

    private BalanceLookup lookupBalance(UUID playerId) {
        try {
            return callOnMain(() -> onlinePlayer(playerId)
                .map(player -> BalanceLookup.available(vault.getBalance(player)))
                .orElseGet(() -> BalanceLookup.unavailable(
                    "Daily claim stopped before the economy was invoked; it may be retried.")));
        } catch (SQLException ex) {
            return BalanceLookup.unavailable(
                "The economy balance could not be checked safely; the claim may be retried.");
        }
    }

    private boolean markDepositing(UUID playerId, LocalDate date, double balanceBefore) {
        try {
            storage.markDepositing(playerId, date, balanceBefore);
            return true;
        } catch (SQLException ex) {
            failReservationSafely(playerId, date,
                "Could not mark Vault deposit as started: " + ex.getMessage());
            plugin.getLogger().log(Level.WARNING,
                "Could not prepare daily deposit for " + playerId, ex);
            return false;
        }
    }

    private DepositLookup invokeDeposit(UUID playerId, LocalDate date, double amount,
                                        double balanceBefore) {
        try {
            DepositLookup lookup = callOnMain(() -> onlinePlayer(playerId)
                .map(player -> DepositLookup.completed(vault.depositDetailed(player, amount)))
                .orElseGet(() -> DepositLookup.notInvoked(
                    "Daily claim stopped before the economy was invoked; it may be retried.")));
            if (!lookup.completed()) {
                failReservationSafely(playerId, date, DISCONNECTED_BEFORE_VAULT);
            }
            return lookup;
        } catch (SQLException ex) {
            markInvocationUncertain(playerId, date, balanceBefore, ex);
            return DepositLookup.notInvoked(
                "The economy result was uncertain. Do not retry; contact staff for reconciliation.");
        }
    }

    private DailyClaimOutcome finalizeDeposit(UUID playerId, LocalDate date, DailyState old,
                                              int streak, double amount,
                                              VaultHook.DepositResult result) throws SQLException {
        DailyStorage.TransactionStatus status = classifyVaultResult(result);
        storage.recordVaultResult(playerId, date, status, result.balanceAfter(),
            result.responseAmount(), result.responseType(), result.errorMessage());
        if (status != DailyStorage.TransactionStatus.DELIVERED) {
            return unsuccessfulDeposit(status);
        }

        DailyState next = new DailyState(date, streak, Math.max(old.highestStreak(), streak),
            old.totalClaims() + 1, old.totalAwarded() + amount, true);
        storage.complete(playerId, date, next);
        return DailyClaimOutcome.success(amount);
    }

    private DailyClaimOutcome reservationConflict(UUID playerId, LocalDate currentDate)
        throws SQLException {
        DailyStorage.Transaction existing = storage.transaction(playerId, currentDate);
        String status = existing == null ? "unknown" : existing.status().name();
        return DailyClaimOutcome.failure("Today's daily transaction is " + status
            + ". Contact staff if your reward or streak is missing.");
    }

    private void failReservationSafely(UUID playerId, LocalDate date, String reason) {
        try {
            storage.fail(playerId, date, reason);
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE,
                "Could not mark pre-Vault daily reservation as failed for " + playerId, ex);
        }
    }

    private void markInvocationUncertain(UUID playerId, LocalDate date, double balanceBefore,
                                         SQLException cause) {
        try {
            storage.recordVaultResult(playerId, date, DailyStorage.TransactionStatus.UNCERTAIN,
                balanceBefore, 0D, "EXCEPTION", cause.getMessage());
        } catch (SQLException storageFailure) {
            storageFailure.addSuppressed(cause);
            plugin.getLogger().log(Level.SEVERE,
                "Could not persist uncertain daily Vault result for " + playerId, storageFailure);
        }
    }

    private DailyClaimOutcome unsuccessfulDeposit(DailyStorage.TransactionStatus status) {
        String message = status == DailyStorage.TransactionStatus.UNCERTAIN
            ? "The economy result was uncertain. Do not retry; contact staff for reconciliation."
            : "The economy rejected the deposit; your streak was not advanced and may be retried.";
        return DailyClaimOutcome.failure(message);
    }

    private DailyStorage.TransactionStatus classifyVaultResult(VaultHook.DepositResult result) {
        boolean exactSuccess = result.success()
            && Double.compare(result.responseAmount(), result.requestedAmount()) == 0;
        if (exactSuccess) {
            return DailyStorage.TransactionStatus.DELIVERED;
        }

        boolean balanceIncreased = result.balanceAfter() > result.balanceBefore();
        boolean unavailable = "UNAVAILABLE".equals(result.responseType());
        boolean definiteFailure = "FAILURE".equals(result.responseType()) && !balanceIncreased;
        if (!result.success() && (unavailable || definiteFailure)) {
            return DailyStorage.TransactionStatus.FAILED;
        }
        return DailyStorage.TransactionStatus.UNCERTAIN;
    }

    private <T> CompletableFuture<T> submit(SqlTask<T> task) {
        if (!accepting.get() || executor == null || executor.isShutdown()) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("Daily service is unavailable"));
        }

        CompletableFuture<T> future = new CompletableFuture<>();
        activeWork.add(future);
        try {
            executor.execute(() -> executeSqlTask(task, future));
        } catch (RejectedExecutionException ex) {
            future.completeExceptionally(ex);
        }
        future.whenComplete((ignored, throwable) -> activeWork.remove(future));
        return future;
    }

    private <T> void executeSqlTask(SqlTask<T> task, CompletableFuture<T> future) {
        try {
            future.complete(task.run());
        } catch (Exception ex) {
            future.completeExceptionally(ex);
        }
    }

    private <T> T callOnMain(Callable<T> callable) throws SQLException {
        if (!accepting.get()) {
            throw new SQLException("Daily service is stopping");
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        if (!runMain(() -> completeCallable(callable, result))) {
            throw new SQLException("Daily main-thread work could not be scheduled");
        }

        try {
            return result.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted waiting for daily main-thread work", ex);
        } catch (ExecutionException | TimeoutException ex) {
            throw new SQLException("Daily main-thread work did not complete safely", ex);
        }
    }

    private <T> void completeCallable(Callable<T> callable, CompletableFuture<T> result) {
        try {
            result.complete(callable.call());
        } catch (Exception ex) {
            result.completeExceptionally(ex);
        }
    }

    private boolean runMain(Runnable runnable) {
        if (!accepting.get()) {
            return false;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, runnable);
            return true;
        } catch (IllegalArgumentException | IllegalPluginAccessException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not schedule daily callback", ex);
            return false;
        }
    }

    private Optional<Player> onlinePlayer(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        return player != null && player.isOnline() ? Optional.of(player) : Optional.empty();
    }

    private void completeToSender(CommandSender sender, CompletableFuture<List<String>> future) {
        future.whenComplete((lines, throwable) -> runMain(() -> {
            if (throwable != null) {
                Throwable cause = unwrapCompletionException(throwable);
                sender.sendMessage("Daily operation failed: " + cause.getMessage());
            } else {
                lines.forEach(sender::sendMessage);
            }
        }));
    }

    private Throwable unwrapCompletionException(Throwable throwable) {
        if (throwable instanceof CompletionException && throwable.getCause() != null) {
            return throwable.getCause();
        }
        return throwable;
    }

    private ZoneId configuredZone() {
        String configured = plugin.getConfig().getString("daily.timezone", DEFAULT_TIMEZONE);
        if (configured == null || configured.isBlank()) {
            return ZoneId.of(DEFAULT_TIMEZONE);
        }
        try {
            return ZoneId.of(configured);
        } catch (DateTimeException ex) {
            plugin.getLogger().warning("Invalid daily timezone " + configured
                + "; using " + DEFAULT_TIMEZONE);
            return ZoneId.of(DEFAULT_TIMEZONE);
        }
    }

    private List<Double> configuredPayouts() {
        List<Double> configured = plugin.getConfig().getDoubleList("daily.payouts");
        if (configured.isEmpty()) {
            return DEFAULT_PAYOUTS;
        }
        if (!DailyPayouts.valid(configured)) {
            plugin.getLogger().warning("Invalid daily.payouts configuration; using safe defaults.");
            return DEFAULT_PAYOUTS;
        }
        return List.copyOf(configured);
    }

    private LocalDate today() {
        return LocalDate.now(zone);
    }

    private record ActiveAnimation(UUID sessionId, BukkitTask task) {
    }

    private record ParsedAdminDate(LocalDate date, int reasonEnd) {
    }

    private record BalanceLookup(boolean available, double amount, String failureMessage) {
        private static BalanceLookup available(double amount) {
            return new BalanceLookup(true, amount, "");
        }

        private static BalanceLookup unavailable(String message) {
            return new BalanceLookup(false, 0D, message);
        }
    }

    private record DepositLookup(boolean completed, VaultHook.DepositResult result,
                                 String failureMessage) {
        private static DepositLookup completed(VaultHook.DepositResult result) {
            return new DepositLookup(true, result, "");
        }

        private static DepositLookup notInvoked(String message) {
            return new DepositLookup(false, VaultHook.DepositResult.unavailable(0D, message), message);
        }
    }

    private record DailyClaimOutcome(String message, boolean claimed, double amount) {
        private static DailyClaimOutcome success(double amount) {
            return new DailyClaimOutcome("", true, amount);
        }

        private static DailyClaimOutcome failure(String message) {
            return new DailyClaimOutcome(message, false, 0D);
        }
    }

    @FunctionalInterface
    private interface SqlTask<T> {
        T run() throws Exception;
    }
}
