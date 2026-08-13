package org.enthusia.tags.rewards.loreitems;

import java.util.Objects;
import java.util.UUID;

public record LoreItemHandoffRecord(
    UUID playerId,
    String rewardId,
    String actionId,
    String definitionKey,
    String externalOperationId,
    LoreItemHandoffState state,
    String lastOutcome,
    int attempts,
    String lastError,
    long nextAttemptAtEpochMillis,
    long createdAtEpochMillis,
    long updatedAtEpochMillis) {

    public LoreItemHandoffRecord {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(rewardId, "rewardId");
        Objects.requireNonNull(actionId, "actionId");
        Objects.requireNonNull(definitionKey, "definitionKey");
        Objects.requireNonNull(externalOperationId, "externalOperationId");
        Objects.requireNonNull(state, "state");
        lastOutcome = lastOutcome == null ? "" : lastOutcome;
        lastError = lastError == null ? "" : lastError;
    }

    public boolean isRetryable(long nowEpochMillis) {
        return (state == LoreItemHandoffState.PENDING || state == LoreItemHandoffState.RETRY)
            && nextAttemptAtEpochMillis <= nowEpochMillis;
    }
}
