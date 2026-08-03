package org.enthusia.tags;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.regex.Pattern;

public final class TagTextFormat {
    private static final Pattern LEGACY_CODE = Pattern.compile("(?i)&(?:#[0-9a-f]{6}|[0-9a-fk-or])");
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
        .character('&')
        .hexColors()
        .build();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private TagTextFormat() {
    }

    public static String canonicalMiniMessage(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        if (!LEGACY_CODE.matcher(input).find()) {
            return input;
        }
        return MINI_MESSAGE.serialize(LEGACY.deserialize(input));
    }

    public static Component deserializeCompat(String input) {
        String canonical = canonicalMiniMessage(input);
        try {
            return MINI_MESSAGE.deserialize(canonical);
        } catch (RuntimeException ex) {
            return Component.text(input == null ? "" : input);
        }
    }

    public static String plainText(String miniMessage) {
        return PLAIN.serialize(deserializeCompat(miniMessage));
    }

    public static String legacyText(String miniMessage) {
        return LEGACY.serialize(deserializeCompat(miniMessage));
    }

    public static String safeDynamicValue(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (LEGACY_CODE.matcher(value).find()) {
            return MINI_MESSAGE.serialize(LEGACY.deserialize(value));
        }
        return MINI_MESSAGE.escapeTags(value);
    }
}
