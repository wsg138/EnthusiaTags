package org.enthusia.tags.rewards.loreitems;

import net.enthusia.loreitems.api.v1.LoreDeliveryResult;
import net.enthusia.loreitems.api.v1.LoreDeliveryStatus;
import net.enthusia.loreitems.api.v1.LoreItemsServiceV1;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class BukkitLoreItemsClient implements LoreItemsClient {
    static final long SERVICE_TIMEOUT_SECONDS = 10L;

    private final Supplier<LoreItemsServiceV1> serviceSupplier;

    public BukkitLoreItemsClient(JavaPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        ServicesManager services = plugin.getServer().getServicesManager();
        this.serviceSupplier = () -> services.load(LoreItemsServiceV1.class);
    }

    BukkitLoreItemsClient(Supplier<LoreItemsServiceV1> serviceSupplier) {
        this.serviceSupplier = Objects.requireNonNull(serviceSupplier, "serviceSupplier");
    }

    @Override
    public CompletionStage<LoreItemsGatewayResult> queue(
        String definitionKey,
        UUID playerId,
        String externalOperationId) {
        if (definitionKey == null || definitionKey.isBlank()
            || playerId == null || externalOperationId == null || externalOperationId.isBlank()) {
            return CompletableFuture.completedFuture(new LoreItemsGatewayResult(
                LoreItemsGatewayResult.Disposition.REVIEW,
                LoreDeliveryStatus.VALIDATION_FAILURE.name(),
                externalOperationId == null ? "" : externalOperationId,
                "Tags rejected a blank LoreItems definition, player UUID, or external operation id"));
        }

        LoreItemsServiceV1 service;
        try {
            service = serviceSupplier.get();
        } catch (RuntimeException ex) {
            return retry(externalOperationId, "SERVICE_LOOKUP_FAILURE", safeMessage(ex));
        }
        if (service == null) {
            return retry(externalOperationId, LoreDeliveryStatus.SERVICE_UNAVAILABLE.name(),
                "LoreItemsServiceV1 is not registered");
        }

        CompletionStage<LoreDeliveryResult> stage;
        try {
            stage = service.queueDelivery(definitionKey, playerId, externalOperationId);
        } catch (RuntimeException ex) {
            return retry(externalOperationId, "QUEUE_INVOCATION_FAILURE", safeMessage(ex));
        }
        if (stage == null) {
            return retry(externalOperationId, "NULL_STAGE", "LoreItems returned a null completion stage");
        }

        CompletableFuture<LoreDeliveryResult> bounded = new CompletableFuture<>();
        try {
            stage.whenComplete((result, failure) -> {
                if (failure != null) {
                    bounded.completeExceptionally(failure);
                } else {
                    bounded.complete(result);
                }
            });
        } catch (RuntimeException ex) {
            return retry(externalOperationId, "STAGE_REGISTRATION_FAILURE", safeMessage(ex));
        }

        return bounded.orTimeout(SERVICE_TIMEOUT_SECONDS, TimeUnit.SECONDS).handle((result, failure) -> {
            if (failure != null) {
                return new LoreItemsGatewayResult(
                    LoreItemsGatewayResult.Disposition.RETRY,
                    failure instanceof java.util.concurrent.TimeoutException ? "TIMEOUT" : "ASYNC_FAILURE",
                    externalOperationId,
                    safeMessage(failure));
            }
            if (result == null) {
                return new LoreItemsGatewayResult(
                    LoreItemsGatewayResult.Disposition.RETRY,
                    "NULL_RESULT",
                    externalOperationId,
                    "LoreItems completed without a result");
            }
            if (!externalOperationId.equals(result.externalOperationId())) {
                return new LoreItemsGatewayResult(
                    LoreItemsGatewayResult.Disposition.REVIEW,
                    "OPERATION_ID_MISMATCH",
                    externalOperationId,
                    "LoreItems response operation id did not match the submitted id");
            }
            return mapResult(result);
        });
    }

    private static LoreItemsGatewayResult mapResult(LoreDeliveryResult result) {
        LoreItemsGatewayResult.Disposition disposition = switch (result.status()) {
            case ACCEPTED_QUEUED, ALREADY_ACCEPTED -> LoreItemsGatewayResult.Disposition.ACCEPTED;
            case SERVICE_UNAVAILABLE -> LoreItemsGatewayResult.Disposition.RETRY;
            case UNKNOWN_DEFINITION, VALIDATION_FAILURE -> LoreItemsGatewayResult.Disposition.REVIEW;
        };
        return new LoreItemsGatewayResult(
            disposition,
            result.status().name(),
            result.externalOperationId(),
            result.detail());
    }

    private static CompletionStage<LoreItemsGatewayResult> retry(
        String externalOperationId,
        String status,
        String detail) {
        return CompletableFuture.completedFuture(new LoreItemsGatewayResult(
            LoreItemsGatewayResult.Disposition.RETRY,
            status,
            externalOperationId,
            detail));
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
