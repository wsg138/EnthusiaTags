package org.enthusia.tags.rewards;

import org.bukkit.Material;
import java.util.List;

public final class RewardAction {
    private final String actionId;
    private final RewardActionType type;
    private final String value;
    private final double amount;
    private final String label;
    private final Material material;
    private final int itemAmount;
    private final String displayName;
    private final List<String> lore;

    public RewardAction(RewardActionType type, String value, double amount, String label) {
        this("", type, value, amount, label, null, 0, null, List.of());
    }

    public RewardAction(String actionId, RewardActionType type, String value, double amount, String label,
                        Material material, int itemAmount, String displayName, List<String> lore) {
        this.actionId = actionId;
        this.type = type;
        this.value = value;
        this.amount = amount;
        this.label = label;
        this.material = material;
        this.itemAmount = itemAmount;
        this.displayName = displayName;
        this.lore = lore == null ? List.of() : List.copyOf(lore);
    }

    public String getActionId() { return actionId; }
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

    public Material getMaterial() { return material; }
    public int getItemAmount() { return itemAmount; }
    public String getDisplayName() { return displayName; }
    public List<String> getLore() { return lore; }
}
