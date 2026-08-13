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
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LoreItemRewardRuntime implements AutoCloseable {
    private static final long RETRY_PERIOD_TICKS = 100L;

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
        LoreItemHandoffCoordinator coordinator = new LoreItemHandoffCoordinator(
            store,
            new ReloadingLoreItemsClient(plugin),
            executor);
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
            return CompletableFuture.failedFuture(new IllegalStateException("LoreItems reward runtime is closed"));
        }
        return coordinator.handoff(playerId, rewardId, actionId, definitionKey);
    }

    public CompletionStage<List<LoreItemHandoffRecord>> inspect(UUID playerId, String rewardId) {
        if (!open.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("LoreItems reward runtime is closed"));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return store.listForReward(playerId, rewardId);
            } catch (SQLException ex) {
                throw new LoreItemHandoffCoordinator.LoreItemHandoffException(
                    "Could not read LoreItems reward handoff status", ex);
            }
        }, executor);
    }

    public CompletionStage<LoreItemHandoffRecord> requestRetry(
        UUID playerId,
        String rewardId,
        String actionId) {
        if (!open.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("LoreItems reward runtime is closed"));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return store.requestRetry(playerId, rewardId, actionId, System.currentTimeMillis());
            } catch (SQLException ex) {
                throw new LoreItemHandoffCoordinator.LoreItemHandoffException(
                    "Could not request LoreItems reward retry", ex);
            }
        }, executor).thenCompose(record -> {
            if (record == null) {
                return CompletableFuture.completedFuture(null);
            }
            if (record.state() == LoreItemHandoffState.ACCEPTED) {
                return CompletableFuture.completedFuture(record);
            }
            return coordinator.handoff(
                record.playerId(),
                record.rewardId(),
                record.actionId(),
                record.definitionKey());
        });
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
            coordinator.retryDue(LoreItemHandoffCoordinator.MAX_RETRY_BATCH)
                .whenComplete((records, failure) -> {
                    if (failure != null && open.get()) {
                        plugin.getLogger().warning(
                            "LoreItems reward retry sweep failed safely: " + safeMessage(failure));
                    }
                });
        } catch (RuntimeException ex) {
            plugin.getLogger().warning(
                "LoreItems reward retry sweep could not start: " + safeMessage(ex));
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
        retryTask = null;
        if (task != null) {
            task.cancel();
        }
        executor.shutdownNow();
        try {
            store.close();
        } catch (SQLException ex) {
            plugin.getLogger().warning("Failed to close LoreItems reward handoff storage: " + ex.getMessage());
        }
    }

    private static String safeMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current != current.getCause()) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
