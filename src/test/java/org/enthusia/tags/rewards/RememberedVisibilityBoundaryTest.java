package org.enthusia.tags.rewards;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.enthusia.tags.PerformanceMonitor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RememberedVisibilityBoundaryTest {
    @Test void earnedMayBeSeenBeforeEventEndsButCannotBeClaimed(@TempDir Path dir) throws Exception {
        UUID id = UUID.randomUUID();
        Player player = mock(Player.class); when(player.getUniqueId()).thenReturn(id);
        var storage = new RewardStorage(dir.resolve("rewards.db").toFile(), new PerformanceMonitor(null));
        storage.init();
        try {
            var criterion = new RewardCriterion(RewardCriterionType.CUSTOM_COUNTER, RewardSourceType.CUSTOM_COUNTER,
                1, null, null, null, "last_watch_halloween_2026", 0, "Eligibility", true);
            var reward = new RewardDefinition("last_watch_remembered_2026", "Remembered", List.of(), Material.PAPER,
                List.of(criterion), List.of(), "halloween", RewardCompletionMode.LATCHED).withHiddenUntilEarned(true);
            storage.saveNow(id, new RewardStorage.StoredRewardData(Set.of(), Map.of("last_watch_earned_halloween_2026", 1L), Map.of(), 1));
            var service = spy(new RewardService(mock(JavaPlugin.class), null, null, new PerformanceMonitor(null), storage));
            doReturn(true).when(service).isAvailable();
            service.preloadPlayerBlocking(id);
            assertTrue(service.canReveal(id, reward));
            assertFalse(service.evaluate(player, reward, new RewardService.ProgressSnapshot(0, new HashMap<>())).claimable());
            assertFalse(service.canReveal(UUID.randomUUID(), reward));
            var unrelated = new RewardDefinition("other_private", "Other", List.of(), Material.PAPER,
                List.of(criterion), List.of(), "halloween", RewardCompletionMode.LATCHED).withHiddenUntilEarned(true);
            assertFalse(service.canReveal(id, unrelated));
        } finally { storage.close(); }
    }
}
