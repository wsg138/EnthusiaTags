package org.enthusia.tags;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.util.Objects;
import java.util.function.UnaryOperator;

public final class TagPlaceholderRenderer {
    private static final String TAG_TOKEN = "enthusia_selected_tag";
    private static final String PLAYER_TOKEN = "enthusia_tag_player";
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private TagPlaceholderRenderer() {
    }

    public static TagPlaceholderOutput render(String lineFormat,
                                              TagDefinition tag,
                                              String playerName,
                                              UnaryOperator<String> externalPlaceholders) {
        if (tag == null) {
            return TagPlaceholderOutput.EMPTY;
        }
        Component playerComponent = Component.text(playerName == null ? "" : playerName);
        Component tagComponent = deserializeTag(tag.getTagText(), playerComponent, playerName);
        String template = TagTextFormat.canonicalMiniMessage(lineFormat)
            .replace("{tag}", '<' + TAG_TOKEN + '>')
            .replace("{player}", '<' + PLAYER_TOKEN + '>');

        String rendered;
        try {
            Component line = MINI_MESSAGE.deserialize(template,
                Placeholder.component(TAG_TOKEN, tagComponent),
                Placeholder.component(PLAYER_TOKEN, playerComponent));
            rendered = MINI_MESSAGE.serialize(line);
        } catch (RuntimeException ex) {
            rendered = template
                .replace('<' + TAG_TOKEN + '>', TagTextFormat.canonicalMiniMessage(tag.getTagText()))
                .replace('<' + PLAYER_TOKEN + '>', TagTextFormat.safeDynamicValue(playerName));
        }

        rendered = Objects.requireNonNullElse(externalPlaceholders.apply(rendered), rendered);
        return new TagPlaceholderOutput(
            rendered,
            TagTextFormat.plainText(rendered),
            tag.getId(),
            TagTextFormat.legacyText(rendered)
        );
    }

    private static Component deserializeTag(String tagText, Component playerComponent, String playerName) {
        String template = TagTextFormat.canonicalMiniMessage(tagText)
            .replace("{player}", '<' + PLAYER_TOKEN + '>');
        try {
            return MINI_MESSAGE.deserialize(template, Placeholder.component(PLAYER_TOKEN, playerComponent));
        } catch (RuntimeException ex) {
            return Component.text((tagText == null ? "" : tagText)
                .replace("{player}", playerName == null ? "" : playerName));
        }
    }
}
