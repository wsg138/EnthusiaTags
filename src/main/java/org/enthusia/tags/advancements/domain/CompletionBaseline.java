package org.enthusia.tags.advancements.domain;
import java.util.HashSet;
import java.util.Set;
/** Session-local evidence; transition recognition is distinct from durable notification delivery. */
public final class CompletionBaseline {
    private final Set<String> observedIncomplete = new HashSet<>();
    private final Set<String> completed = new HashSet<>();
    private final Set<String> pendingLive = new HashSet<>();
    public boolean isLiveCompletion(String id, int verifiedProgress) {
        if (verifiedProgress < 0 || verifiedProgress > 1000 || completed.contains(id)) return false;
        if (verifiedProgress < 1000) { observedIncomplete.add(id); return false; }
        completed.add(id);
        boolean live = observedIncomplete.remove(id);
        if (live) pendingLive.add(id);
        return live;
    }
    /** Persistence failures may retry this completion without recognizing a second transition. */
    public boolean hasPendingLiveCompletion(String id) { return pendingLive.contains(id); }
    public void acknowledge(String id) { pendingLive.remove(id); }
}
