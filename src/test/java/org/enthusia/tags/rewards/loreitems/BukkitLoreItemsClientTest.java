package org.enthusia.tags.rewards.loreitems;

import net.enthusia.loreitems.api.v1.LoreDeliveryResult;
import net.enthusia.loreitems.api.v1.LoreDeliveryStatus;
import net.enthusia.loreitems.api.v1.LoreItemsServiceV1;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BukkitLoreItemsClientTest {
    private static final UUID PLAYER = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final String OPERATION = "enthusiatags:loreitem:v1:test";

    @Test
    void acceptedQueuedIsAccepted() {
        LoreItemsGatewayResult result = queueWithStatus(LoreDeliveryStatus.ACCEPTED_QUEUED);

        assertEquals(LoreItemsGatewayResult.Disposition.ACCEPTED, result.disposition());
        assertEquals(LoreDeliveryStatus.ACCEPTED_QUEUED.name(), result.serviceStatus());
    }

    @Test
    void alreadyAcceptedIsIdempotentSuccess() {
        LoreItemsGatewayResult result = queueWithStatus(LoreDeliveryStatus.ALREADY_ACCEPTED);

        assertEquals(LoreItemsGatewayResult.Disposition.ACCEPTED, result.disposition());
    }

    @Test
    void unavailableAndExceptionalStagesRemainRetryable() {
        BukkitLoreItemsClient missing = new BukkitLoreItemsClient(() -> null);
        assertEquals(LoreItemsGatewayResult.Disposition.RETRY,
            missing.queue("definition", PLAYER, OPERATION).toCompletableFuture().join().disposition());

        LoreItemsServiceV1 failing = (definition, player, operation) ->
            CompletableFuture.failedFuture(new IllegalStateException("reload"));
        BukkitLoreItemsClient exceptional = new BukkitLoreItemsClient(() -> failing);
        LoreItemsGatewayResult result = exceptional.queue("definition", PLAYER, OPERATION)
            .toCompletableFuture().join();

        assertEquals(LoreItemsGatewayResult.Disposition.RETRY, result.disposition());
        assertEquals("ASYNC_FAILURE", result.serviceStatus());
    }

    @Test
    void queueReturnsWithoutWaitingForServiceCompletion() {
        CompletableFuture<LoreDeliveryResult> serviceStage = new CompletableFuture<>();
        LoreItemsServiceV1 service = (definition, player, operation) -> serviceStage;
        BukkitLoreItemsClient client = new BukkitLoreItemsClient(() -> service, 5_000L);

        CompletableFuture<LoreItemsGatewayResult> result = client
            .queue("definition", PLAYER, OPERATION)
            .toCompletableFuture();

        assertFalse(result.isDone());
        serviceStage.complete(new LoreDeliveryResult(
            LoreDeliveryStatus.ACCEPTED_QUEUED,
            OPERATION,
            "accepted"));
        assertEquals(LoreItemsGatewayResult.Disposition.ACCEPTED, result.join().disposition());
    }

    @Test
    void stalledServiceStageTimesOutAsRetryable() {
        LoreItemsServiceV1 service = (definition, player, operation) -> new CompletableFuture<>();
        BukkitLoreItemsClient client = new BukkitLoreItemsClient(() -> service, 25L);

        LoreItemsGatewayResult result = client.queue("definition", PLAYER, OPERATION)
            .toCompletableFuture().join();

        assertEquals(LoreItemsGatewayResult.Disposition.RETRY, result.disposition());
        assertEquals("TIMEOUT", result.serviceStatus());
        assertTrue(result.detail().toLowerCase().contains("timeout"));
    }

    @Test
    void unknownDefinitionAndValidationFailuresRequireReview() {
        assertEquals(LoreItemsGatewayResult.Disposition.REVIEW,
            queueWithStatus(LoreDeliveryStatus.UNKNOWN_DEFINITION).disposition());
        assertEquals(LoreItemsGatewayResult.Disposition.REVIEW,
            queueWithStatus(LoreDeliveryStatus.VALIDATION_FAILURE).disposition());
    }

    @Test
    void mismatchedOperationIdIsNeverAccepted() {
        LoreItemsServiceV1 service = (definition, player, operation) ->
            CompletableFuture.completedFuture(new LoreDeliveryResult(
                LoreDeliveryStatus.ACCEPTED_QUEUED,
                "different-operation",
                "accepted"));
        BukkitLoreItemsClient client = new BukkitLoreItemsClient(() -> service);

        LoreItemsGatewayResult result = client.queue("definition", PLAYER, OPERATION)
            .toCompletableFuture().join();

        assertEquals(LoreItemsGatewayResult.Disposition.REVIEW, result.disposition());
        assertEquals("OPERATION_ID_MISMATCH", result.serviceStatus());
    }

    private static LoreItemsGatewayResult queueWithStatus(LoreDeliveryStatus status) {
        LoreItemsServiceV1 service = new LoreItemsServiceV1() {
            @Override
            public CompletionStage<LoreDeliveryResult> queueDelivery(
                String definitionKey,
                UUID playerId,
                String externalOperationId) {
                return CompletableFuture.completedFuture(new LoreDeliveryResult(
                    status,
                    externalOperationId,
                    "test"));
            }
        };
        return new BukkitLoreItemsClient(() -> service)
            .queue("definition", PLAYER, OPERATION)
            .toCompletableFuture().join();
    }
}
