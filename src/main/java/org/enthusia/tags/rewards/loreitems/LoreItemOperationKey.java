package org.enthusia.tags.rewards.loreitems;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public final class LoreItemOperationKey {
    private static final String PREFIX = "enthusiatags:loreitem:v1:";

    private LoreItemOperationKey() {
    }

    public static String forRewardAction(UUID playerId, String rewardId, String actionId) {
        Objects.requireNonNull(playerId, "playerId");
        byte[] reward = canonicalPart(rewardId, "rewardId").getBytes(StandardCharsets.UTF_8);
        byte[] action = canonicalPart(actionId, "actionId").getBytes(StandardCharsets.UTF_8);
        MessageDigest digest = sha256();
        ByteBuffer identity = ByteBuffer.allocate(Long.BYTES * 2 + Integer.BYTES * 2 + reward.length + action.length);
        identity.putLong(playerId.getMostSignificantBits());
        identity.putLong(playerId.getLeastSignificantBits());
        identity.putInt(reward.length).put(reward);
        identity.putInt(action.length).put(action);
        return PREFIX + HexFormat.of().formatHex(digest.digest(identity.array()));
    }

    private static String canonicalPart(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java runtime does not provide SHA-256", impossible);
        }
    }
}
