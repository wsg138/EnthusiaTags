package org.enthusia.tags.rewards;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.enthusia.tags.PerformanceMonitor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AdvancementUnlockTest {
    @Test void earnedHistoryDoesNotDependOnCurrentProviderOrPaymentSuccess() {
        RewardPlayerState state = new RewardPlayerState();
        assertFalse(RewardService.historicallyCompleted(state, "test"));
        for (RewardStatus status : java.util.List.of(RewardStatus.CLAIM_PENDING, RewardStatus.ITEM_QUEUED,
            RewardStatus.DELIVERY_FAILED, RewardStatus.REQUIRES_RECONCILIATION)) {
            state.setOverall("test", status);
            assertTrue(RewardService.historicallyCompleted(state, "test"));
        }
        state.clearOverall("test");
        state.putState("reward-unlocked:test", "true");
        assertTrue(RewardService.historicallyCompleted(state, "test"));
        assertTrue(state.claimedRewardsSnapshot().isEmpty());
    }

    @Test void concurrentConnectionsElectOneNotifier(@TempDir Path directory) throws Exception {
        UUID id = UUID.randomUUID();
        RewardStorage a = new RewardStorage(directory.resolve("rewards.db").toFile(), new PerformanceMonitor(null));
        RewardStorage b = new RewardStorage(directory.resolve("rewards.db").toFile(), new PerformanceMonitor(null));
        a.init(); b.init();
        try {
            var first = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try { return a.markUnlockedNow(id, "reward"); } catch (Exception ex) { throw new CompletionException(ex); }
            });
            var second = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try { return b.markUnlockedNow(id, "reward"); } catch (Exception ex) { throw new CompletionException(ex); }
            });
            assertNotEquals(first.get(5, java.util.concurrent.TimeUnit.SECONDS), second.get(5, java.util.concurrent.TimeUnit.SECONDS));
        } finally { a.close(); b.close(); }
    }
    @Test
    void onlyTheFirstDurableUnlockMayNotify(@TempDir Path directory) throws Exception {
        UUID id = UUID.randomUUID();
        RewardStorage storage = new RewardStorage(directory.resolve("rewards.db").toFile(), new PerformanceMonitor(null));
        storage.init();
        try {
            assertEquals(Boolean.TRUE, storage.markUnlockedNow(id, "existing_challenge"));
            assertEquals(Boolean.FALSE, storage.markUnlockedNow(id, "existing_challenge"));
            assertTrue(storage.loadNow(id).claims().isEmpty(), "Unlocking must never grant rewards");
        } finally { storage.close(); }
        storage = new RewardStorage(directory.resolve("rewards.db").toFile(), new PerformanceMonitor(null));
        storage.init();
        try {
            assertEquals(Boolean.FALSE, storage.markUnlockedNow(id, "existing_challenge"));
        } finally { storage.close(); }
    }
}
