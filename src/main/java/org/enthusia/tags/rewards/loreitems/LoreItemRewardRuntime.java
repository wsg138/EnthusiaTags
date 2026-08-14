package org.enthusia.tags.rewards.loreitems;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;

// Paper plugins are not J2EE webapps. This dedicated single-thread executor is intentional:
// it serializes the WP-06 SQLite handoff store and is explicitly closed with the plugin runtime.
@SuppressWarnings("PMD.DoNotUseThreads")
public final class LoreItemRewardRuntime implements AutoCloseable {
    private static final long RETRY_PERIOD_TICKS = 100L;
    private static final String RUNTIME_CLOSED = "LoreItems reward runtime is closed";

    private final JavaPlugin plugin;
    private final LoreItemHandoffStore store;
    private final ExecutorService executor;
    private final LoreItemHandoffCoordinator coordinator;
    private final AtomicBoolean open = new AtomicBoolean(true);
    private volatile BukkitTask retryTask;

    private LoreItemRewardRuntime(
        JavaPlugin plugin,
        LoreItemHandoffStore store,
        ExecutorService executor,
        LoreItemHandoffCoordinator coordinator) {
        this.plugin = plugin;
        this.store = store;
        this.executor = executor;
        this.coordinator = coordinator;
    }

    public static LoreItemRewardRuntime enable(JavaPlugin plugin) throws SQLException {
        Objects.requireNonNull(plugin, "plugin");
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs() && !dataFolder.exists()) {
            throw new SQLException("Could not create plugin data directory for LoreItems handoff storage");
        }
        LoreItemHandoffStore store = new LoreItemHandoffStore(
            dataFolder.toPath().resolve("lore-item-handoffs.db"));
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "EnthusiaTags-LoreItems");
            thread.setDaemon(true);
            return thread;
        };
        ExecutorService executor = Executors.newSingleThreadExecutor(factory);
        int maxAutomaticAttempts = Math.max(1, plugin.getConfig().getInt(
            "rewards.lore-items.max-auto-attempts",
            LoreItemHandoffCoordinator.DEFAULT_MAX_AUTOMATIC_ATTEMPTS));
        LoreItemHandoffCoordinator coordinator = new LoreItemHandoffCoordinator(
            store,
            new ReloadingLoreItemsClient(plugin),
            executor,
            System::currentTimeMillis,
            maxAutomaticAttempts);
        LoreItemRewardRuntime runtime = new LoreItemRewardRuntime(plugin, store, executor, coordinator);
        runtime.retryTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
            plugin,
            runtime::retryDueQuietly,
            RETRY_PERIOD_TICKS,
            RETRY_PERIOD_TICKS);
        runtime.kickRetries();
        return runtime;
    }

    public CompletionStage<LoreItemHandoffRecord> handoff(
        UUID playerId,
        String rewardId,
        String actionId,
        String definitionKey) {
        if (!open.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException(RUNTIME_CLOSED));
        }
        return coordinator.handoff(playerId, rewardId, actionId, definitionKey);
    }

    public CompletionStage<List<LoreItemHandoffRecord>> inspect(UUID playerId, String rewardId) {
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

    public CompletionStage<List<LoreItemHandoffRecord>> acceptedPendingFinalization(int requestedLimit) {
        if (!open.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException(RUNTIME_CLOSED));
        }
        int limit = Math.max(0, Math.min(requestedLimit, LoreItemHandoffCoordinator.MAX_RETRY_BATCH));
        if (limit == 0) {
            return CompletableFuture.completedFuture(List.of());
        }
        return supplyAsyncSafely(() -> listAcceptedPendingFinalization(limit));
    }

    public CompletionStage<LoreItemHandoffRecord> markReview(
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

    private List<LoreItemHandoffRecord> listAcceptedPendingFinalization(int limit) {
        try {
            return store.listAcceptedPendingFinalization(limit);
        } catch (SQLException ex) {
            throw new LoreItemHandoffCoordinator.LoreItemHandoffException(
                "Could not load accepted LoreItems handoffs awaiting Tags finalization", ex);
        }
    }

    public CompletionStage<LoreItemHandoffRecord> requestRetry(
        UUID playerId,
        String rewardId,
        String actionId) {
        if (!open.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException(RUNTIME_CLOSED));
        }
        return supplyAsyncSafely(() -> requestRetryRecord(playerId, rewardId, actionId))
            .thenCompose(this::submitRequestedRetry);
    }

    private LoreItemHandoffRecord requestRetryRecord(UUID playerId, String rewardId, String actionId) {
        try {
            return store.requestRetry(playerId, rewardId, actionId, System.currentTimeMillis());
        } catch (SQLException ex) {
            throw new LoreItemHandoffCoordinator.LoreItemHandoffException(
                "Could not request LoreItems reward retry", ex);
        }
    }

    private CompletionStage<LoreItemHandoffRecord> submitRequestedRetry(LoreItemHandoffRecord record) {
        if (record == null) {
            return CompletableFuture.completedFuture(null);
        }
        if (LoreItemHandoffState.ACCEPTED.equals(record.state())) {
            return CompletableFuture.completedFuture(record);
        }
        return coordinator.handoff(
            record.playerId(),
            record.rewardId(),
            record.actionId(),
            record.definitionKey());
    }

    public void kickRetries() {
        if (open.get()) {
            retryDueQuietly();
        }
    }

    private void retryDueQuietly() {
        if (!open.get()) {
            return;
        }
        try {
            coordinator.retryDue(
                LoreItemHandoffCoordinator.MAX_RETRY_BATCH,
                this::logRetryRecordFailure)
                .whenComplete((records, failure) -> logRetryFailure(failure));
        } catch (RuntimeException ex) {
            plugin.getLogger().warning(
                "LoreItems reward retry sweep could not start: " + ThrowableDescriptions.describe(ex));
        }
    }

    private void logRetryRecordFailure(Throwable failure) {
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

    public boolean isOpen() {
        return open.get();
    }

    @Override
    public void close() {
        if (!open.compareAndSet(true, false)) {
            return;
        }
        BukkitTask task = retryTask;
        if (task != null) {
            task.cancel();
        }
        executor.shutdownNow();
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
