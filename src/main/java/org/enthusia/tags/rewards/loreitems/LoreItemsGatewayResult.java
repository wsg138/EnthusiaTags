package org.enthusia.tags.rewards.loreitems;

import java.util.Objects;

public record LoreItemsGatewayResult(
    Disposition disposition,
    String serviceStatus,
    String externalOperationId,
    String detail) {

    public LoreItemsGatewayResult {
        Objects.requireNonNull(disposition, "disposition");
        Objects.requireNonNull(serviceStatus, "serviceStatus");
        Objects.requireNonNull(externalOperationId, "externalOperationId");
        detail = detail == null ? "" : detail;
    }

    public enum Disposition {
        ACCEPTED,
        RETRY,
        REVIEW
    }
}
