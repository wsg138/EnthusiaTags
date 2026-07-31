package org.enthusia.tags.rewards;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerStatisticIncrementEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class RewardTracker implements Listener {
    private static final String KILL_CONFIG = "rewards.anti-farm.kills.";

    private final JavaPlugin plugin;
    private final RewardService rewardService;
    private final Map<UUID, Map<UUID, Long>> firstHitTimes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> activeSampleTimes = new ConcurrentHashMap<>();
    private BukkitTask playtimeTask;

    public RewardTracker(JavaPlugin plugin, RewardService rewardService) {
        this.plugin = plugin;
        this.rewardService = rewardService;
    }

    public void start(JavaPlugin schedulingPlugin) {
        if (playtimeTask != null) {
            return;
        }
        playtimeTask = Bukkit.getScheduler().runTaskTimer(schedulingPlugin, this::tickPlaytime, 20L, 1200L);
    }

    public void stop() {
        if (playtimeTask != null) {
            playtimeTask.cancel();
            playtimeTask = null;
        }
        firstHitTimes.clear();
        activeSampleTimes.clear();
    }

    @EventHandler
    public void onAsyncPreLogin(AsyncPlayerPreLoginEvent event) {
        rewardService.preloadPlayerBlocking(event.getUniqueId());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        rewardService.loadPlayer(player);
        initializeProtectedCountersWhenLoaded(player, 0);
        if (player.hasPermission("enthusia.tags.admin")) {
            for (String warning : rewardService.getStaffWarnings()) {
                player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(warning));
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        firstHitTimes.remove(playerId);
        firstHitTimes.values().forEach(map -> map.remove(playerId));
        activeSampleTimes.remove(playerId);
        rewardService.setCounter(playerId, "consecutive_active_ms", 0L);
        rewardService.setCounter(playerId, "consecutive_active", 0L);
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
        long now = System.currentTimeMillis();
        firstHitTimes.computeIfAbsent(damager.getUniqueId(), ignored -> new ConcurrentHashMap<>())
            .compute(victim.getUniqueId(), (ignored, first) -> first == null || now - first > 10_000L ? now : first);
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
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStatisticIncrement(PlayerStatisticIncrementEvent event) {
        rewardService.invalidateProgress(event.getPlayer().getUniqueId());
        rewardService.queueUnlockCheck(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity().getShooter() instanceof Player player) || event.getHitEntity() == null) {
            return;
        }
        rewardService.incrementCounter(player.getUniqueId(), "projectile_hits", 1);
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

        boolean credited = creditKill(killer, victim);
        if (!credited) {
            cleanupHit(killer, victim);
            return;
        }

        rewardService.incrementCounter(victim.getUniqueId(), "pvp_deaths", 1);
        rewardService.incrementCounter(killer.getUniqueId(), KillFarmLimiter.TRUSTED_KILL_COUNTER, 1);
        rewardService.incrementCounter(killer.getUniqueId(), "kill_streak", 1);
        updateDeathStreakSame(victim, killer);
        updateQuickKill(killer, victim);
        updateFullArmorKill(killer, victim);
        updateLowHealthKill(killer);
        cleanupHit(killer, victim);
    }

    private boolean creditKill(Player killer, Player victim) {
        if (!plugin.getConfig().getBoolean(KILL_CONFIG + "enabled", true)) {
            return true;
        }
        long cooldownMinutes = clamp(plugin.getConfig().getLong(
            KILL_CONFIG + "same-victim-cooldown-minutes", 30L), 0L, 1440L);
        long windowHours = clamp(plugin.getConfig().getLong(
            KILL_CONFIG + "victim-window-hours", 24L), 1L, 168L);
        int maximum = (int) clamp(plugin.getConfig().getLong(
            KILL_CONFIG + "max-counted-per-victim-window", 3L), 1L, 100L);
        String stateKey = KillFarmLimiter.PAIR_STATE_PREFIX + victim.getUniqueId();
        KillFarmLimiter.Decision decision = KillFarmLimiter.evaluate(
            rewardService.getState(killer.getUniqueId(), stateKey),
            System.currentTimeMillis(), cooldownMinutes * 60_000L,
            windowHours * 3_600_000L, maximum);
        if (decision.credited()) {
            rewardService.setState(killer.getUniqueId(), stateKey, decision.nextState());
        }
        return decision.credited();
    }

    private void initializeProtectedCountersWhenLoaded(Player player, int attempt) {
        if (!player.isOnline()) {
            return;
        }
        if (rewardService.isPlayerStateLoaded(player.getUniqueId())) {
            initializeProtectedCounters(player);
            return;
        }
        if (attempt >= 20) {
            plugin.getLogger().warning("Could not initialize protected reward counters for "
                + player.getUniqueId() + " because reward state did not load within 20 seconds.");
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin,
            () -> initializeProtectedCountersWhenLoaded(player, attempt + 1), 20L);
    }

    private void initializeProtectedCounters(Player player) {
        UUID playerId = player.getUniqueId();
        if (!"1".equals(rewardService.getState(playerId, KillFarmLimiter.INITIALIZED_STATE))) {
            rewardService.setCounter(playerId, KillFarmLimiter.TRUSTED_KILL_COUNTER,
                player.getStatistic(Statistic.PLAYER_KILLS));
            rewardService.setState(playerId, KillFarmLimiter.INITIALIZED_STATE, "1");
        }
        for (Material material : NaturalBlockPolicy.trackedMaterials()) {
            String marker = NaturalBlockPolicy.initializedState(material);
            if ("1".equals(rewardService.getState(playerId, marker))) {
                continue;
            }
            rewardService.setCounter(playerId, NaturalBlockPolicy.counterKey(material),
                player.getStatistic(Statistic.MINE_BLOCK, material));
            rewardService.setState(playerId, marker, "1");
        }
    }

    private long clamp(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private void cleanupHit(Player killer, Player victim) {
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
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID playerId = player.getUniqueId();
            long oldMaxPing = rewardService.getCounter(playerId, "max_ping_ms");
            if (player.getPing() > oldMaxPing) {
                rewardService.setCounter(playerId, "max_ping_ms", player.getPing());
            }
            String state = rewardService.getLivePlaytimeState(player);
            if ("ACTIVE".equalsIgnoreCase(state)) {
                Long previous = activeSampleTimes.put(playerId, now);
                long elapsed = previous == null ? 0L : Math.min(90_000L, Math.max(0L, now - previous));
                long streakMs = rewardService.incrementCounter(playerId, "consecutive_active_ms", elapsed);
                long streakMinutes = streakMs / 60_000L;
                rewardService.setCounter(playerId, "consecutive_active", streakMinutes);
                rewardService.setCounter(playerId, "max_consecutive_active",
                    Math.max(streakMinutes, rewardService.getCounter(playerId, "max_consecutive_active")));
                int maxY = rewardService.getConfig().undergroundMaxY();
                if (player.getLocation().getY() <= maxY) {
                    long undergroundMs = rewardService.incrementCounter(playerId, "underground_active_ms", elapsed);
                    rewardService.setCounter(playerId, "underground_active", undergroundMs / 60_000L);
                }
            } else {
                activeSampleTimes.remove(playerId);
                rewardService.setCounter(playerId, "consecutive_active_ms", 0L);
                rewardService.setCounter(playerId, "consecutive_active", 0L);
            }
        }
    }

    private Player resolveDamager(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            return player;
        }
        if (event.getDamager() instanceof Projectile projectile
            && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }
}
