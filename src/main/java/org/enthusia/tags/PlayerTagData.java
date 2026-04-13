package org.enthusia.tags;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerTagData {
    private final Set<String> ownedTags = ConcurrentHashMap.newKeySet();
    private volatile String selectedTag;
    private volatile boolean vanished;

    public Set<String> getOwnedTags() {
        return ownedTags;
    }

    public String getSelectedTag() {
        return selectedTag;
    }

    public void setSelectedTag(String selectedTag) {
        this.selectedTag = selectedTag;
    }

    public boolean isVanished() {
        return vanished;
    }

    public void setVanished(boolean vanished) {
        this.vanished = vanished;
    }
}
