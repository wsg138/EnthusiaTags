package org.enthusia.tags.advancements;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class BundledRewardFixture {
    private BundledRewardFixture() {}

    static ConfigurationSection rewards() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        try (var input = BundledRewardFixture.class.getResourceAsStream("/rewards.yml")) {
            assertNotNull(input, "Bundled rewards.yml must exist");
            config.load(new InputStreamReader(input, StandardCharsets.UTF_8));
        }
        var result = config.getConfigurationSection("rewards");
        assertNotNull(result, "Bundled rewards section must exist");
        return result;
    }
}
