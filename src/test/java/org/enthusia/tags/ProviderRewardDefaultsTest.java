package org.enthusia.tags;
import org.bukkit.configuration.file.YamlConfiguration;
import org.enthusia.tags.advancements.domain.ProviderRewardEvidence;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;
class ProviderRewardDefaultsTest {
    static YamlConfiguration bundled(String resource) throws Exception {
        try (var in = ProviderRewardDefaultsTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in);
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }
    @Test void defaultsRecoverAllProviderGoalsAndReferencedTags() throws Exception {
        var rewards = bundled("rewards.yml");
        var tags = bundled("config.yml");
        var found = new HashSet<String>();
        for (String id : rewards.getConfigurationSection("rewards").getKeys(false)) {
            var reward = rewards.getConfigurationSection("rewards." + id);
            String key = reward.getString("criteria.complete.key", "");
            if (!key.startsWith("advancement_reward:")) continue;
            found.add(key.substring("advancement_reward:".length()));
            assertEquals(1, reward.getLong("criteria.complete.amount"));
            for (String action : reward.getConfigurationSection("rewards").getKeys(false)) {
                var definition = reward.getConfigurationSection("rewards." + action);
                if ("TAG".equals(definition.getString("type"))) assertTrue(tags.isConfigurationSection("tags." + definition.getString("id")), id);
            }
        }
        assertEquals(38, found.size());
        assertEquals(ProviderRewardEvidence.keys(), found);
    }
    @Test void migrationPreservesOverridesHigherVersionsAndDoesNotRestoreLaterDeletions() throws Exception {
        var defaults = bundled("rewards.yml");
        var target = new YamlConfiguration();
        target.set("config-version", 8);
        target.set("rewards.adv_express_first_class.rewards.payout.amount", 73);
        target.set("rewards.custom.criteria.c1.type", "CUSTOM_COUNTER");
        target.set("rewards.custom.criteria.c1.key", "custom_counter");
        assertTrue(ProviderRewardDefaults.migrate("rewards.yml", target, defaults, new ConfigMigrator.MigrationReport()));
        assertEquals(8, target.getInt("config-version"));
        assertEquals(73, target.getInt("rewards.adv_express_first_class.rewards.payout.amount"));
        assertFalse(target.contains("rewards.adv_express_first_class.criteria"), "Existing custom sections remain whole");
        assertEquals("custom_counter", target.getString("rewards.custom.criteria.c1.key"));
        assertFalse(target.contains("rewards.donor_avid"));
        target.set("rewards.adv_diary_first_entry", null);
        String once = target.saveToString();
        assertFalse(ProviderRewardDefaults.migrate("rewards.yml", target, defaults, new ConfigMigrator.MigrationReport()));
        assertEquals(once, target.saveToString());
        var tags = new YamlConfiguration();
        tags.set("tags.adv_clutch.tag-text", "My custom tag");
        ProviderRewardDefaults.migrate("config.yml", tags, bundled("config.yml"), new ConfigMigrator.MigrationReport());
        assertEquals("My custom tag", tags.getString("tags.adv_clutch.tag-text"));
        assertFalse(tags.contains("tags.donor_avid"));
    }
}
