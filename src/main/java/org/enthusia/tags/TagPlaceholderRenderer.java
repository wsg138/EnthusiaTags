package org.enthusia.tags;

import java.util.Objects;
import java.util.function.UnaryOperator;

public final class TagPlaceholderRenderer {
    private TagPlaceholderRenderer() {
    }

    public static TagPlaceholderOutput render(String lineFormat,
                                              TagDefinition tag,
                                              String playerName,
                                              UnaryOperator<String> externalPlaceholders) {
        if (tag == null) {
            return TagPlaceholderOutput.EMPTY;
        }
        String format = TagTextFormat.canonicalMiniMessage(lineFormat);
        String tagText = TagTextFormat.canonicalMiniMessage(tag.getTagText());
        String rendered = format.replace("{tag}", tagText)
            .replace("{player}", TagTextFormat.safeDynamicValue(playerName));
        rendered = Objects.requireNonNullElse(externalPlaceholders.apply(rendered), rendered);
        return new TagPlaceholderOutput(
            rendered,
            TagTextFormat.plainText(rendered),
            tag.getId(),
            TagTextFormat.legacyText(rendered)
        );
    }
}
