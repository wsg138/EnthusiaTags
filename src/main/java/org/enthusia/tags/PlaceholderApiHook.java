package org.enthusia.tags;

import org.bukkit.entity.Player;

public final class PlaceholderApiHook {
    private final boolean available;

    public PlaceholderApiHook() {
        boolean found;
        try {
            Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            found = true;
        } catch (ClassNotFoundException ignored) {
            found = false;
        }
        this.available = found;
    }

    public String apply(Player player, String input) {
        if (!available) {
            return input;
        }
        try {
            Class<?> api = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            return (String) api.getMethod("setPlaceholders", Player.class, String.class)
                .invoke(null, player, input);
        } catch (ReflectiveOperationException ex) {
            return input;
        }
    }
}
