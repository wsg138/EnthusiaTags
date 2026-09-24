package org.enthusia.tags.cosmetics;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OriginalMessageChoiceTest {
    private CosmeticDefinition cosmetic(String id, String category, CosmeticType type, String message) {
        return new CosmeticDefinition(id, id, category, type, null, null, null, null,
            message, "enthusia.cosmetics.use", 0, 0, 0, 0);
    }

    @Test
    void originalNeverProducesAReplacementEvenWhenMisconfigured() {
        assertNull(cosmetic("original", "join", CosmeticType.ORIGINAL, "do not send this").getMessage());
        assertEquals("custom", cosmetic("custom", "join", CosmeticType.JOIN_MESSAGE, "custom").getMessage());
    }

    @Test
    void originalRemainsFirstWhenMigrationAppendsItAfterExistingChoices() {
        var first = cosmetic("existing-first", "join", CosmeticType.JOIN_MESSAGE, "first");
        var second = cosmetic("existing-second", "join", CosmeticType.JOIN_MESSAGE, "second");
        var original = cosmetic("original", "join", CosmeticType.ORIGINAL, null);
        var otherCategory = cosmetic("other", "quit", CosmeticType.QUIT_MESSAGE, "bye");
        assertEquals(java.util.List.of(original, first, second),
            CosmeticsMenu.categoryChoices(java.util.List.of(first, second, otherCategory, original), "join"));
    }

    @Test
    void everyMessageCategoryHasAnExplicitUnrestrictedOriginalChoice() throws Exception {
        try (var resource = getClass().getResourceAsStream("/cosmetics.yml")) {
            assertNotNull(resource);
            var config = YamlConfiguration.loadConfiguration(new InputStreamReader(resource, StandardCharsets.UTF_8));
            for (String category : new String[] { "join", "quit", "kill_message" }) {
                String path = "cosmetics.original_" + category;
                assertEquals(category, config.getString(path + ".category"));
                assertEquals("ORIGINAL", config.getString(path + ".type"));
                assertEquals("enthusia.cosmetics.use", config.getString(path + ".permission"));
                assertNull(config.getString(path + ".message"), "Original must defer to the existing message owner");
            }
        }
    }
}
