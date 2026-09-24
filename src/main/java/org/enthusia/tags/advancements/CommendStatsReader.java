package org.enthusia.tags.advancements;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.enthusia.tags.advancements.domain.ReputationMilestoneProgress;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Strict read-only adapter for EnthusiaCommend advancement evidence in data.yml. */
final class CommendStatsReader {
    static Map<UUID, ReputationMilestoneProgress.Stats> parse(String yaml) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        ConfigurationSection evidence = config.getConfigurationSection("advancementEvidence");
        if (evidence == null && config.contains("advancementEvidence")) {
            throw new IllegalArgumentException("Invalid advancementEvidence section");
        }
        if (evidence == null) {
            Object version = config.get("dataVersion");
            if (version instanceof Integer value && value >= 9) {
                return Map.of();
            }
            throw new IllegalArgumentException("Missing advancementEvidence section");
        }

        Map<UUID, ReputationMilestoneProgress.Stats> result = new HashMap<>();
        for (String key : evidence.getKeys(false)) {
            UUID playerId = UUID.fromString(key);
            if (!playerId.toString().equalsIgnoreCase(key)) {
                throw new IllegalArgumentException("Invalid player UUID");
            }
            ConfigurationSection player = evidence.getConfigurationSection(key);
            if (player == null) {
                throw new IllegalArgumentException("Invalid reputation evidence record");
            }
            if (result.putIfAbsent(playerId, parsePlayer(player)) != null) {
                throw new IllegalArgumentException("Duplicate player UUID in reputation evidence");
            }
        }
        return Map.copyOf(result);
    }
    private static ReputationMilestoneProgress.Stats parsePlayer(ConfigurationSection player) {
        boolean positiveReceived = bool(player.get("positiveReceived"));
        int maxOverall = nonNegative(player.get("maxOverall"));
        int minOverall = nonPositive(player.get("minOverall"));
        boolean recovered = bool(player.get("recoveredFromSevere"));

        ConfigurationSection categories = player.getConfigurationSection("categoryMax");
        if (categories == null && player.contains("categoryMax")) {
            throw new IllegalArgumentException("Invalid categoryMax section");
        }
        return new ReputationMilestoneProgress.Stats(
            positiveReceived,
            maxOverall,
            minOverall,
            recovered,
            category(categories, "WAS_KIND"),
            category(categories, "GAVE_ITEMS"),
            category(categories, "TRUSTWORTHY"),
            category(categories, "GOOD_STALL")
        );
    }

    private static int category(ConfigurationSection categories, String key) {
        if (categories == null || categories.get(key) == null) return 0;
        return nonNegative(categories.get(key));
    }

    private static boolean bool(Object value) {
        if (!(value instanceof Boolean result)) {
            throw new IllegalArgumentException("Invalid or absent reputation boolean");
        }
        return result;
    }

    private static int nonNegative(Object value) {
        if (!(value instanceof Integer result) || result < 0) {
            throw new IllegalArgumentException("Invalid or absent non-negative reputation counter");
        }
        return result;
    }

    private static int nonPositive(Object value) {
        if (!(value instanceof Integer result) || result > 0) {
            throw new IllegalArgumentException("Invalid or absent non-positive reputation counter");
        }
        return result;
    }
}
