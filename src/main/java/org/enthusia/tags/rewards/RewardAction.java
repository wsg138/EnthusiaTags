package org.enthusia.tags.rewards;

public final class RewardAction {
    private final RewardActionType type;
    private final String value;
    private final double amount;
    private final String label;

    public RewardAction(RewardActionType type, String value, double amount, String label) {
        this.type = type;
        this.value = value;
        this.amount = amount;
        this.label = label;
    }

    public RewardActionType getType() {
        return type;
    }

    public String getValue() {
        return value;
    }

    public double getAmount() {
        return amount;
    }

    public String getLabel() {
        return label;
    }
}
