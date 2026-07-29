package org.enthusia.tags.daily;

import java.io.File;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.enthusia.tags.rewards.VaultHook;

public final class DailyService implements CommandExecutor, Listener {
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
        zone = parseZone(plugin.getConfig().getString("daily.timezone", "America/Indiana/Indianapolis"));
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
        zone = parseZone(plugin.getConfig().getString("daily.timezone", "America/Indiana/Indianapolis"));
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
        if (args.length == 2 && args[0].equalsIgnoreCase("animation")) {
            player.sendMessage("Use the animation toggle inside /daily.");
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
                    live.sendMessage(Component.text("Daily rewards are temporarily unavailable."));
                } else if (allowAnimation && plugin.getConfig().getBoolean("daily.animation.enabled", true)
                    && view.state().animationEnabled()
                    && DailyRules.nextStreak(view.state().lastClaimDate(), today(),
                        view.state().currentStreak()) > 0) {
                    openAnimation(live);
                } else {
                    live.openInventory(menu(live, view.state(), view.transaction()));
                }
            }));
    }

    private void openAnimation(Player player) {
        cancelAnimation(player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(new Holder(true), 27, Component.text("Daily Reward"));
        ItemStack pane = named(Material.LIGHT_BLUE_STAINED_GLASS_PANE, " ");
        for (int slot : List.of(0,1,2,3,4,5,6,7,8,9,17,18,19,20,21,22,23,24,25,26)) inventory.setItem(slot, pane);
        inventory.setItem(13, named(Material.GOLD_INGOT, "Daily reward"));
        player.openInventory(inventory);
        long duration = Math.max(12L, Math.min(18L, plugin.getConfig().getLong("daily.animation.duration-ticks", 15L)));
        animations.put(player.getUniqueId(), Bukkit.getScheduler().runTaskLater(plugin, () -> {
            animations.remove(player.getUniqueId());
            Player live = onlinePlayer(player.getUniqueId());
            if (live != null) openLoaded(live.getUniqueId(), false);
        }, duration));
    }

    private Inventory menu(Player player, DailyState state) {
        return menu(player, state, null);
    }

    private Inventory menu(Player player, DailyState state, DailyStorage.Transaction transaction) {
        Inventory inventory = Bukkit.createInventory(new Holder(false), 27, Component.text("Daily Reward"));
        LocalDate today = today();
        int next = DailyRules.nextStreak(state.lastClaimDate(), today, state.currentStreak());
        int payoutDay = next == 0 ? state.currentStreak() : next;
        inventory.setItem(11, named(Material.CLOCK, "Streak: " + state.currentStreak()
            + " | Best: " + state.highestStreak() + " | Claims: " + state.totalClaims()));
        Material claimMaterial = next == 0 ? Material.GRAY_DYE : Material.EMERALD;
        String claimText = next == 0 ? "Already claimed today"
            : "Claim $" + DailyRules.payout(payoutDay, payouts);
        if (transaction != null && transaction.status() == DailyStorage.TransactionStatus.UNCERTAIN) {
            claimMaterial = Material.REDSTONE;
            claimText = "Daily deposit uncertain - contact staff";
        } else if (transaction != null && transaction.status() == DailyStorage.TransactionStatus.FAILED) {
            claimMaterial = Material.YELLOW_DYE;
            claimText = "Previous deposit failed safely - click to retry";
        } else if (transaction != null && (transaction.status() == DailyStorage.TransactionStatus.PREPARED
            || transaction.status() == DailyStorage.TransactionStatus.DEPOSITING
            || transaction.status() == DailyStorage.TransactionStatus.RECONCILED)) {
            claimMaterial = Material.CLOCK;
            claimText = "Daily claim is still being processed";
        }
        inventory.setItem(13, named(claimMaterial, claimText));
        inventory.setItem(15, named(state.animationEnabled() ? Material.LIME_DYE : Material.RED_DYE,
            "Animation: " + (state.animationEnabled() ? "ON" : "OFF")));
        return inventory;
    }

    @EventHandler public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || holder.animation()) return;
        if (event.getRawSlot() == 13) claim(player);
        if (event.getRawSlot() == 15) {
            UUID playerId = player.getUniqueId();
            submit(() -> {
                DailyState state = storage.load(playerId, animationDefault());
                storage.saveAnimationPreference(playerId, !state.animationEnabled(), animationDefault());
                return new DailyState(state.lastClaimDate(), state.currentStreak(),
                    state.highestStreak(), state.totalClaims(), state.totalAwarded(), !state.animationEnabled());
            }).whenComplete((state, throwable) -> runMain(() -> {
                Player live = onlinePlayer(playerId);
                if (live == null) return;
                if (throwable != null) live.sendMessage(Component.text("Could not save the animation preference."));
                else live.openInventory(menu(live, state));
            }));
        }
    }

    @EventHandler public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }

    @EventHandler public void quit(PlayerQuitEvent event) { cancelAnimation(event.getPlayer().getUniqueId()); }
    @EventHandler public void close(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Holder holder && holder.animation()
            && event.getPlayer() instanceof Player player) cancelAnimation(player.getUniqueId());
    }

    private void claim(Player player) {
        UUID id = player.getUniqueId();
        if (!accepting.get() || !acceptingClaims.get()) {
            player.sendMessage(Component.text("Daily rewards are temporarily unavailable."));
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
                    live.sendMessage(Component.text("Daily rewards are temporarily unavailable."));
                    return;
                }
                live.sendMessage(Component.text(outcome.message()));
                if (outcome.state() != null) live.openInventory(menu(live, outcome.state()));
            });
        });
    }

    private DailyClaimOutcome claimOffThread(UUID id) throws SQLException {
        try {
            LocalDate today = today();
            DailyState old = storage.load(id, animationDefault());
            int streak = DailyRules.nextStreak(old.lastClaimDate(), today, old.currentStreak());
            if (streak == 0) {
                return new DailyClaimOutcome("You already claimed today's reward.", null);
            }
            double amount = DailyRules.payout(streak, payouts);
            if (!storage.reserve(id, today, amount)) {
                DailyStorage.Transaction existing = storage.transaction(id, today);
                String status = existing == null ? "unknown" : existing.status().name();
                return new DailyClaimOutcome("Today's daily transaction is " + status
                    + ". Contact staff if your reward or streak is missing.", null);
            }
            Player live = callOnMain(() -> onlinePlayer(id));
            if (live == null) {
                storage.fail(id, today, "Player disconnected before Vault invocation");
                return new DailyClaimOutcome("Daily claim stopped before the economy was invoked; it may be retried.",
                    null);
            }
            double before = callOnMain(() -> vault.getBalance(live));
            storage.markDepositing(id, today, before);
            VaultHook.DepositResult result = callOnMain(() -> {
                Player current = onlinePlayer(id);
                if (current == null) return null;
                return vault.depositDetailed(current, amount);
            });
            if (result == null) {
                storage.fail(id, today, "Player disconnected before Vault invocation");
                return new DailyClaimOutcome("Daily claim stopped before the economy was invoked; it may be retried.",
                    null);
            }
            DailyStorage.TransactionStatus resultStatus = classifyVaultResult(result);
            storage.recordVaultResult(id, today, resultStatus,
                result.balanceAfter(), result.responseAmount(), result.responseType(), result.errorMessage());
            if (resultStatus != DailyStorage.TransactionStatus.DELIVERED) {
                String message = resultStatus == DailyStorage.TransactionStatus.UNCERTAIN
                    ? "The economy result was uncertain. Do not retry; contact staff for reconciliation."
                    : "The economy rejected the deposit; your streak was not advanced and may be retried.";
                return new DailyClaimOutcome(message, null);
            }
            DailyState next = new DailyState(today, streak, Math.max(old.highestStreak(), streak),
                old.totalClaims() + 1, old.totalAwarded() + amount, old.animationEnabled());
            storage.complete(id, today, next);
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

    private void cancelAnimation(UUID id) {
        BukkitTask task = animations.remove(id);
        if (task != null) task.cancel();
    }
    private LocalDate today() { return LocalDate.now(zone); }
    private boolean animationDefault() {
        return plugin.getConfig().getBoolean("daily.animation.default-player-preference", true);
    }
    private ZoneId parseZone(String configured) {
        try { return ZoneId.of(configured); } catch (DateTimeException ex) {
            plugin.getLogger().warning("Invalid daily timezone " + configured + "; using America/Indiana/Indianapolis");
            return ZoneId.of("America/Indiana/Indianapolis");
        }
    }
    private ItemStack named(Material material, String name) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(name));
        stack.setItemMeta(meta);
        return stack;
    }
    private record Holder(boolean animation) implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }

    private record DailyClaimOutcome(String message, DailyState state) {
    }

    private record DailyView(DailyState state, DailyStorage.Transaction transaction) {
    }

    @FunctionalInterface
    private interface SqlTask<T> {
        T run() throws Exception;
    }
}
