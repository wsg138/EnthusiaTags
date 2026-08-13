#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: str, old: str, new: str, label: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one target, found {count}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


# Coordinator: configured attempt ceiling + per-record batch isolation.
path = "src/main/java/org/enthusia/tags/rewards/loreitems/LoreItemHandoffCoordinator.java"
replace_once(path,
'''    static final int MAX_RETRY_BATCH = 50;
    static final long BASE_RETRY_MILLIS = 5_000L;
    static final long MAX_RETRY_MILLIS = 300_000L;
''',
'''    static final int MAX_RETRY_BATCH = 50;
    static final int DEFAULT_MAX_AUTOMATIC_ATTEMPTS = 48;
    static final long BASE_RETRY_MILLIS = 5_000L;
    static final long MAX_RETRY_MILLIS = 300_000L;
''', "coordinator constants")
replace_once(path,
'''    private final Executor ioExecutor;
    private final LongSupplier clock;
''',
'''    private final Executor ioExecutor;
    private final LongSupplier clock;
    private final int maxAutomaticAttempts;
''', "coordinator field")
replace_once(path,
'''    public LoreItemHandoffCoordinator(
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
''',
'''    public LoreItemHandoffCoordinator(
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
''', "coordinator constructors")
replace_once(path,
'''        for (LoreItemHandoffRecord record : records) {
            retries.add(handoff(
                record.playerId(),
                record.rewardId(),
                record.actionId(),
                record.definitionKey()).toCompletableFuture());
        }
''',
'''        for (LoreItemHandoffRecord record : records) {
            retries.add(isolateRetry(record));
        }
''', "retry batch loop")
replace_once(path,
'''        return CompletableFuture.allOf(retries.toArray(CompletableFuture[]::new))
            .thenApply(ignored -> retries.stream().map(CompletableFuture::join).toList());
    }

    private CompletionStage<LoreItemHandoffRecord> submitIfDue(LoreItemHandoffRecord record) {
''',
'''        return CompletableFuture.allOf(retries.toArray(CompletableFuture[]::new))
            .thenApply(ignored -> retries.stream().map(CompletableFuture::join).toList());
    }

    private CompletableFuture<LoreItemHandoffRecord> isolateRetry(LoreItemHandoffRecord record) {
        try {
            return handoff(
                record.playerId(),
                record.rewardId(),
                record.actionId(),
                record.definitionKey())
                .handle((result, failure) -> failure == null ? result : record)
                .toCompletableFuture();
        } catch (RuntimeException ignored) {
            return CompletableFuture.completedFuture(record);
        }
    }

    private CompletionStage<LoreItemHandoffRecord> submitIfDue(LoreItemHandoffRecord record) {
''', "retry isolation helper")
replace_once(path,
'''        if (record.state() == LoreItemHandoffState.ACCEPTED
            || record.state() == LoreItemHandoffState.REVIEW
            || !record.isRetryable(now)) {
            return CompletableFuture.completedFuture(record);
        }
        return client.queue(
''',
'''        if (record.state() == LoreItemHandoffState.ACCEPTED
            || record.state() == LoreItemHandoffState.REVIEW
            || !record.isRetryable(now)) {
            return CompletableFuture.completedFuture(record);
        }
        if (record.attempts() >= maxAutomaticAttempts) {
            return CompletableFuture.completedFuture(markRetryLimitReached(record, now));
        }
        return client.queue(
''', "retry ceiling check")
replace_once(path,
'''    private LoreItemHandoffRecord persistResult(
''',
'''    private LoreItemHandoffRecord markRetryLimitReached(LoreItemHandoffRecord record, long now) {
        try {
            return store.markReview(
                record.externalOperationId(),
                "RETRY_LIMIT_REACHED",
                "Automatic LoreItems retry limit reached after " + record.attempts()
                    + " attempts; staff review or explicit loreretry is required",
                now);
        } catch (SQLException ex) {
            throw new LoreItemHandoffException("Could not persist LoreItems retry-limit review state", ex);
        }
    }

    private LoreItemHandoffRecord persistResult(
''', "retry ceiling persistence")

# Store: stable REVIEW transition without incrementing service attempt count.
path = "src/main/java/org/enthusia/tags/rewards/loreitems/LoreItemHandoffStore.java"
replace_once(path,
'''    public synchronized LoreItemHandoffRecord requestRetry(
''',
'''    public synchronized LoreItemHandoffRecord markReview(
        String externalOperationId,
        String outcome,
        String detail,
        long nowEpochMillis) throws SQLException {
        String operationId = requiredText(externalOperationId, "externalOperationId");
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE lore_item_handoffs
               SET state = 'REVIEW', last_outcome = ?, last_error = ?, next_attempt_at = 0, updated_at = ?
             WHERE external_operation_id = ? AND reward_finalized = 0
            """)) {
            statement.setString(1, outcome == null ? "" : outcome);
            statement.setString(2, detail == null ? "" : detail);
            statement.setLong(3, nowEpochMillis);
            statement.setString(4, operationId);
            statement.executeUpdate();
        }
        LoreItemHandoffRecord updated = loadByOperationId(operationId);
        if (updated == null) {
            throw new SQLException("Lore-item handoff operation was not found: " + operationId);
        }
        return updated;
    }

    public synchronized LoreItemHandoffRecord requestRetry(
''', "store review transition")

# Runtime: read configured max attempts and expose finalization-review transition.
path = "src/main/java/org/enthusia/tags/rewards/loreitems/LoreItemRewardRuntime.java"
replace_once(path,
'''        LoreItemHandoffCoordinator coordinator = new LoreItemHandoffCoordinator(
            store,
            new ReloadingLoreItemsClient(plugin),
            executor);
''',
'''        int maxAutomaticAttempts = Math.max(1, plugin.getConfig().getInt(
            "rewards.lore-items.max-auto-attempts",
            LoreItemHandoffCoordinator.DEFAULT_MAX_AUTOMATIC_ATTEMPTS));
        LoreItemHandoffCoordinator coordinator = new LoreItemHandoffCoordinator(
            store,
            new ReloadingLoreItemsClient(plugin),
            executor,
            System::currentTimeMillis,
            maxAutomaticAttempts);
''', "runtime configured attempt ceiling")
replace_once(path,
'''    public CompletionStage<Void> markRewardFinalized(String externalOperationId) {
''',
'''    public CompletionStage<LoreItemHandoffRecord> markReview(
        String externalOperationId,
        String outcome,
        String detail) {
        if (!open.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException(RUNTIME_CLOSED));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return store.markReview(externalOperationId, outcome, detail, System.currentTimeMillis());
            } catch (SQLException ex) {
                throw new LoreItemHandoffCoordinator.LoreItemHandoffException(
                    "Could not persist LoreItems review state", ex);
            }
        }, executor);
    }

    public CompletionStage<Void> markRewardFinalized(String externalOperationId) {
''', "runtime review API")

# Config: explicit automatic-attempt ceiling.
path = "src/main/resources/config.yml"
replace_once(path,
'''rewards:
  global-scan:
''',
'''rewards:
  lore-items:
    # Automatic handoff retries stop here and move to staff REVIEW. Explicit loreretry can resume.
    max-auto-attempts: 48
  global-scan:
''', "config retry ceiling")

# RewardService: changed/missing configuration moves accepted handoff to stable REVIEW.
path = "src/main/java/org/enthusia/tags/rewards/RewardService.java"
replace_once(path,
'''            if (action == null) {
                plugin.getLogger().warning(
                    "Accepted LoreItems handoff needs staff review because its Tags reward/action changed: "
                        + record.externalOperationId());
                return;
            }
''',
'''            if (action == null) {
                moveAcceptedLoreItemToReview(record,
                    "Tags reward/action/definition changed after LoreItems accepted the operation");
                return;
            }
''', "finalization config drift")
replace_once(path,
'''    private RewardAction findLoreItemAction(RewardDefinition reward, LoreItemHandoffRecord record) {
''',
'''    private void moveAcceptedLoreItemToReview(LoreItemHandoffRecord record, String detail) {
        try {
            loreItemRewardRuntime.markReview(
                record.externalOperationId(),
                "TAGS_CONFIG_MISMATCH",
                detail)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
            plugin.getLogger().warning(
                "Accepted LoreItems handoff moved to staff review: " + record.externalOperationId()
                    + " (" + detail + ")");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            plugin.getLogger().warning(
                "Interrupted while moving accepted LoreItems handoff to review: "
                    + record.externalOperationId());
        } catch (ExecutionException | TimeoutException ex) {
            plugin.getLogger().warning(
                "Accepted LoreItems handoff could not be moved to review and remains pending finalization: "
                    + record.externalOperationId() + ": " + safeThrowableMessage(ex));
        }
    }

    private RewardAction findLoreItemAction(RewardDefinition reward, LoreItemHandoffRecord record) {
''', "finalization review helper")

# Coordinator tests: prove retry ceiling becomes stable REVIEW.
path = "src/test/java/org/enthusia/tags/rewards/loreitems/LoreItemHandoffCoordinatorTest.java"
replace_once(path,
'''    @Test
    void retryBackoffIsBounded() {
''',
'''    @Test
    void automaticRetryLimitMovesHandoffToReviewWithoutAnotherServiceCall() throws Exception {
        UUID player = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
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

            LoreItemHandoffRecord first = coordinator.handoff(player, REWARD, ACTION, "hourglass")
                .toCompletableFuture().join();
            clock.set(first.nextAttemptAtEpochMillis());
            LoreItemHandoffRecord second = coordinator.handoff(player, REWARD, ACTION, "hourglass")
                .toCompletableFuture().join();
            clock.set(second.nextAttemptAtEpochMillis());
            LoreItemHandoffRecord exhausted = coordinator.handoff(player, REWARD, ACTION, "hourglass")
                .toCompletableFuture().join();

            assertEquals(2, calls.get());
            assertEquals(2, exhausted.attempts());
            assertEquals(LoreItemHandoffState.REVIEW, exhausted.state());
            assertEquals("RETRY_LIMIT_REACHED", exhausted.lastOutcome());
            assertEquals(0L, exhausted.nextAttemptAtEpochMillis());
            assertEquals(List.of(), store.listDue(clock.get(), 10));
        }
    }

    @Test
    void retryBackoffIsBounded() {
''', "retry ceiling test")

# Adapter test: explicitly cover wrapped timeout classification.
path = "src/test/java/org/enthusia/tags/rewards/loreitems/BukkitLoreItemsClientTest.java"
replace_once(path,
'''    @Test
    void unknownDefinitionAndValidationFailuresRequireReview() {
''',
'''    @Test
    void wrappedTimeoutFailureIsStillClassifiedAsTimeout() {
        LoreItemsServiceV1 service = (definition, player, operation) ->
            CompletableFuture.failedFuture(new java.util.concurrent.CompletionException(
                new java.util.concurrent.TimeoutException("wrapped timeout")));
        BukkitLoreItemsClient client = new BukkitLoreItemsClient(() -> service);

        LoreItemsGatewayResult result = client.queue(DEFINITION, PLAYER, OPERATION)
            .toCompletableFuture().join();

        assertEquals(LoreItemsGatewayResult.Disposition.RETRY, result.disposition());
        assertEquals("TIMEOUT", result.serviceStatus());
    }

    @Test
    void unknownDefinitionAndValidationFailuresRequireReview() {
''', "wrapped timeout test")
