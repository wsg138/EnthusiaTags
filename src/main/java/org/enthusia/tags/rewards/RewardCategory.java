package org.enthusia.tags.rewards;

import org.bukkit.Material;

public record RewardCategory(String id, String name, Material icon, String parent, java.util.List<String> tags) {
    public RewardCategory(String id, String name, Material icon) {
        this(id, name, icon, null, java.util.List.of());
    }
    public RewardCategory {
        tags = java.util.List.copyOf(tags);
    }
}
