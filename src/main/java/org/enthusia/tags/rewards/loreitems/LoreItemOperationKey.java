package org.enthusia.tags.rewards.loreitems;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public final class LoreItemOperationKey {
    private static final String PREFIX = "enthusiatags:loreitem:v1:";

    private LoreItemOperationKey() {
    }

    public static String forRewardAction(UUID playerId, String rewardId, String actionId) {
        Objects.requireNonNull(playerId, "playerId");
        String reward = canonicalPart(rewardId, "rewardId");
        String action = canonicalPart(actionId, "actionId");
        return PREFIX + playerId + ':' + reward.length() + ':' + reward + ':'
            + action.length() + ':' + action;
    }

    private static String canonicalPart(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
