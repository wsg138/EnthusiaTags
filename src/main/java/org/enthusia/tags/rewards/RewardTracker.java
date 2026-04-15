package org.enthusia.tags.rewards;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RewardTracker implements Listener {
    private final RewardService rewardService;
    private final Map<UUID, Map<UUID, Long>> firstHitTimes = new ConcurrentHashMap<>();
    private BukkitTask playtimeTask;

    public RewardTracker(RewardService rewardService) {
        this.rewardService = rewardService;
    }

    public void start(JavaPlugin plugin) {
        if (playtimeTask != null) {
            return;
        }
        playtimeTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickPlaytime, 20L, 1200L);
    }

    public void stop() {
        if (playtimeTask != null) {
            playtimeTask.cancel();
            playtimeTask = null;
        }
        firstHitTimes.clear();
    }

    @EventHandler
    public void onAsyncPreLogin(AsyncPlayerPreLoginEvent event) {
        rewardService.preloadPlayerBlocking(event.getUniqueId());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        rewardService.loadPlayer(event.getPlayer());
        if (event.getPlayer().hasPermission("enthusia.tags.admin")) {
            for (String warning : rewardService.getStaffWarnings()) {
                event.getPlayer().sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(warning));
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        rewardService.unloadPlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player damager = resolveDamager(event);
        if (damager == null) {
            return;
        }
        firstHitTimes.computeIfAbsent(damager.getUniqueId(), ignored -> new ConcurrentHashMap<>())
            .putIfAbsent(victim.getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Material type = event.getBlock().getType();
        if (type.name().endsWith("_LOG")) {
            rewardService.incrementCounter(event.getPlayer().getUniqueId(), "logs_mined", 1);
        }
        if (type == Material.DIRT || type == Material.COARSE_DIRT || type == Material.ROOTED_DIRT) {
            rewardService.incrementCounter(event.getPlayer().getUniqueId(), "dirt_mined", 1);
        }
        if (type == Material.STONE || type == Material.DEEPSLATE || type == Material.ANDESITE
            || type == Material.DIORITE || type == Material.GRANITE) {
            rewardService.incrementCounter(event.getPlayer().getUniqueId(), "stone_mined", 1);
        }
        if (type == Material.IRON_ORE || type == Material.DEEPSLATE_IRON_ORE) {
            rewardService.incrementCounter(event.getPlayer().getUniqueId(), "iron_ore_mined", 1);
        }
        if (type == Material.NETHERRACK) {
            rewardService.incrementCounter(event.getPlayer().getUniqueId(), "netherrack_mined", 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (event.getItem().getItemStack().getType() == Material.DIAMOND) {
            rewardService.incrementCounter(player.getUniqueId(), "diamonds_obtained",
                event.getItem().getItemStack().getAmount());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        rewardService.setCounter(victim.getUniqueId(), "kill_streak", 0);
        handleDeathCause(victim);

        if (killer == null) {
            rewardService.setCounter(victim.getUniqueId(), "death_streak_same", 0);
            rewardService.setState(victim.getUniqueId(), "last_killer", "");
            return;
        }

        rewardService.incrementCounter(killer.getUniqueId(), "kill_streak", 1);
        updateDeathStreakSame(victim, killer);
        updateQuickKill(killer, victim);
        updateFullArmorKill(killer, victim);
        updateLowHealthKill(killer);

        Map<UUID, Long> hits = firstHitTimes.get(killer.getUniqueId());
        if (hits != null) {
            hits.remove(victim.getUniqueId());
        }
    }

    private void updateDeathStreakSame(Player victim, Player killer) {
        String lastKiller = rewardService.getState(victim.getUniqueId(), "last_killer");
        if (lastKiller != null && lastKiller.equalsIgnoreCase(killer.getUniqueId().toString())) {
            rewardService.incrementCounter(victim.getUniqueId(), "death_streak_same", 1);
            return;
        }
        rewardService.setCounter(victim.getUniqueId(), "death_streak_same", 1);
        rewardService.setState(victim.getUniqueId(), "last_killer", killer.getUniqueId().toString());
    }

    private void updateQuickKill(Player killer, Player victim) {
        Map<UUID, Long> victimMap = firstHitTimes.get(killer.getUniqueId());
        if (victimMap == null) {
            return;
        }
        Long firstHit = victimMap.get(victim.getUniqueId());
        if (firstHit != null && System.currentTimeMillis() - firstHit <= 10_000L) {
            rewardService.incrementCounter(killer.getUniqueId(), "quick_kill", 1);
        }
    }

    private void updateFullArmorKill(Player killer, Player victim) {
        var inv = victim.getInventory();
        if (inv.getHelmet() == null || inv.getChestplate() == null
            || inv.getLeggings() == null || inv.getBoots() == null) {
            return;
        }
        if (inv.getHelmet().getType().isAir()
            || inv.getChestplate().getType().isAir()
            || inv.getLeggings().getType().isAir()
            || inv.getBoots().getType().isAir()) {
            return;
        }
        rewardService.incrementCounter(killer.getUniqueId(), "kill_full_armor", 1);
    }

    private void updateLowHealthKill(Player killer) {
        if (killer.getHealth() <= 6.0) {
            rewardService.incrementCounter(killer.getUniqueId(), "kill_low_health", 1);
        }
    }

    private void handleDeathCause(Player victim) {
        EntityDamageEvent last = victim.getLastDamageCause();
        if (last == null) {
            return;
        }
        EntityDamageEvent.DamageCause cause = last.getCause();
        if (cause == EntityDamageEvent.DamageCause.FALL) {
            rewardService.incrementCounter(victim.getUniqueId(), "death_cause:fall", 1);
        } else if (cause == EntityDamageEvent.DamageCause.LAVA) {
            rewardService.incrementCounter(victim.getUniqueId(), "death_cause:lava", 1);
        } else if (cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
            || cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) {
            rewardService.incrementCounter(victim.getUniqueId(), "death_cause:explosion", 1);
        }
        if (last instanceof EntityDamageByEntityEvent byEntity && byEntity.getDamager() instanceof Chicken) {
            rewardService.incrementCounter(victim.getUniqueId(), "death_cause:chicken", 1);
        }
    }

    private void tickPlaytime() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            String state = rewardService.getLivePlaytimeState(player);
            if ("ACTIVE".equalsIgnoreCase(state)) {
                rewardService.incrementCounter(player.getUniqueId(), "consecutive_active", 1);
            } else {
                rewardService.setCounter(player.getUniqueId(), "consecutive_active", 0);
            }
            int maxY = rewardService.getConfig().undergroundMaxY();
            if ("ACTIVE".equalsIgnoreCase(state) && player.getLocation().getY() <= maxY) {
                rewardService.incrementCounter(player.getUniqueId(), "underground_active", 1);
            }
        }
    }

    private Player resolveDamager(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            return player;
        }
        if (event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }
}
