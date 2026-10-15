package org.enthusia.tags.advancements;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.enthusia.tags.advancements.application.CompletionHistory;
import org.enthusia.tags.advancements.application.CompletionLedger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CompletionHistoryTest {
    private static final String KEY = "express/first_class";
    private final UUID player = UUID.randomUUID();
    private final CompletionLedger ledger = mock(CompletionLedger.class);

    @Test void persistedCompletionRestoresProgressAndDropsDuplicateButNotUnknownNodes() {
        when(ledger.load(player)).thenReturn(CompletableFuture.completedFuture(Set.of(KEY, "removed/node")));
        var progress = new HashMap<String, Integer>();
        var pending = new HashSet<>(Set.of(KEY));
        assertTrue(new CompletionHistory(ledger, player).reconcile(progress, pending, Set.of(KEY)));
        assertEquals(Map.of(KEY, 1000), progress);
        assertTrue(pending.isEmpty());
        verify(ledger, never()).record(any(), any());
    }

    @Test void failedLoadIsNotEmptyHistoryAndCanRetry() {
        when(ledger.load(player)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("busy")))
            .thenReturn(CompletableFuture.completedFuture(Set.of(KEY)));
        var history = new CompletionHistory(ledger, player);
        var pending = new HashSet<>(Set.of(KEY));
        var progress = new HashMap<>(Map.of(KEY, 1000));
        assertThrows(RuntimeException.class, () -> history.reconcile(progress, pending, Set.of(KEY)));
        assertEquals(Set.of(KEY), pending);
        assertTrue(history.reconcile(progress, pending, Set.of(KEY)));
        assertTrue(pending.isEmpty());
    }

    @Test void pendingWriteDefersDeliveryAndFailedCommitRetainsLiveEvidence() {
        when(ledger.load(player)).thenReturn(CompletableFuture.completedFuture(Set.of()));
        var write = new CompletableFuture<Set<String>>();
        when(ledger.record(player, Set.of(KEY))).thenReturn(write)
            .thenReturn(CompletableFuture.completedFuture(Set.of(KEY)));
        var history = new CompletionHistory(ledger, player);
        var pending = new HashSet<>(Set.of(KEY));
        var progress = new HashMap<>(Map.of(KEY, 1000));
        assertFalse(history.reconcile(progress, pending, Set.of(KEY)));
        assertFalse(history.reconcile(progress, pending, Set.of(KEY)));
        verify(ledger, times(1)).record(player, Set.of(KEY));
        write.completeExceptionally(new IllegalStateException("disk"));
        assertThrows(RuntimeException.class, () -> history.reconcile(progress, pending, Set.of(KEY)));
        assertEquals(Set.of(KEY), pending);
        assertFalse(history.reconcile(progress, pending, Set.of(KEY)));
        assertTrue(history.reconcile(progress, pending, Set.of(KEY)));
        assertEquals(Set.of(KEY), pending);
        history.consumeAuthorization(KEY);
        assertTrue(history.reconcile(progress, pending, Set.of(KEY)));
        assertTrue(pending.isEmpty());
    }

    @Test void historicalSeedIsSilentEvenIfLaterQueuedAndAnotherWriterWinsSilently() {
        when(ledger.load(player)).thenReturn(CompletableFuture.completedFuture(Set.of()));
        when(ledger.record(player, Set.of(KEY))).thenReturn(CompletableFuture.completedFuture(Set.of(KEY)));
        var history = new CompletionHistory(ledger, player);
        var progress = new HashMap<>(Map.of(KEY, 1000));
        var pending = new HashSet<String>();
        assertFalse(history.reconcile(progress, pending, Set.of(KEY)));
        pending.add(KEY);
        assertTrue(history.reconcile(progress, pending, Set.of(KEY)));
        assertTrue(pending.isEmpty());
        when(ledger.record(player, Set.of(KEY))).thenReturn(CompletableFuture.completedFuture(Set.of()));
        var second = new CompletionHistory(ledger, player);
        pending.add(KEY);
        assertFalse(second.reconcile(progress, pending, Set.of(KEY)));
        assertTrue(second.reconcile(progress, pending, Set.of(KEY)));
        assertTrue(pending.isEmpty());
    }

    @Test void rewardCallbacksRemainOwnedByTheirExistingDurableUnlockLedger() {
        when(ledger.load(player)).thenReturn(CompletableFuture.completedFuture(Set.of()));
        var history = new CompletionHistory(ledger, player);
        String reward = NativeAdvancementController.key("a_reward");
        var progress = new HashMap<>(Map.of(reward, 1000));
        var pending = new HashSet<String>();
        // The progress poll can run before RewardService's asynchronous completion callback.
        assertTrue(history.reconcile(progress, pending, Set.of(KEY)));
        pending.add(reward);
        assertTrue(history.reconcile(progress, pending, Set.of(KEY)));
        assertEquals(Set.of(reward), pending);
        verify(ledger, never()).record(any(), any());
    }

    @Test void uncertainDispatchConsumesOnlyTheAttemptedKey() {
        String other = "express/pen_pal";
        Set<String> keys = Set.of(KEY, other);
        when(ledger.load(player)).thenReturn(CompletableFuture.completedFuture(Set.of()));
        when(ledger.record(player, keys)).thenReturn(CompletableFuture.completedFuture(keys));
        var history = new CompletionHistory(ledger, player);
        var progress = new HashMap<>(Map.of(KEY, 1000, other, 1000));
        var pending = new HashSet<>(keys);
        assertFalse(history.reconcile(progress, pending, keys));
        assertTrue(history.reconcile(progress, pending, keys));
        history.consumeAuthorization(KEY);
        // A throw aborts the delivery loop before the second key is attempted.
        assertTrue(history.reconcile(progress, pending, keys));
        assertEquals(Set.of(other), pending);
    }
}
