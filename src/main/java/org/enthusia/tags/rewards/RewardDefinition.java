package org.enthusia.tags.rewards;

import org.bukkit.Material;

import java.util.List;
import java.util.Objects;

public final class RewardDefinition {
    private final String id;
    private final String name;
    private final List<String> description;
    private final Material icon;
    private final List<RewardCriterion> criteria;
    private final List<RewardAction> actions;
    private final String category;
    private final RewardCompletionMode completionMode;

    public RewardDefinition(String id,
                            String name,
                            List<String> description,
                            Material icon,
                            List<RewardCriterion> criteria,
                            List<RewardAction> actions,
                            String category) {
        this(id, name, description, icon, criteria, actions, category, RewardCompletionMode.LATCHED);
    }

    public RewardDefinition(String id, String name, List<String> description, Material icon,
                            List<RewardCriterion> criteria, List<RewardAction> actions, String category,
                            RewardCompletionMode completionMode) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        this.description = description == null ? List.of() : List.copyOf(description);
        this.icon = icon == null ? Material.NAME_TAG : icon;
        this.criteria = criteria == null ? List.of() : List.copyOf(criteria);
        this.actions = actions == null ? List.of() : List.copyOf(actions);
        this.category = category == null ? "misc" : category;
        this.completionMode = completionMode == null ? RewardCompletionMode.LATCHED : completionMode;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public List<String> getDescription() {
        return description;
    }

    public Material getIcon() {
        return icon;
    }

    public List<RewardCriterion> getCriteria() {
        return criteria;
    }

    public List<RewardAction> getActions() {
        return actions;
    }

    public String getCategory() {
        return category;
    }

    public RewardCompletionMode getCompletionMode() {
        return completionMode;
    }
}
