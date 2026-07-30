package org.enthusia.tags.daily;

import java.io.File;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.enthusia.tags.rewards.VaultHook;

public final class DailyService implements CommandExecutor, Listener {
    private static final String DAILY_UNAVAILABLE = "Daily rewards are temporarily unavailable.";
    private static final String DEFAULT_TIMEZONE = "America/Indiana/Indianapolis";
    private static final int[] DAY_SLOTS = {10, 11, 12, 13, 14, 15, 16};
    private static final int[] ANIMATION_DAY_SLOTS = {28, 29, 30, 31, 32, 33, 34};
    private static final int[] ANIMATION_BORDER = {
        0, 1, 2, 3, 4, 5, 6, 7, 8,
        17, 26, 35,
        44, 43, 42, 41, 40, 39, 38, 37, 36,
        27, 18, 9
    };

    private final JavaPlugin plugin;
    private final VaultHook vault = new VaultHook();
    private final Set<UUID> claims = ConcurrentHashMap.newKeySet();
    private final Map<UUID, BukkitTask> animations = new ConcurrentHashMap<>();
    private final Set<CompletableFuture<?>> activeWork = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean accepting = new AtomicBoolean(false);
    private final AtomicBoolean acceptingClaims = new AtomicBoolean(false);
    private DailyStorage storage;
    private ExecutorService executor;
    private volatile ZoneId zone;
    private volatile List<Double> payouts;

    public DailyService(JavaPlugin plugin) { this.plugin = plugin; }

    public void enable() throws SQLException {
        zone = parseZone(plugin.getConfig().getString("daily.timezone", DEFAULT_TIMEZONE));
        payouts = plugin.getConfig().getDoubleList("daily.payouts");
        if (payouts.isEmpty()) payouts = List.of(5D, 10D, 15D, 20D, 30D, 40D, 50D);
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
        animations.values().forEach(BukkitTask::cancel);
        animations.clear();
        boolean workersStopped = true;
        if (executor != null) {
            executor.shutdownNow();
            try {
                workersStopped = executor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                workersStopped = false;
            }
        }
        activeWork.clear();
        claims.clear();
        if (!workersStopped) {
            plugin.getLogger().severe("Daily workers did not stop; daily storage will remain open.");
            return;
        }
        if (storage != null) try { storage.close(); } catch (SQLException ex) {
            plugin.getLogger().warning("Failed to close daily storage: " + ex.getMessage());
        }
    }

    public void reload() {
        acceptingClaims.set(false);
        if (!claims.isEmpty()) {
            Bukkit.getScheduler().runTaskLater(plugin, this::reload, 1L);
            return;
        }
        zone = parseZone(plugin.getConfig().getString("daily.timezone", DEFAULT_TIMEZONE));
        List<Double> configured = plugin.getConfig().getDoubleList("daily.payouts");
        payouts = configured.isEmpty() ? List.of(5D, 10D, 15D, 20D, 30D, 40D, 50D)
            : List.copyOf(configured);
        vault.setup();
        acceptingClaims.set(true);
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use /daily.");
            return true;
        }
        open(player);
        return true;
    }

    public boolean handleAdminCommand(CommandSender sender, String[] args) {
        if (args.length < 2 || (!args[0].equalsIgnoreCase("inspect")
            && !args[0].equalsIgnoreCase("reconcile"))) {
            sendAdminUsage(sender);
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[1]);
        if (target == null) {
            try {
                target = Bukkit.getOfflinePlayer(UUID.fromString(args[1]));
            } catch (IllegalArgumentException ex) {
                sender.sendMessage("Player must be cached or supplied as a UUID.");
                return true;
            }
        }
        LocalDate date = today();
        int reasonEnd = args.length;
        if (args[0].equalsIgnoreCase("inspect") && args.length > 2) {
            try {
                date = LocalDate.parse(args[2]);
            } catch (DateTimeException ex) {
                sender.sendMessage("Date must use YYYY-MM-DD.");
                return true;
            }
        } else if (args[0].equalsIgnoreCase("reconcile") && args.length > 4) {
            try {
                date = LocalDate.parse(args[args.length - 1]);
                reasonEnd--;
            } catch (DateTimeException ignored) {
                // The final argument is part of the required free-form reason.
            }
        }
        UUID playerId = target.getUniqueId();
        LocalDate selectedDate = date;
        if (args[0].equalsIgnoreCase("inspect")) {
            completeToSender(sender, submit(() -> inspect(playerId, selectedDate)));
            return true;
        }
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
        DailyState old = storage.load(playerId, animationDefault());
        DailyStorage.Transaction transaction = storage.transaction(playerId, date);
        if (transaction == null) throw new SQLException("Daily transaction not found");
        boolean delivered = decision.equals("delivered");
        if (delivered && old.lastClaimDate() != null && date.isBefore(old.lastClaimDate())) {
            throw new SQLException("Cannot finalize a transaction older than the current daily state");
        }
        boolean stateAlreadyApplied = delivered && date.equals(old.lastClaimDate());
        DailyState next = null;
        if (delivered && !stateAlreadyApplied) {
            int streak = DailyRules.nextStreak(old.lastClaimDate(), date, old.currentStreak());
            if (streak <= 0) throw new SQLException("Daily date ordering cannot be applied safely");
            next = new DailyState(date, streak, Math.max(old.highestStreak(), streak),
                old.totalClaims() + 1, old.totalAwarded() + transaction.amount(), old.animationEnabled());
        }
        DailyStorage.Transaction result = storage.reconcileAtomic(playerId, date, administrator,
            delivered, reason, next, stateAlreadyApplied);
        return List.of("Daily transaction reconciled atomically as " + decision + " for " + date
            + "; final status=" + result.status() + ".");
    }

    private List<String> inspect(UUID playerId, LocalDate date) throws SQLException {
        List<String> lines = new ArrayList<>();
        DailyState state = storage.load(playerId, animationDefault());
        DailyStorage.Transaction tx = storage.transaction(playerId, date);
        lines.add("Daily inspection: " + playerId + " / " + date);
        lines.add("  state last=" + state.lastClaimDate() + " streak=" + state.currentStreak()
            + " highest=" + state.highestStreak() + " claims=" + state.totalClaims()
            + " awarded=" + state.totalAwarded());
        if (tx == null) {
            lines.add("  transaction: none");
        } else {
            lines.add("  transaction status=" + tx.status() + " amount=" + tx.amount()
                + " created=" + tx.createdAt() + " completed=" + tx.completedAt());
            lines.add("  vault before=" + tx.balanceBefore() + " after=" + tx.balanceAfter()
                + " requested=" + tx.amount() + " returned=" + tx.responseAmount()
                + " provider=" + tx.responseType() + " failure=" + tx.failure());
        }
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
        return lines;
    }

    private void sendAdminUsage(CommandSender sender) {
        sender.sendMessage("Usage: /enthusiatags daily inspect <player> [YYYY-MM-DD]");
        sender.sendMessage("       /enthusiatags daily reconcile <player> <delivered|retry> <reason> [YYYY-MM-DD]");
    }

    public void open(Player player) {
        openLoaded(player.getUniqueId(), true);
    }

    private void openLoaded(UUID playerId, boolean allowAnimation) {
        submit(() -> new DailyView(storage.load(playerId, animationDefault()),
            storage.transaction(playerId, today()))).whenComplete((view, throwable) ->
            runMain(() -> {
                Player live = onlinePlayer(playerId);
                if (live == null) return;
                if (throwable != null) {
                    live.sendMessage(Component.text(DAILY_UNAVAILABLE));
                } else if (allowAnimation && animationEnabled()) {
                    openAnimation(live, view);
                } else {
                    live.openInventory(menu(view.state(), view.transaction()));
                }
            }));
    }

    private void openAnimation(Player player, DailyView view) {
        UUID playerId = player.getUniqueId();
        UUID sessionId = UUID.randomUUID();
        cancelAnimation(playerId);
        Inventory inventory = Bukkit.createInventory(new Holder(true, -1, sessionId), 45,
            Component.text("Daily Rewards", NamedTextColor.GOLD));
        player.openInventory(inventory);

        int frames = clamp(plugin.getConfig().getInt("daily.animation.frames", 18), 12, 30);
        long frameTicks = clamp(plugin.getConfig().getLong("daily.animation.frame-ticks", 2L), 1L, 5L);

        BukkitRunnable animation = new BukkitRunnable() {
            private int frame;

            @Override public void run() {
                Player live = onlinePlayer(playerId);
                if (live == null || !hasAnimationSession(live, sessionId)) {
                    animations.remove(playerId);
                    cancel();
                    return;
                }

                renderAnimationFrame(inventory, view, frame, frames);
                playAnimationSound(live, frame, frames);
                frame++;

                if (frame >= frames) {
                    animations.remove(playerId);
                    cancel();
                    Bukkit.getScheduler().runTaskLater(plugin, () -> completeAnimation(
                        playerId, sessionId, view), 1L);
                }
            }
        };
        BukkitTask task = animation.runTaskTimer(plugin, 0L, frameTicks);
        animations.put(playerId, task);
    }

    private boolean hasAnimationSession(Player player, UUID sessionId) {
        InventoryHolder inventoryHolder = player.getOpenInventory().getTopInventory().getHolder();
        return inventoryHolder instanceof Holder holder
            && holder.animation()
            && sessionId.equals(holder.sessionId());
    }

    private void completeAnimation(UUID playerId, UUID sessionId, DailyView view) {
        Player player = onlinePlayer(playerId);
        if (player != null && hasAnimationSession(player, sessionId)) {
            player.openInventory(menu(view.state(), view.transaction()));
        }
    }

    private void renderAnimationFrame(Inventory inventory, DailyView view, int frame, int frames) {
        AnimationContext context = animationContext(view);
        fillAnimationBackground(inventory, frame, frames);
        renderAnimationBorder(inventory, frame);
        renderAnimationDays(inventory, view, context, frame);
        renderAnimationStats(inventory, view.state(), frame);
        renderAnimationCenter(inventory, context.activeDay(), frame, frames);
    }

    private AnimationContext animationContext(DailyView view) {
        LocalDate currentDate = today();
        int next = DailyRules.nextStreak(view.state().lastClaimDate(), currentDate,
            view.state().currentStreak());
        int activeDay = next == 0 ? Math.max(1, view.state().currentStreak()) : next;
        int completed = activeCompletedDays(view.state(), currentDate);
        return new AnimationContext(next, activeDay, completed);
    }

    private void fillAnimationBackground(Inventory inventory, int frame, int frames) {
        ItemStack background = named(Material.BLACK_STAINED_GLASS_PANE, " ");
        Material borderMaterial = frame >= frames - 3
            ? Material.YELLOW_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE;
        ItemStack border = named(borderMaterial, " ");
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, background);
        for (int slot : ANIMATION_BORDER) inventory.setItem(slot, border);
    }

    private void renderAnimationBorder(Inventory inventory, int frame) {
        int head = frame % ANIMATION_BORDER.length;
        setAnimationBorder(inventory, head, Material.WHITE_STAINED_GLASS_PANE);
        setAnimationBorder(inventory, head - 1, Material.YELLOW_STAINED_GLASS_PANE);
        setAnimationBorder(inventory, head - 2, Material.ORANGE_STAINED_GLASS_PANE);
        setAnimationBorder(inventory, head - 3, Material.BROWN_STAINED_GLASS_PANE);
    }

    private void renderAnimationDays(Inventory inventory, DailyView view,
                                     AnimationContext context, int frame) {
        int revealCount = Math.min(DAY_SLOTS.length, Math.max(0, frame - 2));
        for (int index = 0; index < revealCount; index++) {
            int day = displayedDay(index, context.activeDay());
            DayStatus status = dayStatus(day, context.next(), context.completed(), view.transaction());
            inventory.setItem(ANIMATION_DAY_SLOTS[index],
                dayItem(day, isRollingDay(index, context.activeDay()), status));
        }
    }

    private void renderAnimationStats(Inventory inventory, DailyState state, int frame) {
        if (frame < 5) return;
        inventory.setItem(20, statItem(Material.CLOCK, "Current Streak", state.currentStreak()));
        inventory.setItem(24, statItem(Material.BEACON, "Best Streak", state.highestStreak()));
    }

    private void renderAnimationCenter(Inventory inventory, int activeDay, int frame, int frames) {
        CenterFrame centerFrame = centerFrame(activeDay, frame, frames);
        ItemStack center = named(centerFrame.material(), centerFrame.name());
        if (centerFrame.glowing()) addGlow(center);
        inventory.setItem(22, center);
    }

    private CenterFrame centerFrame(int activeDay, int frame, int frames) {
        if (frame < 4) {
            return new CenterFrame(Material.CLOCK, "Loading your streak...", false);
        }
        if (frame < 8) {
            return new CenterFrame(Material.GOLD_NUGGET, "Finding Day " + activeDay + "...", false);
        }
        if (frame < frames - 3) {
            String payout = formatAmount(DailyRules.payout(activeDay, payouts));
            return new CenterFrame(Material.GOLD_INGOT,
                "Day " + activeDay + " • $" + payout, false);
        }
        return new CenterFrame(Material.EMERALD, "Daily Rewards Ready", true);
    }

    private void setAnimationBorder(Inventory inventory, int index, Material material) {
        int normalized = Math.floorMod(index, ANIMATION_BORDER.length);
        inventory.setItem(ANIMATION_BORDER[normalized], named(material, " "));
    }

    private Inventory menu(DailyState state) {
        return menu(state, null);
    }

    private Inventory menu(DailyState state, DailyStorage.Transaction transaction) {
        LocalDate currentDate = today();
        int next = DailyRules.nextStreak(state.lastClaimDate(), currentDate, state.currentStreak());
        int activeDay = next == 0 ? Math.max(1, state.currentStreak()) : next;
        int completed = activeCompletedDays(state, currentDate);
        int claimSlot = claimSlot(next, transaction);

        Inventory inventory = Bukkit.createInventory(new Holder(false, claimSlot, null), 27,
            Component.text("Daily Rewards", NamedTextColor.GOLD));
        inventory.setItem(3, statItem(Material.CLOCK, "Current Streak", state.currentStreak()));
        inventory.setItem(5, statItem(Material.BEACON, "Best Streak", state.highestStreak()));

        for (int index = 0; index < DAY_SLOTS.length; index++) {
            int day = displayedDay(index, activeDay);
            DayStatus status = dayStatus(day, next, completed, transaction);
            inventory.setItem(DAY_SLOTS[index], dayItem(day, isRollingDay(index, activeDay), status));
        }
        return inventory;
    }

    private int displayedDay(int index, int activeDay) {
        return isRollingDay(index, activeDay) ? activeDay : index + 1;
    }

    private boolean isRollingDay(int index, int activeDay) {
        return index == DAY_SLOTS.length - 1 && activeDay > DAY_SLOTS.length;
    }

    private int activeCompletedDays(DailyState state, LocalDate currentDate) {
        if (state.lastClaimDate() == null) return 0;
        if (state.lastClaimDate().equals(currentDate)
            || state.lastClaimDate().plusDays(1).equals(currentDate)) {
            return Math.max(0, state.currentStreak());
        }
        return 0;
    }

    private int claimSlot(int next, DailyStorage.Transaction transaction) {
        if (next <= 0 || transactionBlocksClaim(transaction)) return -1;
        return next >= DAY_SLOTS.length ? DAY_SLOTS[DAY_SLOTS.length - 1] : DAY_SLOTS[next - 1];
    }

    private DayStatus dayStatus(int day, int next, int completed,
                                DailyStorage.Transaction transaction) {
        if (day <= completed) return DayStatus.CLAIMED;
        if (next <= 0 || day != next) return DayStatus.UPCOMING;
        return currentDayStatus(transaction);
    }

    private DayStatus currentDayStatus(DailyStorage.Transaction transaction) {
        if (transaction == null) return DayStatus.CLAIMABLE;
        return switch (transaction.status()) {
            case UNCERTAIN -> DayStatus.RECONCILIATION;
            case FAILED -> DayStatus.RETRY;
            case PREPARED, DEPOSITING, RECONCILED -> DayStatus.PROCESSING;
            default -> DayStatus.CLAIMABLE;
        };
    }

    private boolean transactionBlocksClaim(DailyStorage.Transaction transaction) {
        if (transaction == null) return false;
        return switch (transaction.status()) {
            case UNCERTAIN, PREPARED, DEPOSITING, RECONCILED -> true;
            default -> false;
        };
    }

    private ItemStack statItem(Material material, String label, int value) {
        return item(material, Component.text(label, NamedTextColor.GOLD), List.of(
            Component.text(String.valueOf(value), NamedTextColor.WHITE)
        ), false);
    }

    private ItemStack dayItem(int day, boolean rolling, DayStatus status) {
        Material material = rolling ? Material.NETHER_STAR : status.material();
        Component name = Component.text("Day " + day, status.color());
        List<Component> lore = List.of(
            Component.text("Amount: ", NamedTextColor.GRAY)
                .append(Component.text("$" + formatAmount(DailyRules.payout(day, payouts)), NamedTextColor.GOLD)),
            Component.text(status.statusLine(), status.color())
        );
        return item(material, name, lore, status.glowing());
    }

    @EventHandler public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || holder.animation()) return;
        if (holder.claimSlot() >= 0 && event.getRawSlot() == holder.claimSlot()) claim(player);
    }

    @EventHandler public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }

    @EventHandler public void quit(PlayerQuitEvent event) {
        cancelAnimation(event.getPlayer().getUniqueId());
    }

    @EventHandler public void close(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Holder holder && holder.animation()
            && event.getPlayer() instanceof Player player) cancelAnimation(player.getUniqueId());
    }

    private void claim(Player player) {
        UUID id = player.getUniqueId();
        if (!accepting.get() || !acceptingClaims.get()) {
            player.sendMessage(Component.text(DAILY_UNAVAILABLE));
            return;
        }
        if (!claims.add(id)) {
            player.sendMessage(Component.text("Your daily claim is already being processed."));
            return;
        }
        CompletableFuture<DailyClaimOutcome> work = submit(() -> claimOffThread(id));
        work.whenComplete((outcome, throwable) -> {
            claims.remove(id);
            runMain(() -> {
                Player live = onlinePlayer(id);
                if (live == null) return;
                if (throwable != null) {
                    plugin.getLogger().warning("Daily claim failed for " + id + ": " + throwable.getMessage());
                    live.sendMessage(Component.text(DAILY_UNAVAILABLE));
                    return;
                }
                live.sendMessage(Component.text(outcome.message()));
                if (outcome.state() != null) {
                    playClaimSound(live);
                    live.openInventory(menu(outcome.state()));
                } else {
                    openLoaded(id, false);
                }
            });
        });
    }

    private DailyClaimOutcome claimOffThread(UUID id) throws SQLException {
        try {
            LocalDate currentDate = today();
            DailyState old = storage.load(id, animationDefault());
            int streak = DailyRules.nextStreak(old.lastClaimDate(), currentDate, old.currentStreak());
            if (streak == 0) {
                return new DailyClaimOutcome("You already claimed today's reward.", null);
            }
            double amount = DailyRules.payout(streak, payouts);
            if (!storage.reserve(id, currentDate, amount)) {
                DailyStorage.Transaction existing = storage.transaction(id, currentDate);
                String status = existing == null ? "unknown" : existing.status().name();
                return new DailyClaimOutcome("Today's daily transaction is " + status
                    + ". Contact staff if your reward or streak is missing.", null);
            }
            Player live = callOnMain(() -> onlinePlayer(id));
            if (live == null) {
                storage.fail(id, currentDate, "Player disconnected before Vault invocation");
                return new DailyClaimOutcome("Daily claim stopped before the economy was invoked; it may be retried.",
                    null);
            }
            double before = callOnMain(() -> vault.getBalance(live));
            storage.markDepositing(id, currentDate, before);
            VaultHook.DepositResult result = callOnMain(() -> {
                Player current = onlinePlayer(id);
                if (current == null) return null;
                return vault.depositDetailed(current, amount);
            });
            if (result == null) {
                storage.fail(id, currentDate, "Player disconnected before Vault invocation");
                return new DailyClaimOutcome("Daily claim stopped before the economy was invoked; it may be retried.",
                    null);
            }
            DailyStorage.TransactionStatus resultStatus = classifyVaultResult(result);
            storage.recordVaultResult(id, currentDate, resultStatus,
                result.balanceAfter(), result.responseAmount(), result.responseType(), result.errorMessage());
            if (resultStatus != DailyStorage.TransactionStatus.DELIVERED) {
                String message = resultStatus == DailyStorage.TransactionStatus.UNCERTAIN
                    ? "The economy result was uncertain. Do not retry; contact staff for reconciliation."
                    : "The economy rejected the deposit; your streak was not advanced and may be retried.";
                return new DailyClaimOutcome(message, null);
            }
            DailyState next = new DailyState(currentDate, streak, Math.max(old.highestStreak(), streak),
                old.totalClaims() + 1, old.totalAwarded() + amount, old.animationEnabled());
            storage.complete(id, currentDate, next);
            return new DailyClaimOutcome("Daily reward claimed: $" + amount, next);
        } catch (SQLException ex) {
            throw ex;
        }
    }

    private DailyStorage.TransactionStatus classifyVaultResult(VaultHook.DepositResult result) {
        if (result.success() && Double.compare(result.responseAmount(), result.requestedAmount()) == 0) {
            return DailyStorage.TransactionStatus.DELIVERED;
        }
        boolean balanceIncreased = result.balanceAfter() > result.balanceBefore();
        boolean explicitRetryable = "UNAVAILABLE".equals(result.responseType())
            || ("FAILURE".equals(result.responseType()) && !balanceIncreased);
        if (!result.success() && explicitRetryable) {
            return DailyStorage.TransactionStatus.FAILED;
        }
        return DailyStorage.TransactionStatus.UNCERTAIN;
    }

    private <T> CompletableFuture<T> submit(SqlTask<T> task) {
        if (!accepting.get() || executor == null || executor.isShutdown()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Daily service is unavailable"));
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        activeWork.add(future);
        try {
            executor.execute(() -> {
                try {
                    future.complete(task.run());
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            });
        } catch (RuntimeException ex) {
            future.completeExceptionally(ex);
        }
        future.whenComplete((ignored, throwable) -> activeWork.remove(future));
        return future;
    }

    private <T> T callOnMain(Callable<T> callable) throws SQLException {
        if (!accepting.get()) throw new SQLException("Daily service is stopping");
        CompletableFuture<T> result = new CompletableFuture<>();
        runMain(() -> {
            try {
                result.complete(callable.call());
            } catch (Exception ex) {
                result.completeExceptionally(ex);
            }
        });
        try {
            return result.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted waiting for daily main-thread work", ex);
        } catch (ExecutionException | TimeoutException ex) {
            throw new SQLException("Daily main-thread work did not complete safely", ex);
        }
    }

    private void runMain(Runnable runnable) {
        if (!accepting.get()) return;
        try {
            Bukkit.getScheduler().runTask(plugin, runnable);
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Could not schedule daily callback: " + ex.getMessage());
        }
    }

    private Player onlinePlayer(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        return player != null && player.isOnline() ? player : null;
    }

    private void completeToSender(CommandSender sender, CompletableFuture<List<String>> future) {
        future.whenComplete((lines, throwable) -> runMain(() -> {
            if (throwable != null) {
                Throwable cause = throwable instanceof CompletionException ? throwable.getCause() : throwable;
                sender.sendMessage("Daily operation failed: "
                    + (cause == null ? throwable.getMessage() : cause.getMessage()));
            } else {
                lines.forEach(sender::sendMessage);
            }
        }));
    }

    private boolean animationEnabled() {
        return plugin.getConfig().getBoolean("daily.animation.enabled", true);
    }

    private boolean animationDefault() {
        return true;
    }

    private void playAnimationSound(Player player, int frame, int frames) {
        if (!plugin.getConfig().getBoolean("daily.animation.sound.enabled", true)) return;
        if (frame >= frames - 1) {
            playConfiguredSound(player, "daily.animation.sound.final-sound",
                Sound.BLOCK_AMETHYST_BLOCK_CHIME,
                "daily.animation.sound.final-volume", 0.65F,
                "daily.animation.sound.final-pitch", 1.2F);
            return;
        }
        boolean accent = frame > 0 && frame % 4 == 3;
        String soundPath = accent
            ? "daily.animation.sound.accent-sound" : "daily.animation.sound.step-sound";
        Sound fallback = accent ? Sound.BLOCK_NOTE_BLOCK_CHIME : Sound.BLOCK_NOTE_BLOCK_HAT;
        Sound sound = configuredSound(soundPath, fallback);
        float volume = configuredFloat("daily.animation.sound.volume", 0.35F, 0F, 2F);
        float startPitch = configuredFloat("daily.animation.sound.starting-pitch", 0.72F, 0.5F, 2F);
        float pitchStep = configuredFloat("daily.animation.sound.pitch-step", 0.055F, 0F, 0.2F);
        float pitch = Math.min(2F, startPitch + (frame * pitchStep) + (accent ? 0.08F : 0F));
        player.playSound(player.getLocation(), sound, SoundCategory.MASTER, volume, pitch);
    }

    private void playClaimSound(Player player) {
        if (!plugin.getConfig().getBoolean("daily.claim-sound.enabled", true)) return;
        playConfiguredSound(player, "daily.claim-sound.sound", Sound.ENTITY_EXPERIENCE_ORB_PICKUP,
            "daily.claim-sound.volume", 0.7F,
            "daily.claim-sound.pitch", 1.25F);
    }

    private void playConfiguredSound(Player player, String soundPath, Sound fallback,
                                     String volumePath, float defaultVolume,
                                     String pitchPath, float defaultPitch) {
        Sound sound = configuredSound(soundPath, fallback);
        float volume = configuredFloat(volumePath, defaultVolume, 0F, 2F);
        float pitch = configuredFloat(pitchPath, defaultPitch, 0.5F, 2F);
        player.playSound(player.getLocation(), sound, SoundCategory.MASTER, volume, pitch);
    }

    private Sound configuredSound(String path, Sound fallback) {
        String configured = plugin.getConfig().getString(path);
        if (configured == null || configured.isBlank()) return fallback;
        try {
            return Sound.valueOf(configured.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Invalid sound " + configured + " at " + path
                + "; using " + fallback.name());
            return fallback;
        }
    }

    private float configuredFloat(String path, float fallback, float minimum, float maximum) {
        return (float) Math.max(minimum, Math.min(maximum,
            plugin.getConfig().getDouble(path, fallback)));
    }

    private void cancelAnimation(UUID id) {
        BukkitTask task = animations.remove(id);
        if (task != null) task.cancel();
    }

    private LocalDate today() { return LocalDate.now(zone); }

    private ZoneId parseZone(String configured) {
        if (configured == null || configured.isBlank()) return ZoneId.of(DEFAULT_TIMEZONE);
        try {
            return ZoneId.of(configured);
        } catch (DateTimeException ex) {
            plugin.getLogger().warning("Invalid daily timezone " + configured
                + "; using " + DEFAULT_TIMEZONE);
            return ZoneId.of(DEFAULT_TIMEZONE);
        }
    }

    private ItemStack named(Material material, String name) {
        return item(material, Component.text(name), List.of(), false);
    }

    private ItemStack item(Material material, Component name, List<Component> lore, boolean glowing) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        meta.displayName(name);
        if (!lore.isEmpty()) meta.lore(lore);
        if (glowing) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private void addGlow(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return;
        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        stack.setItemMeta(meta);
    }

    private String formatAmount(double amount) {
        return BigDecimal.valueOf(amount).stripTrailingZeros().toPlainString();
    }

    private int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private long clamp(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private record Holder(boolean animation, int claimSlot, UUID sessionId) implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }

    private record DailyClaimOutcome(String message, DailyState state) {
    }

    private record DailyView(DailyState state, DailyStorage.Transaction transaction) {
    }

    private record AnimationContext(int next, int activeDay, int completed) {
    }

    private record CenterFrame(Material material, String name, boolean glowing) {
    }

    private enum DayStatus {
        CLAIMED(Material.LIME_STAINED_GLASS_PANE, NamedTextColor.GREEN, "Claimed", false),
        CLAIMABLE(Material.GOLD_INGOT, NamedTextColor.GOLD,
            "Available now — click to claim", true),
        UPCOMING(Material.GRAY_STAINED_GLASS_PANE, NamedTextColor.GRAY, "Upcoming", false),
        RETRY(Material.YELLOW_DYE, NamedTextColor.YELLOW,
            "Previous deposit failed — click to retry", true),
        PROCESSING(Material.CLOCK, NamedTextColor.YELLOW,
            "Claim is still being processed", false),
        RECONCILIATION(Material.REDSTONE, NamedTextColor.RED,
            "Uncertain deposit — contact staff", false);

        private final Material material;
        private final NamedTextColor color;
        private final String statusLine;
        private final boolean glowing;

        DayStatus(Material material, NamedTextColor color, String statusLine, boolean glowing) {
            this.material = material;
            this.color = color;
            this.statusLine = statusLine;
            this.glowing = glowing;
        }

        Material material() { return material; }
        NamedTextColor color() { return color; }
        String statusLine() { return statusLine; }
        boolean glowing() { return glowing; }
    }

    @FunctionalInterface
    private interface SqlTask<T> {
        T run() throws Exception;
    }
}
