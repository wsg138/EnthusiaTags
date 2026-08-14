package org.enthusia.tags.rewards;

import org.enthusia.tags.PerformanceMonitor;
import org.enthusia.tags.rewards.loreitems.LoreItemHandoffCoordinator;
import org.enthusia.tags.rewards.loreitems.LoreItemHandoffRecord;
import org.enthusia.tags.rewards.loreitems.LoreItemHandoffState;
import org.enthusia.tags.rewards.loreitems.LoreItemHandoffStore;
import org.enthusia.tags.rewards.loreitems.LoreItemsGatewayResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RewardStorageLoreItemRecoveryTest {
    private static final UUID PLAYER = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final String REWARD = "lore-reward";
    private static final String ACTION = "unique-hourglass";
    private static final String DEFINITION = "hourglass";
    private static final String FINGERPRINT = "v1:lore-item:hourglass";

    @Test
    void acceptedHandoffRecoversFailedLoreItemActionOnlyForMatchingFingerprint(@TempDir Path tempDir)
        throws Exception {
        UUID playerId = PLAYER;
        RewardAction action = loreAction();

        RewardStorage storage = new RewardStorage(
            tempDir.resolve("rewards.db").toFile(),
            new PerformanceMonitor(null));
        storage.init();
        try {
            storage.saveActionLedgerNow(
                playerId, REWARD, action, FINGERPRINT, RewardStatus.CLAIM_PENDING, null, null);
            storage.saveActionLedgerNow(
                playerId, REWARD, action, FINGERPRINT, RewardStatus.DELIVERY_FAILED, null,
                "LoreItems service unavailable");

            assertFalse(storage.acceptLoreItemHandoffNow(
                playerId, REWARD, action, "different-fingerprint", "accepted externally"));
            assertEquals(
                RewardStatus.DELIVERY_FAILED,
                storage.loadActionLedgerNow(playerId, REWARD).get(ACTION).status());

            assertTrue(storage.acceptLoreItemHandoffNow(
                playerId, REWARD, action, FINGERPRINT, "accepted externally"));
            Map<String, RewardStorage.ActionLedgerEntry> recovered =
                storage.loadActionLedgerNow(playerId, REWARD);
            assertEquals(RewardStatus.CLAIMED, recovered.get(ACTION).status());
            assertEquals("accepted externally", recovered.get(ACTION).errorMessage());

            assertTrue(storage.acceptLoreItemHandoffNow(
                playerId, REWARD, action, FINGERPRINT, "replayed accepted handoff"));
            assertEquals(
                RewardStatus.CLAIMED,
                storage.loadActionLedgerNow(playerId, REWARD).get(ACTION).status());
        } finally {
            storage.close();
        }
    }

    @Test
    void staffRetryCanFinalizePreviouslyReviewedLoreItemAction(@TempDir Path tempDir) throws Exception {
        RewardAction action = loreAction();
        RewardStorage storage = new RewardStorage(
            tempDir.resolve("reviewed-retry-rewards.db").toFile(), new PerformanceMonitor(null));
        storage.init();
        try (LoreItemHandoffStore handoffs =
                 new LoreItemHandoffStore(tempDir.resolve("reviewed-retry-handoffs.db"))) {
            storage.saveActionLedgerNow(
                PLAYER, REWARD, action, FINGERPRINT, RewardStatus.CLAIM_PENDING, null, null);
            storage.saveActionLedgerNow(
                PLAYER, REWARD, action, FINGERPRINT, RewardStatus.REQUIRES_RECONCILIATION, null,
                "LoreItems review required");
            long now = System.currentTimeMillis();
            LoreItemHandoffRecord prepared = handoffs.prepare(PLAYER, REWARD, ACTION, DEFINITION, now);
            assertEquals(LoreItemHandoffState.REVIEW,
                handoffs.markReview(prepared.externalOperationId(), "UNKNOWN_DEFINITION",
                    "definition corrected before staff retry", now).state());
            assertEquals(LoreItemHandoffState.RETRY,
                handoffs.requestRetry(PLAYER, REWARD, ACTION, now).state());
            LoreItemHandoffCoordinator coordinator = new LoreItemHandoffCoordinator(
                handoffs,
                (definitionKey, targetPlayer, operationId) -> CompletableFuture.completedFuture(
                    new LoreItemsGatewayResult(LoreItemsGatewayResult.Disposition.ACCEPTED,
                        "ACCEPTED_QUEUED", operationId, "accepted after staff retry")),
                Runnable::run);
            LoreItemHandoffRecord accepted = coordinator.handoff(
                PLAYER, REWARD, ACTION, DEFINITION).toCompletableFuture().join();
            assertEquals(LoreItemHandoffState.ACCEPTED, accepted.state());
            assertTrue(storage.acceptLoreItemHandoffNow(
                PLAYER, REWARD, action, FINGERPRINT, "accepted after staff retry"));
            assertEquals(RewardStatus.CLAIMED,
                storage.loadActionLedgerNow(PLAYER, REWARD).get(ACTION).status());
            storage.finalizeRewardNow(PLAYER, REWARD);
            assertTrue(storage.loadNow(PLAYER).claims().contains(REWARD));
            handoffs.markRewardFinalized(accepted.externalOperationId(), System.currentTimeMillis());
            assertTrue(handoffs.loadByOperationId(accepted.externalOperationId()).rewardFinalized());
        } finally {
            storage.close();
        }
    }

    private static RewardAction loreAction() {
        return new RewardAction(ACTION, RewardActionType.LORE_ITEM, DEFINITION, 0.0D,
            "Hourglass", null, 0, null, List.of(), true);
    }

}
