package org.enthusia.tags.rewards;

import org.bukkit.configuration.file.FileConfiguration;

import org.bukkit.Material;

import java.util.LinkedHashMap;
import java.util.Map;

public record RewardsConfig(String playtimeActivePlaceholder,
                            String playtimeAfkPlaceholder,
                            String playtimeTotalPlaceholder,
                            String playtimeStatePlaceholder,
                            int undergroundMaxY,
                            Map<String, RewardCategory> categories) {
    public static RewardsConfig from(FileConfiguration config) {
        Map<String, RewardCategory> categories = new LinkedHashMap<>();
        var section = config.getConfigurationSection("categories");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                String name = section.getString(key + ".name", key);
                Material icon = Material.matchMaterial(section.getString(key + ".icon", "PAPER"));
                categories.put(key.toLowerCase(), new RewardCategory(key.toLowerCase(), name, icon));
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
        return new RewardsConfig(
            config.getString("placeholders.playtime-active-minutes", "%playtime_active%"),
            config.getString("placeholders.playtime-afk-minutes", "%playtime_afk%"),
            config.getString("placeholders.playtime-total-minutes", "%playtime_total%"),
            config.getString("placeholders.playtime-state", "%playtime_state%"),
            config.getInt("underground-max-y", 56),
            categories
        );
    }
}
