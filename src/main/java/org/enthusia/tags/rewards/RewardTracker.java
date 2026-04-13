package org.enthusia.tags.rewards;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.enthusia.tags.PlaceholderApiHook;
import org.enthusia.tags.TagService;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class RewardTracker implements Listener {
    private final RewardService rewardService;
    private final TagService tagService;
    private final PlaceholderApiHook placeholderApiHook = new PlaceholderApiHook();
    private final Map<UUID, Map<UUID, Long>> firstHitTimes = new HashMap<>();
    private int taskId = -1;

    public RewardTracker(RewardService rewardService, TagService tagService) {
        this.rewardService = rewardService;
        this.tagService = tagService;
    }

    public void start(JavaPlugin plugin) {
        if (taskId != -1) {
            return;
        }
        taskId = Bukkit.getScheduler().runTaskTimer(plugin, this::tickPlaytime, 20L, 1200L).getTaskId();
    }

    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
        firstHitTimes.clear();
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        if (!(event.getDamager() instanceof Player damager)) {
            return;
        }
        firstHitTimes.computeIfAbsent(damager.getUniqueId(), ignored -> new HashMap<>())
            .putIfAbsent(victim.getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Material type = event.getBlock().getType();
        if (type.name().endsWith("_LOG")) {
            incrementCounter(event.getPlayer().getUniqueId(), "logs_mined", 1);
        }
        if (type == Material.DIRT || type == Material.COARSE_DIRT || type == Material.ROOTED_DIRT) {
            incrementCounter(event.getPlayer().getUniqueId(), "dirt_mined", 1);
        }
        if (type == Material.STONE || type == Material.DEEPSLATE || type == Material.ANDESITE
            || type == Material.DIORITE || type == Material.GRANITE) {
            incrementCounter(event.getPlayer().getUniqueId(), "stone_mined", 1);
        }
        if (type == Material.IRON_ORE || type == Material.DEEPSLATE_IRON_ORE) {
            incrementCounter(event.getPlayer().getUniqueId(), "iron_ore_mined", 1);
        }
        if (type == Material.NETHERRACK) {
            incrementCounter(event.getPlayer().getUniqueId(), "netherrack_mined", 1);
        }
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (event.getItem().getItemStack().getType() == Material.DIAMOND) {
            incrementCounter(player.getUniqueId(), "diamonds_obtained", event.getItem().getItemStack().getAmount());
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        setCounter(victim.getUniqueId(), "kill_streak", 0);

        handleDeathCause(victim);

        if (killer == null) {
            setCounter(victim.getUniqueId(), "death_streak_same", 0);
            setState(victim.getUniqueId(), "last_killer", "");
            return;
        }

        long newKillStreak = incrementCounter(killer.getUniqueId(), "kill_streak", 1);

        updateDeathStreakSame(victim, killer);
        updateQuickKill(killer, victim);
        updateFullArmorKill(killer, victim);
        updateLowHealthKill(killer);

        firstHitTimes.computeIfAbsent(killer.getUniqueId(), ignored -> new HashMap<>())
            .remove(victim.getUniqueId());
    }

    private void updateDeathStreakSame(Player victim, Player killer) {
        String lastKiller = getState(victim.getUniqueId(), "last_killer");
        if (lastKiller != null && lastKiller.equalsIgnoreCase(killer.getUniqueId().toString())) {
            incrementCounter(victim.getUniqueId(), "death_streak_same", 1);
        } else {
            setCounter(victim.getUniqueId(), "death_streak_same", 1);
            setState(victim.getUniqueId(), "last_killer", killer.getUniqueId().toString());
        }
    }

    private void updateQuickKill(Player killer, Player victim) {
        Map<UUID, Long> victimMap = firstHitTimes.get(killer.getUniqueId());
        if (victimMap == null) {
            return;
        }
        Long firstHit = victimMap.get(victim.getUniqueId());
        if (firstHit == null) {
            return;
        }
        if (System.currentTimeMillis() - firstHit <= 10_000) {
            incrementCounter(killer.getUniqueId(), "quick_kill", 1);
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
        incrementCounter(killer.getUniqueId(), "kill_full_armor", 1);
    }

    private void updateLowHealthKill(Player killer) {
        if (killer.getHealth() <= 6.0) {
            incrementCounter(killer.getUniqueId(), "kill_low_health", 1);
        }
    }

    private void handleDeathCause(Player victim) {
        EntityDamageEvent last = victim.getLastDamageCause();
        if (last == null) {
            return;
        }
        EntityDamageEvent.DamageCause cause = last.getCause();
        if (cause == EntityDamageEvent.DamageCause.FALL) {
            incrementCounter(victim.getUniqueId(), "death_cause:fall", 1);
        } else if (cause == EntityDamageEvent.DamageCause.LAVA) {
            incrementCounter(victim.getUniqueId(), "death_cause:lava", 1);
        } else if (cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
            || cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) {
            incrementCounter(victim.getUniqueId(), "death_cause:explosion", 1);
        }
        if (last instanceof EntityDamageByEntityEvent byEntity
            && byEntity.getDamager() instanceof Chicken) {
            incrementCounter(victim.getUniqueId(), "death_cause:chicken", 1);
        }
    }

    private void tickPlaytime() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            String state = resolvePlaceholder(player, rewardService.getConfig().playtimeStatePlaceholder());
            if ("ACTIVE".equalsIgnoreCase(state)) {
                incrementCounter(player.getUniqueId(), "consecutive_active", 1);
            } else {
                setCounter(player.getUniqueId(), "consecutive_active", 0);
            }
            int maxY = rewardService.getConfig().undergroundMaxY();
            if (player.getLocation().getY() <= maxY && "ACTIVE".equalsIgnoreCase(state)) {
                incrementCounter(player.getUniqueId(), "underground_active", 1);
            }
        }
    }

    private String resolvePlaceholder(Player player, String placeholder) {
        if (placeholder == null || placeholder.isBlank()) {
            return "";
        }
        String output = placeholderApiHook.apply(player, placeholder);
        return output == null ? "" : output.trim();
    }

    private long incrementCounter(UUID playerId, String key, long delta) {
        try {
            return rewardService.getStorage().incrementCounter(playerId, key, delta);
        } catch (SQLException ex) {
            return 0L;
        }
    }

    private void setCounter(UUID playerId, String key, long value) {
        try {
            rewardService.getStorage().setCounter(playerId, key, value);
        } catch (SQLException ignored) {
        }
    }

    private String getState(UUID playerId, String key) {
        try {
            return rewardService.getStorage().getState(playerId, key);
        } catch (SQLException ex) {
            return null;
        }
    }

    private void setState(UUID playerId, String key, String value) {
        try {
            rewardService.getStorage().setState(playerId, key, value);
        } catch (SQLException ignored) {
        }
    }
}
