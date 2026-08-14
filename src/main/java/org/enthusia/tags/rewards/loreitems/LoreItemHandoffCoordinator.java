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
import java.util.function.Consumer;
import java.util.function.LongSupplier;

public final class LoreItemHandoffCoordinator {
    static final int MAX_RETRY_BATCH = 50;
    static final int DEFAULT_MAX_AUTOMATIC_ATTEMPTS = 48;
    private static final String STAFF_RETRY_REQUESTED = "STAFF_RETRY_REQUESTED";
    static final long BASE_RETRY_MILLIS = 5_000L;
    static final long MAX_RETRY_MILLIS = 300_000L;

    private final LoreItemHandoffStore store;
    private final LoreItemsClient client;
    private final Executor ioExecutor;
    private final LongSupplier clock;
    private final int maxAutomaticAttempts;
    private final ConcurrentHashMap<String, CompletableFuture<LoreItemHandoffRecord>> inFlight =
        new ConcurrentHashMap<>();

    public LoreItemHandoffCoordinator(
        LoreItemHandoffStore store,
        LoreItemsClient client,
        Executor ioExecutor) {
        this(store, client, ioExecutor, System::currentTimeMillis, DEFAULT_MAX_AUTOMATIC_ATTEMPTS);
    }

    LoreItemHandoffCoordinator(
        LoreItemHandoffStore store,
        LoreItemsClient client,
        Executor ioExecutor,
        LongSupplier clock) {
        this(store, client, ioExecutor, clock, DEFAULT_MAX_AUTOMATIC_ATTEMPTS);
    }

    LoreItemHandoffCoordinator(
        LoreItemHandoffStore store,
        LoreItemsClient client,
        Executor ioExecutor,
        LongSupplier clock,
        int maxAutomaticAttempts) {
        this.store = Objects.requireNonNull(store, "store");
        this.client = Objects.requireNonNull(client, "client");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxAutomaticAttempts < 1) {
            throw new IllegalArgumentException("maxAutomaticAttempts must be positive");
        }
        this.maxAutomaticAttempts = maxAutomaticAttempts;
    }

    public CompletionStage<LoreItemHandoffRecord> handoff(
        UUID playerId,
        String rewardId,
        String actionId,
        String definitionKey) {
        String operationId = LoreItemOperationKey.forRewardAction(playerId, rewardId, actionId);
        CompletableFuture<LoreItemHandoffRecord> reserved = new CompletableFuture<>();
        CompletableFuture<LoreItemHandoffRecord> existing = inFlight.putIfAbsent(operationId, reserved);
        if (existing != null) {
            return existing;
        }
        startHandoff(operationId, reserved, playerId, rewardId, actionId, definitionKey);
        return reserved;
    }

    private void startHandoff(
        String operationId,
        CompletableFuture<LoreItemHandoffRecord> reserved,
        UUID playerId,
        String rewardId,
        String actionId,
        String definitionKey) {
        try {
            CompletableFuture
                .supplyAsync(() -> prepare(playerId, rewardId, actionId, definitionKey), ioExecutor)
                .thenCompose(this::submitIfDue)
                .whenComplete((result, failure) -> completeHandoff(operationId, reserved, result, failure));
        } catch (RuntimeException ex) {
            reserved.completeExceptionally(ex);
            inFlight.remove(operationId, reserved);
        }
    }

    private void completeHandoff(
        String operationId,
        CompletableFuture<LoreItemHandoffRecord> reserved,
        LoreItemHandoffRecord result,
        Throwable failure) {
        if (failure == null) {
            reserved.complete(result);
        } else {
            reserved.completeExceptionally(failure);
        }
        inFlight.remove(operationId, reserved);
    }

    public CompletionStage<List<LoreItemHandoffRecord>> retryDue(int requestedLimit) {
        return retryDue(requestedLimit, ignored -> { });
    }

    public CompletionStage<List<LoreItemHandoffRecord>> retryDue(
        int requestedLimit,
        Consumer<Throwable> failureHandler) {
        Objects.requireNonNull(failureHandler, "failureHandler");
        int limit = Math.max(0, Math.min(requestedLimit, MAX_RETRY_BATCH));
        if (limit == 0) {
            return CompletableFuture.completedFuture(List.of());
        }
        return CompletableFuture.supplyAsync(() -> listDue(limit), ioExecutor)
            .thenCompose(records -> submitRetries(records, failureHandler));
    }

    private CompletionStage<List<LoreItemHandoffRecord>> submitRetries(
        List<LoreItemHandoffRecord> records,
        Consumer<Throwable> failureHandler) {
        List<CompletableFuture<LoreItemHandoffRecord>> retries = new ArrayList<>(records.size());
        for (LoreItemHandoffRecord record : records) {
            retries.add(isolateRetry(record, failureHandler));
        }
        return CompletableFuture.allOf(retries.toArray(CompletableFuture[]::new))
            .thenApply(ignored -> retries.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .toList());
    }

    private CompletableFuture<LoreItemHandoffRecord> isolateRetry(
        LoreItemHandoffRecord record,
        Consumer<Throwable> failureHandler) {
        try {
            return handoff(
                record.playerId(),
                record.rewardId(),
                record.actionId(),
                record.definitionKey())
                .handle((result, failure) -> {
                    if (failure != null) {
                        reportRetryFailure(failureHandler, failure);
                        return null;
                    }
                    return result;
                })
                .toCompletableFuture();
        } catch (RuntimeException ex) {
            reportRetryFailure(failureHandler, ex);
            return CompletableFuture.completedFuture(null);
        }
    }

    private static void reportRetryFailure(Consumer<Throwable> failureHandler, Throwable failure) {
        try {
            failureHandler.accept(failure);
        } catch (RuntimeException ignored) {
            // A diagnostics callback must never make another durable handoff fail.
        }
    }

    private CompletionStage<LoreItemHandoffRecord> submitIfDue(LoreItemHandoffRecord record) {
        long now = clock.getAsLong();
        if (record.state() == LoreItemHandoffState.ACCEPTED
            || record.state() == LoreItemHandoffState.REVIEW
            || !record.isRetryable(now)) {
            return CompletableFuture.completedFuture(record);
        }
        if (record.attempts() >= maxAutomaticAttempts
            && !STAFF_RETRY_REQUESTED.equals(record.lastOutcome())) {
            return CompletableFuture.completedFuture(markRetryLimitReached(record, now));
        }
        return client.queue(
                record.definitionKey(),
                record.playerId(),
                record.externalOperationId())
            .handleAsync((result, failure) -> persistResult(record, result, failure), ioExecutor);
    }

    private LoreItemHandoffRecord markRetryLimitReached(
        LoreItemHandoffRecord record,
        long now) {
        try {
            return store.markReview(
                record.externalOperationId(),
                "RETRY_LIMIT_REACHED",
                "Automatic LoreItems retry limit reached after " + record.attempts()
                    + " attempts; staff review or explicit loreretry is required",
                now);
        } catch (SQLException ex) {
            throw new LoreItemHandoffException(
                "Could not persist LoreItems retry-limit review state", ex);
        }
    }

    private LoreItemHandoffRecord persistResult(
        LoreItemHandoffRecord record,
        LoreItemsGatewayResult result,
        Throwable failure) {
        long now = clock.getAsLong();
        if (failure != null) {
            return persistRetryOutcome(record, "CLIENT_STAGE_FAILURE", safeMessage(failure), now);
        }
        if (result == null) {
            return persistRetryOutcome(
                record,
                "NULL_CLIENT_RESULT",
                "LoreItems client completed without a result",
                now);
        }
        return switch (result.disposition()) {
            case ACCEPTED -> persistOutcome(
                record, LoreItemHandoffState.ACCEPTED, result.serviceStatus(), result.detail(), 0L, now);
            case RETRY -> persistRetryOutcome(record, result.serviceStatus(), result.detail(), now);
            case REVIEW -> persistOutcome(
                record, LoreItemHandoffState.REVIEW, result.serviceStatus(), result.detail(), 0L, now);
        };
    }

    private LoreItemHandoffRecord persistRetryOutcome(
        LoreItemHandoffRecord record,
        String outcome,
        String detail,
        long now) {
        int nextAttemptNumber = record.attempts() + 1;
        boolean exhausted = nextAttemptNumber >= maxAutomaticAttempts;
        String persistedDetail = detail;
        if (exhausted) {
            String suffix = "automatic retry limit reached after " + nextAttemptNumber
                + " attempts; staff review or explicit loreretry is required";
            persistedDetail = detail == null || detail.isBlank() ? suffix : detail + "; " + suffix;
        }
        return persistOutcome(
            record,
            exhausted ? LoreItemHandoffState.REVIEW : LoreItemHandoffState.RETRY,
            outcome,
            persistedDetail,
            exhausted ? 0L : now + backoffMillis(nextAttemptNumber),
            now);
    }

    private LoreItemHandoffRecord persistOutcome(
        LoreItemHandoffRecord record,
        LoreItemHandoffState state,
        String outcome,
        String detail,
        long nextAttempt,
        long now) {
        try {
            return store.recordOutcome(
                record.externalOperationId(), state, outcome, detail, nextAttempt, now);
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
        private static final long serialVersionUID = 1L;

        public LoreItemHandoffException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
