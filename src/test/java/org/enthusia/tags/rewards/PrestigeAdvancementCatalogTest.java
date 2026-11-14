package org.enthusia.tags.rewards;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** REQ-1150 to REQ-1153: bundled guild prestige advancements fed by LumaGuilds counters. */
class PrestigeAdvancementCatalogTest {
    private static final List<String> ROMAN = List.of("I", "II", "III", "IV", "V", "VI");

    private YamlConfiguration load(String resource) throws Exception {
        try (var reader = new InputStreamReader(getClass().getResourceAsStream(resource), StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }

    private static void assertSingleCounter(ConfigurationSection reward, String key) {
        ConfigurationSection criteria = reward.getConfigurationSection("criteria");
        assertNotNull(criteria, reward.getName() + " has no criteria");
        Set<String> ids = criteria.getKeys(false);
        assertEquals(1, ids.size(), reward.getName() + " must have exactly one criterion");
        ConfigurationSection criterion = criteria.getConfigurationSection(ids.iterator().next());
        assertEquals("CUSTOM_COUNTER", criterion.getString("type"));
        assertEquals(key, criterion.getString("key"));
        assertEquals(1, criterion.getInt("amount"));
        assertEquals("LATCHED", reward.getString("completion-mode", "LATCHED").toUpperCase(Locale.ROOT));
    }

    @Test
    void bundlesOneLatchedAchievementPerPrestigeTier() throws Exception {
        YamlConfiguration rewards = load("/rewards.yml");
        assertTrue(rewards.contains("categories.prestige"), "prestige category missing");
        for (int tier = 1; tier <= 6; tier++) {
            ConfigurationSection reward = rewards.getConfigurationSection("rewards.prestige_" + tier);
            assertNotNull(reward, "rewards.prestige_" + tier + " missing");
            assertEquals("prestige", reward.getString("category"));
            assertFalse(reward.getBoolean("hidden-until-earned", false), "tier achievements are public");
            // REQ-1154: one toast per member, no chat line or Discord post per member.
            assertTrue(reward.isBoolean("announce-completion"), "prestige_" + tier + " needs announce-completion");
            assertFalse(reward.getBoolean("announce-completion"), "prestige_" + tier + " must be toast-only");
            assertSingleCounter(reward, "guild_prestige_" + tier);
        }
    }

    @Test
    void bundlesHiddenServerFirstAchievementPerTier() throws Exception {
        YamlConfiguration rewards = load("/rewards.yml");
        for (int tier = 1; tier <= 6; tier++) {
            ConfigurationSection reward = rewards.getConfigurationSection("rewards.prestige_first_" + tier);
            assertNotNull(reward, "rewards.prestige_first_" + tier + " missing");
            assertEquals("prestige", reward.getString("category"));
            assertTrue(reward.getBoolean("hidden-until-earned", false), "server firsts stay hidden until earned");
            assertSingleCounter(reward, "guild_prestige_first_" + tier);
        }
    }

    @Test
    void prestigeRewardsAreCosmeticTagsThatExist() throws Exception {
        YamlConfiguration rewards = load("/rewards.yml");
        YamlConfiguration config = load("/config.yml");
        int tagCount = 0;
        for (String id : rewards.getConfigurationSection("rewards").getKeys(false)) {
            if (!"prestige".equals(rewards.getString("rewards." + id + ".category"))) continue;
            ConfigurationSection actions = rewards.getConfigurationSection("rewards." + id + ".rewards");
            if (actions == null) continue;
            for (String action : actions.getKeys(false)) {
                assertEquals("TAG", actions.getString(action + ".type"), id + " must only grant tags");
                String tag = actions.getString(action + ".id");
                assertTrue(config.contains("tags." + tag + ".display-name"), "tag " + tag + " not defined");
                assertTrue(config.contains("tags." + tag + ".tag-text"), "tag " + tag + " has no tag text");
                tagCount++;
            }
        }
        assertTrue(tagCount >= 1, "prestige achievements should grant at least one tag");
    }

    @Test
    void bundlesNativeFramesForPrestigeTiers() throws Exception {
        YamlConfiguration config = load("/config.yml");
        List<String> expected = List.of("TASK", "TASK", "GOAL", "GOAL", "GOAL", "CHALLENGE");
        for (int tier = 1; tier <= 6; tier++) {
            assertEquals(expected.get(tier - 1), config.getString("advancements.frames.prestige_" + tier),
                "frame for Prestige " + ROMAN.get(tier - 1));
        }
    }
}
