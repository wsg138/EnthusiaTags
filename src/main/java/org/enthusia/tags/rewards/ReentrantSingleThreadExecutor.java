package org.enthusia.tags.rewards;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Serial executor that runs nested submissions inline when they originate from
 * its own worker thread. This prevents a storage callback from deadlocking by
 * submitting another storage operation and synchronously waiting for it.
 * RewardStorage must instantiate this executor for the safeguard to apply.
 */
final class ReentrantSingleThreadExecutor extends AbstractExecutorService {
    private final AtomicReference<Thread> worker = new AtomicReference<>();
    private final ExecutorService delegate;

    ReentrantSingleThreadExecutor(String threadName) {
        delegate = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            worker.set(thread);
            return thread;
        });
    }

    @Override
    public void execute(Runnable command) {
        if (command == null) {
            throw new NullPointerException("command");
        }
        if (isShutdown()) {
            throw new RejectedExecutionException("Reward storage executor is shut down");
        }
        if (Thread.currentThread() == worker.get()) {
            command.run();
            return;
        }
        delegate.execute(command);
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }
}
