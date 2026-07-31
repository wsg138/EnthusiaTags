package org.enthusia.tags.rewards;

enum PendingClaimRecoveryDecision {
    RESUME,
    SUCCESS,
    ITEM_QUEUED,
    RECONCILIATION_REQUIRED,
    IN_PROGRESS;

    static PendingClaimRecoveryDecision from(RewardStatus status) {
        if (status == null || status == RewardStatus.DELIVERY_FAILED) {
            return RESUME;
        }
        return switch (status) {
            case CLAIMED -> SUCCESS;
            case ITEM_QUEUED -> ITEM_QUEUED;
            case REQUIRES_RECONCILIATION -> RECONCILIATION_REQUIRED;
            default -> IN_PROGRESS;
        };
    }

    RewardClaimResult result() {
        return switch (this) {
            case SUCCESS -> RewardClaimResult.SUCCESS;
            case ITEM_QUEUED -> RewardClaimResult.ITEM_QUEUED;
            case RECONCILIATION_REQUIRED -> RewardClaimResult.RECONCILIATION_REQUIRED;
            case IN_PROGRESS -> RewardClaimResult.CLAIM_IN_PROGRESS;
            case RESUME -> throw new IllegalStateException("Resume decisions do not have a terminal result");
        };
    }
}
