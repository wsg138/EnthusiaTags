package org.enthusia.tags.rewards;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;
import org.enthusia.tags.PerformanceMonitor;
import org.enthusia.tags.TagDefinition;
import static org.mockito.Mockito.*;

class PrivateRewardVisibilityTest {
    @Test void privateRewardsFailClosedUntilVerifiedOrDurablyEarned() {
        assertTrue(PrivateRewardVisibility.reveal(false, false));
        assertTrue(PrivateRewardVisibility.reveal(false, true));
        assertFalse(PrivateRewardVisibility.reveal(true, false));
        assertTrue(PrivateRewardVisibility.reveal(true, true));
    }
    @Test void onlyPersistedUnlockOrClaimRevealsAndRestartKeepsIt(@TempDir Path dir) throws Exception {
        UUID player = UUID.randomUUID();
        JavaPlugin plugin = mock(JavaPlugin.class);
        var storage = new RewardStorage(dir.resolve("rewards.db").toFile(), new PerformanceMonitor(null));
        storage.init();
        try {
            var hidden = new RewardDefinition("private", "Private", List.of(), Material.PAPER, List.of(), List.of(), "misc", RewardCompletionMode.LATCHED).withHiddenUntilEarned(true);
            var ordinary = new RewardDefinition("public", "Public", List.of(), Material.PAPER, List.of(), List.of(), "misc");
            var first = spy(new RewardService(plugin, null, null, new PerformanceMonitor(null), storage));
            doReturn(true).when(first).isAvailable();
            first.preloadPlayerBlocking(player);
            assertTrue(first.canReveal(player, ordinary));
            assertFalse(first.canReveal(player, hidden));
            assertTrue(storage.markUnlockedNow(player, "private"));
            // The old loaded snapshot does not invent completion before a reload.
            assertFalse(first.canReveal(player, hidden));
            var reloaded = spy(new RewardService(plugin, null, null, new PerformanceMonitor(null), storage));
            doReturn(true).when(reloaded).isAvailable();
            reloaded.preloadPlayerBlocking(player);
            assertTrue(reloaded.canReveal(player, hidden));
            var field = RewardService.class.getDeclaredField("rewards"); field.setAccessible(true);
            field.set(reloaded, Map.of("private", hidden));
            var tag = new TagDefinition("private_tag", "Private", "Private", Material.PAPER, List.of(), "private");
            assertTrue(reloaded.canRevealTag(player, tag));
            assertFalse(reloaded.canRevealTag(UUID.randomUUID(), tag));
            doReturn(false).when(reloaded).isAvailable();
            assertFalse(reloaded.canRevealTag(player, tag));
            assertTrue(reloaded.canReveal(player, ordinary));
            doThrow(new IllegalStateException("unavailable")).when(reloaded).isAvailable();
            assertFalse(reloaded.canReveal(player, hidden));
            assertTrue(reloaded.canReveal(player, ordinary));
        } finally { storage.close(); }
    }

    @Test void malformedExplicitPrivateFlagFailsClosed() {
        var config = new org.bukkit.configuration.file.YamlConfiguration();
        assertFalse(RewardService.privateRewardFlag(config, "r"));
        config.set("r.hidden-until-earned", false); assertFalse(RewardService.privateRewardFlag(config, "r"));
        config.set("r.hidden-until-earned", true); assertTrue(RewardService.privateRewardFlag(config, "r"));
        config.set("r.hidden-until-earned", "not-a-boolean"); assertTrue(RewardService.privateRewardFlag(config, "r"));
    }

    @org.junit.jupiter.api.Test void rememberedRewardIsPrivateEvenIfFlagIsAbsentOrFalse() {
        var config = new org.bukkit.configuration.file.YamlConfiguration();
        assertTrue(RewardService.privateRewardFlag(config, "last_watch_remembered_2026"));
        config.set("last_watch_remembered_2026.hidden-until-earned", false);
        assertTrue(RewardService.privateRewardFlag(config, "last_watch_remembered_2026"));
    }
    @org.junit.jupiter.api.Test void rememberedRejectsAlteredRewardOrEligibilityContract() {
        var criterion = new RewardCriterion(RewardCriterionType.CUSTOM_COUNTER, RewardSourceType.CUSTOM_COUNTER, 1, null, null, null, "last_watch_halloween_2026", 0, "", true);
        var action = new RewardAction(RewardActionType.TAG, "remembered", 0, "");
        assertTrue(RewardService.validRememberedReward(java.util.List.of(criterion), java.util.List.of(action)));
        var wrong = new RewardCriterion(RewardCriterionType.CUSTOM_COUNTER, RewardSourceType.CUSTOM_COUNTER, 1, null, null, null, "unrelated", 0, "", true);
        assertFalse(RewardService.validRememberedReward(java.util.List.of(wrong), java.util.List.of(action)));
        var wrongSource = new RewardCriterion(RewardCriterionType.CUSTOM_COUNTER, RewardSourceType.PLACEHOLDER, 1, null, null, null, "last_watch_halloween_2026", 0, "", true);
        assertFalse(RewardService.validRememberedReward(java.util.List.of(wrongSource), java.util.List.of(action)));
        assertFalse(RewardService.validRememberedReward(java.util.List.of(), java.util.List.of(action)));
        assertFalse(RewardService.validRememberedReward(java.util.List.of(criterion), java.util.List.of()));
    }
}
