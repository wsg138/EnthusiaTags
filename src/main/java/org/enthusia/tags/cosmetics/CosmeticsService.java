package org.enthusia.tags.cosmetics;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.enthusia.tags.Messages;
import org.enthusia.tags.PerformanceMonitor;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class CosmeticsService {
    private final JavaPlugin plugin;
    private final Messages messages;
    private final PerformanceMonitor performanceMonitor;
    private final Map<String, CosmeticsCategory> categories = new LinkedHashMap<>();
    private final Map<String, CosmeticDefinition> cosmetics = new LinkedHashMap<>();
    private final Map<UUID, Map<String, String>> selections = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Void>> pendingLoads = new ConcurrentHashMap<>();
    private final Map<UUID, CosmeticDefinition> projectiles = new ConcurrentHashMap<>();
    private final Map<UUID, Long> projectileBirthTicks = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> activeTrailPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Double> spiralAngles = new ConcurrentHashMap<>();
    private final Map<UUID, org.bukkit.Location> lastTrailLocations = new ConcurrentHashMap<>();

    private CosmeticsStorage storage;
    private BukkitTask trailTask;
    private int maxTrackedProjectiles = 300;
    private int maxProjectileParticlesPerTick = 600;
    private long projectileTrailMaxAgeTicks = 200L;

    public CosmeticsService(JavaPlugin plugin, Messages messages, PerformanceMonitor performanceMonitor) {
        this.plugin = plugin;
        this.messages = messages;
        this.performanceMonitor = performanceMonitor;
    }

    public void enable() {
        ensureDefaults();
        initStorage();
        reload();
        startTrailTask();
    }

    public void disable() {
        stopTrailTask();
        pendingLoads.clear();
        selections.clear();
        spiralAngles.clear();
        projectiles.clear();
        projectileBirthTicks.clear();
        activeTrailPlayers.clear();
        lastTrailLocations.clear();
        if (storage != null) {
            storage.close();
        }
    }

    public void reload() {
        ensureDefaults();
        categories.clear();
        cosmetics.clear();
        loadConfig();
        reloadPerformanceConfig();
        startTrailTask();
        for (Player player : Bukkit.getOnlinePlayers()) {
            preloadPlayer(player.getUniqueId());
            refreshActiveTrail(player);
        }
    }

    public void preloadPlayer(UUID playerId) {
        if (selections.containsKey(playerId)) {
            return;
        }
        pendingLoads.computeIfAbsent(playerId, ignored ->
            storage.loadSelectionsAsync(playerId).handle((data, throwable) -> {
                if (throwable != null) {
                    plugin.getLogger().warning("Failed to load cosmetics for " + playerId + ": " + throwable.getMessage());
                } else {
                    selections.put(playerId, new ConcurrentHashMap<>(data));
                    Player player = Bukkit.getPlayer(playerId);
                    if (player != null) {
                        Bukkit.getScheduler().runTask(plugin, () -> refreshActiveTrail(player));
                    }
                }
                return (Void) null;
            }).whenComplete((ignoredResult, ignoredThrowable) -> pendingLoads.remove(playerId)));
    }

    public void preloadPlayerBlocking(UUID playerId) {
        if (selections.containsKey(playerId)) {
            return;
        }
        try {
            selections.put(playerId, new ConcurrentHashMap<>(storage.loadSelectionsNow(playerId)));
        } catch (SQLException ex) {
            plugin.getLogger().warning("Failed to preload cosmetics for " + playerId + ": " + ex.getMessage());
        }
    }

    public void loadPlayer(Player player) {
        preloadPlayer(player.getUniqueId());
    }

    public void unloadPlayer(Player player) {
        selections.remove(player.getUniqueId());
        pendingLoads.remove(player.getUniqueId());
        spiralAngles.remove(player.getUniqueId());
        lastTrailLocations.remove(player.getUniqueId());
        activeTrailPlayers.remove(player.getUniqueId());
    }

    public Map<String, CosmeticsCategory> getCategories() {
        return categories;
    }

    public Map<String, CosmeticDefinition> getCosmetics() {
        return cosmetics;
    }

    public String getSelection(UUID playerId, String category) {
        Map<String, String> map = selections.get(playerId);
        if (map == null) {
            return null;
        }
        return map.get(category);
    }

    public String getActiveSelection(UUID playerId, String category, Player player) {
        String selection = getSelection(playerId, category);
        if (selection == null) {
            return null;
        }
        CosmeticDefinition cosmetic = cosmetics.get(selection.toLowerCase(Locale.ROOT));
        if (cosmetic == null || !player.hasPermission(cosmetic.getPermission())) {
            return null;
        }
        return selection;
    }

    public boolean toggleCosmetic(Player player, CosmeticDefinition cosmetic) {
        String category = cosmetic.getCategory();
        String current = getSelection(player.getUniqueId(), category);
        String next = cosmetic.getId();
        if (current != null && current.equalsIgnoreCase(next)) {
            return setSelection(player, category, null);
        }
        if (!player.hasPermission(cosmetic.getPermission())) {
            return false;
        }
        return setSelection(player, category, next);
    }

    public boolean setSelection(Player player, String category, String cosmeticId) {
        selections.computeIfAbsent(player.getUniqueId(), ignored -> new ConcurrentHashMap<>());
        if (cosmeticId == null) {
            selections.get(player.getUniqueId()).remove(category);
        } else {
            selections.get(player.getUniqueId()).put(category, cosmeticId.toLowerCase(Locale.ROOT));
        }
        refreshActiveTrail(player);
        storage.setSelectionAsync(player.getUniqueId(), category, cosmeticId == null ? null : cosmeticId.toLowerCase(Locale.ROOT))
            .exceptionally(throwable -> {
                plugin.getLogger().warning("Failed to store cosmetic selection for " + player.getUniqueId() + ": "
                    + throwable.getMessage());
                return null;
            });
        return true;
    }

    public String formatMessage(String key) {
        return messages.get(key);
    }

    public void applyKillEffect(Player killer, Player victim) {
        CosmeticDefinition cosmetic = getActiveCosmetic(killer, "kill");
        if (cosmetic == null) {
            return;
        }
        switch (cosmetic.getType()) {
            case KILL_PARTICLE -> spawnParticle(victim, cosmetic, cosmetic.getCount());
            case KILL_ITEM_RAIN -> spawnItemRain(victim, cosmetic);
            default -> {
            }
        }
    }

    public void applyDeathEffect(Player victim) {
        CosmeticDefinition cosmetic = getActiveCosmetic(victim, "death");
        if (cosmetic == null) {
            return;
        }
        switch (cosmetic.getType()) {
            case DEATH_PARTICLE -> spawnParticle(victim, cosmetic, cosmetic.getCount());
            case DEATH_TNT -> spawnFakeTnt(victim);
            default -> {
            }
        }
    }

    public String getKillMessage(Player killer, Player victim) {
        CosmeticDefinition cosmetic = getActiveCosmetic(killer, "kill_message");
        if (cosmetic == null || cosmetic.getType() != CosmeticType.KILL_MESSAGE || cosmetic.getMessage() == null) {
            return null;
        }
        return cosmetic.getMessage()
            .replace("{killer}", killer.getName())
            .replace("{victim}", victim.getName());
    }

    public String getJoinMessage(Player player) {
        CosmeticDefinition cosmetic = getActiveCosmetic(player, "join");
        if (cosmetic == null || cosmetic.getMessage() == null) {
            return null;
        }
        return cosmetic.getMessage().replace("{player}", player.getName());
    }

    public String getQuitMessage(Player player) {
        CosmeticDefinition cosmetic = getActiveCosmetic(player, "quit");
        if (cosmetic == null || cosmetic.getMessage() == null) {
            return null;
        }
        return cosmetic.getMessage().replace("{player}", player.getName());
    }

    public void registerProjectile(Projectile projectile, CosmeticDefinition cosmetic) {
        if (cosmetic == null || cosmetic.getParticle() == null) {
            return;
        }
        if (projectiles.size() >= maxTrackedProjectiles) {
            performanceMonitor.increment("cosmetics.projectile.skipped-cap");
            return;
        }
        projectiles.put(projectile.getUniqueId(), cosmetic);
        projectileBirthTicks.put(projectile.getUniqueId(), (long) Bukkit.getCurrentTick());
    }

    public void unregisterProjectile(Entity entity) {
        projectiles.remove(entity.getUniqueId());
        projectileBirthTicks.remove(entity.getUniqueId());
    }

    private void refreshActiveTrail(Player player) {
        String selected = getActiveSelection(player.getUniqueId(), "trail", player);
        if (selected == null) {
            activeTrailPlayers.remove(player.getUniqueId());
            lastTrailLocations.remove(player.getUniqueId());
            return;
        }
        CosmeticDefinition cosmetic = cosmetics.get(selected.toLowerCase(Locale.ROOT));
        if (cosmetic != null && (cosmetic.getType() == CosmeticType.TRAIL_PARTICLE || cosmetic.getType() == CosmeticType.TRAIL_SPIRAL)) {
            activeTrailPlayers.add(player.getUniqueId());
        } else {
            activeTrailPlayers.remove(player.getUniqueId());
        }
    }

    private CosmeticDefinition getActiveCosmetic(Player player, String category) {
        String cosmeticId = getSelection(player.getUniqueId(), category);
        if (cosmeticId == null) {
            return null;
        }
        CosmeticDefinition cosmetic = cosmetics.get(cosmeticId.toLowerCase(Locale.ROOT));
        if (cosmetic == null || !player.hasPermission(cosmetic.getPermission())) {
            return null;
        }
        return cosmetic;
    }

    private void startTrailTask() {
        stopTrailTask();
        long interval = Math.max(2L, plugin.getConfig().getLong("performance.cosmetics-trail-ticks", 10L));
        trailTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (activeTrailPlayers.isEmpty() && projectiles.isEmpty()) {
                return;
            }
            performanceMonitor.add("cosmetics.trail.active-players", activeTrailPlayers.size());
            for (UUID playerId : java.util.List.copyOf(activeTrailPlayers)) {
                Player player = Bukkit.getPlayer(playerId);
                if (player == null || !player.isOnline()) {
                    activeTrailPlayers.remove(playerId);
                    continue;
                }
                CosmeticDefinition cosmetic = getActiveCosmetic(player, "trail");
                if (cosmetic == null) {
                    activeTrailPlayers.remove(player.getUniqueId());
                    lastTrailLocations.remove(player.getUniqueId());
                    continue;
                }
                if (!hasMovedEnoughForTrail(player)) {
                    continue;
                }
                if (cosmetic.getType() == CosmeticType.TRAIL_PARTICLE) {
                    spawnParticle(player, cosmetic, cosmetic.getCount());
                } else if (cosmetic.getType() == CosmeticType.TRAIL_SPIRAL) {
                    spawnSpiral(player, cosmetic);
                }
            }
            if (!projectiles.isEmpty()) {
                int emitted = 0;
                for (Map.Entry<UUID, CosmeticDefinition> entry : projectiles.entrySet()) {
                    performanceMonitor.increment("cosmetics.projectile.scanned");
                    Long born = projectileBirthTicks.get(entry.getKey());
                    if (born != null && Bukkit.getCurrentTick() - born > projectileTrailMaxAgeTicks) {
                        projectiles.remove(entry.getKey());
                        projectileBirthTicks.remove(entry.getKey());
                        continue;
                    }
                    Entity entity = Bukkit.getEntity(entry.getKey());
                    if (!(entity instanceof Projectile projectile) || projectile.isDead() || !projectile.isValid()) {
                        projectiles.remove(entry.getKey());
                        projectileBirthTicks.remove(entry.getKey());
                        continue;
                    }
                    CosmeticDefinition cosmetic = entry.getValue();
                    if (cosmetic == null || cosmetic.getParticle() == null) {
                        projectiles.remove(entry.getKey());
                        projectileBirthTicks.remove(entry.getKey());
                        continue;
                    }
                    int count = Math.max(1, cosmetic.getCount());
                    if (emitted + count > maxProjectileParticlesPerTick) {
                        performanceMonitor.increment("cosmetics.projectile.skipped-particle-cap");
                        break;
                    }
                    emitted += count;
                    projectile.getWorld().spawnParticle(cosmetic.getParticle(),
                        projectile.getLocation(),
                        count,
                        cosmetic.getSpread(), cosmetic.getSpread(), cosmetic.getSpread(),
                        cosmetic.getSpeed());
                }
            }
        }, interval, interval);
    }

    private void stopTrailTask() {
        if (trailTask != null) {
            trailTask.cancel();
            trailTask = null;
        }
    }

    private void spawnParticle(Player player, CosmeticDefinition cosmetic, int count) {
        if (cosmetic.getParticle() == null) {
            return;
        }
        player.getWorld().spawnParticle(cosmetic.getParticle(),
            player.getLocation().add(0, 1.0, 0),
            Math.max(1, count),
            cosmetic.getSpread(), cosmetic.getSpread(), cosmetic.getSpread(),
            cosmetic.getSpeed());
    }

    private void spawnItemRain(Player victim, CosmeticDefinition cosmetic) {
        Material item = cosmetic.getItem() == null ? Material.GOLD_NUGGET : cosmetic.getItem();
        victim.getWorld().spawnParticle(Particle.ITEM,
            victim.getLocation().add(0, 1.2, 0),
            Math.max(1, cosmetic.getCount()),
            0.4, 0.4, 0.4,
            0.05,
            new org.bukkit.inventory.ItemStack(item));
    }

    private void spawnFakeTnt(Player victim) {
        victim.getWorld().spawnParticle(Particle.EXPLOSION, victim.getLocation().add(0, 0.5, 0), 1);
        victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.2f);
    }

    private void spawnSpiral(Player player, CosmeticDefinition cosmetic) {
        if (cosmetic.getParticle() == null) {
            return;
        }
        double angle = spiralAngles.getOrDefault(player.getUniqueId(), 0.0D);
        double radius = cosmetic.getRadius() <= 0.0D ? 0.6D : cosmetic.getRadius();
        for (int i = 0; i < 2; i++) {
            double x = Math.cos(angle + (i * Math.PI)) * radius;
            double z = Math.sin(angle + (i * Math.PI)) * radius;
            player.getWorld().spawnParticle(cosmetic.getParticle(),
                player.getLocation().add(x, 1.0, z),
                1, 0.0, 0.0, 0.0, 0.0);
        }
        spiralAngles.put(player.getUniqueId(), angle + 0.4D);
    }

    private boolean hasMovedEnoughForTrail(Player player) {
        org.bukkit.Location current = player.getLocation();
        org.bukkit.Location previous = lastTrailLocations.put(player.getUniqueId(), current.clone());
        if (previous == null || !previous.getWorld().equals(current.getWorld())) {
            return false;
        }
        return previous.distanceSquared(current) >= 0.04D;
    }

    private void initStorage() {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            plugin.getLogger().warning("Failed to create data folder for cosmetics.");
        }
        storage = new CosmeticsStorage(new File(dataFolder, "cosmetics.db"), performanceMonitor);
        try {
            storage.init();
        } catch (SQLException ex) {
            plugin.getLogger().severe("Failed to initialize cosmetics database: " + ex.getMessage());
        }
    }

    private void reloadPerformanceConfig() {
        maxTrackedProjectiles = Math.max(1, plugin.getConfig().getInt("performance.max-tracked-projectiles", 300));
        maxProjectileParticlesPerTick = Math.max(1, plugin.getConfig().getInt("performance.max-projectile-particles-per-tick", 600));
        projectileTrailMaxAgeTicks = Math.max(20L, plugin.getConfig().getLong("performance.projectile-trail-max-age-ticks", 200L));
    }

    private void ensureDefaults() {
        File file = new File(plugin.getDataFolder(), "cosmetics.yml");
        if (!file.exists()) {
            plugin.saveResource("cosmetics.yml", false);
        }
        var configFile = YamlConfiguration.loadConfiguration(file);
        try (var stream = plugin.getResource("cosmetics.yml")) {
            if (stream == null) {
                return;
            }
            var defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
            configFile.setDefaults(defaults);
            configFile.options().copyDefaults(true);
            configFile.save(file);
        } catch (Exception ignored) {
        }
    }

    private void loadConfig() {
        File file = new File(plugin.getDataFolder(), "cosmetics.yml");
        var config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection categorySection = config.getConfigurationSection("categories");
        if (categorySection != null) {
            for (String key : categorySection.getKeys(false)) {
                String name = categorySection.getString(key + ".name", key);
                Material icon = Material.matchMaterial(categorySection.getString(key + ".icon", "PAPER"));
                String normalizedKey = key.toLowerCase(Locale.ROOT);
                categories.put(normalizedKey, new CosmeticsCategory(normalizedKey, name, icon));
            }
        }

        ConfigurationSection cosmeticSection = config.getConfigurationSection("cosmetics");
        if (cosmeticSection == null) {
            return;
        }
        for (String id : cosmeticSection.getKeys(false)) {
            ConfigurationSection entry = cosmeticSection.getConfigurationSection(id);
            if (entry == null) {
                continue;
            }
            String name = entry.getString("name", id);
            String category = entry.getString("category", "misc").toLowerCase(Locale.ROOT);
            CosmeticType type = parseCosmeticType(entry.getString("type", "TRAIL_PARTICLE"), "cosmetics." + id + ".type");
            if (type == null) {
                continue;
            }
            Material icon = parseMaterial(entry.getString("icon", "PAPER"), "cosmetics." + id + ".icon");
            Particle particle = parseParticle(entry.getString("particle", ""), "cosmetics." + id + ".particle");
            Material item = Material.matchMaterial(entry.getString("item", ""));
            Sound sound = parseSound(entry.getString("sound", ""), "cosmetics." + id + ".sound");
            String message = entry.getString("message", null);
            String permission = entry.getString("permission", "enthusia.cosmetics." + id.toLowerCase(Locale.ROOT));
            int count = entry.getInt("count", defaultCount(type));
            double spread = entry.getDouble("spread", defaultSpread(type));
            double speed = entry.getDouble("speed", defaultSpeed(type));
            double radius = entry.getDouble("radius", 0.6D);
            String normalizedId = id.toLowerCase(Locale.ROOT);
            cosmetics.put(normalizedId, new CosmeticDefinition(
                normalizedId, name, category, type, icon == null ? Material.PAPER : icon, particle, item, sound,
                message, permission, count, spread, speed, radius));
        }
    }

    private CosmeticType parseCosmeticType(String value, String path) {
        try {
            return CosmeticType.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (Exception ex) {
            plugin.getLogger().warning("Invalid cosmetic type at " + path + ": " + value);
            performanceMonitor.increment("config.validation.bad-cosmetic-type");
            return null;
        }
    }

    private Material parseMaterial(String value, String path) {
        Material material = Material.matchMaterial(value == null ? "" : value);
        if (material == null) {
            plugin.getLogger().warning("Invalid material at " + path + ": " + value);
            performanceMonitor.increment("config.validation.bad-material");
            return Material.PAPER;
        }
        return material;
    }

    private Particle parseParticle(String value, String path) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Particle.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Invalid particle at " + path + ": " + value);
            performanceMonitor.increment("config.validation.bad-particle");
            return null;
        }
    }

    private Sound parseSound(String value, String path) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Sound.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Invalid sound at " + path + ": " + value);
            performanceMonitor.increment("config.validation.bad-sound");
            return null;
        }
    }

    private int defaultCount(CosmeticType type) {
        return switch (type) {
            case KILL_PARTICLE, KILL_ITEM_RAIN -> 28;
            case DEATH_PARTICLE, DEATH_TNT -> 32;
            case TRAIL_PARTICLE, TRAIL_SPIRAL, PROJECTILE_TRAIL -> 6;
            default -> 8;
        };
    }

    private double defaultSpread(CosmeticType type) {
        return switch (type) {
            case TRAIL_PARTICLE, PROJECTILE_TRAIL -> 0.25D;
            default -> 0.35D;
        };
    }

    private double defaultSpeed(CosmeticType type) {
        return switch (type) {
            case KILL_PARTICLE, DEATH_PARTICLE -> 0.05D;
            case PROJECTILE_TRAIL -> 0.02D;
            default -> 0.01D;
        };
    }
}
