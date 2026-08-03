package org.enthusia.tags;

import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PlaceholderApiHook {
    private static final Pattern PLACEHOLDER = Pattern.compile("%([^%]+)%");
    private static final String OWN_PREFIX = "enthusiatags_";

    private final BiFunction<Player, String, String> resolver;

    public PlaceholderApiHook() {
        this(findResolver());
    }

    PlaceholderApiHook(BiFunction<Player, String, String> resolver) {
        this.resolver = resolver;
    }

    public boolean isAvailable() {
        return resolver != null;
    }

    public String apply(Player player, String input) {
        if (resolver == null || input == null || input.indexOf('%') < 0) {
            return input;
        }
        Matcher matcher = PLACEHOLDER.matcher(input);
        StringBuffer output = new StringBuffer(input.length());
        while (matcher.find()) {
            String token = matcher.group();
            String identifier = matcher.group(1).toLowerCase(Locale.ROOT);
            String replacement;
            if (identifier.startsWith(OWN_PREFIX)) {
                replacement = "";
            } else {
                String resolved = resolver.apply(player, token);
                replacement = resolved == null || resolved.equals(token)
                    ? token
                    : TagTextFormat.safeDynamicValue(resolved);
            }
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private static BiFunction<Player, String, String> findResolver() {
        try {
            Class<?> api = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            Method method = api.getMethod("setPlaceholders", Player.class, String.class);
            return (player, token) -> {
                try {
                    return (String) method.invoke(null, player, token);
                } catch (ReflectiveOperationException | RuntimeException ex) {
                    return token;
                }
            };
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }
}
