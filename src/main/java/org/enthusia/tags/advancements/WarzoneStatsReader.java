package org.enthusia.tags.advancements;

import java.util.Map;
import java.util.UUID;
import java.util.HashMap;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.enthusia.tags.advancements.domain.DuelMilestoneProgress;

/** Strict adapter for WarzoneDuels' persisted statistics format. */
final class WarzoneStatsReader {
    static Map<UUID, DuelMilestoneProgress.Stats> parse(String yaml) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        ConfigurationSection players = config.getConfigurationSection("players");
        if (players == null) throw new IllegalArgumentException("Missing players section");
        Map<UUID, DuelMilestoneProgress.Stats> result = new HashMap<>();
        for (String key : players.getKeys(false)) {
            if (result.putIfAbsent(playerId(key), playerStats(players.getConfigurationSection(key))) != null) {
                throw new IllegalArgumentException("Duplicate player UUID in duel evidence");
            }
        }
        return Map.copyOf(result);
    }

    private static UUID playerId(String key) {
        UUID id = UUID.fromString(key);
        if (!id.toString().equalsIgnoreCase(key)) throw new IllegalArgumentException("Invalid player UUID");
        return id;
    }

    private static DuelMilestoneProgress.Stats playerStats(ConfigurationSection player) {
        if (player == null) throw new IllegalArgumentException("Invalid player record");
        ConfigurationSection evidence = player.getConfigurationSection("advancements");
        if (evidence == null && player.contains("advancements")) {
            throw new IllegalArgumentException("Invalid advancements section");
        }
        return new DuelMilestoneProgress.Stats(
            counter(player.get("wins")), counter(player.get("best-win-streak")),
            optionalCounter(evidence, "challenges-sent"), optionalCounter(evidence, "spoils-claims"),
            optionalCounter(evidence, "mutual-draws"), optionalCounter(evidence, "custom-rules-wins"),
            optionalCounter(evidence, "restricted-mobility-wins"), optionalCounter(evidence, "low-health-wins"));
    }

    private static int optionalCounter(ConfigurationSection evidence, String key) {
        Object value = evidence == null ? null : evidence.get(key);
        return value == null ? 0 : counter(value);
    }

    private static int counter(Object value) {
        if (!(value instanceof Integer number) || number < 0) {
            throw new IllegalArgumentException("Invalid or absent duel counter");
        }
        return number;
    }
}
