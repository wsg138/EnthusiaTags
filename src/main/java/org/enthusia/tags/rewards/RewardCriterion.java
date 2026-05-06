package org.enthusia.tags.rewards;

import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.entity.EntityType;

public final class RewardCriterion {
    private final RewardCriterionType type;
    private final RewardSourceType sourceType;
    private final long amount;
    private final Material material;
    private final Statistic statistic;
    private final EntityType entityType;
    private final String key;
    private final int maxY;
    private final String label;
    private final boolean valid;

    public RewardCriterion(RewardCriterionType type, long amount, Material material, String key, int maxY, String label) {
        this(type, RewardSourceType.LEGACY, amount, material, null, null, key, maxY, label, true);
    }

    public RewardCriterion(RewardCriterionType type,
                           RewardSourceType sourceType,
                           long amount,
                           Material material,
                           Statistic statistic,
                           EntityType entityType,
                           String key,
                           int maxY,
                           String label,
                           boolean valid) {
        this.type = type;
        this.sourceType = sourceType == null ? RewardSourceType.LEGACY : sourceType;
        this.amount = amount;
        this.material = material;
        this.statistic = statistic;
        this.entityType = entityType;
        this.key = key;
        this.maxY = maxY;
        this.label = label;
        this.valid = valid;
    }

    public RewardCriterionType getType() {
        return type;
    }

    public RewardSourceType getSourceType() {
        return sourceType;
    }

    public long getAmount() {
        return amount;
    }

    public Material getMaterial() {
        return material;
    }

    public Statistic getStatistic() {
        return statistic;
    }

    public EntityType getEntityType() {
        return entityType;
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

    public boolean isValid() {
        return valid;
    }
}
