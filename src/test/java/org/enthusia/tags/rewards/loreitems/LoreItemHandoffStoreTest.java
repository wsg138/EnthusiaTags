package org.enthusia.tags.rewards.loreitems;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoreItemHandoffStoreTest {
    private static final UUID PLAYER = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final String ACTION = "action";
    private static final String REWARD = "reward";
    private static final String HOURGLASS = "hourglass";

    @TempDir
    Path tempDir;

    @Test
    void intentAndOperationIdentitySurviveStoreRestart() throws Exception {
        Path database = tempDir.resolve("lore-handoffs.db");
        String operation;

        try (LoreItemHandoffStore store = new LoreItemHandoffStore(database)) {
            LoreItemHandoffRecord prepared = store.prepare(PLAYER, "Reward-One", "Action-One", HOURGLASS, 1000L);
            operation = prepared.externalOperationId();
            assertEquals(LoreItemHandoffState.PENDING, prepared.state());
            assertEquals(0, prepared.attempts());
        }

        try (LoreItemHandoffStore restarted = new LoreItemHandoffStore(database)) {
            LoreItemHandoffRecord replay = restarted.prepare(PLAYER, "reward-one", "action-one", HOURGLASS, 2000L);
            assertEquals(operation, replay.externalOperationId());
            assertEquals(1000L, replay.createdAtEpochMillis());
        }
    }

    @Test
    void changedDefinitionForExistingClaimRequiresReviewInsteadOfNewIdentity() throws Exception {
        try (LoreItemHandoffStore store = new LoreItemHandoffStore(tempDir.resolve("definition-change.db"))) {
            store.prepare(PLAYER, REWARD, ACTION, HOURGLASS, 1000L);

            assertThrows(SQLException.class,
                () -> store.prepare(PLAYER, REWARD, ACTION, "dragon-breath", 2000L));
        }
    }

    @Test
    void retryQueueIsOrderedBoundedAndOnlyReturnsDueRows() throws Exception {
        UUID secondPlayer = UUID.fromString("11111111-2222-3333-4444-555555555555");
        UUID thirdPlayer = UUID.fromString("22222222-3333-4444-5555-666666666666");
        UUID acceptedPlayer = UUID.fromString("33333333-4444-5555-6666-777777777777");
        try (LoreItemHandoffStore store = new LoreItemHandoffStore(tempDir.resolve("retry.db"))) {
            LoreItemHandoffRecord latestDue = store.prepare(PLAYER, "reward-a", ACTION, HOURGLASS, 1000L);
            LoreItemHandoffRecord earliestDue = store.prepare(secondPlayer, "reward-b", ACTION, "star", 1100L);
            LoreItemHandoffRecord middleDue = store.prepare(thirdPlayer, "reward-c", ACTION, "ember", 1200L);
            LoreItemHandoffRecord accepted = store.prepare(acceptedPlayer, "reward-d", ACTION, "leaf", 1300L);
            assertNotEquals(latestDue.externalOperationId(), earliestDue.externalOperationId());

            store.recordOutcome(latestDue.externalOperationId(), LoreItemHandoffState.RETRY,
                "SERVICE_UNAVAILABLE", "latest", 5000L, 1400L);
            store.recordOutcome(earliestDue.externalOperationId(), LoreItemHandoffState.RETRY,
                "SERVICE_UNAVAILABLE", "earliest", 3000L, 1500L);
            store.recordOutcome(middleDue.externalOperationId(), LoreItemHandoffState.RETRY,
                "SERVICE_UNAVAILABLE", "middle", 4000L, 1600L);
            store.recordOutcome(accepted.externalOperationId(), LoreItemHandoffState.ACCEPTED,
                "ACCEPTED_QUEUED", "accepted", 0L, 1700L);

            assertEquals(List.of(), store.listDue(2999L, 2));
            List<LoreItemHandoffRecord> due = store.listDue(5000L, 2);
            assertEquals(2, due.size());
            assertEquals(earliestDue.externalOperationId(), due.get(0).externalOperationId());
            assertEquals(middleDue.externalOperationId(), due.get(1).externalOperationId());
            assertEquals(1, due.get(0).attempts());
            assertEquals(1, due.get(1).attempts());
        }
    }

    @Test
    void finalizationAcknowledgesOnlyTheExactAcceptedOperation() throws Exception {
        try (LoreItemHandoffStore store = new LoreItemHandoffStore(tempDir.resolve("finalization.db"))) {
            LoreItemHandoffRecord first = store.prepare(PLAYER, REWARD, "first", HOURGLASS, 1000L);
            LoreItemHandoffRecord second = store.prepare(PLAYER, REWARD, "second", "star", 1001L);
            store.recordOutcome(first.externalOperationId(), LoreItemHandoffState.ACCEPTED,
                "ACCEPTED_QUEUED", "accepted first", 0L, 1100L);
            store.recordOutcome(second.externalOperationId(), LoreItemHandoffState.ACCEPTED,
                "ACCEPTED_QUEUED", "accepted second", 0L, 1101L);

            assertEquals(2, store.listAcceptedPendingFinalization(10).size());
            store.markRewardFinalized(first.externalOperationId(), 1200L);
            store.markRewardFinalized(first.externalOperationId(), 1201L);

            List<LoreItemHandoffRecord> remaining = store.listAcceptedPendingFinalization(10);
            assertEquals(1, remaining.size());
            assertEquals(second.externalOperationId(), remaining.getFirst().externalOperationId());
            assertTrue(store.loadByOperationId(first.externalOperationId()).rewardFinalized());
            assertFalse(store.loadByOperationId(second.externalOperationId()).rewardFinalized());

            LoreItemHandoffRecord review = store.markReview(
                second.externalOperationId(),
                "TAGS_RECONCILIATION_REVIEW",
                "configuration changed",
                1300L);
            assertEquals(LoreItemHandoffState.REVIEW, review.state());
            assertEquals(List.of(), store.listAcceptedPendingFinalization(10));
        }
    }

    @Test
    void staffRetryCanRequeueReviewButNeverReopensAcceptedOperation() throws Exception {
        try (LoreItemHandoffStore store = new LoreItemHandoffStore(tempDir.resolve("staff-retry.db"))) {
            LoreItemHandoffRecord review = store.prepare(PLAYER, REWARD, ACTION, HOURGLASS, 1000L);
            review = store.recordOutcome(review.externalOperationId(), LoreItemHandoffState.REVIEW,
                "UNKNOWN_DEFINITION", "missing definition", 0L, 1100L);

            LoreItemHandoffRecord retry = store.requestRetry(PLAYER, REWARD, ACTION, 2000L);
            assertEquals(LoreItemHandoffState.RETRY, retry.state());
            assertEquals(review.externalOperationId(), retry.externalOperationId());
            assertEquals(2000L, retry.nextAttemptAtEpochMillis());

            LoreItemHandoffRecord accepted = store.recordOutcome(retry.externalOperationId(), LoreItemHandoffState.ACCEPTED,
                "ALREADY_ACCEPTED", "accepted", 0L, 2100L);
            LoreItemHandoffRecord protectedAccepted = store.requestRetry(PLAYER, REWARD, ACTION, 3000L);

            assertEquals(LoreItemHandoffState.ACCEPTED, protectedAccepted.state());
            assertEquals(accepted.externalOperationId(), protectedAccepted.externalOperationId());
            assertEquals(0L, protectedAccepted.nextAttemptAtEpochMillis());
        }
    }
}
