package org.enthusia.tags.rewards;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import org.bukkit.Material;

final class NaturalBlockPolicy {
    private static final Set<Material> TRACKED = Set.copyOf(EnumSet.of(
        Material.COAL_ORE,
        Material.DEEPSLATE_COAL_ORE,
        Material.IRON_ORE,
        Material.DEEPSLATE_IRON_ORE,
        Material.COPPER_ORE,
        Material.DEEPSLATE_COPPER_ORE,
        Material.GOLD_ORE,
        Material.DEEPSLATE_GOLD_ORE,
        Material.REDSTONE_ORE,
        Material.DEEPSLATE_REDSTONE_ORE,
        Material.EMERALD_ORE,
        Material.DEEPSLATE_EMERALD_ORE,
        Material.LAPIS_ORE,
        Material.DEEPSLATE_LAPIS_ORE,
        Material.DIAMOND_ORE,
        Material.DEEPSLATE_DIAMOND_ORE,
        Material.NETHER_GOLD_ORE,
        Material.NETHER_QUARTZ_ORE,
        Material.ANCIENT_DEBRIS
    ));

    private NaturalBlockPolicy() {
    }

    static boolean isTracked(Material material) {
        return material != null && TRACKED.contains(material);
    }

    static Set<Material> trackedMaterials() {
        return TRACKED;
    }

    static String counterKey(Material material) {
        return "natural_mined:" + material.name().toLowerCase(Locale.ROOT);
    }

    static String initializedState(Material material) {
        return "natural-mine-initialized:" + material.name().toLowerCase(Locale.ROOT);
    }
}
