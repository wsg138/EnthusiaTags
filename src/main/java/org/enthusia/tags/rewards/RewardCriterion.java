package org.enthusia.tags.rewards;

import org.bukkit.Material;

public final class RewardCriterion {
    private final RewardCriterionType type;
    private final long amount;
    private final Material material;
    private final String key;
    private final int maxY;
    private final String label;

    public RewardCriterion(RewardCriterionType type, long amount, Material material, String key, int maxY, String label) {
        this.type = type;
        this.amount = amount;
        this.material = material;
        this.key = key;
        this.maxY = maxY;
        this.label = label;
    }

    public RewardCriterionType getType() {
        return type;
    }

    public long getAmount() {
        return amount;
    }

    public Material getMaterial() {
        return material;
    }

    public String getKey() {
        return key;
    }

    public int getMaxY() {
        return maxY;
    }

    public String getLabel() {
        return label;
    }
}
