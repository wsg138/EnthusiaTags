package org.enthusia.tags.rewards.loreitems;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

public final class LoreItemHandoffCoordinator {
    static final int MAX_RETRY_BATCH = 50;
    static final long BASE_RETRY_MILLIS = 5_000L;
    static final long MAX_RETRY_MILLIS = 300_000L;

    private final LoreItemHandoffStore store;
    private final LoreItemsClient client;
    private final Executor ioExecutor;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, CompletableFuture<LoreItemHandoffRecord>> inFlight =
        new ConcurrentHashMap<>();

    public LoreItemHandoffCoordinator(
        LoreItemHandoffStore store,
        LoreItemsClient client,
        Executor ioExecutor) {
        this(store, client, ioExecutor, System::currentTimeMillis);
    }

    LoreItemHandoffCoordinator(
        LoreItemHandoffStore store,
        LoreItemsClient client,
        Executor ioExecutor,
        LongSupplier clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.client = Objects.requireNonNull(client, "client");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public CompletionStage<LoreItemHandoffRecord> handoff(
        UUID playerId,
        String rewardId,
        String actionId,
        String definitionKey) {
        String operationId = LoreItemOperationKey.forRewardAction(playerId, rewardId, actionId);
        return inFlight.computeIfAbsent(operationId, ignored -> {
            CompletableFuture<LoreItemHandoffRecord> future = CompletableFuture
                .supplyAsync(() -> prepare(playerId, rewardId, actionId, definitionKey), ioExecutor)
                .thenCompose(this::submitIfDue)
                .toCompletableFuture();
            future.whenComplete((result, failure) -> inFlight.remove(operationId, future));
            return future;
        });
    }

    public CompletionStage<List<LoreItemHandoffRecord>> retryDue(int requestedLimit) {
        int limit = Math.max(0, Math.min(requestedLimit, MAX_RETRY_BATCH));
        if (limit == 0) {
            return CompletableFuture.completedFuture(List.of());
        }
        return CompletableFuture.supplyAsync(() -> listDue(limit), ioExecutor)
            .thenCompose(records -> {
                List<CompletableFuture<LoreItemHandoffRecord>> retries = new ArrayList<>();
                for (LoreItemHandoffRecord record : records) {
                    retries.add(handoff(
                        record.playerId(),
                        record.rewardId(),
                        record.actionId(),
                        record.definitionKey()).toCompletableFuture());
                }
                return CompletableFuture.allOf(retries.toArray(CompletableFuture[]::new))
                    .thenApply(ignored -> retries.stream().map(CompletableFuture::join).toList());
            });
    }

    private CompletionStage<LoreItemHandoffRecord> submitIfDue(LoreItemHandoffRecord record) {
        long now = clock.getAsLong();
        if (record.state() == LoreItemHandoffState.ACCEPTED
            || record.state() == LoreItemHandoffState.REVIEW
            || !record.isRetryable(now)) {
            return CompletableFuture.completedFuture(record);
        }
        return client.queue(
                record.definitionKey(),
                record.playerId(),
                record.externalOperationId())
            .handleAsync((result, failure) -> persistResult(record, result, failure), ioExecutor);
    }

    private LoreItemHandoffRecord persistResult(
        LoreItemHandoffRecord record,
        LoreItemsGatewayResult result,
        Throwable failure) {
        long now = clock.getAsLong();
        LoreItemHandoffState nextState;
        String outcome;
        String detail;
        long nextAttempt;

        if (failure != null) {
            nextState = LoreItemHandoffState.RETRY;
            outcome = "CLIENT_STAGE_FAILURE";
            detail = safeMessage(failure);
            nextAttempt = now + backoffMillis(record.attempts() + 1);
        } else if (result == null) {
            nextState = LoreItemHandoffState.RETRY;
            outcome = "NULL_CLIENT_RESULT";
            detail = "LoreItems client completed without a result";
            nextAttempt = now + backoffMillis(record.attempts() + 1);
        } else {
            outcome = result.serviceStatus();
            detail = result.detail();
            switch (result.disposition()) {
                case ACCEPTED -> {
                    nextState = LoreItemHandoffState.ACCEPTED;
                    nextAttempt = 0L;
                }
                case RETRY -> {
                    nextState = LoreItemHandoffState.RETRY;
                    nextAttempt = now + backoffMillis(record.attempts() + 1);
                }
                case REVIEW -> {
                    nextState = LoreItemHandoffState.REVIEW;
                    nextAttempt = 0L;
                }
                default -> throw new IllegalStateException("Unhandled LoreItems disposition");
            }
        }

        try {
            return store.recordOutcome(
                record.externalOperationId(),
                nextState,
                outcome,
                detail,
                nextAttempt,
                now);
        } catch (SQLException ex) {
            throw new LoreItemHandoffException("Could not persist LoreItems handoff outcome", ex);
        }
    }

    private LoreItemHandoffRecord prepare(
        UUID playerId,
        String rewardId,
        String actionId,
        String definitionKey) {
        try {
            return store.prepare(playerId, rewardId, actionId, definitionKey, clock.getAsLong());
        } catch (SQLException ex) {
            throw new LoreItemHandoffException("Could not persist LoreItems handoff intent", ex);
        }
    }

    private List<LoreItemHandoffRecord> listDue(int limit) {
        try {
            return store.listDue(clock.getAsLong(), limit);
        } catch (SQLException ex) {
            throw new LoreItemHandoffException("Could not load pending LoreItems handoffs", ex);
        }
    }

    static long backoffMillis(int attemptNumber) {
        int exponent = Math.max(0, Math.min(attemptNumber - 1, 16));
        long delay;
        try {
            delay = Math.multiplyExact(BASE_RETRY_MILLIS, 1L << exponent);
        } catch (ArithmeticException ex) {
            delay = MAX_RETRY_MILLIS;
        }
        return Math.min(delay, MAX_RETRY_MILLIS);
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    public static final class LoreItemHandoffException extends RuntimeException {
        public LoreItemHandoffException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
