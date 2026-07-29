package org.enthusia.tags.daily;

import java.io.File;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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
    private DailyStorage storage;
    private ZoneId zone;
    private List<Double> payouts;

    public DailyService(JavaPlugin plugin) { this.plugin = plugin; }

    public void enable() throws SQLException {
        zone = parseZone(plugin.getConfig().getString("daily.timezone", "America/Indiana/Indianapolis"));
        payouts = plugin.getConfig().getDoubleList("daily.payouts");
        if (payouts.isEmpty()) payouts = List.of(5D, 10D, 15D, 20D, 30D, 40D, 50D);
        storage = new DailyStorage(new File(plugin.getDataFolder(), "daily.db"));
        vault.setup();
    }

    public void disable() {
        animations.values().forEach(BukkitTask::cancel);
        animations.clear();
        claims.clear();
        if (storage != null) try { storage.close(); } catch (SQLException ex) {
            plugin.getLogger().warning("Failed to close daily storage: " + ex.getMessage());
        }
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

    public void open(Player player) {
        try {
            DailyState state = storage.load(player.getUniqueId(), animationDefault());
            if (plugin.getConfig().getBoolean("daily.animation.enabled", true) && state.animationEnabled()
                && DailyRules.nextStreak(state.lastClaimDate(), today(), state.currentStreak()) > 0) {
                openAnimation(player);
            } else {
                player.openInventory(menu(player, state));
            }
        } catch (SQLException ex) {
            player.sendMessage(Component.text("Daily rewards are temporarily unavailable."));
        }
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
            if (player.isOnline()) try {
                player.openInventory(menu(player, storage.load(player.getUniqueId(), animationDefault())));
            } catch (SQLException ignored) { }
        }, duration));
    }

    private Inventory menu(Player player, DailyState state) {
        Inventory inventory = Bukkit.createInventory(new Holder(false), 27, Component.text("Daily Reward"));
        LocalDate today = today();
        int next = DailyRules.nextStreak(state.lastClaimDate(), today, state.currentStreak());
        int payoutDay = next == 0 ? state.currentStreak() : next;
        inventory.setItem(11, named(Material.CLOCK, "Streak: " + state.currentStreak()
            + " | Best: " + state.highestStreak() + " | Claims: " + state.totalClaims()));
        inventory.setItem(13, named(next == 0 ? Material.GRAY_DYE : Material.EMERALD,
            next == 0 ? "Already claimed today" : "Claim $" + DailyRules.payout(payoutDay, payouts)));
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
            try {
                DailyState state = storage.load(player.getUniqueId(), animationDefault());
                storage.saveAnimationPreference(player.getUniqueId(), !state.animationEnabled(), animationDefault());
                player.openInventory(menu(player, new DailyState(state.lastClaimDate(), state.currentStreak(),
                    state.highestStreak(), state.totalClaims(), state.totalAwarded(), !state.animationEnabled())));
            } catch (SQLException ex) {
                player.sendMessage(Component.text("Could not save the animation preference."));
            }
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
        if (!claims.add(id)) return;
        try {
            LocalDate today = today();
            DailyState old = storage.load(id, animationDefault());
            int streak = DailyRules.nextStreak(old.lastClaimDate(), today, old.currentStreak());
            if (streak == 0) {
                player.sendMessage(Component.text("You already claimed today's reward."));
                return;
            }
            double amount = DailyRules.payout(streak, payouts);
            if (!storage.reserve(id, today, amount)) return;
            double before = vault.getBalance(player);
            storage.markDepositing(id, today, before);
            VaultHook.DepositResult result = vault.depositDetailed(player, amount);
            storage.recordVaultResult(id, today,
                result.success() ? DailyStorage.TransactionStatus.DELIVERED : DailyStorage.TransactionStatus.FAILED,
                result.balanceAfter(), result.responseAmount(), result.responseType(), result.errorMessage());
            if (!result.success()) {
                player.sendMessage(Component.text("The economy rejected the deposit; your streak was not advanced."));
                return;
            }
            DailyState next = new DailyState(today, streak, Math.max(old.highestStreak(), streak),
                old.totalClaims() + 1, old.totalAwarded() + amount, old.animationEnabled());
            storage.complete(id, today, next);
            player.sendMessage(Component.text("Daily reward claimed: $" + amount));
            player.openInventory(menu(player, next));
        } catch (SQLException ex) {
            plugin.getLogger().warning("Daily claim failed for " + id + ": " + ex.getMessage());
        } finally {
            claims.remove(id);
        }
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
}
