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
    private final Supplier<ProviderSnapshot> providerSupplier;
    private final Supplier<LoreItemsClient> clientFactory;
    private volatile LoreItemsClient delegate;
    private volatile Object provider;

    public ReloadingLoreItemsClient(JavaPlugin plugin) {
        this(providerSupplier(plugin), clientFactory(plugin));
    }

    ReloadingLoreItemsClient(
        Supplier<ProviderSnapshot> providerSupplier,
        Supplier<LoreItemsClient> clientFactory) {
        this.providerSupplier = Objects.requireNonNull(providerSupplier, "providerSupplier");
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory");
    }

    @Override
    public CompletionStage<LoreItemsGatewayResult> queue(
        String definitionKey,
        UUID playerId,
        String externalOperationId) {
        ProviderSnapshot snapshot = providerSupplier.get();
        if (snapshot == null || snapshot.provider() == null || !snapshot.enabled()) {
            delegate = null;
            provider = null;
            return unavailable(externalOperationId, "EnthusiaLoreItems is not enabled");
        }

        LoreItemsClient current = delegate;
        if (current == null || provider != snapshot.provider()) {
            try {
                current = clientFactory.get();
                delegate = current;
                provider = snapshot.provider();
            } catch (LinkageError error) {
                delegate = null;
                provider = null;
                return unavailable(externalOperationId,
                    "LoreItems V1 API is not linkable from the current plugin classloader: " + safeMessage(error));
            } catch (RuntimeException ex) {
                delegate = null;
                provider = null;
                return unavailable(externalOperationId,
                    "LoreItems V1 API adapter could not initialize: " + safeMessage(ex));
            }
        }

        try {
            return current.queue(definitionKey, playerId, externalOperationId);
        } catch (LinkageError error) {
            delegate = null;
            provider = null;
            return unavailable(externalOperationId,
                "LoreItems V1 API linkage changed during reload: " + safeMessage(error));
        }
    }

    record ProviderSnapshot(Object provider, boolean enabled) {
        static ProviderSnapshot unavailable() {
            return new ProviderSnapshot(null, false);
        }
    }

    private static Supplier<ProviderSnapshot> providerSupplier(JavaPlugin plugin) {
        JavaPlugin checkedPlugin = Objects.requireNonNull(plugin, "plugin");
        return () -> {
            Plugin loreItems = checkedPlugin.getServer().getPluginManager().getPlugin("EnthusiaLoreItems");
            if (loreItems == null) {
                return ProviderSnapshot.unavailable();
            }
            return new ProviderSnapshot(loreItems, loreItems.isEnabled());
        };
    }

    private static Supplier<LoreItemsClient> clientFactory(JavaPlugin plugin) {
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
