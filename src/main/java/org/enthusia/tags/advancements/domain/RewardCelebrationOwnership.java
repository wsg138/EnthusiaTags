package org.enthusia.tags.advancements.domain;

/** Stable reward IDs, not display titles, identify provider-owned celebrations. */
public final class RewardCelebrationOwnership {
    private RewardCelebrationOwnership() {}

    public static boolean tagsCelebrates(String rewardId, boolean holidaysEnabled) {
        // Holidays celebrates holidays/forgotten_melody when it increments the
        // disc counter. Tags still projects and claims this reward normally.
        return !holidaysEnabled || !"watcher_melody".equals(rewardId);
    }
}
