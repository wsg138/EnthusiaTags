package org.enthusia.tags.rewards;

/** Actions are kept in the server-side holder, not accepted from player item metadata. */
public record RewardMenuAction(Type type, String value) {
    public enum Type { CATEGORY, BACK, READY, FILTER, SORT, GROUP, PREVIOUS, NEXT, CLOSE, REFRESH, CLAIM, TAGS, COSMETICS }
    public RewardMenuAction(Type type) { this(type, ""); }
}
