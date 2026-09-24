package org.enthusia.tags.advancements;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.enthusia.tags.advancements.domain.DiaryMilestoneProgress;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Strict read-only adapter for DiaryKeeper's persisted advancement evidence. */
final class DiaryStatsReader {
    private DiaryStatsReader() {
    }

    static Map<UUID, DiaryMilestoneProgress.Stats> parse(String yaml) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return parseConfiguration(config);
    }

    static Map<UUID, DiaryMilestoneProgress.Stats> read(Path file) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        // load(Reader) propagates malformed input; loadConfiguration(File) would log and return defaults.
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            config.load(reader);
        }
        return parseConfiguration(config);
    }

    private static Map<UUID, DiaryMilestoneProgress.Stats> parseConfiguration(YamlConfiguration config) {
        ConfigurationSection players = optionalSection(config, "players");
        if (players == null) {
            return Map.of();
        }

        Map<UUID, DiaryMilestoneProgress.Stats> result = new HashMap<>();
        for (String key : players.getKeys(false)) {
            UUID player = UUID.fromString(key);
            if (!player.toString().equalsIgnoreCase(key)) {
                throw new IllegalArgumentException("Invalid DiaryKeeper player UUID");
            }
            if (result.putIfAbsent(player, parsePlayer(players.getConfigurationSection(key))) != null) {
                throw new IllegalArgumentException("Duplicate player UUID in DiaryKeeper evidence");
            }
        }
        return Map.copyOf(result);
    }
    private static DiaryMilestoneProgress.Stats parsePlayer(ConfigurationSection player) {
        if (player == null) {
            throw new IllegalArgumentException("Invalid DiaryKeeper player record");
        }
        long issuedAt = issuanceTimestamp(player.get("issuedAt"));
        ConfigurationSection evidence = optionalSection(player, "advancements");
        if (evidence == null) {
            return new DiaryMilestoneProgress.Stats(
                issuedAt > 0, 0, 0, 0, 0, 0);
        }

        boolean received = evidence.contains("received")
            ? booleanValue(evidence.get("received"))
            : issuedAt > 0;
        return new DiaryMilestoneProgress.Stats(
            received,
            counter(evidence, "edits"),
            counter(evidence, "destructionAttempts"),
            counter(evidence, "voidReturns"),
            counter(evidence, "containerAttempts"),
            counter(evidence, "groundPickups")
        );
    }

    private static ConfigurationSection optionalSection(ConfigurationSection parent, String key) {
        ConfigurationSection result = parent.getConfigurationSection(key);
        if (result == null && parent.contains(key)) {
            throw new IllegalArgumentException("Invalid DiaryKeeper section: " + key);
        }
        return result;
    }

    private static long issuanceTimestamp(Object value) {
        if (value == null) return 0L;
        if (value instanceof Integer number && number >= 0) return number.longValue();
        if (value instanceof Long number && number >= 0L) return number;
        throw new IllegalArgumentException("Invalid DiaryKeeper issuance timestamp");
    }

    private static int counter(ConfigurationSection section, String key) {
        Object value = section.get(key);
        if (value == null) return 0;
        if (!(value instanceof Integer result) || result < 0) {
            throw new IllegalArgumentException("Invalid DiaryKeeper counter: " + key);
        }
        return result;
    }
    private static boolean booleanValue(Object value) {
        if (!(value instanceof Boolean result)) {
            throw new IllegalArgumentException("Invalid DiaryKeeper boolean");
        }
        return result;
    }
}
