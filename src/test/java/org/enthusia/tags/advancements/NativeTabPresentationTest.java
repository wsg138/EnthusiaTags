package org.enthusia.tags.advancements;

import io.github.badgersmc.advancements.pilot.ProjectionService.Node;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.enthusia.tags.rewards.RewardCriterion;
import org.enthusia.tags.rewards.RewardCriterionType;
import org.enthusia.tags.rewards.RewardDefinition;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativeTabPresentationTest {
    @Test void logoUsesScopedModelInsteadOfSharedPaperSelector() {
        ItemMeta meta = mock(ItemMeta.class);
        NativeTabPresentation.applyLogo(meta, 815001, "enthusia:advancement/logo");
        verify(meta).setCustomModelData(815001);
        verify(meta).setItemModel(new NamespacedKey("enthusia", "advancement/logo"));
    }

    @Test void providerRewardIsConnectedToItsActualAdvancement() {
        var reward = reward("adv_commend_a_good_word", "advancements", "advancement_reward:reputation/a_good_word");
        var provider = node("reputation/a_good_word", null, 1, 35);
        var claim = node(NativeAdvancementController.key(reward.getId()), null, 28, 29);
        var result = NativeTabPresentation.organize(List.of(provider, claim), List.of(reward));
        var shown = result.get(1);
        assertEquals(provider.key(), shown.parentKey());
        assertEquals(3, shown.x());
        assertEquals(result.get(0).y() + 1, shown.y());
        assertEquals(2, result.get(0).x());
        assertEquals(claim.key(), shown.key());
        assertEquals(claim.description(), shown.description());
    }

    @Test void extraRewardsWrapInsteadOfCreatingAnUnboundedRow() {
        var rewards = new ArrayList<RewardDefinition>();
        var nodes = new ArrayList<Node>();
        for (int i = 0; i < 13; i++) {
            var reward = reward("custom_" + i, "supporter", "entitlement:custom_" + i);
            rewards.add(reward);
            nodes.add(node(NativeAdvancementController.key(reward.getId()), null, i + 1, 40));
        }
        var result = NativeTabPresentation.organize(nodes, rewards);
        assertTrue(result.stream().allMatch(n -> n.x() >= 1 && n.x() <= 4));
        assertEquals(4, result.stream().map(Node::y).distinct().count());
        assertEquals(13, result.stream().map(Node::key).distinct().count());
        assertDoesNotThrow(() -> AdvancementNodeOrder.parentFirst(result));
    }

    @Test void bundledLayoutAndMetadataStayIntact() {
        var reward = reward("first_hour", "playtime", "minutes");
        var original = node(NativeAdvancementController.key(reward.getId()), null, 1, 1);
        assertEquals(List.of(original), NativeTabPresentation.organize(List.of(original), List.of(reward)));
    }

    @Test void missingProviderAndMultipleClaimsNeverOverlapOrCreateDanglingParents() {
        var source = node("express/first_class", null, 1, 42);
        var rewards = List.of(reward("first_claim", "advancements", "advancement_reward:express/first_class"),
            reward("second_claim", "advancements", "advancement_reward:express/first_class"),
            reward("disabled_provider", "advancements", "advancement_reward:diary/dear_diary"));
        var nodes = new ArrayList<Node>(); nodes.add(source);
        rewards.forEach(r -> nodes.add(node(NativeAdvancementController.key(r.getId()), null, 1, 29)));
        var result = NativeTabPresentation.organize(nodes, rewards);
        assertEquals(result.size(), result.stream().map(n -> n.x() + ":" + n.y()).distinct().count());
        assertDoesNotThrow(() -> AdvancementNodeOrder.parentFirst(result));
    }

    @Test void completeCatalogAndAllProviderClaimCardsHaveUniquePositionsAndStableMetadata() throws Exception {
        var nodes = new ArrayList<Node>();
        var rewards = new ArrayList<RewardDefinition>();
        for (String id : BundledRewardFixture.rewards().getKeys(false)) {
            var placement = AdvancementLayout.placement(id, null, 99, 99);
            rewards.add(reward(id, "built_in", "counter"));
            nodes.add(node(NativeAdvancementController.key(id), placement.parentId() == null ? null
                : NativeAdvancementController.key(placement.parentId()), placement.x(), placement.y()));
        }
        var providers = new ArrayList<Node>();
        providers.addAll(WarzoneAdvancementBridge.nodes(45));
        providers.addAll(CommendAdvancementBridge.nodes(51));
        providers.addAll(ExpressAdvancementBridge.nodes(58));
        providers.addAll(DiaryAdvancementBridge.nodes(65, 815002));
        nodes.addAll(providers);
        for (Node provider : providers) {
            var reward = reward("claim_" + provider.key().replace('/', '_'), "advancements", "advancement_reward:" + provider.key());
            rewards.add(reward);
            nodes.add(node(NativeAdvancementController.key(reward.getId()), null, nodes.size(), 29));
        }
        var result = NativeTabPresentation.organize(nodes, rewards);
        assertEquals(nodes.size(), result.size());
        assertEquals(result.size(), result.stream().map(n -> n.x() + ":" + n.y()).distinct().count());
        assertDoesNotThrow(() -> AdvancementNodeOrder.parentFirst(result));
        for (int i = 0; i < nodes.size(); i++) {
            assertEquals(nodes.get(i).key(), result.get(i).key());
            assertEquals(nodes.get(i).description(), result.get(i).description());
            assertEquals(nodes.get(i).customModelData(), result.get(i).customModelData());
            assertEquals(nodes.get(i).itemModel(), result.get(i).itemModel());
        }
    }

    @Test void explicitEmptyItemModelRetainsAdministratorCmdOnlyOption() {
        ItemMeta meta = mock(ItemMeta.class);
        NativeTabPresentation.applyLogo(meta, 42, "");
        verify(meta).setCustomModelData(42);
        verify(meta, never()).setItemModel(any());
    }

    static RewardDefinition reward(String id, String category, String counter) {
        return new RewardDefinition(id, id, List.of("Requirements and manual claims stay intact"), Material.PAPER,
            List.of(new RewardCriterion(RewardCriterionType.CUSTOM_COUNTER, 1, null, counter, 0, "Requirement")), List.of(), category);
    }
    static Node node(String key, String parent, int x, int y) {
        return new Node(key, parent, key, List.of("Original tooltip"), Material.PAPER, "TASK", x, y);
    }
}
