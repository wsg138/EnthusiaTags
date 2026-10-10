package org.enthusia.tags.rewards;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public record RewardsConfig(String playtimeActivePlaceholder,
                            String playtimeAfkPlaceholder,
                            String playtimeTotalPlaceholder,
                            String playtimeStatePlaceholder,
                            int undergroundMaxY,
                            boolean allowPlaceholderPlaytimeFallback,
                            String baltopPluginName,
                            Map<String, RewardCategory> categories) {
    public static RewardsConfig from(FileConfiguration config) {
        Map<String, RewardCategory> categories = new LinkedHashMap<>();
        var section = config.getConfigurationSection("categories");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                RewardCategory category = loadCategory(section, key);
                categories.put(category.id(), category);
            }
        }
        if (categories.isEmpty()) {
            categories.put("playtime", new RewardCategory("playtime", "&bPlaytime", Material.CLOCK));
            categories.put("mining", new RewardCategory("mining", "&aMining", Material.IRON_PICKAXE));
            categories.put("combat", new RewardCategory("combat", "&cCombat", Material.IRON_SWORD));
            categories.put("deaths", new RewardCategory("deaths", "&7Deaths", Material.SKELETON_SKULL));
            categories.put("economy", new RewardCategory("economy", "&6Economy", Material.GOLD_INGOT));
            categories.put("misc", new RewardCategory("misc", "&dMisc", Material.NAME_TAG));
        }
        validateParents(categories);
        return new RewardsConfig(
            config.getString("placeholders.playtime-active-minutes", "%playtime_active%"),
            config.getString("placeholders.playtime-afk-minutes", "%playtime_afk%"),
            config.getString("placeholders.playtime-total-minutes", "%playtime_total%"),
            config.getString("placeholders.playtime-state", "%playtime_state%"),
            config.getInt("underground-max-y", 56),
            config.getBoolean("integrations.playtime.allow-placeholder-fallback", false),
            config.getString("integrations.baltop.plugin-name", "EnthusiaCurrency"),
            categories
        );
    }

    private static void validateParents(Map<String, RewardCategory> categories) {
        var visited = new java.util.HashSet<String>();
        for (RewardCategory category : categories.values()) {
            visited.clear();
            for (RewardCategory node = category; node.parent() != null; node = categories.get(node.parent())) {
                if (!visited.add(node.id()) || !categories.containsKey(node.parent())) {
                    throw new IllegalArgumentException("Invalid reward category parent chain: " + category.id());
                }
            }
        }
    }

    private static RewardCategory loadCategory(org.bukkit.configuration.ConfigurationSection section, String key) {
        String name = section.getString(key + ".name", key);
        Material icon = Material.matchMaterial(section.getString(key + ".icon", "PAPER"));
        String parent = section.getString(key + ".parent");
        if (parent != null) parent = parent.toLowerCase(Locale.ROOT);
        return new RewardCategory(key.toLowerCase(Locale.ROOT), name, icon, parent,
            section.getStringList(key + ".tags").stream().map(id -> id.toLowerCase(Locale.ROOT)).toList());
    }
}
