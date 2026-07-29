package org.enthusia.tags.rewards;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Per-player mutable cache. Every mutation and snapshot is serialized on this
 * instance so a revision always describes the exact maps copied with it.
 */
final class RewardPlayerState {
    private final Set<String> claimedRewardIds = new HashSet<>();
    private final Map<String, Long> counterValues = new HashMap<>();
    private final Map<String, String> stateValues = new HashMap<>();
    private boolean loaded;
    private boolean dirty;
    private long revision;

    synchronized boolean isLoaded() {
        return loaded;
    }

    synchronized boolean isDirty() {
        return dirty;
    }

    synchronized boolean isClaimed(String rewardId) {
        return claimedRewardIds.contains(rewardId);
    }

    synchronized Set<String> claimedRewardsSnapshot() {
        return Set.copyOf(claimedRewardIds);
    }

    synchronized Map<String, Long> countersSnapshot() {
        return Map.copyOf(counterValues);
    }

    synchronized Map<String, String> statesSnapshot() {
        return Map.copyOf(stateValues);
    }

    synchronized long getCounter(String key) {
        return counterValues.getOrDefault(key, 0L);
    }

    synchronized long incrementCounter(String key, long delta) {
        long next = counterValues.getOrDefault(key, 0L) + delta;
        counterValues.put(key, next);
        changed();
        return next;
    }

    synchronized void setCounter(String key, long value) {
        if (counterValues.getOrDefault(key, 0L) == value && counterValues.containsKey(key)) {
            return;
        }
        counterValues.put(key, value);
        changed();
    }

    synchronized void raiseCounter(String key, long value) {
        if (value > counterValues.getOrDefault(key, 0L)) {
            counterValues.put(key, value);
            changed();
        }
    }

    synchronized void mergeCounters(Map<String, Long> deltas) {
        if (deltas.isEmpty()) {
            return;
        }
        deltas.forEach((key, value) -> counterValues.merge(key, value, Long::sum));
        changed();
    }

    synchronized String getState(String key) {
        return stateValues.get(key);
    }

    synchronized boolean hasState(String key) {
        return stateValues.containsKey(key);
    }

    synchronized void putState(String key, String value) {
        if (value.equals(stateValues.get(key))) {
            return;
        }
        stateValues.put(key, value);
        changed();
    }

    synchronized void removeState(String key) {
        if (stateValues.remove(key) != null) {
            changed();
        }
    }

    synchronized Set<String> stateKeysWithPrefix(String prefix) {
        Set<String> result = new HashSet<>();
        for (String key : stateValues.keySet()) {
            if (key.startsWith(prefix)) {
                result.add(key);
            }
        }
        return result;
    }

    synchronized void setOverall(String rewardId, RewardStatus status) {
        stateValues.put("reward-delivery:" + rewardId, status.name());
        changed();
    }

    synchronized void clearOverall(String rewardId) {
        if (stateValues.remove("reward-delivery:" + rewardId) != null) {
            changed();
        }
    }

    synchronized void markClaimed(String rewardId) {
        claimedRewardIds.add(rewardId);
        stateValues.put("reward-delivery:" + rewardId, RewardStatus.CLAIMED.name());
        changed();
    }

    synchronized void applyReconciliation(String rewardId, RewardStatus overall, boolean claimed,
                                          Set<String> removedKeys, boolean legacyUnmapped, long storedRevision) {
        removedKeys.forEach(stateValues::remove);
        if (legacyUnmapped) {
            stateValues.put("reward-legacy-unmapped:" + rewardId, "requires-force-resolution");
        } else {
            stateValues.remove("reward-legacy-unmapped:" + rewardId);
        }
        stateValues.put("reward-delivery:" + rewardId, overall.name());
        if (claimed) {
            claimedRewardIds.add(rewardId);
        } else {
            claimedRewardIds.remove(rewardId);
        }
        revision = Math.max(revision + 1L, storedRevision + 1L);
        dirty = true;
    }

    synchronized void applyDurableFinalization(String rewardId, long storedRevision) {
        claimedRewardIds.add(rewardId);
        stateValues.put("reward-delivery:" + rewardId, RewardStatus.CLAIMED.name());
        revision = Math.max(revision + 1L, storedRevision + 1L);
        dirty = true;
    }

    synchronized LegacyMutation resolveLegacy(String rewardId, boolean delivered) {
        String prefix = "reward-action:" + rewardId + ":";
        Set<String> removed = stateKeysWithPrefix(prefix);
        String previousOverall = stateValues.get("reward-delivery:" + rewardId);
        removed.forEach(stateValues::remove);
        if (delivered) {
            stateValues.put("reward-legacy-unmapped:" + rewardId, "requires-force-resolution");
            stateValues.put("reward-delivery:" + rewardId, RewardStatus.REQUIRES_RECONCILIATION.name());
        } else {
            stateValues.remove("reward-legacy-unmapped:" + rewardId);
            stateValues.put("reward-delivery:" + rewardId, RewardStatus.DELIVERY_FAILED.name());
        }
        changed();
        return new LegacyMutation(Set.copyOf(removed), previousOverall);
    }

    synchronized void hydrate(RewardStorage.StoredRewardData data) {
        claimedRewardIds.clear();
        claimedRewardIds.addAll(data.claims());
        counterValues.clear();
        counterValues.putAll(data.counters());
        stateValues.clear();
        stateValues.putAll(data.states());
        revision = Math.max(0L, data.revision());
        Long oldPvpDeaths = counterValues.remove("PVP_DEATHS");
        if (oldPvpDeaths != null) {
            counterValues.merge("pvp_deaths", oldPvpDeaths, Math::max);
            changed();
        } else {
            dirty = false;
        }
        loaded = true;
    }

    synchronized RewardStorage.StoredRewardData snapshot() {
        return new RewardStorage.StoredRewardData(
            new HashSet<>(claimedRewardIds),
            new HashMap<>(counterValues),
            new HashMap<>(stateValues),
            revision);
    }

    synchronized RewardStorage.StoredRewardData barrierSnapshot() {
        revision++;
        dirty = true;
        return snapshot();
    }

    synchronized void markClean(long savedRevision) {
        if (revision == savedRevision) {
            dirty = false;
        }
    }

    private void changed() {
        revision++;
        dirty = true;
    }

    record LegacyMutation(Set<String> removedKeys, String previousOverall) {
    }
}
