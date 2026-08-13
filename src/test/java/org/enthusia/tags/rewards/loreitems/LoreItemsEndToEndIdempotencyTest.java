package org.enthusia.tags.rewards.loreitems;

import net.enthusia.loreitems.api.v1.LoreDeliveryResult;
import net.enthusia.loreitems.api.v1.LoreDeliveryStatus;
import net.enthusia.loreitems.api.v1.LoreItemsServiceV1;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LoreItemsEndToEndIdempotencyTest {
    private static final UUID PLAYER = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final String REWARD = "reward";
    private static final String ACTION = "lore-hourglass";
    private static final String DEFINITION = "hourglass";

    @TempDir
    Path tempDir;

    @Test
    void repeatedLogicalClaimAfterAcceptanceDoesNotIssueAnotherServiceRequest() throws Exception {
        CountingIdempotentService service = new CountingIdempotentService();
        BukkitLoreItemsClient client = new BukkitLoreItemsClient(() -> service);

        try (LoreItemHandoffStore store = new LoreItemHandoffStore(tempDir.resolve("repeated.db"))) {
            LoreItemHandoffCoordinator coordinator = new LoreItemHandoffCoordinator(
                store, client, Runnable::run, () -> 1000L);

            LoreItemHandoffRecord first = coordinator.handoff(PLAYER, REWARD, ACTION, DEFINITION)
                .toCompletableFuture().join();
            LoreItemHandoffRecord replay = coordinator.handoff(PLAYER, REWARD, ACTION, DEFINITION)
                .toCompletableFuture().join();

            assertEquals(LoreItemHandoffState.ACCEPTED, first.state());
            assertEquals(first.externalOperationId(), replay.externalOperationId());
            assertEquals(1, service.invocations());
            assertEquals(1, service.physicalAwards());
        }
    }

    @Test
    void crashAfterServiceAcceptanceReplaysSameOperationWithoutSecondPhysicalAward() throws Exception {
        Path database = tempDir.resolve("crash-after-acceptance.db");
        CountingIdempotentService service = new CountingIdempotentService();
        BukkitLoreItemsClient client = new BukkitLoreItemsClient(() -> service);
        String operationId;

        try (LoreItemHandoffStore beforeCrash = new LoreItemHandoffStore(database)) {
            LoreItemHandoffRecord prepared = beforeCrash.prepare(PLAYER, REWARD, ACTION, DEFINITION, 1000L);
            operationId = prepared.externalOperationId();
            LoreItemsGatewayResult accepted = client.queue(DEFINITION, PLAYER, operationId)
                .toCompletableFuture().join();
            assertEquals(LoreItemsGatewayResult.Disposition.ACCEPTED, accepted.disposition());
            // Simulate process loss before Tags records the returned acceptance.
        }

        try (LoreItemHandoffStore afterRestart = new LoreItemHandoffStore(database)) {
            LoreItemHandoffCoordinator coordinator = new LoreItemHandoffCoordinator(
                afterRestart, client, Runnable::run, () -> 2000L);
            LoreItemHandoffRecord recovered = coordinator.handoff(PLAYER, REWARD, ACTION, DEFINITION)
                .toCompletableFuture().join();

            assertEquals(LoreItemHandoffState.ACCEPTED, recovered.state());
            assertEquals(operationId, recovered.externalOperationId());
            assertEquals("ALREADY_ACCEPTED", recovered.lastOutcome());
            assertEquals(2, service.invocations());
            assertEquals(1, service.physicalAwards());
        }
    }

    private static final class CountingIdempotentService implements LoreItemsServiceV1 {
        private final Set<String> acceptedOperations = new HashSet<>();
        private int invocations;
        private int physicalAwards;

        @Override
        public CompletionStage<LoreDeliveryResult> queueDelivery(
            String definitionKey,
            UUID playerId,
            String externalOperationId) {
            invocations++;
            boolean firstAcceptance = acceptedOperations.add(externalOperationId);
            if (firstAcceptance) {
                physicalAwards++;
            }
            LoreDeliveryStatus status = firstAcceptance
                ? LoreDeliveryStatus.ACCEPTED_QUEUED
                : LoreDeliveryStatus.ALREADY_ACCEPTED;
            return CompletableFuture.completedFuture(new LoreDeliveryResult(
                status,
                externalOperationId,
                firstAcceptance ? "accepted" : "already accepted"));
        }

        int invocations() {
            return invocations;
        }

        int physicalAwards() {
            return physicalAwards;
        }
    }
}
