package org.enthusia.tags.rewards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.enthusia.tags.PerformanceMonitor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RewardStorageReentrancyTest {
    @Test
    void blockingReadFromStorageCompletionDoesNotDeadlock(@TempDir Path tempDir) throws Exception {
        RewardStorage storage = storage(tempDir);
        UUID playerId = UUID.randomUUID();
        try {
            RewardStorage.StoredRewardData loaded = storage.loadAsync(playerId).thenApply(ignored -> {
                try {
                    return storage.loadNow(playerId);
                } catch (SQLException ex) {
                    throw new CompletionException(ex);
                }
            }).get(2, TimeUnit.SECONDS);

            assertTrue(loaded.claims().isEmpty());
        } finally {
            storage.close();
        }
    }

    @Test
    void blockingSaveFromStorageCompletionDoesNotDeadlock(@TempDir Path tempDir) throws Exception {
        RewardStorage storage = storage(tempDir);
        UUID playerId = UUID.randomUUID();
        RewardStorage.StoredRewardData snapshot = new RewardStorage.StoredRewardData(
            Set.of("test_reward"), Map.of("test_counter", 4L), Map.of("test_state", "ok"), 1L);
        try {
            RewardStorage.WriteResult result = storage.loadAsync(playerId).thenApply(ignored -> {
                try {
                    return storage.saveNow(playerId, snapshot);
                } catch (SQLException ex) {
                    throw new CompletionException(ex);
                }
            }).get(2, TimeUnit.SECONDS);

            assertEquals(RewardStorage.WriteResult.WRITTEN, result);
            assertTrue(storage.loadNow(playerId).claims().contains("test_reward"));
        } finally {
            storage.close();
        }
    }

    @Test
    void acceptedWorkerCanFinishNestedWorkAfterShutdownBegins() throws Exception {
        ReentrantSingleThreadExecutor executor = new ReentrantSingleThreadExecutor("test-reentrant-storage");
        CompletableFuture<String> completed = new CompletableFuture<>();

        executor.execute(() -> {
            executor.shutdown();
            CompletableFuture.runAsync(() -> completed.complete("finished"), executor).join();
        });

        assertEquals("finished", completed.get(2, TimeUnit.SECONDS));
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
    }

    private RewardStorage storage(Path tempDir) throws SQLException {
        RewardStorage storage = new RewardStorage(tempDir.resolve("rewards.db").toFile(),
            new PerformanceMonitor(null));
        storage.init();
        return storage;
    }
}
