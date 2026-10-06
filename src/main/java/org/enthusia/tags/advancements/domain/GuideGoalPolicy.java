package org.enthusia.tags.advancements.domain;

public final class GuideGoalPolicy {
    private GuideGoalPolicy() { }

    public static boolean supports(String status, boolean evidenceKnown) {
        return evidenceKnown && ("LOCKED".equals(status) || "UNLOCKED".equals(status));
    }

    public static boolean supportsCounter(String key) {
        return "stone_mined".equals(key) || "logs_mined".equals(key)
            || ProviderRewardEvidence.supportsCounter(key);
    }
}
