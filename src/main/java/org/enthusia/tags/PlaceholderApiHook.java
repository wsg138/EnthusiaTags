package org.enthusia.tags;

import org.bukkit.entity.Player;

public final class PlaceholderApiHook {
    private final boolean available;
    private final java.lang.reflect.Method setPlaceholdersMethod;

    public PlaceholderApiHook() {
        boolean found;
        java.lang.reflect.Method method = null;
        try {
            Class<?> api = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            method = api.getMethod("setPlaceholders", Player.class, String.class);
            found = true;
        } catch (ReflectiveOperationException ignored) {
            found = false;
        }
        this.available = found;
        this.setPlaceholdersMethod = method;
    }

    public String apply(Player player, String input) {
        if (!available || input == null || input.indexOf('%') < 0) {
            return input;
        }
        try {
            return (String) setPlaceholdersMethod.invoke(null, player, input);
        } catch (ReflectiveOperationException ex) {
            return input;
        }
    }
}
