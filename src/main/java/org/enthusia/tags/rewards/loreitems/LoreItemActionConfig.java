package org.enthusia.tags.rewards.loreitems;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class LoreItemActionConfig {
    private static final Pattern DEFINITION_KEY = Pattern.compile("[a-z0-9](?:[a-z0-9_-]{0,63})");
    private static final Set<String> ALLOWED_FIELDS = Set.of(
        "action-id", "type", "definition-key", "label");

    private LoreItemActionConfig() {
    }

    public static Validation validate(ConfigurationSection section) {
        if (section == null) {
            return new Validation("", "Lore-item action must be a configuration section");
        }
        for (String field : section.getKeys(false)) {
            if (!ALLOWED_FIELDS.contains(field)) {
                return new Validation("", "unknown lore-item field '" + field + "'");
            }
        }
        String raw = section.getString("definition-key", "");
        String canonical = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        if (canonical.isBlank()) {
            return new Validation("", "definition-key is required");
        }
        if (!DEFINITION_KEY.matcher(canonical).matches()) {
            return new Validation("", "definition-key must be 1-64 letters, digits, underscores, or hyphens");
        }
        return new Validation(canonical, null);
    }

    public record Validation(String definitionKey, String error) {
        public boolean valid() {
            return error == null;
        }
    }
}
