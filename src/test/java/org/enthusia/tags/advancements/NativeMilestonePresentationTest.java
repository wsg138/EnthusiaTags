package org.enthusia.tags.advancements;

import io.github.badgersmc.advancements.pilot.ProjectionService.Node;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.enthusia.tags.rewards.RewardCriterion;
import org.enthusia.tags.rewards.RewardCriterionType;
import org.enthusia.tags.rewards.RewardDefinition;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeMilestonePresentationTest {
    private static final String PROVIDER = "diary/dear_diary";
    private static final String REWARD = NativeAdvancementController.key("diary_reward");

    private RewardDefinition reward(long amount) {
        return new RewardDefinition("diary_reward", "Dear Diary...", List.of("Reward details"), Material.PAPER,
            List.of(new RewardCriterion(RewardCriterionType.CUSTOM_COUNTER, amount, null,
                "advancement_reward:" + PROVIDER, 0, "Receive diary")), List.of(), "advancements");
    }

    private Node node(String key, String parent) {
        return new Node(key, parent, "Dear Diary...", List.of("Provider details"), Material.WRITABLE_BOOK,
            null, "nexo:diary_book", "TASK", 1, 1);
    }

    @Test void mirrorsBecomeOneProviderWithRewardInformationAndStableDescendants() {
        var reward = reward(1);
        var presentation = NativeMilestonePresentation.create(List.of(
            node(REWARD, null), node(PROVIDER, null), node("custom_child", REWARD)), List.of(reward));
        assertEquals(List.of(PROVIDER, "custom_child"), presentation.nodes().stream().map(Node::key).toList());
        assertEquals(PROVIDER, presentation.nodes().get(1).parentKey());
        var merged = presentation.nodes().getFirst();
        assertEquals("nexo:diary_book", merged.itemModel());
        assertTrue(merged.description().contains("Provider details"));
        assertTrue(merged.description().stream().anyMatch(s -> s.contains("Claim with /rewards")));
        assertFalse(presentation.ownsCelebration(REWARD));
        assertTrue(presentation.ownsCelebration(PROVIDER));
        assertEquals(1, reward.getCriteria().size());
        assertEquals("diary_reward", reward.getId());
    }

    @Test void historicalClaimProgressIsSilentlyRetainedOnProviderAndAliasNeverProjected() {
        var presentation = NativeMilestonePresentation.create(List.of(node(REWARD, null), node(PROVIDER, null)), List.of(reward(1)));
        assertEquals(Map.of(PROVIDER, 1000), presentation.progress(Map.of(REWARD, 1000, PROVIDER, 0, "removed", 1000)));
        assertEquals(Map.of(PROVIDER, 1000), presentation.progress(Map.of(REWARD, 0, PROVIDER, 1000)));
    }

    @Test void disabledProviderAndHigherThresholdRewardsRemainIndependent() {
        var missing = NativeMilestonePresentation.create(List.of(node(REWARD, null)), List.of(reward(1)));
        assertEquals(1, missing.nodes().size());
        assertTrue(missing.ownsCelebration(REWARD));
        var distinct = NativeMilestonePresentation.create(List.of(node(REWARD, null), node(PROVIDER, null)), List.of(reward(2)));
        assertEquals(2, distinct.nodes().size());
        assertTrue(distinct.ownsCelebration(REWARD));
    }

    @Test void sameTitlesAreNotEvidenceOfDuplicateCriteria() {
        var presentation = NativeMilestonePresentation.create(List.of(node("a", null), node("b", null)), List.of());
        assertEquals(2, presentation.nodes().size());
    }

    @Test void multiCriterionRewardsAreNotFoldedIntoProvider() {
        var criteria = new java.util.ArrayList<>(reward(1).getCriteria());
        criteria.add(new RewardCriterion(RewardCriterionType.CUSTOM_COUNTER, 1, null, "other_counter", 0, "Other"));
        var multi = new RewardDefinition("diary_reward", "Dear Diary...", List.of(), Material.PAPER,
            criteria, List.of(), "advancements");
        var presentation = NativeMilestonePresentation.create(List.of(node(REWARD, null), node(PROVIDER, null)), List.of(multi));
        assertEquals(2, presentation.nodes().size());
        assertTrue(presentation.ownsCelebration(REWARD));
    }

    @Test void multipleRewardAliasesMergeIntoOneProviderWithoutDroppingProgress() {
        var second = new RewardDefinition("second", "Another claim", List.of(), Material.PAPER,
            reward(1).getCriteria(), List.of(), "advancements");
        String key = NativeAdvancementController.key(second.getId());
        var presentation = NativeMilestonePresentation.create(List.of(node(REWARD, null), node(key, null), node(PROVIDER, null)),
            List.of(reward(1), second));
        assertEquals(1, presentation.nodes().size());
        assertEquals(Map.of(PROVIDER, 1000), presentation.progress(Map.of(REWARD, 500, key, 1000)));
        assertFalse(presentation.ownsCelebration(key));
    }

    @Test void explicitNonCounterSourcesAreNotMistakenForProviderEvidence() {
        var criterion = new RewardCriterion(RewardCriterionType.CUSTOM_COUNTER,
            org.enthusia.tags.rewards.RewardSourceType.VAULT_BALANCE, 1, null, null, null,
            "advancement_reward:" + PROVIDER, 0, "Balance", true);
        var unrelated = new RewardDefinition("diary_reward", "Custom reward", List.of(), Material.PAPER,
            List.of(criterion), List.of(), "advancements");
        var presentation = NativeMilestonePresentation.create(List.of(node(REWARD, null), node(PROVIDER, null)), List.of(unrelated));
        assertEquals(2, presentation.nodes().size());
        assertTrue(presentation.ownsCelebration(REWARD));
    }
}
