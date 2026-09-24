package org.enthusia.tags.advancements;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.enthusia.tags.advancements.domain.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class NotificationRetryTest {
    @Test void failedRendererCallRetainsPendingWork() {
        Set<String> pending=new LinkedHashSet<>(List.of("ready","incomplete"));
        Map<String,Integer> progress=Map.of("ready",1000,"incomplete",500);
        assertThrows(IllegalStateException.class,()->PendingCelebrations.deliver(pending,progress,key->{throw new IllegalStateException("offline");}));
        assertEquals(Set.of("ready","incomplete"),pending);
        AtomicInteger sent=new AtomicInteger();
        PendingCelebrations.deliver(pending,progress,key->sent.incrementAndGet());
        PendingCelebrations.deliver(pending,progress,key->sent.incrementAndGet());
        assertEquals(1,sent.get()); assertEquals(Set.of("incomplete"),pending);
    }
    @Test void persistenceRetryKeepsLiveClassificationUntilAcknowledged() {
        var b=new CompletionBaseline(); b.isLiveCompletion("x",999);
        assertTrue(b.isLiveCompletion("x",1000)); assertFalse(b.isLiveCompletion("x",1000));
        assertTrue(b.hasPendingLiveCompletion("x")); b.acknowledge("x"); assertFalse(b.hasPendingLiveCompletion("x"));
        b.isLiveCompletion("x",0); assertFalse(b.isLiveCompletion("x",1000));
    }
}
