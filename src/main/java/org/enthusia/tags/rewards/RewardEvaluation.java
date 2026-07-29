package org.enthusia.tags.rewards;

import java.util.Map;

public record RewardEvaluation(RewardStatus status, Map<String, Long> progress,
                               boolean previouslyUnlocked, boolean claimable,
                               String diagnosticReason) {
    public RewardEvaluation {
        progress = progress == null ? Map.of() : Map.copyOf(progress);
        diagnosticReason = diagnosticReason == null ? "" : diagnosticReason;
    }
}
