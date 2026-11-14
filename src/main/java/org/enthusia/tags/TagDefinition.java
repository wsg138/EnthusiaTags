package org.enthusia.tags;

import org.bukkit.Material;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class TagDefinition {
    private final String id;
    private final String displayName;
    private final String tagText;
    private final Material icon;
    private final List<String> description;
    private final String hiddenUntilReward;

    public TagDefinition(String id, String displayName, String tagText, Material icon, List<String> description) {
        this(id, displayName, tagText, icon, description, null);
    }

    public TagDefinition(String id, String displayName, String tagText, Material icon, List<String> description, String hiddenUntilReward) {
        this.id = Objects.requireNonNull(id, "id");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.tagText = Objects.requireNonNull(tagText, "tagText");
        this.icon = icon == null ? Material.NAME_TAG : icon;
        this.description = description == null ? List.of() : List.copyOf(description);
        this.hiddenUntilReward = hiddenUntilReward == null || hiddenUntilReward.isBlank() ? null : hiddenUntilReward.toLowerCase(java.util.Locale.ROOT);
    }

    public String getHiddenUntilReward() { return hiddenUntilReward; }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getTagText() {
        return tagText;
    }

    public Material getIcon() {
        return icon;
    }

    public List<String> getDescription() {
        return Collections.unmodifiableList(description);
    }
}
