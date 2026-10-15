package org.enthusia.tags.advancements.application;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Completion evidence only; this port cannot grant or claim rewards. */
public interface CompletionLedger extends AutoCloseable {
    CompletableFuture<Set<String>> load(UUID player);
    /** Atomically store completions and return only keys first inserted by this call. */
    CompletableFuture<Set<String>> record(UUID player, Set<String> completed);
    @Override void close();
}
