package org.enthusia.tags.advancements.domain;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Session projection of read-only EnthusiaExpress mail history. */
public final class ExpressMilestoneProgress {
    public record Stats(
        int sentPackages,
        int sentLetters,
        int claimedPackages,
        int readLetters,
        int maxPackedItems,
        int returnedClaims
    ) {
        public Stats {
            if (sentPackages < 0 || sentLetters < 0 || claimedPackages < 0
                || readLetters < 0 || maxPackedItems < 0 || returnedClaims < 0) {
                throw new IllegalArgumentException("Invalid express statistics");
            }
        }
    }

    public record Update(Map<String, Integer> progress, Set<String> celebrate) {}

    private Map<String, Integer> known = Map.of();
    public Update observe(Stats stats) {
        if (stats == null) return new Update(known, Set.of());

        Map<String, Integer> next = new HashMap<>();
        next.put("express/first_class", scaled(stats.sentPackages(), 1));
        next.put("express/care_package", scaled(stats.maxPackedItems(), 64));
        next.put("express/frequent_shipper", scaled(stats.sentPackages(), 10));
        next.put("express/postal_legend", scaled(stats.sentPackages(), 50));
        next.put("express/pen_pal", scaled(stats.sentLetters(), 1));
        next.put("express/correspondent", scaled(stats.sentLetters(), 10));
        next.put("express/youve_got_mail", scaled(stats.claimedPackages(), 1));
        next.put("express/parcel_collector", scaled(stats.claimedPackages(), 10));
        next.put("express/read_all_about_it", scaled(stats.readLetters(), 1));
        next.put("express/avid_reader", scaled(stats.readLetters(), 10));
        next.put("express/return_to_sender", scaled(stats.returnedClaims(), 1));

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
