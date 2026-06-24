package org.enthusia.tags.rewards;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class RewardPlayerState {
    private final Set<String> claimedRewardIds = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> counterValues = new ConcurrentHashMap<>();
    private final Map<String, String> stateValues = new ConcurrentHashMap<>();
    private volatile boolean loaded;
    private volatile boolean dirty;

    Set<String> claimedRewards() {
        return claimedRewardIds;
    }

    Map<String, Long> counters() {
        return counterValues;
    }

    Map<String, String> states() {
        return stateValues;
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
