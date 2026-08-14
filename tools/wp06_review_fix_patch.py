#!/usr/bin/env python3
from pathlib import Path
import re


def replace_once(path: str, old: str, new: str, label: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one target, found {count}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


def regex_once(path: str, pattern: str, replacement: str, label: str, flags: int = 0) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    updated, count = re.subn(pattern, replacement, text, count=1, flags=flags)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one regex target, found {count}")
    file.write_text(updated, encoding="utf-8")


# Build/release guardrails.
replace_once(
    ".github/workflows/publish-latest.yml",
    "      - name: Test and package\n        run: mvn --batch-mode --no-transfer-progress clean test package\n",
    "      - name: Bootstrap pinned production LoreItems release\n        run: bash tools/bootstrap_loreitems_release.sh\n\n      - name: Test and package\n        run: mvn --batch-mode --no-transfer-progress clean test package\n",
    "publish workflow LoreItems bootstrap",
)

path = "pom.xml"
file = Path(path)
text = file.read_text(encoding="utf-8")
needle = "        <plugins>\n"
if text.count(needle) != 1:
    raise RuntimeError("pom plugins: expected exactly one plugins block")
antrun = """        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-antrun-plugin</artifactId>
                <version>3.1.0</version>
                <executions>
                    <execution>
                        <id>verify-pinned-loreitems-release</id>
                        <phase>validate</phase>
                        <goals>
                            <goal>run</goal>
                        </goals>
                        <configuration>
                            <target>
                                <checksum file="${loreitems.release.jar}" algorithm="SHA-256"
                                          property="loreitems.actual.sha256"/>
                                <condition property="loreitems.release.sha256.matches">
                                    <equals arg1="${loreitems.actual.sha256}"
                                            arg2="${loreitems.release.sha256}"/>
                                </condition>
                                <fail unless="loreitems.release.sha256.matches"
                                      message="Pinned EnthusiaLoreItems release JAR checksum mismatch"/>
                            </target>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
"""
file.write_text(text.replace(needle, antrun, 1), encoding="utf-8")

replace_once(
    "src/test/java/org/enthusia/tags/rewards/loreitems/ReleasedLoreItemsApiContractTest.java",
    "class ReleasedLoreItemsApiContractTest {\n",
    "class ReleasedLoreItemsApiContractTest {\n    private static final String APPROVED_RELEASE_SHA256 =\n        \"7c862b0ae545d710a33267ad6e19a4ae26d97323e97f40707c1475c9f9ba7063\";\n",
    "approved production digest constant",
)
replace_once(
    "src/test/java/org/enthusia/tags/rewards/loreitems/ReleasedLoreItemsApiContractTest.java",
    "        String expectedSha = System.getProperty(\"loreitems.release.sha256\");\n\n        assertEquals(expectedSha, sha256(jar));\n",
    "        String configuredSha = System.getProperty(\"loreitems.release.sha256\");\n\n        assertEquals(APPROVED_RELEASE_SHA256, configuredSha,\n            \"the Maven release pin must match the approved production artifact\");\n        assertEquals(APPROVED_RELEASE_SHA256, sha256(jar),\n            \"the test artifact bytes must match the approved production artifact\");\n",
    "hard-coded production digest assertions",
)

# Shared safe throwable description.
throwable_helper = Path("src/main/java/org/enthusia/tags/rewards/loreitems/ThrowableDescriptions.java")
if throwable_helper.exists():
    raise RuntimeError("ThrowableDescriptions.java unexpectedly already exists")
throwable_helper.write_text(
    """package org.enthusia.tags.rewards.loreitems;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

public final class ThrowableDescriptions {
    private ThrowableDescriptions() {
    }

    public static String describe(Throwable throwable) {
        if (throwable == null) {
            return "Unknown failure";
        }
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = throwable;
        while (visited.add(current)) {
            Throwable cause = current.getCause();
            if (cause == null || visited.contains(cause)) {
                break;
            }
            current = cause;
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
""",
    encoding="utf-8",
)

# Coordinator: retry isolation and automatic attempt ceiling.
replace_once(
    "src/main/java/org/enthusia/tags/rewards/loreitems/LoreItemHandoffCoordinator.java",
    "import java.util.function.LongSupplier;\n",
    "import java.util.function.Consumer;\nimport java.util.function.LongSupplier;\n",
    "coordinator Consumer import",
)
replace_once(
    "src/main/java/org/enthusia/tags/rewards/loreitems/LoreItemHandoffCoordinator.java",
    "    static final int MAX_RETRY_BATCH = 50;\n    static final long BASE_RETRY_MILLIS = 5_000L;\n",
    "    static final int MAX_RETRY_BATCH = 50;\n    static final int DEFAULT_MAX_AUTOMATIC_ATTEMPTS = 48;\n    private static final String STAFF_RETRY_REQUESTED = \"STAFF_RETRY_REQUESTED\";\n    static final long BASE_RETRY_MILLIS = 5_000L;\n",
    "coordinator retry constants",
)
replace_once(
    "src/main/java/org/enthusia/tags/rewards/loreitems/LoreItemHandoffCoordinator.java",
    "    private final Executor ioExecutor;\n    private final LongSupplier clock;\n",
    "    private final Executor ioExecutor;\n    private final LongSupplier clock;\n    private final int maxAutomaticAttempts;\n",
    "coordinator max attempt field",
)
regex_once(
    "src/main/java/org/enthusia/tags/rewards/loreitems/LoreItemHandoffCoordinator.java",
    r"    public LoreItemHandoffCoordinator\(\n        LoreItemHandoffStore store,\n        LoreItemsClient client,\n        Executor ioExecutor\) \{\n        this\(store, client, ioExecutor, System::currentTimeMillis\);\n    \}\n\n    LoreItemHandoffCoordinator\(\n        LoreItemHandoffStore store,\n        LoreItemsClient client,\n        Executor ioExecutor,\n        LongSupplier clock\) \{\n        this\.store = Objects\.requireNonNull\(store, \"store\"\);\n        this\.client = Objects\.requireNonNull\(client, \"client\"\);\n        this\.ioExecutor = Objects\.requireNonNull\(ioExecutor, \"ioExecutor\"\);\n        this\.clock = Objects\.requireNonNull\(clock, \"clock\"\);\n    \}",
    """    public LoreItemHandoffCoordinator(
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
    }""",
    "coordinator constructors",
)
regex_once(
    "src/main/java/org/enthusia/tags/rewards/loreitems/LoreItemHandoffCoordinator.java",
    r"    public CompletionStage<List<LoreItemHandoffRecord>> retryDue\(int requestedLimit\) \{.*?\n    private CompletionStage<LoreItemHandoffRecord> submitIfDue",
    """    public CompletionStage<List<LoreItemHandoffRecord>> retryDue(int requestedLimit) {
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

    private CompletionStage<LoreItemHandoffRecord> submitIfDue""",
    "coordinator isolated retry batch",
    flags=re.S,
)
replace_once(
    "src/main/java/org/enthusia/tags/rewards/loreitems/LoreItemHandoffCoordinator.java",
    "        if (record.state() == LoreItemHandoffState.ACCEPTED\n            || record.state() == LoreItemHandoffState.REVIEW\n            || !record.isRetryable(now)) {\n            return CompletableFuture.completedFuture(record);\n        }\n        return client.queue(\n",
    "        if (record.state() == LoreItemHandoffState.ACCEPTED\n            || record.state() == LoreItemHandoffState.REVIEW\n            || !record.isRetryable(now)) {\n            return CompletableFuture.completedFuture(record);\n        }\n        if (record.attempts() >= maxAutomaticAttempts\n            && !STAFF_RETRY_REQUESTED.equals(record.lastOutcome())) {\n            return CompletableFuture.completedFuture(markRetryLimitReached(record, now));\n        }\n        return client.queue(\n",
    "coordinator pre-submit retry ceiling",
)
regex_once(
    "src/main/java/org/enthusia/tags/rewards/loreitems/LoreItemHandoffCoordinator.java",
    r"    private LoreItemHandoffRecord persistResult\(.*?\n    private LoreItemHandoffRecord prepare\(",
    """    private LoreItemHandoffRecord markRetryLimitReached(
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

    private LoreItemHandoffRecord prepare(""",
    "coordinator persisted outcome logic",
    flags=re.S,
)

# Store: stable REVIEW transition and explicit staff retry marker.
replace_once(
    "src/main/java/org/enthusia/tags/rewards/loreitems/LoreItemHandoffStore.java",
    "    public synchronized LoreItemHandoffRecord requestRetry(\n",
    """    public synchronized LoreItemHandoffRecord markReview(
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
            if (statement.executeUpdate() != EXPECTED_SINGLE_ROW) {
                throw new SQLException("Lore-item handoff could not be moved to review: " + operationId);
            }
        }
        LoreItemHandoffRecord updated = loadByOperationId(operationId);
        if (updated == null) {
            throw new SQLException("Lore-item handoff disappeared after review transition");
        }
        return updated;
    }

    public synchronized LoreItemHandoffRecord requestRetry(
""",
    "store markReview method",
)
replace_once(
    "src/main/java/org/enthusia/tags/rewards/loreitems/LoreItemHandoffStore.java",
    "            UPDATE lore_item_handoffs\n               SET state = ?, next_attempt_at = ?, reward_finalized = 0, updated_at = ?\n             WHERE external_operation_id = ?\n            \"\"\")) {\n            statement.setString(1, LoreItemHandoffState.RETRY.name());\n            statement.setLong(2, nowEpochMillis);\n            statement.setLong(3, nowEpochMillis);\n            statement.setString(4, existing.externalOperationId());\n",
    "            UPDATE lore_item_handoffs\n               SET state = ?, last_outcome = 'STAFF_RETRY_REQUESTED', last_error = '',\n                   next_attempt_at = ?, reward_finalized = 0, updated_at = ?\n             WHERE external_operation_id = ?\n            \"\"\")) {\n            statement.setString(1, LoreItemHandoffState.RETRY.name());\n            statement.setLong(2, nowEpochMillis);\n            statement.setLong(3, nowEpochMillis);\n            statement.setString(4, existing.externalOperationId());\n",
    "store explicit retry marker",
)

# Runtime: configured ceiling, rejection-safe async APIs, review transition and orderly shutdown.
replace_once(
    "src/main/java/org/enthusia/tags/rewards/loreitems/LoreItemRewardRuntime.java",
    "import java.util.concurrent.ExecutorService;\nimport java.util.concurrent.Executors;\nimport java.util.concurrent.ThreadFactory;\n",
    "import java.util.concurrent.ExecutorService;\nimport java.util.concurrent.Executors;\nimport java.util.concurrent.RejectedExecutionException;\nimport java.util.concurrent.ThreadFactory;\nimport java.util.concurrent.TimeUnit;\nimport java.util.function.Supplier;\n",
    "runtime async imports",
)
replace_once(
    "src/main/java/org/enthusia/tags/rewards/loreitems/LoreItemRewardRuntime.java",
    "        ExecutorService executor = Executors.newSingleThreadExecutor(factory);\n        LoreItemHandoffCoordinator coordinator = new LoreItemHandoffCoordinator(\n            store,\n            new ReloadingLoreItemsClient(plugin),\n            executor);\n",
    "        ExecutorService executor = Executors.newSingleThreadExecutor(factory);\n        int maxAutomaticAttempts = Math.max(1, plugin.getConfig().getInt(\n            \"rewards.lore-items.max-auto-attempts\",\n            LoreItemHandoffCoordinator.DEFAULT_MAX_AUTOMATIC_ATTEMPTS));\n        LoreItemHandoffCoordinator coordinator = new LoreItemHandoffCoordinator(\n            store,\n            new ReloadingLoreItemsClient(plugin),\n            executor,\n            System::currentTimeMillis,\n            maxAutomaticAttempts);\n",
    "runtime configured retry ceiling",
)
regex_once(
    "src/main/java/org/enthusia/tags/rewards/loreitems/LoreItemRewardRuntime.java",
    r"    public CompletionStage<List<LoreItemHandoffRecord>> inspect\(UUID playerId, String rewardId\) \{.*?\n    \}\n\n    public CompletionStage<List<LoreItemHandoffRecord>> acceptedPendingFinalization",
    """    public CompletionStage<List<LoreItemHandoffRecord>> inspect(UUID playerId, String rewardId) {
        if (!open.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException(RUNTIME_CLOSED));
        }
        return supplyAsyncSafely(() -> {
            try {
                return store.listForReward(playerId, rewardId);
            } catch (SQLException ex) {
                throw new LoreItemHandoffCoordinator.LoreItemHandoffException(
                    "Could not read LoreItems reward handoff status", ex);
            }
        });
    }

    public CompletionStage<List<LoreItemHandoffRecord>> acceptedPendingFinalization""",
    "runtime inspect safe submission",
    flags=re.S,
)
replace_once(
    "src/main/java/org/enthusia/tags/rewards/loreitems/LoreItemRewardRuntime.java",
    "return CompletableFuture.supplyAsync(() -> listAcceptedPendingFinalization(limit), executor);",
    "return supplyAsyncSafely(() -> listAcceptedPendingFinalization(limit));",
    "runtime accepted-finalization safe submission",
)
replace_once(
    "src/main/java/org/enthusia/tags/rewards/loreitems/LoreItemRewardRuntime.java",
    """    public CompletionStage<Void> markRewardFinalized(String externalOperationId) {
        if (!open.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException(RUNTIME_CLOSED));
        }
        return CompletableFuture.runAsync(() -> {
            try {
                store.markRewardFinalized(externalOperationId, System.currentTimeMillis());
            } catch (SQLException ex) {
                throw new LoreItemHandoffCoordinator.LoreItemHandoffException(
                    "Could not persist LoreItems reward finalization acknowledgement", ex);
            }
        }, executor);
    }
""",
    """    public CompletionStage<LoreItemHandoffRecord> markReview(
        String externalOperationId,
        String outcome,
        String detail) {
        if (!open.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException(RUNTIME_CLOSED));
        }
        return supplyAsyncSafely(() -> {
            try {
                return store.markReview(externalOperationId, outcome, detail, System.currentTimeMillis());
            } catch (SQLException ex) {
                throw new LoreItemHandoffCoordinator.LoreItemHandoffException(
                    "Could not persist LoreItems review state", ex);
            }
        });
    }

    public CompletionStage<Void> markRewardFinalized(String externalOperationId) {
        if (!open.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException(RUNTIME_CLOSED));
        }
        return runAsyncSafely(() -> {
            try {
                store.markRewardFinalized(externalOperationId, System.currentTimeMillis());
            } catch (SQLException ex) {
                throw new LoreItemHandoffCoordinator.LoreItemHandoffException(
                    "Could not persist LoreItems reward finalization acknowledgement", ex);
            }
        });
    }
""",
    "runtime review/finalization APIs",
)
replace_once(
    "src/main/java/org/enthusia/tags/rewards/loreitems/LoreItemRewardRuntime.java",
    "        return CompletableFuture.supplyAsync(() -> requestRetryRecord(playerId, rewardId, actionId), executor)\n            .thenCompose(this::submitRequestedRetry);\n",
    "        return supplyAsyncSafely(() -> requestRetryRecord(playerId, rewardId, actionId))\n            .thenCompose(this::submitRequestedRetry);\n",
    "runtime explicit retry safe submission",
)
replace_once(
    "src/main/java/org/enthusia/tags/rewards/loreitems/LoreItemRewardRuntime.java",
    "            coordinator.retryDue(LoreItemHandoffCoordinator.MAX_RETRY_BATCH)\n                .whenComplete((records, failure) -> logRetryFailure(failure));\n        } catch (RuntimeException ex) {\n            plugin.getLogger().warning(\n                \"LoreItems reward retry sweep could not start: \" + safeMessage(ex));\n        }\n",
    "            coordinator.retryDue(\n                LoreItemHandoffCoordinator.MAX_RETRY_BATCH,\n                this::logRetryRecordFailure)\n                .whenComplete((records, failure) -> logRetryFailure(failure));\n        } catch (RuntimeException ex) {\n            plugin.getLogger().warning(\n                \"LoreItems reward retry sweep could not start: \" + ThrowableDescriptions.describe(ex));\n        }\n",
    "runtime retry diagnostics",
)
replace_once(
    "src/main/java/org/enthusia/tags/rewards/loreitems/LoreItemRewardRuntime.java",
    """    private void logRetryFailure(Throwable failure) {
        if (failure != null && open.get()) {
            plugin.getLogger().warning(
                "LoreItems reward retry sweep failed safely: " + safeMessage(failure));
        }
    }
""",
    """    private void logRetryRecordFailure(Throwable failure) {
        if (failure != null && open.get()) {
            plugin.getLogger().warning(
                "LoreItems reward retry record failed safely and the sweep continued: "
                    + ThrowableDescriptions.describe(failure));
        }
    }

    private void logRetryFailure(Throwable failure) {
        if (failure != null && open.get()) {
            plugin.getLogger().warning(
                "LoreItems reward retry sweep failed safely: " + ThrowableDescriptions.describe(failure));
        }
    }
""",
    "runtime retry failure log methods",
)
regex_once(
    "src/main/java/org/enthusia/tags/rewards/loreitems/LoreItemRewardRuntime.java",
    r"        executor\.shutdownNow\(\);\n        try \{\n            store\.close\(\);\n        \} catch \(SQLException ex\) \{\n            plugin\.getLogger\(\)\.warning\(\"Failed to close LoreItems reward handoff storage: \" \+ ex\.getMessage\(\)\);\n        \}\n    \}\n\n    private static String safeMessage\(Throwable throwable\) \{.*?\n    \}\n\}",
    """        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                plugin.getLogger().warning(
                    "LoreItems handoff workers did not stop before storage close.");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            plugin.getLogger().warning(
                "Interrupted while waiting for LoreItems handoff workers to stop.");
        }
        try {
            store.close();
        } catch (SQLException ex) {
            plugin.getLogger().warning("Failed to close LoreItems reward handoff storage: " + ex.getMessage());
        }
    }

    private <T> CompletionStage<T> supplyAsyncSafely(Supplier<T> supplier) {
        try {
            return CompletableFuture.supplyAsync(supplier, executor);
        } catch (RejectedExecutionException ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }

    private CompletionStage<Void> runAsyncSafely(Runnable action) {
        try {
            return CompletableFuture.runAsync(action, executor);
        } catch (RejectedExecutionException ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }
}
""",
    "runtime orderly shutdown and safe submission helpers",
    flags=re.S,
)

# Admin path.
replace_once(
    "src/main/java/org/enthusia/tags/rewards/loreitems/LoreItemRewardAdmin.java",
    "        if (args.length == SUBCOMMAND_ARGUMENT_COUNT) {\n            return List.of(STATUS_COMMAND, RETRY_COMMAND);\n        }\n",
    "        if (args.length == SUBCOMMAND_ARGUMENT_COUNT) {\n            String prefix = args[0].toLowerCase(java.util.Locale.ROOT);\n            return List.of(STATUS_COMMAND, RETRY_COMMAND).stream()\n                .filter(option -> option.startsWith(prefix))\n                .toList();\n        }\n",
    "LoreItems admin prefix completion",
)
replace_once(
    "src/main/java/org/enthusia/tags/rewards/loreitems/LoreItemRewardAdmin.java",
    """    private void scheduleMain(Runnable action) {
        plugin.getServer().getScheduler().runTask(plugin, action);
    }

    private void sendError(CommandSender sender, Throwable failure) {
        Throwable current = failure;
        Throwable cause = current.getCause();
        while (cause != null && !Objects.equals(current, cause)) {
            current = cause;
            cause = current.getCause();
        }
        String detail = current.getMessage();
        if (detail == null || detail.isBlank()) {
            detail = current.getClass().getSimpleName();
        }
        send(sender, messages.get("rewards-loreitems-admin-error").replace("{error}", detail));
    }
""",
    """    private void scheduleMain(Runnable action) {
        if (!plugin.isEnabled()) {
            plugin.getLogger().warning("Could not deliver LoreItems admin output because the plugin is disabled.");
            return;
        }
        try {
            plugin.getServer().getScheduler().runTask(plugin, action);
        } catch (RuntimeException ex) {
            plugin.getLogger().warning(
                "Could not deliver LoreItems admin output: " + ThrowableDescriptions.describe(ex));
        }
    }

    private void sendError(CommandSender sender, Throwable failure) {
        send(sender, messages.get("rewards-loreitems-admin-error")
            .replace("{error}", ThrowableDescriptions.describe(failure)));
    }
""",
    "LoreItems admin scheduler/error handling",
)

# RewardService behavior.
replace_once(
    "src/main/java/org/enthusia/tags/rewards/RewardService.java",
    "import org.enthusia.tags.rewards.loreitems.LoreItemRewardRuntime;\n",
    "import org.enthusia.tags.rewards.loreitems.LoreItemRewardRuntime;\nimport org.enthusia.tags.rewards.loreitems.ThrowableDescriptions;\n",
    "RewardService throwable helper import",
)
replace_once(
    "src/main/java/org/enthusia/tags/rewards/RewardService.java",
    "    private final Set<String> inFlightClaims = ConcurrentHashMap.newKeySet();\n    private final Set<CompletableFuture<?>> activeOperations = ConcurrentHashMap.newKeySet();\n",
    "    private final Set<String> inFlightClaims = ConcurrentHashMap.newKeySet();\n    private final Set<String> loreItemReviewWarningOperations = ConcurrentHashMap.newKeySet();\n    private final Set<CompletableFuture<?>> activeOperations = ConcurrentHashMap.newKeySet();\n",
    "RewardService review warning set",
)
replace_once(
    "src/main/java/org/enthusia/tags/rewards/RewardService.java",
    "            storage.saveActionLedgerNow(playerId, rewardId, action, fingerprint,\n                RewardStatus.CLAIM_PENDING, null, null);\n",
    "            if (existing != null && existing.status() == RewardStatus.CLAIM_PENDING\n                && isRecoverableLorePending(reward, existing)) {\n                state.setOverall(rewardId, RewardStatus.CLAIM_PENDING);\n                persistStateBarrier(playerId, state);\n                return RewardClaimResult.CLAIM_IN_PROGRESS;\n            }\n            storage.saveActionLedgerNow(playerId, rewardId, action, fingerprint,\n                RewardStatus.CLAIM_PENDING, null, null);\n",
    "RewardService recoverable pending early return",
)
service = Path("src/main/java/org/enthusia/tags/rewards/RewardService.java")
text = service.read_text(encoding="utf-8").replace("safeThrowableMessage(", "ThrowableDescriptions.describe(")
text, count = re.subn(
    r"\n    private static String ThrowableDescriptions\.describe\(Throwable throwable\) \{.*?\n    \}\n",
    "\n",
    text,
    count=1,
    flags=re.S,
)
if count != 1:
    raise RuntimeError("RewardService safe throwable method removal failed")
service.write_text(text, encoding="utf-8")
replace_once(
    "src/main/java/org/enthusia/tags/rewards/RewardService.java",
    "            if (action == null) {\n                plugin.getLogger().warning(\n                    \"Accepted LoreItems handoff needs staff review because its Tags reward/action changed: \"\n                        + record.externalOperationId());\n                return;\n            }\n",
    "            if (action == null) {\n                moveAcceptedLoreItemToReview(\n                    record,\n                    \"Tags reward/action/definition changed after LoreItems accepted the operation\");\n                return;\n            }\n",
    "RewardService changed action finalization",
)
replace_once(
    "src/main/java/org/enthusia/tags/rewards/RewardService.java",
    "            if (!storage.acceptLoreItemHandoffNow(\n                record.playerId(), record.rewardId(), action, fingerprint, evidence)) {\n                plugin.getLogger().warning(\n                    \"Accepted LoreItems handoff could not be reconciled automatically with the Tags action ledger: \"\n                        + record.externalOperationId());\n                return;\n            }\n",
    "            if (!storage.acceptLoreItemHandoffNow(\n                record.playerId(), record.rewardId(), action, fingerprint, evidence)) {\n                moveAcceptedLoreItemToReview(\n                    record,\n                    \"Tags action ledger no longer matches the accepted LoreItems operation\");\n                return;\n            }\n",
    "RewardService unrecoverable action ledger finalization",
)
replace_once(
    "src/main/java/org/enthusia/tags/rewards/RewardService.java",
    "    private RewardAction findLoreItemAction(RewardDefinition reward, LoreItemHandoffRecord record) {\n",
    """    private void moveAcceptedLoreItemToReview(
        LoreItemHandoffRecord record,
        String detail) {
        try {
            loreItemRewardRuntime.markReview(
                record.externalOperationId(),
                "TAGS_RECONCILIATION_REVIEW",
                detail)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
            plugin.getLogger().warning(
                "Accepted LoreItems handoff moved to staff review: "
                    + record.externalOperationId() + " (" + detail + ")");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            warnLoreItemReviewTransitionOnce(
                record,
                "Interrupted while moving accepted LoreItems handoff to review");
        } catch (ExecutionException | TimeoutException ex) {
            warnLoreItemReviewTransitionOnce(
                record,
                "Accepted LoreItems handoff could not be moved to review and remains pending finalization: "
                    + ThrowableDescriptions.describe(ex));
        }
    }

    private void warnLoreItemReviewTransitionOnce(
        LoreItemHandoffRecord record,
        String detail) {
        if (loreItemReviewWarningOperations.add(record.externalOperationId())) {
            plugin.getLogger().warning(detail + " operation=" + record.externalOperationId());
        }
    }

    private RewardAction findLoreItemAction(RewardDefinition reward, LoreItemHandoffRecord record) {
""",
    "RewardService stable review transition helper",
)
replace_once(
    "src/main/java/org/enthusia/tags/rewards/RewardService.java",
    "        if (usesLoreItemRewards() && (loreItemRewardRuntime == null || !loreItemRewardRuntime.isOpen())) {\n            integrationStatus.addWarning(\"&cEnthusiaTags warning: durable LoreItems handoff storage is unavailable. Lore-item rewards are blocked.\");\n        } else if (usesLoreItemRewards()) {\n",
    "        boolean hasLoreItemRewards = usesLoreItemRewards();\n        if (hasLoreItemRewards && (loreItemRewardRuntime == null || !loreItemRewardRuntime.isOpen())) {\n            integrationStatus.addWarning(\"&cEnthusiaTags warning: durable LoreItems handoff storage is unavailable. Lore-item rewards are blocked.\");\n        } else if (hasLoreItemRewards) {\n",
    "RewardService single LoreItems integration scan",
)

# Root/reward tab completion.
replace_once(
    "src/main/java/org/enthusia/tags/EnthusiaTagsPlugin.java",
    "                if (args.length == SINGLE_ARGUMENT_COUNT) {\n                    return java.util.List.of(\"reload\", \"performance\", \"rewards\", \"daily\");\n                }\n                if (args.length >= NESTED_COMMAND_ARGUMENT_COUNT\n                    && args[0].equalsIgnoreCase(\"rewards\") && loreItemRewardAdmin != null) {\n                    return loreItemRewardAdmin.tabComplete(\n                        sender,\n                        java.util.Arrays.copyOfRange(args, 1, args.length));\n                }\n",
    "                if (args.length == SINGLE_ARGUMENT_COUNT) {\n                    String prefix = args[0].toLowerCase(java.util.Locale.ROOT);\n                    return java.util.List.of(\"reload\", \"performance\", \"rewards\", \"daily\").stream()\n                        .filter(option -> option.startsWith(prefix))\n                        .toList();\n                }\n                if (args.length >= NESTED_COMMAND_ARGUMENT_COUNT\n                    && args[0].equalsIgnoreCase(\"rewards\")) {\n                    String[] rewardArgs = java.util.Arrays.copyOfRange(args, 1, args.length);\n                    if (rewardArgs.length == SINGLE_ARGUMENT_COUNT) {\n                        String prefix = rewardArgs[0].toLowerCase(java.util.Locale.ROOT);\n                        java.util.stream.Stream<String> rewardCommands = java.util.stream.Stream.of(\n                            \"syncall\", \"sync\", \"debug\", \"ipbypass\", \"bypass\",\n                            \"reconcile\", \"inspect\", \"items\", \"ip\");\n                        java.util.stream.Stream<String> loreCommands = loreItemRewardAdmin == null\n                            ? java.util.stream.Stream.empty()\n                            : loreItemRewardAdmin.tabComplete(sender, rewardArgs).stream();\n                        return java.util.stream.Stream.concat(rewardCommands, loreCommands)\n                            .filter(option -> option.startsWith(prefix))\n                            .distinct()\n                            .toList();\n                    }\n                    if (loreItemRewardAdmin != null) {\n                        return loreItemRewardAdmin.tabComplete(sender, rewardArgs);\n                    }\n                }\n",
    "root/reward tab completion",
)

# Configuration and docs.
replace_once(
    "src/main/resources/config.yml",
    "rewards:\n  anti-farm:\n",
    "rewards:\n  lore-items:\n    # Automatic handoff attempts stop here and move to REVIEW; staff loreretry can try again.\n    max-auto-attempts: 48\n\n  anti-farm:\n",
    "LoreItems retry ceiling config",
)
doc = Path("docs/loreitems-integration.md")
text = doc.read_text(encoding="utf-8")
old = "The test suite independently verifies the same checksum, the V1 API class entries, `API_VERSION == 1`, and the exact published status enum surface."
new = "The Maven build re-verifies the downloaded JAR against the pinned SHA-256 before compilation, and the test suite independently anchors the configured checksum and JAR bytes to the approved production digest while checking the V1 API class entries, `API_VERSION == 1`, and published status enum surface."
if old not in text:
    raise RuntimeError("operator doc checksum wording target missing")
text = text.replace(old, new, 1)
old = "Retry delay starts at 5 seconds, doubles per attempt, and is capped at 5 minutes."
new = "Retry delay starts at 5 seconds, doubles per attempt, and is capped at 5 minutes. Automatic attempts stop at `rewards.lore-items.max-auto-attempts` (default 48) and move the handoff to `REVIEW`; an explicit staff `loreretry` performs another attempt with the same external operation ID."
if old not in text:
    raise RuntimeError("operator doc retry wording target missing")
text = text.replace(old, new, 1)
doc.write_text(text, encoding="utf-8")

# Tests.
regex_once(
    "src/test/java/org/enthusia/tags/rewards/loreitems/LoreItemHandoffStoreTest.java",
    r"    @Test\n    void retryQueueIsOrderedBoundedAndOnlyReturnsDueRows\(\) throws Exception \{.*?\n    \}\n\n    @Test\n    void finalizationAcknowledgesOnlyTheExactAcceptedOperation",
    """    @Test
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
    void finalizationAcknowledgesOnlyTheExactAcceptedOperation""",
    "retry queue order/limit test",
    flags=re.S,
)
replace_once(
    "src/test/java/org/enthusia/tags/rewards/loreitems/LoreItemHandoffStoreTest.java",
    "            assertFalse(store.loadByOperationId(second.externalOperationId()).rewardFinalized());\n        }\n    }\n",
    "            assertFalse(store.loadByOperationId(second.externalOperationId()).rewardFinalized());\n\n            LoreItemHandoffRecord review = store.markReview(\n                second.externalOperationId(),\n                \"TAGS_RECONCILIATION_REVIEW\",\n                \"configuration changed\",\n                1300L);\n            assertEquals(LoreItemHandoffState.REVIEW, review.state());\n            assertEquals(List.of(), store.listAcceptedPendingFinalization(10));\n        }\n    }\n",
    "review state excluded from accepted finalization queue",
)
replace_once(
    "src/test/java/org/enthusia/tags/rewards/loreitems/LoreItemHandoffCoordinatorTest.java",
    "    @Test\n    void retryBackoffIsBounded() {\n",
    """    @Test
    void automaticRetryLimitMovesHandoffToReviewAndStaffRetryCanTryAgain() throws Exception {
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
            LoreItemHandoffRecord exhausted = coordinator.handoff(player, REWARD, ACTION, "hourglass")
                .toCompletableFuture().join();

            assertEquals(2, calls.get());
            assertEquals(2, exhausted.attempts());
            assertEquals(LoreItemHandoffState.REVIEW, exhausted.state());
            assertEquals(0L, exhausted.nextAttemptAtEpochMillis());
            assertEquals(java.util.List.of(), store.listDue(clock.get(), 10));

            LoreItemHandoffRecord staffRetry = store.requestRetry(player, REWARD, ACTION, clock.get());
            assertEquals("STAFF_RETRY_REQUESTED", staffRetry.lastOutcome());
            LoreItemHandoffRecord afterStaffAttempt = coordinator.handoff(player, REWARD, ACTION, "hourglass")
                .toCompletableFuture().join();
            assertEquals(3, calls.get());
            assertEquals(3, afterStaffAttempt.attempts());
            assertEquals(LoreItemHandoffState.REVIEW, afterStaffAttempt.state());
        }
    }

    @Test
    void retrySweepKeepsSuccessfulRecordsWhenAnotherRecordThrows() throws Exception {
        UUID firstPlayer = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
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
""",
    "coordinator retry ceiling/isolation tests",
)
replace_once(
    "src/test/java/org/enthusia/tags/rewards/loreitems/LoreItemsArchitectureTest.java",
    "    @Test\n    void rewardClaimWorkerOwnsTheOnlyLoreItemWait() throws Exception {\n",
    """    @Test
    void runtimeAndPublishWorkflowKeepRecoveryBounded() throws Exception {
        String runtime = Files.readString(MAIN_JAVA.resolve(
            "org/enthusia/tags/rewards/loreitems/LoreItemRewardRuntime.java"));
        String rewardService = Files.readString(MAIN_JAVA.resolve(
            "org/enthusia/tags/rewards/RewardService.java"));
        String publishWorkflow = Files.readString(Path.of(".github/workflows/publish-latest.yml"));

        assertTrue(runtime.contains("RejectedExecutionException"),
            "runtime submissions must turn executor shutdown races into failed futures");
        assertTrue(runtime.contains("awaitTermination"),
            "runtime must wait for LoreItems workers before closing SQLite");
        assertTrue(rewardService.contains("return RewardClaimResult.CLAIM_IN_PROGRESS;"),
            "recoverable LoreItems CLAIM_PENDING rows must return pending instead of rewriting the ledger");
        int bootstrap = publishWorkflow.indexOf("bash tools/bootstrap_loreitems_release.sh");
        int maven = publishWorkflow.indexOf("mvn --batch-mode --no-transfer-progress clean test package");
        assertTrue(bootstrap >= 0 && maven > bootstrap,
            "publish-latest must bootstrap the pinned LoreItems artifact before Maven");
    }

    @Test
    void rewardClaimWorkerOwnsTheOnlyLoreItemWait() throws Exception {
""",
    "architecture runtime/publish guards",
)

handoff = Path("docs/wp-06-loreitems-integration-handoff.md")
text = handoff.read_text(encoding="utf-8")
text = text.replace(
    "- Build/test uses the exact production LoreItems `v1.0.0` artifact rather than a source checkout.",
    "- Build/test uses the exact production LoreItems `v1.0.0` artifact rather than a source checkout; bootstrap and Maven validation verify its SHA-256 while the contract test hard-codes the approved digest.",
    1,
)
handoff.write_text(text, encoding="utf-8")
