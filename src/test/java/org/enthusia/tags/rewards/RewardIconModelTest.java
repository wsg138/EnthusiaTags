package org.enthusia.tags.rewards;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RewardIconModelTest {
    private static RewardDefinition reward() {
        return new RewardDefinition("disc", "Disc", List.of(), Material.MUSIC_DISC_11, List.of(), List.of(), "christmas");
    }

    @Test
    void rewardsHaveNoIconModelUnlessOneIsSet() {
        assertNull(reward().getIconModel());
    }

    @Test
    void iconModelIsKeptAlongsideOtherCopies() {
        RewardDefinition modelled = reward().withIconModel(" enthusia:silent_snow_disc ");
        assertEquals("enthusia:silent_snow_disc", modelled.getIconModel());
        assertEquals("enthusia:silent_snow_disc", modelled.withHiddenUntilEarned(true).getIconModel());
        assertTrue(modelled.withHiddenUntilEarned(true).isHiddenUntilEarned());
        assertEquals(Material.MUSIC_DISC_11, modelled.getIcon());
    }

    @Test
    void blankIconModelClearsIt() {
        RewardDefinition plain = reward();
        assertSame(plain, plain.withIconModel("  "));
        assertNull(plain.withIconModel("enthusia:x").withIconModel(null).getIconModel());
    }

    @Test
    void silentSnowLooksDifferentFromTheWatcherMelody() throws Exception {
        YamlConfiguration config;
        try (var stream = getClass().getClassLoader().getResourceAsStream("rewards.yml")) {
            assertNotNull(stream);
            config = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        assertEquals("enthusia:silent_snow_disc", config.getString("rewards.silent_snow_disc.icon-model"));
        assertNotEquals(config.getString("rewards.watcher_melody.icon-model"),
            config.getString("rewards.silent_snow_disc.icon-model"));
    }

    @Test
    void silentSnowIsAnEpicDiscWithAPurpleName() throws Exception {
        YamlConfiguration config;
        try (var stream = getClass().getClassLoader().getResourceAsStream("rewards.yml")) {
            assertNotNull(stream);
            config = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        String give = config.getString("rewards.silent_snow_disc.rewards.r1.id");
        assertNotNull(give);
        // Epic shows the name in purple, like an elytra; a colour on item_name would override it.
        org.junit.jupiter.api.Assertions.assertTrue(give.contains("minecraft:rarity=\"epic\""), give);
        org.junit.jupiter.api.Assertions.assertTrue(give.contains("minecraft:item_name={\"text\":\"Music Disc\"}"), give);
    }
}
