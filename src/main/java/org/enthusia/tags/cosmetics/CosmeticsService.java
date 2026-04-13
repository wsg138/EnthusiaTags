package org.enthusia.tags.cosmetics;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;
import org.enthusia.tags.Messages;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CosmeticsService {
    private final JavaPlugin plugin;
    private final Messages messages;
    private final Map<String, CosmeticsCategory> categories = new LinkedHashMap<>();
    private final Map<String, CosmeticDefinition> cosmetics = new LinkedHashMap<>();
    private final Map<UUID, Map<String, String>> selections = new ConcurrentHashMap<>();
    private final Map<UUID, CosmeticDefinition> projectiles = new ConcurrentHashMap<>();
    private final Map<UUID, Double> spiralAngles = new ConcurrentHashMap<>();
    private CosmeticsStorage storage;
    private int trailTaskId = -1;

    public CosmeticsService(JavaPlugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    public void enable() {
        ensureDefaults();
        reload();
        initStorage();
        startTrailTask();
    }

    public void disable() {
        stopTrailTask();
        selections.clear();
        if (storage != null) {
            storage.close();
        }
    }

    public void reload() {
        ensureDefaults();
        categories.clear();
        cosmetics.clear();
        loadConfig();
        reopenStorage();
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

    public void loadPlayer(Player player) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Map<String, String> data = storage.loadSelections(player.getUniqueId());
                selections.put(player.getUniqueId(), data);
            } catch (SQLException ex) {
                plugin.getLogger().warning("Failed to load cosmetics for " + player.getUniqueId() + ": " + ex.getMessage());
            }
        });
    }

    public void unloadPlayer(Player player) {
        selections.remove(player.getUniqueId());
        spiralAngles.remove(player.getUniqueId());
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
        selections.computeIfAbsent(player.getUniqueId(), ignored -> new ConcurrentHashMap<>())
            .put(category, cosmeticId);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                storage.setSelection(player.getUniqueId(), category, cosmeticId);
            } catch (SQLException ex) {
                plugin.getLogger().warning("Failed to store cosmetic selection: " + ex.getMessage());
            }
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
        if (cosmetic == null || cosmetic.getType() != CosmeticType.KILL_MESSAGE) {
            return null;
        }
        if (cosmetic.getMessage() == null) {
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

    private CosmeticDefinition getActiveCosmetic(Player player, String category) {
        String cosmeticId = getSelection(player.getUniqueId(), category);
        if (cosmeticId == null) {
            return null;
        }
        CosmeticDefinition cosmetic = cosmetics.get(cosmeticId.toLowerCase());
        if (cosmetic == null) {
            return null;
        }
        if (!player.hasPermission(cosmetic.getPermission())) {
            return null;
        }
        return cosmetic;
    }

    public void registerProjectile(Projectile projectile, CosmeticDefinition cosmetic) {
        if (cosmetic == null || cosmetic.getParticle() == null) {
            return;
        }
        projectiles.put(projectile.getUniqueId(), cosmetic);
    }

    public void unregisterProjectile(Entity entity) {
        projectiles.remove(entity.getUniqueId());
    }

    private void startTrailTask() {
        if (trailTaskId != -1) {
            return;
        }
        trailTaskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                CosmeticDefinition cosmetic = getActiveCosmetic(player, "trail");
                if (cosmetic == null) {
                    continue;
                }
                if (cosmetic.getType() == CosmeticType.TRAIL_PARTICLE) {
                    spawnParticle(player, cosmetic, cosmetic.getCount());
                } else if (cosmetic.getType() == CosmeticType.TRAIL_SPIRAL) {
                    spawnSpiral(player, cosmetic);
                }
            }
            if (!projectiles.isEmpty()) {
                for (Map.Entry<UUID, CosmeticDefinition> entry : projectiles.entrySet()) {
                    Entity entity = Bukkit.getEntity(entry.getKey());
                    if (!(entity instanceof Projectile projectile) || projectile.isDead()) {
                        projectiles.remove(entry.getKey());
                        continue;
                    }
                    CosmeticDefinition cosmetic = entry.getValue();
                    if (cosmetic == null || cosmetic.getParticle() == null) {
                        projectiles.remove(entry.getKey());
                        continue;
                    }
                    projectile.getWorld().spawnParticle(cosmetic.getParticle(),
                        projectile.getLocation(),
                        Math.max(1, cosmetic.getCount()),
                        cosmetic.getSpread(), cosmetic.getSpread(), cosmetic.getSpread(),
                        cosmetic.getSpeed());
                }
            }
        }, 20L, 10L).getTaskId();
    }

    private void stopTrailTask() {
        if (trailTaskId != -1) {
            Bukkit.getScheduler().cancelTask(trailTaskId);
            trailTaskId = -1;
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
            20,
            0.4, 0.4, 0.4,
            0.05,
            new org.bukkit.inventory.ItemStack(item));
    }

    private void spawnFakeTnt(Player victim) {
        victim.getWorld().spawnParticle(Particle.EXPLOSION,
            victim.getLocation().add(0, 0.5, 0),
            1);
        victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.2f);
    }

    private void spawnSpiral(Player player, CosmeticDefinition cosmetic) {
        if (cosmetic.getParticle() == null) {
            return;
        }
        double angle = spiralAngles.getOrDefault(player.getUniqueId(), 0.0);
        double radius = cosmetic.getRadius() <= 0 ? 0.6 : cosmetic.getRadius();
        for (int i = 0; i < 2; i++) {
            double x = Math.cos(angle + (i * Math.PI)) * radius;
            double z = Math.sin(angle + (i * Math.PI)) * radius;
            player.getWorld().spawnParticle(cosmetic.getParticle(),
                player.getLocation().add(x, 1.0, z),
                1,
                0.0, 0.0, 0.0,
                0.0);
        }
        spiralAngles.put(player.getUniqueId(), angle + 0.4);
    }

    private void initStorage() {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            plugin.getLogger().warning("Failed to create data folder for cosmetics.");
        }
        storage = new CosmeticsStorage(new File(dataFolder, "cosmetics.db"));
        try {
            storage.init();
        } catch (SQLException ex) {
            plugin.getLogger().severe("Failed to initialize cosmetics database: " + ex.getMessage());
        }
    }

    private void reopenStorage() {
        if (storage != null) {
            storage.close();
        }
        initStorage();
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
            var defaults = YamlConfiguration.loadConfiguration(
                new InputStreamReader(stream, StandardCharsets.UTF_8));
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
                categories.put(key.toLowerCase(), new CosmeticsCategory(key.toLowerCase(), name, icon));
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
            String category = entry.getString("category", "misc").toLowerCase();
            CosmeticType type = CosmeticType.valueOf(entry.getString("type", "TRAIL_PARTICLE"));
            Material icon = Material.matchMaterial(entry.getString("icon", "PAPER"));
            Particle particle = parseParticle(entry.getString("particle", ""));
            Material item = Material.matchMaterial(entry.getString("item", ""));
            Sound sound = parseSound(entry.getString("sound", ""));
            String message = entry.getString("message", null);
            String permission = entry.getString("permission", "enthusia.cosmetics." + id.toLowerCase());
            int count = entry.getInt("count", defaultCount(type));
            double spread = entry.getDouble("spread", defaultSpread(type));
            double speed = entry.getDouble("speed", defaultSpeed(type));
            double radius = entry.getDouble("radius", 0.6);
            cosmetics.put(id.toLowerCase(), new CosmeticDefinition(
                id.toLowerCase(), name, category, type, icon, particle, item, sound, message, permission,
                count, spread, speed, radius));
        }
    }

    private Particle parseParticle(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Particle.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private Sound parseSound(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Sound.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private int defaultCount(CosmeticType type) {
        return switch (type) {
            case KILL_PARTICLE, KILL_ITEM_RAIN -> 28;
            case DEATH_PARTICLE, DEATH_TNT -> 32;
            case TRAIL_PARTICLE, TRAIL_SPIRAL -> 6;
            case PROJECTILE_TRAIL -> 6;
            default -> 8;
        };
    }

    private double defaultSpread(CosmeticType type) {
        return switch (type) {
            case TRAIL_PARTICLE, PROJECTILE_TRAIL -> 0.25;
            default -> 0.35;
        };
    }

    private double defaultSpeed(CosmeticType type) {
        return switch (type) {
            case KILL_PARTICLE, DEATH_PARTICLE -> 0.05;
            case PROJECTILE_TRAIL -> 0.02;
            default -> 0.01;
        };
    }
}
