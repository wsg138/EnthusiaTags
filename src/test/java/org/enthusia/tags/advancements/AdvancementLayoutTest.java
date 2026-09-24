package org.enthusia.tags.advancements;

import java.util.HashSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AdvancementLayoutTest {
    @Test void everyBundledRewardHasAnIntentionalPlacement() throws Exception {
        var rewards = BundledRewardFixture.rewards();
        for (String id : rewards.getKeys(false)) {
            assertTrue(AdvancementLayout.hasFixed(id), id);
        }
    }

    @Test void fixedPlacementsDoNotOverlap() throws Exception {
        var coordinates = new HashSet<String>();
        String[] ids = bundledIds();
        for (String id : ids) {
            var placement = AdvancementLayout.placement(id, null, 99, 99);
            assertTrue(
                coordinates.add(placement.x() + ":" + placement.y()),
                id + " overlaps another bundled advancement");
        }
    }

    @Test void branchesUseExpectedParentsAndCompactBounds() {
        var combat = AdvancementLayout.placement("deadeye", null, 99, 99);
        assertEquals("arrow_storm", combat.parentId());
        assertEquals(11, combat.x());
        assertEquals(10, combat.y());

        var economy = AdvancementLayout.placement("baltop_top3", null, 99, 99);
        assertEquals("wealthy", economy.parentId());
        assertEquals(4, economy.x());
        assertEquals(16, economy.y());

        assertEquals(29, AdvancementLayout.warzoneBaseY(7));
        assertEquals(33, AdvancementLayout.warzoneBaseY(8));
        assertEquals(29, AdvancementLayout.reputationBaseY(7, false));
        assertEquals(35, AdvancementLayout.reputationBaseY(7, true));
        assertEquals(29, AdvancementLayout.expressBaseY(7, false, false));
        assertEquals(35, AdvancementLayout.expressBaseY(7, true, false));
        assertEquals(42, AdvancementLayout.expressBaseY(7, true, true));
        assertEquals(49, AdvancementLayout.diaryBaseY(7, true, true, true));
    }

    @Test void unknownFutureRewardKeepsLinearFallback() {
        var fallback = AdvancementLayout.placement("future_reward", "previous", 7, 31);
        assertEquals("previous", fallback.parentId());
        assertEquals(7, fallback.x());
        assertEquals(31, fallback.y());
    }
    private String[] bundledIds() throws Exception {
        var rewards = BundledRewardFixture.rewards();
        return rewards.getKeys(false).toArray(String[]::new);
    }
}
