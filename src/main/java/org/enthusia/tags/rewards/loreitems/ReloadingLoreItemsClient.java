package org.enthusia.tags.rewards.loreitems;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Defers loading the typed V1 adapter until the LoreItems plugin is actually enabled.
 * This keeps EnthusiaLoreItems a soft dependency while still using the typed Bukkit
 * service contract once the provider is present.
 */
public final class ReloadingLoreItemsClient implements LoreItemsClient {
    private final JavaPlugin plugin;
    private volatile LoreItemsClient delegate;
    private volatile Plugin provider;

    public ReloadingLoreItemsClient(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public CompletionStage<LoreItemsGatewayResult> queue(
        String definitionKey,
        UUID playerId,
        String externalOperationId) {
        Plugin loreItems = plugin.getServer().getPluginManager().getPlugin("EnthusiaLoreItems");
        if (loreItems == null || !loreItems.isEnabled()) {
            delegate = null;
            provider = null;
            return unavailable(externalOperationId, "EnthusiaLoreItems is not enabled");
        }

        LoreItemsClient current = delegate;
        if (current == null || provider != loreItems) {
            try {
                current = new BukkitLoreItemsClient(plugin);
                delegate = current;
                provider = loreItems;
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
