package org.enthusia.tags;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class RewardConfigV5MigrationTest {
    @Test
    void addsMissingRewardsWithoutReplacingExistingCustomizations() throws Exception {
        YamlConfiguration defaults = bundledRewards();
        YamlConfiguration existing = new YamlConfiguration();
        existing.set("config-version", 4);
        existing.set("rewards.payday", defaults.get("rewards.payday"));
        existing.set("rewards.payday.rewards.r1.amount", 15D);
        existing.set("rewards.gold_rush", defaults.get("rewards.gold_rush"));
        existing.set("rewards.gold_rush.rewards.payout.type", "ITEM");
        existing.set("rewards.gold_rush.rewards.payout.material", "GOLD_BLOCK");
        existing.set("rewards.gold_rush.rewards.payout.amount", 4);
        existing.set("rewards.gold_rush.rewards.payout.label", "Administrator trophy");
        existing.set("rewards.deep_gold_rush", defaults.get("rewards.deep_gold_rush"));
        existing.set("rewards.deep_gold_rush.rewards.item.type", "ITEM");
        existing.set("rewards.deep_gold_rush.rewards.item.material", "GOLD_INGOT");
        existing.set("rewards.deep_gold_rush.rewards.item.amount", 16);
        existing.set("rewards.custom_reward.name", "Custom Reward");

        boolean changed = RewardConfigV5Migration.migrateRewards(existing, defaults,
            new ConfigMigrator.MigrationReport());

        assertTrue(changed);
        assertEquals(150D, existing.getDouble("rewards.payday.rewards.r1.amount"));
        assertEquals("ITEM", existing.getString("rewards.gold_rush.rewards.payout.type"));
        assertEquals("GOLD_BLOCK",
            existing.getString("rewards.gold_rush.rewards.payout.material"));
        assertEquals(4, existing.getInt("rewards.gold_rush.rewards.payout.amount"));
        assertEquals("Administrator trophy",
            existing.getString("rewards.gold_rush.rewards.payout.label"));
        assertFalse(existing.contains("rewards.deep_gold_rush.rewards.item"));
        assertEquals("Custom Reward", existing.getString("rewards.custom_reward.name"));

        ConfigurationSection rewards = existing.getConfigurationSection("rewards");
        assertNotNull(rewards);
        assertEquals(101, rewards.getKeys(false).size());
        assertTrue(existing.contains("rewards.ultimate_survivor"));
    }

    private YamlConfiguration bundledRewards() throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream("rewards.yml")) {
            assertNotNull(stream);
            return YamlConfiguration.loadConfiguration(
                new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
    }
}
