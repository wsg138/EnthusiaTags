package org.enthusia.tags.rewards.loreitems;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoreItemOperationKeyTest {
    private static final UUID PLAYER = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @Test
    void identityIsStableAcrossCaseAndWhitespaceNormalization() {
        String first = LoreItemOperationKey.forRewardAction(PLAYER, " FirstReward ", " Action-1 ");
        String replay = LoreItemOperationKey.forRewardAction(PLAYER, "firstreward", "action-1");

        assertEquals(first, replay);
    }

    @Test
    void lengthDelimitedIdentityInputDoesNotCollideWhenDelimitersAppearInIds() {
        String first = LoreItemOperationKey.forRewardAction(PLAYER, "a:b", "c");
        String second = LoreItemOperationKey.forRewardAction(PLAYER, "a", "b:c");

        assertNotEquals(first, second);
    }

    @Test
    void distinctPlayerRewardOrActionProducesDistinctIdentity() {
        String baseline = LoreItemOperationKey.forRewardAction(PLAYER, "reward", "action");

        assertNotEquals(baseline, LoreItemOperationKey.forRewardAction(
            UUID.fromString("11111111-2222-3333-4444-555555555555"), "reward", "action"));
        assertNotEquals(baseline, LoreItemOperationKey.forRewardAction(PLAYER, "reward-2", "action"));
        assertNotEquals(baseline, LoreItemOperationKey.forRewardAction(PLAYER, "reward", "action-2"));
    }

    @Test
    void maximumConfiguredIdsStayBelowReleasedLoreItemsOperationLimit() {
        String operationId = LoreItemOperationKey.forRewardAction(
            PLAYER,
            "r".repeat(64),
            "a".repeat(64));

        assertTrue(operationId.length() <= 160, "released LoreItems API accepts at most 160 characters");
    }

    @Test
    void blankIdentityPartsAreRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> LoreItemOperationKey.forRewardAction(PLAYER, " ", "action"));
        assertThrows(IllegalArgumentException.class,
            () -> LoreItemOperationKey.forRewardAction(PLAYER, "reward", " "));
    }
}
