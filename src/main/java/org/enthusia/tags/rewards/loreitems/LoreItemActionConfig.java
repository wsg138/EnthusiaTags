package org.enthusia.tags.rewards.loreitems;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class LoreItemActionConfig {
    private static final Pattern DEFINITION_KEY = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final Set<String> ALLOWED_KEYS = Set.of("action-id", "type", "definition-key", "label");

    private LoreItemActionConfig() {
    }

    public static Validation validate(ConfigurationSection section) {
        for (String key : section.getKeys(false)) {
            if (!ALLOWED_KEYS.contains(key)) {
                return Validation.error("unknown lore-item field '" + key + "'");
            }
        }
        String rawDefinitionKey = section.getString("definition-key");
        if (rawDefinitionKey == null || rawDefinitionKey.isBlank()) {
            return Validation.error("missing definition-key");
        }
        String definitionKey = rawDefinitionKey.trim().toLowerCase(Locale.ROOT);
        if (!DEFINITION_KEY.matcher(definitionKey).matches()) {
            return Validation.error(
                "definition-key must be 1-64 lowercase letters, digits, underscores, or hyphens and begin with a lowercase letter or digit");
        }
        return Validation.valid(definitionKey);
    }

    public record Validation(boolean valid, String definitionKey, String error) {
        static Validation valid(String definitionKey) {
            return new Validation(true, definitionKey, "");
        }

        static Validation error(String error) {
            return new Validation(false, "", error);
        }
    }
}
