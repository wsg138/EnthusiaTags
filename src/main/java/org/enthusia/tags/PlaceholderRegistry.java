package org.enthusia.tags;

import org.bukkit.entity.Player;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public final class PlaceholderRegistry {
    private final Map<String, Function<Player, String>> placeholders = new ConcurrentHashMap<>();

    public void register(String key, Function<Player, String> resolver) {
        placeholders.put(key.toLowerCase(), resolver);
    }

    public String apply(Player player, String input) {
        String output = input;
        for (Map.Entry<String, Function<Player, String>> entry : placeholders.entrySet()) {
            String token = "{" + entry.getKey() + "}";
            if (output.contains(token)) {
                output = output.replace(token, entry.getValue().apply(player));
            }
        }
        return output;
    }
}
