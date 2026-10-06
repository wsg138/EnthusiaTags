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
    @Test void providerTooltipPreservesIconAndListsManualGoldClaim() {
        String milestone = "diary/dear_diary";
        var criterion = new RewardCriterion(RewardCriterionType.CUSTOM_COUNTER,
            org.enthusia.tags.rewards.RewardSourceType.CUSTOM_COUNTER, 1, null, null, null,
            "advancement_reward:" + milestone, 0, "Diary", true);
        var gold = new RewardAction(RewardActionType.MONEY, "", 20, "Existing payout");
        var reward = new RewardDefinition("adv_diary_dear_diary", "Diary", List.of(), null,
            List.of(criterion), List.of(gold), "advancements");
        var node = new io.github.badgersmc.advancements.pilot.ProjectionService.Node(milestone,
            null, "Diary", List.of("Provider evidence", "Rewards: None (display-only)."),
            org.bukkit.Material.PAPER, 815002, "enthusia:journal_quill", "TASK", 3, 7);
        var decorated = NativeAdvancementController.withProviderRewards(List.of(node),
            java.util.Map.of(reward.getId(), reward)).getFirst();
        assertEquals(node.key(), decorated.key());
        assertEquals(node.parentKey(), decorated.parentKey());
        assertEquals(node.title(), decorated.title());
        assertEquals(node.icon(), decorated.icon());
        assertEquals(node.customModelData(), decorated.customModelData());
        assertEquals(node.itemModel(), decorated.itemModel());
        assertEquals(node.frame(), decorated.frame());
        assertEquals(node.x(), decorated.x());
        assertEquals(node.y(), decorated.y());
        String text = String.join("\n", decorated.description());
        assertFalse(text.contains("Rewards: None"));
        assertTrue(text.contains("20"));
        assertTrue(text.contains("/rewards"));
        assertTrue(text.contains("one account per challenge/IP"));
    }
}
