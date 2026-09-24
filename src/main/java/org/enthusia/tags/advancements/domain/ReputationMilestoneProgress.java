package org.enthusia.tags.advancements.domain;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Session projection of provider-owned reputation evidence; never pays rewards. */
public final class ReputationMilestoneProgress {
    public record Stats(
        boolean positiveReceived,
        int maxOverall,
        int minOverall,
        boolean recoveredFromSevere,
        int kindMax,
        int generousMax,
        int trustworthyMax,
        int goodStallMax
    ) {
        public Stats {
            if (maxOverall < 0 || minOverall > 0
                || kindMax < 0 || generousMax < 0
                || trustworthyMax < 0 || goodStallMax < 0) {
                throw new IllegalArgumentException("Invalid reputation advancement evidence");
            }
        }
    }

    public record Update(Map<String, Integer> progress, Set<String> celebrate) {}

    private Map<String, Integer> known = Map.of();
    public Update observe(Stats stats) {
        if (stats == null) return new Update(known, Set.of());

        Map<String, Integer> next = new HashMap<>();
        next.put("reputation/a_good_word", stats.positiveReceived() ? 1000 : 0);
        next.put("reputation/well_regarded", scaledPositive(stats.maxOverall(), 10));
        next.put("reputation/pillar_of_the_community", scaledPositive(stats.maxOverall(), 20));
        next.put("reputation/bad_reputation", scaledNegative(stats.minOverall(), 10));
        next.put("reputation/public_enemy", scaledNegative(stats.minOverall(), 25));
        next.put("reputation/redemption_arc", stats.recoveredFromSevere() ? 1000 : 0);
        next.put("reputation/kind_soul", scaledPositive(stats.kindMax(), 5));
        next.put("reputation/generous_spirit", scaledPositive(stats.generousMax(), 5));
        next.put("reputation/trusted_name", scaledPositive(stats.trustworthyMax(), 5));
        next.put("reputation/merchant_of_merit", scaledPositive(stats.goodStallMax(), 5));

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

    private static int scaledPositive(int value, int threshold) {
        if (value <= 0) return 0;
        return (int) Math.min(1000L, (long) value * 1000 / threshold);
    }

    private static int scaledNegative(int value, int threshold) {
        if (value >= 0) return 0;
        return (int) Math.min(1000L, -(long) value * 1000 / threshold);
    }
}
