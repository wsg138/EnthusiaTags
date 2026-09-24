package org.enthusia.tags.advancements.domain;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
/** Remove work only after the renderer accepts it; failed calls remain retryable. */
public final class PendingCelebrations {
    private PendingCelebrations() {}
    public static void deliver(Set<String> pending, Map<String,Integer> progress, Consumer<String> delivery) {
        var iterator=pending.iterator();
        while(iterator.hasNext()) {
            String key=iterator.next();
            if(progress.getOrDefault(key,-1)!=1000) continue;
            delivery.accept(key);
            iterator.remove();
        }
    }
}
