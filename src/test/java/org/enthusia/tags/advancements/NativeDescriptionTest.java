package org.enthusia.tags.advancements;

import java.util.List;
import org.enthusia.tags.rewards.RewardAction;
import org.enthusia.tags.rewards.RewardActionType;
import org.enthusia.tags.rewards.RewardCriterion;
import org.enthusia.tags.rewards.RewardCriterionType;
import org.enthusia.tags.rewards.RewardDefinition;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeDescriptionTest {
    @Test void idsAreStableAndPunctuationCannotCollide() {
        assertEquals(NativeAdvancementController.key("Veteran"), NativeAdvancementController.key("veteran"));
        assertNotEquals(NativeAdvancementController.key("a b"), NativeAdvancementController.key("a_b"));
        assertTrue(NativeAdvancementController.key("A/B").matches("[a-z0-9/._-]+"));
    }
    @Test void tooltipIncludesThresholdQuantityNetworkRuleAndManualClaim() {
        var criterion = new RewardCriterion(RewardCriterionType.PLAYTIME_ACTIVE_MINUTES, 60000, null, null, 0, "Active minutes");
        var gold = new RewardAction(RewardActionType.MONEY, "", 3000, "Payout");
        var definition = new RewardDefinition("existing", "Existing", List.of("Existing description"), null,
            List.of(criterion), List.of(gold), "playtime");
        String text = String.join("\n", NativeAdvancementController.description(definition));
        assertTrue(text.contains("60000"));
        assertTrue(text.contains("3000"));
        assertTrue(text.contains("one account per challenge/IP"));
        assertTrue(text.contains("/rewards"));
    }
}
