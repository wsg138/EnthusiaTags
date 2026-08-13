package org.enthusia.tags.rewards.loreitems;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface LoreItemsClient {
    CompletionStage<LoreItemsGatewayResult> queue(
        String definitionKey,
        UUID playerId,
        String externalOperationId);
}
