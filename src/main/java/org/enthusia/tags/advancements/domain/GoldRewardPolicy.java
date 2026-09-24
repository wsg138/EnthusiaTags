package org.enthusia.tags.advancements.domain;

/** Typed rewards only: arbitrary console commands cannot be classified safely. */
public final class GoldRewardPolicy {
    private GoldRewardPolicy() { }

    public static boolean isNetworkLimited(String actionType, String material) {
        return "MONEY".equals(actionType) || ("ITEM".equals(actionType)
            && ("RAW_GOLD".equals(material) || "RAW_GOLD_BLOCK".equals(material)));
    }
}
