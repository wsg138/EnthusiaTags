package org.enthusia.tags.rewards.loreitems;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LoreItemHandoffCoordinatorTest {
    private static final String ACTION = "action";
    private static final String REWARD = "reward";
    private static final String PLAYER_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String HOURGLASS = "hourglass";

    @TempDir
    Path tempDir;

    @Test
    void unavailableServiceKeepsSameOperationPendingUntilDueRetrySucceeds() throws Exception {
        UUID player = UUID.fromString(PLAYER_ID);
        AtomicLong clock = new AtomicLong(10_000L);
        AtomicInteger calls = new AtomicInteger();
        LoreItemsClient client = (definition, playerId, operation) -> {
            int call = calls.incrementAndGet();
            LoreItemsGatewayResult result = call == 1
                ? new LoreItemsGatewayResult(
                    LoreItemsGatewayResult.Disposition.RETRY,
                    "SERVICE_UNAVAILABLE",
                    operation,
                    "reload")
                : new LoreItemsGatewayResult(
                    LoreItemsGatewayResult.Disposition.ACCEPTED,
                    "ALREADY_ACCEPTED",
                    operation,
                    "replayed safely");
            return CompletableFuture.completedFuture(result);
        };

        try (LoreItemHandoffStore store = new LoreItemHandoffStore(tempDir.resolve("retry.db"))) {
            LoreItemHandoffCoordinator coordinator = new LoreItemHandoffCoordinator(
                store, client, Runnable::run, clock::get);

            LoreItemHandoffRecord first = coordinator.handoff(player, REWARD, ACTION, HOURGLASS)
                .toCompletableFuture().join();
            assertEquals(LoreItemHandoffState.RETRY, first.state());
            assertEquals(1, first.attempts());
            assertEquals(15_000L, first.nextAttemptAtEpochMillis());

            LoreItemHandoffRecord tooSoon = coordinator.handoff(player, REWARD, ACTION, HOURGLASS)
                .toCompletableFuture().join();
            assertEquals(first.externalOperationId(), tooSoon.externalOperationId());
            assertEquals(1, calls.get());

            clock.set(15_000L);
            LoreItemHandoffRecord accepted = coordinator.handoff(player, REWARD, ACTION, HOURGLASS)
                .toCompletableFuture().join();
            assertEquals(LoreItemHandoffState.ACCEPTED, accepted.state());
            assertEquals(first.externalOperationId(), accepted.externalOperationId());
            assertEquals(2, accepted.attempts());
            assertEquals(2, calls.get());
        }
    }

    @Test
    void crashAfterLoreItemsAcceptanceReplaysPreparedIntentWithSameOperationId() throws Exception {
        UUID player = UUID.fromString(PLAYER_ID);
        Path database = tempDir.resolve("restart.db");
        String operationId;

        try (LoreItemHandoffStore beforeCrash = new LoreItemHandoffStore(database)) {
            LoreItemHandoffRecord prepared = beforeCrash.prepare(
                player, REWARD, ACTION, "star", 1000L);
            operationId = prepared.externalOperationId();
            // Model the crash window: LoreItems accepted the operation, but Tags died before
            // persisting the returned acceptance. The Tags row intentionally remains PENDING.
        }

        AtomicInteger calls = new AtomicInteger();
        LoreItemsClient replayClient = (definition, playerId, operation) -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(new LoreItemsGatewayResult(
                LoreItemsGatewayResult.Disposition.ACCEPTED,
                "ALREADY_ACCEPTED",
                operation,
                "same operation was already accepted"));
        };

        try (LoreItemHandoffStore afterRestart = new LoreItemHandoffStore(database)) {
            LoreItemHandoffCoordinator coordinator = new LoreItemHandoffCoordinator(
                afterRestart, replayClient, Runnable::run, () -> 2000L);
            LoreItemHandoffRecord recovered = coordinator.handoff(
                player, REWARD, ACTION, "star").toCompletableFuture().join();

            assertEquals(operationId, recovered.externalOperationId());
            assertEquals(LoreItemHandoffState.ACCEPTED, recovered.state());
            assertEquals("ALREADY_ACCEPTED", recovered.lastOutcome());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void permanentServiceRejectionMovesClaimToReviewWithoutAutomaticRetry() throws Exception {
        UUID player = UUID.fromString(PLAYER_ID);
        AtomicInteger calls = new AtomicInteger();
        LoreItemsClient client = (definition, playerId, operation) -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(new LoreItemsGatewayResult(
                LoreItemsGatewayResult.Disposition.REVIEW,
                "UNKNOWN_DEFINITION",
                operation,
                "missing definition"));
        };

        try (LoreItemHandoffStore store = new LoreItemHandoffStore(tempDir.resolve("review.db"))) {
            LoreItemHandoffCoordinator coordinator = new LoreItemHandoffCoordinator(
                store, client, Runnable::run, () -> 1000L);

            LoreItemHandoffRecord first = coordinator.handoff(player, REWARD, ACTION, "missing")
                .toCompletableFuture().join();
            LoreItemHandoffRecord replay = coordinator.handoff(player, REWARD, ACTION, "missing")
                .toCompletableFuture().join();

            assertEquals(LoreItemHandoffState.REVIEW, first.state());
            assertEquals(LoreItemHandoffState.REVIEW, replay.state());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void automaticRetryLimitMovesHandoffToReviewAndStaffRetryCanTryAgain() throws Exception {
        UUID player = UUID.fromString(PLAYER_ID);
        AtomicLong clock = new AtomicLong(1000L);
        AtomicInteger calls = new AtomicInteger();
        LoreItemsClient client = (definition, playerId, operation) -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(new LoreItemsGatewayResult(
                LoreItemsGatewayResult.Disposition.RETRY,
                "SERVICE_UNAVAILABLE",
                operation,
                "offline"));
        };

        try (LoreItemHandoffStore store = new LoreItemHandoffStore(tempDir.resolve("retry-limit.db"))) {
            LoreItemHandoffCoordinator coordinator = new LoreItemHandoffCoordinator(
                store, client, Runnable::run, clock::get, 2);

            LoreItemHandoffRecord first = coordinator.handoff(player, REWARD, ACTION, HOURGLASS)
                .toCompletableFuture().join();
            clock.set(first.nextAttemptAtEpochMillis());
            LoreItemHandoffRecord exhausted = coordinator.handoff(player, REWARD, ACTION, HOURGLASS)
                .toCompletableFuture().join();

            assertEquals(2, calls.get());
            assertEquals(2, exhausted.attempts());
            assertEquals(LoreItemHandoffState.REVIEW, exhausted.state());
            assertEquals(0L, exhausted.nextAttemptAtEpochMillis());
            assertEquals(java.util.List.of(), store.listDue(clock.get(), 10));

            LoreItemHandoffRecord staffRetry = store.requestRetry(player, REWARD, ACTION, clock.get());
            assertEquals("STAFF_RETRY_REQUESTED", staffRetry.lastOutcome());
            LoreItemHandoffRecord afterStaffAttempt = coordinator.handoff(player, REWARD, ACTION, HOURGLASS)
                .toCompletableFuture().join();
            assertEquals(3, calls.get());
            assertEquals(3, afterStaffAttempt.attempts());
            assertEquals(LoreItemHandoffState.REVIEW, afterStaffAttempt.state());
        }
    }

    @Test
    void retrySweepKeepsSuccessfulRecordsWhenAnotherRecordThrows() throws Exception {
        UUID firstPlayer = UUID.fromString(PLAYER_ID);
        UUID secondPlayer = UUID.fromString("11111111-2222-3333-4444-555555555555");
        AtomicInteger failures = new AtomicInteger();
        LoreItemsClient client = (definition, playerId, operation) -> {
            if ("boom".equals(definition)) {
                throw new IllegalStateException("synthetic retry failure");
            }
            return CompletableFuture.completedFuture(new LoreItemsGatewayResult(
                LoreItemsGatewayResult.Disposition.ACCEPTED,
                "ACCEPTED_QUEUED",
                operation,
                "accepted"));
        };

        try (LoreItemHandoffStore store = new LoreItemHandoffStore(tempDir.resolve("isolated-retry.db"))) {
            store.prepare(firstPlayer, "reward-a", ACTION, "boom", 1000L);
            LoreItemHandoffRecord success = store.prepare(secondPlayer, "reward-b", ACTION, "star", 1001L);
            LoreItemHandoffCoordinator coordinator = new LoreItemHandoffCoordinator(
                store, client, Runnable::run, () -> 2000L);

            java.util.List<LoreItemHandoffRecord> completed = coordinator.retryDue(10, ignored -> failures.incrementAndGet())
                .toCompletableFuture().join();

            assertEquals(1, failures.get());
            assertEquals(1, completed.size());
            assertEquals(success.externalOperationId(), completed.getFirst().externalOperationId());
            assertEquals(LoreItemHandoffState.ACCEPTED, completed.getFirst().state());
        }
    }

    @Test
    void retryBackoffIsBounded() {
        assertEquals(5_000L, LoreItemHandoffCoordinator.backoffMillis(1));
        assertEquals(10_000L, LoreItemHandoffCoordinator.backoffMillis(2));
        assertEquals(300_000L, LoreItemHandoffCoordinator.backoffMillis(20));
    }
}
