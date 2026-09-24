package org.enthusia.tags.advancements.domain;

import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;

/** Session projection of provider-owned duel statistics; never pays rewards. */
public final class DuelMilestoneProgress {
    public record Stats(
        int wins,
        int bestStreak,
        int challengesSent,
        int spoilsClaims,
        int mutualDraws,
        int customRulesWins,
        int restrictedMobilityWins,
        int lowHealthWins
    ) {
        public Stats(int wins, int bestStreak) {
            this(wins, bestStreak, 0, 0, 0, 0, 0, 0);
        }

        public Stats {
            if (wins < 0 || bestStreak < 0 || bestStreak > wins
                || challengesSent < 0 || spoilsClaims < 0 || mutualDraws < 0
                || customRulesWins < 0 || restrictedMobilityWins < 0 || lowHealthWins < 0) {
                throw new IllegalArgumentException("Invalid duel stats");
            }
        }
    }

    public record Update(Map<String, Integer> progress, Set<String> celebrate) {}

    private Map<String, Integer> known = Map.of();

    public Update observe(Stats stats) {
        if (stats == null) return new Update(known, Set.of());
        Map<String, Integer> next = new HashMap<>(Map.of(
            "warzone_duels/welcome_to_thunderdome", scaled(stats.challengesSent(), 1),
            "warzone_duels/first_blood", scaled(stats.wins(), 1),
            "warzone_duels/victor_spoils", scaled(stats.spoilsClaims(), 1),
            "warzone_duels/price_for_peace", scaled(stats.mutualDraws(), 1),
            "warzone_duels/my_house_my_rules", scaled(stats.customRulesWins(), 1),
            "warzone_duels/adapt_and_overcome", scaled(stats.restrictedMobilityWins(), 1),
            "warzone_duels/not_even_close", scaled(stats.lowHealthWins(), 1),
            "warzone_duels/unstoppable", scaled(stats.bestStreak(), 5),
            "warzone_duels/gladiator", scaled(stats.wins(), 50)
        ));
        Set<String> celebrate = new HashSet<>();
        next.replaceAll((key, value) -> Math.max(value, known.getOrDefault(key, 0)));
        if (!known.isEmpty()) next.forEach((key, value) -> {
            if (value == 1000 && known.getOrDefault(key, 0) < 1000) {
                celebrate.add(key);
            }
        });
        known = Map.copyOf(next);
        return new Update(known, Set.copyOf(celebrate));
    }

    private static int scaled(int value, int threshold) {
        return (int) Math.min(1000L, (long) value * 1000 / threshold);
    }
}
