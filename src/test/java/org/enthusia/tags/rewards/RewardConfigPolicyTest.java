package org.enthusia.tags.rewards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class RewardConfigPolicyTest {
    private static final Set<String> PHYSICAL_GOLD = Set.of(
        "GOLD_NUGGET", "GOLD_INGOT", "GOLD_BLOCK", "RAW_GOLD", "RAW_GOLD_BLOCK"
    );

    @Test
    void bundledRewardsUseTheFullVaultBackedConfiguration() throws Exception {
        YamlConfiguration config;
        try (var stream = getClass().getClassLoader().getResourceAsStream("rewards.yml")) {
            assertNotNull(stream);
            config = YamlConfiguration.loadConfiguration(
                new InputStreamReader(stream, StandardCharsets.UTF_8));
        }

        assertEquals(5, config.getInt("config-version"));
        ConfigurationSection rewards = config.getConfigurationSection("rewards");
        assertNotNull(rewards);
        assertEquals(100, rewards.getKeys(false).size());

        int moneyActions = 0;
        int maximumActions = 0;
        for (String rewardId : rewards.getKeys(false)) {
            ConfigurationSection actions = config.getConfigurationSection(
                "rewards." + rewardId + ".rewards");
            if (actions == null) {
                continue;
            }
            for (String actionId : actions.getKeys(false)) {
                String path = actions.getCurrentPath() + "." + actionId;
                String type = config.getString(path + ".type", "");
                if ("MONEY".equals(type)) {
                    moneyActions++;
                    double amount = config.getDouble(path + ".amount");
                    assertTrue(amount > 0D && amount <= RewardMoneyPolicy.MAX_AMOUNT, path);
                    if (Double.compare(amount, RewardMoneyPolicy.MAX_AMOUNT) == 0) {
                        maximumActions++;
                    }
                }
                if ("ITEM".equals(type)) {
                    assertFalse(PHYSICAL_GOLD.contains(
                        config.getString(path + ".material", "")), path);
                }
            }
        }

        assertEquals(48, moneyActions);
        assertEquals(3, maximumActions);
        assertEquals(5000D,
            config.getDouble("rewards.two_thousand_hours.rewards.payout.amount"));
        assertEquals(5000D,
            config.getDouble("rewards.ten_million_steps.rewards.payout.amount"));
        assertEquals(5000D,
            config.getDouble("rewards.ultimate_survivor.rewards.payout.amount"));
    }
}
