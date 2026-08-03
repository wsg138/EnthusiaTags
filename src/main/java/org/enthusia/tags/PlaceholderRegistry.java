package org.enthusia.tags;

import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public final class PlaceholderRegistry {
    private final Map<String, RegisteredPlaceholder> placeholders = new ConcurrentHashMap<>();

    public void register(String key, Function<Player, String> resolver) {
        placeholders.put(key.toLowerCase(Locale.ROOT), new RegisteredPlaceholder(resolver, false));
    }

    public void registerTrusted(String key, Function<Player, String> resolver) {
        placeholders.put(key.toLowerCase(Locale.ROOT), new RegisteredPlaceholder(resolver, true));
    }

    public String apply(Player player, String input) {
        String output = input;
        for (Map.Entry<String, RegisteredPlaceholder> entry : placeholders.entrySet()) {
            String token = "{" + entry.getKey() + "}";
            if (!output.contains(token)) {
                continue;
            }
            String value = entry.getValue().resolver().apply(player);
            String replacement = entry.getValue().trusted()
                ? (value == null ? "" : value)
                : TagTextFormat.safeDynamicValue(value);
            output = output.replace(token, replacement);
        }
        return output;
    }

    private record RegisteredPlaceholder(Function<Player, String> resolver, boolean trusted) {
    }
}
