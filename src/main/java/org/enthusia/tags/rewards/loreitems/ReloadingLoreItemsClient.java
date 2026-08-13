package org.enthusia.tags.rewards.loreitems;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * Defers loading the typed V1 adapter until the LoreItems plugin is actually enabled.
 * This keeps EnthusiaLoreItems a soft dependency while still using the typed Bukkit
 * service contract once the provider is present.
 */
public final class ReloadingLoreItemsClient implements LoreItemsClient {
    private static final Object UNAVAILABLE_PROVIDER = new Object();
    private static final LoreItemsClient EMPTY_CLIENT = (definitionKey, playerId, externalOperationId) ->
        unavailable(externalOperationId, "EnthusiaLoreItems is not enabled");

    private final Supplier<ProviderSnapshot> providerLookup;
    private final Supplier<LoreItemsClient> adapterFactory;
    private volatile AdapterCache cache = AdapterCache.empty();

    public ReloadingLoreItemsClient(JavaPlugin plugin) {
        this(createProviderLookup(plugin), createAdapterFactory(plugin));
    }

    ReloadingLoreItemsClient(
        Supplier<ProviderSnapshot> providerLookup,
        Supplier<LoreItemsClient> adapterFactory) {
        this.providerLookup = Objects.requireNonNull(providerLookup, "providerLookup");
        this.adapterFactory = Objects.requireNonNull(adapterFactory, "adapterFactory");
    }

    @Override
    public CompletionStage<LoreItemsGatewayResult> queue(
        String definitionKey,
        UUID playerId,
        String externalOperationId) {
        ProviderSnapshot snapshot = providerLookup.get();
        if (!snapshot.available()) {
            resetCache();
            return unavailable(externalOperationId, "EnthusiaLoreItems is not enabled");
        }
        try {
            LoreItemsClient current = clientFor(snapshot);
            return invoke(current, definitionKey, playerId, externalOperationId);
        } catch (LinkageError error) {
            resetCache();
            return unavailable(externalOperationId,
                "LoreItems V1 API is not linkable from the current plugin classloader: " + safeMessage(error));
        } catch (RuntimeException ex) {
            resetCache();
            return unavailable(externalOperationId,
                "LoreItems V1 API adapter could not initialize: " + safeMessage(ex));
        }
    }

    private LoreItemsClient clientFor(ProviderSnapshot snapshot) {
        AdapterCache current = cache;
        if (current.matches(snapshot.provider())) {
            return current.client();
        }
        LoreItemsClient created = Objects.requireNonNull(adapterFactory.get(), "adapterFactory returned null");
        cache = new AdapterCache(snapshot.provider(), created);
        return created;
    }

    private CompletionStage<LoreItemsGatewayResult> invoke(
        LoreItemsClient current,
        String definitionKey,
        UUID playerId,
        String externalOperationId) {
        try {
            return current.queue(definitionKey, playerId, externalOperationId);
        } catch (LinkageError error) {
            resetCache();
            return unavailable(externalOperationId,
                "LoreItems V1 API linkage changed during reload: " + safeMessage(error));
        }
    }

    private void resetCache() {
        cache = AdapterCache.empty();
    }

    record ProviderSnapshot(Object provider, boolean enabled) {
        ProviderSnapshot {
            Objects.requireNonNull(provider, "provider");
        }

        static ProviderSnapshot unavailable() {
            return new ProviderSnapshot(UNAVAILABLE_PROVIDER, false);
        }

        boolean available() {
            return enabled && !Objects.equals(provider, UNAVAILABLE_PROVIDER);
        }
    }

    private record AdapterCache(Object provider, LoreItemsClient client) {
        private AdapterCache {
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(client, "client");
        }

        static AdapterCache empty() {
            return new AdapterCache(UNAVAILABLE_PROVIDER, EMPTY_CLIENT);
        }

        boolean matches(Object candidate) {
            return !Objects.equals(provider, UNAVAILABLE_PROVIDER) && Objects.equals(provider, candidate);
        }
    }

    private static Supplier<ProviderSnapshot> createProviderLookup(JavaPlugin plugin) {
        JavaPlugin checkedPlugin = Objects.requireNonNull(plugin, "plugin");
        return () -> {
            Plugin loreItems = checkedPlugin.getServer().getPluginManager().getPlugin("EnthusiaLoreItems");
            if (loreItems == null) {
                return ProviderSnapshot.unavailable();
            }
            return new ProviderSnapshot(loreItems, loreItems.isEnabled());
        };
    }

    private static Supplier<LoreItemsClient> createAdapterFactory(JavaPlugin plugin) {
        JavaPlugin checkedPlugin = Objects.requireNonNull(plugin, "plugin");
        return () -> new BukkitLoreItemsClient(checkedPlugin);
    }

    private static CompletionStage<LoreItemsGatewayResult> unavailable(
        String externalOperationId,
        String detail) {
        return CompletableFuture.completedFuture(new LoreItemsGatewayResult(
            LoreItemsGatewayResult.Disposition.RETRY,
            "SERVICE_UNAVAILABLE",
            externalOperationId == null ? "" : externalOperationId,
            detail));
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }
}
