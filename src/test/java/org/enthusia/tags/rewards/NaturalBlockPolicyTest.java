package org.enthusia.tags.rewards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class NaturalBlockPolicyTest {
    @Test
    void tracksSilkTouchableOresAndAncientDebris() {
        assertTrue(NaturalBlockPolicy.isTracked(Material.DIAMOND_ORE));
        assertTrue(NaturalBlockPolicy.isTracked(Material.DEEPSLATE_DIAMOND_ORE));
        assertTrue(NaturalBlockPolicy.isTracked(Material.ANCIENT_DEBRIS));
        assertTrue(NaturalBlockPolicy.isTracked(Material.NETHER_QUARTZ_ORE));
        assertFalse(NaturalBlockPolicy.isTracked(Material.DIAMOND_BLOCK));
        assertFalse(NaturalBlockPolicy.isTracked(Material.STONE));
    }

    @Test
    void usesStableMaterialSpecificCounterKeys() {
        assertEquals("natural_mined:ancient_debris",
            NaturalBlockPolicy.counterKey(Material.ANCIENT_DEBRIS));
    }
}
