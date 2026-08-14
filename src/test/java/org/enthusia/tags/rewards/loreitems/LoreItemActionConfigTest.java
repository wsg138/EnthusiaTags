package org.enthusia.tags.rewards.loreitems;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoreItemActionConfigTest {
    private static final String DEFINITION_KEY_FIELD = "definition-key";

    @Test
    void validDefinitionKeyIsCanonicalizedWithoutExposingImplementationDetails() {
        ConfigurationSection section = actionSection();
        section.set(DEFINITION_KEY_FIELD, " Hourglass_One ");
        section.set("label", "&eHourglass");

        LoreItemActionConfig.Validation validation = LoreItemActionConfig.validate(section);

        assertTrue(validation.valid());
        assertEquals("hourglass_one", validation.definitionKey());
    }

    @Test
    void missingBlankAndMalformedDefinitionKeysAreRejected() {
        ConfigurationSection missing = actionSection();
        assertFalse(LoreItemActionConfig.validate(missing).valid());

        ConfigurationSection blank = actionSection();
        blank.set(DEFINITION_KEY_FIELD, "  ");
        assertFalse(LoreItemActionConfig.validate(blank).valid());

        ConfigurationSection malformed = actionSection();
        malformed.set(DEFINITION_KEY_FIELD, "bad key!");
        assertFalse(LoreItemActionConfig.validate(malformed).valid());
    }

    @Test
    void unknownLoreItemFieldsAreRejected() {
        ConfigurationSection section = actionSection();
        section.set(DEFINITION_KEY_FIELD, "hourglass");
        section.set("id", "legacy-field-must-not-be-accepted");

        LoreItemActionConfig.Validation validation = LoreItemActionConfig.validate(section);

        assertFalse(validation.valid());
        assertTrue(validation.error().contains("unknown lore-item field 'id'"));
    }

    @Test
    void definitionKeyLengthIsBoundedToReleasedContractGrammar() {
        ConfigurationSection sixtyFour = actionSection();
        sixtyFour.set(DEFINITION_KEY_FIELD, "a".repeat(64));
        assertTrue(LoreItemActionConfig.validate(sixtyFour).valid());

        ConfigurationSection sixtyFive = actionSection();
        sixtyFive.set(DEFINITION_KEY_FIELD, "a".repeat(65));
        assertFalse(LoreItemActionConfig.validate(sixtyFive).valid());
    }

    private static ConfigurationSection actionSection() {
        YamlConfiguration configuration = new YamlConfiguration();
        ConfigurationSection section = configuration.createSection("action");
        section.set("action-id", "lore-hourglass");
        section.set("type", "LORE_ITEM");
        return section;
    }
}
