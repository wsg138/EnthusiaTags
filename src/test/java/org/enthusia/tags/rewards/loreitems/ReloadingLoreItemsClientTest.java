package org.enthusia.tags.rewards.loreitems;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReloadingLoreItemsClientTest {
    private static final UUID PLAYER = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final String OPERATION = "enthusiatags:loreitem:v1:test";

    @Test
    void absentProviderCanRegisterLaterWithoutRestartingTags() {
        AtomicReference<ReloadingLoreItemsClient.ProviderSnapshot> provider =
            new AtomicReference<>(ReloadingLoreItemsClient.ProviderSnapshot.unavailable());
        AtomicInteger adapterCreations = new AtomicInteger();
        LoreItemsClient accepted = (definition, player, operation) -> CompletableFuture.completedFuture(
            new LoreItemsGatewayResult(
                LoreItemsGatewayResult.Disposition.ACCEPTED,
                "ACCEPTED_QUEUED",
                operation,
                "accepted"));
        ReloadingLoreItemsClient client = new ReloadingLoreItemsClient(
            provider::get,
            () -> {
                adapterCreations.incrementAndGet();
                return accepted;
            });

        assertEquals(LoreItemsGatewayResult.Disposition.RETRY,
            client.queue("hourglass", PLAYER, OPERATION).toCompletableFuture().join().disposition());
        assertEquals(0, adapterCreations.get());

        Object firstProvider = new Object();
        provider.set(new ReloadingLoreItemsClient.ProviderSnapshot(firstProvider, true));
        assertEquals(LoreItemsGatewayResult.Disposition.ACCEPTED,
            client.queue("hourglass", PLAYER, OPERATION).toCompletableFuture().join().disposition());
        assertEquals(1, adapterCreations.get());

        client.queue("hourglass", PLAYER, OPERATION).toCompletableFuture().join();
        assertEquals(1, adapterCreations.get(), "same enabled provider should reuse the typed adapter");
    }

    @Test
    void disableAndProviderReplacementDiscardCachedAdapter() {
        Object firstProvider = new Object();
        Object replacementProvider = new Object();
        AtomicReference<ReloadingLoreItemsClient.ProviderSnapshot> provider =
            new AtomicReference<>(new ReloadingLoreItemsClient.ProviderSnapshot(firstProvider, true));
        AtomicInteger adapterCreations = new AtomicInteger();
        ReloadingLoreItemsClient client = new ReloadingLoreItemsClient(
            provider::get,
            () -> {
                adapterCreations.incrementAndGet();
                return (definition, player, operation) -> CompletableFuture.completedFuture(
                    new LoreItemsGatewayResult(
                        LoreItemsGatewayResult.Disposition.ACCEPTED,
                        "ALREADY_ACCEPTED",
                        operation,
                        "accepted"));
            });

        client.queue("hourglass", PLAYER, OPERATION).toCompletableFuture().join();
        assertEquals(1, adapterCreations.get());

        provider.set(new ReloadingLoreItemsClient.ProviderSnapshot(firstProvider, false));
        assertEquals(LoreItemsGatewayResult.Disposition.RETRY,
            client.queue("hourglass", PLAYER, OPERATION).toCompletableFuture().join().disposition());

        provider.set(new ReloadingLoreItemsClient.ProviderSnapshot(replacementProvider, true));
        assertEquals(LoreItemsGatewayResult.Disposition.ACCEPTED,
            client.queue("hourglass", PLAYER, OPERATION).toCompletableFuture().join().disposition());
        assertEquals(2, adapterCreations.get(), "replacement provider must receive a fresh adapter lookup");
    }
}
