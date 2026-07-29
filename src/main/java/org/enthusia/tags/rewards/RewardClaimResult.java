package org.enthusia.tags.rewards;

public enum RewardClaimResult {
    SUCCESS,
    LOADING,
    ALREADY_CLAIMED,
    NOT_READY,
    IP_ALREADY_CLAIMED,
    DELIVERY_FAILED,
    ITEM_QUEUED,
    RECONCILIATION_REQUIRED,
    CLAIM_IN_PROGRESS,
    SERVICE_UNAVAILABLE
}
