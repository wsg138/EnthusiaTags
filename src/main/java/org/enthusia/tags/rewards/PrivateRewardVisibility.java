package org.enthusia.tags.rewards;

/** Public rewards retain existing behavior; private rewards require durable eligibility. */
public final class PrivateRewardVisibility {
    private PrivateRewardVisibility() {}
    public static boolean reveal(boolean hidden, boolean durablyEarned) {
        return !hidden || durablyEarned;
    }
}
