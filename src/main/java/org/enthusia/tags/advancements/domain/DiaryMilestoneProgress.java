package org.enthusia.tags.advancements.domain;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Session projection of provider-owned DiaryKeeper evidence; never pays rewards. */
public final class DiaryMilestoneProgress {
    public record Stats(
        boolean received,
        int edits,
        int destructionAttempts,
        int voidReturns,
        int containerAttempts,
        int groundPickups
    ) {
        public Stats {
            if (edits < 0 || destructionAttempts < 0 || voidReturns < 0
                || containerAttempts < 0 || groundPickups < 0) {
                throw new IllegalArgumentException("Invalid diary advancement evidence");
            }
        }
    }

    public record Update(Map<String, Integer> progress, Set<String> celebrate) {}

    private Map<String, Integer> known = Map.of();
    public Update observe(Stats stats) {
        if (stats == null) return new Update(known, Set.of());

        Map<String, Integer> next = new HashMap<>();
        next.put("diary/dear_diary", stats.received() ? 1000 : 0);
        next.put("diary/first_entry", scaled(stats.edits(), 1));
        next.put("diary/prolific_writer", scaled(stats.edits(), 25));
        next.put("diary/indestructible", scaled(stats.destructionAttempts(), 1));
        next.put("diary/stubborn", scaled(stats.destructionAttempts(), 10));
        next.put("diary/void_walker", scaled(stats.voidReturns(), 1));
        next.put("diary/nice_try", scaled(stats.containerAttempts(), 1));
        next.put("diary/finders_keepers", scaled(stats.groundPickups(), 1));

        Set<String> celebrate = new HashSet<>();
        next.replaceAll((key, value) -> Math.max(value, known.getOrDefault(key, 0)));
        if (!known.isEmpty()) {
            next.forEach((key, value) -> {
                if (value == 1000 && known.getOrDefault(key, 0) < 1000) {
                    celebrate.add(key);
                }
            });
        }
        known = Map.copyOf(next);
        return new Update(known, Set.copyOf(celebrate));
    }

    private static int scaled(int value, int threshold) {
        return (int) Math.min(1000L, (long) value * 1000 / threshold);
    }
}
