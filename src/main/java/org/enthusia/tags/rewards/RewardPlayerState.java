package org.enthusia.tags.rewards;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class RewardPlayerState {
    private final Set<String> claimedRewards = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> counters = new ConcurrentHashMap<>();
    private final Map<String, String> states = new ConcurrentHashMap<>();
    private volatile boolean loaded;
    private volatile boolean dirty;

    Set<String> claimedRewards() {
        return claimedRewards;
    }

    Map<String, Long> counters() {
        return counters;
    }

    Map<String, String> states() {
        return states;
    }

    boolean isLoaded() {
        return loaded;
    }

    void setLoaded(boolean loaded) {
        this.loaded = loaded;
    }

    boolean isDirty() {
        return dirty;
    }

    void setDirty(boolean dirty) {
        this.dirty = dirty;
    }
}
