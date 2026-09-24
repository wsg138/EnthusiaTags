package org.enthusia.tags.rewards;

public enum RewardStatus {
    LOCKED,
    UNLOCKED,
    CLAIM_PENDING,
    ITEM_QUEUED,
    CLAIMED,
    WITHHELD_NETWORK_LIMIT,
    DELIVERY_FAILED,
    REQUIRES_RECONCILIATION;

    /** Terminal for an individual action; withholding is not a deposit. */
    public boolean isActionSettled() {
        return this == CLAIMED || this == WITHHELD_NETWORK_LIMIT;
    }
}
