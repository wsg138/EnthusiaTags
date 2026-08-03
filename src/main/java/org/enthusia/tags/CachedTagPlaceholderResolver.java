package org.enthusia.tags;

import java.util.function.UnaryOperator;

final class CachedTagPlaceholderResolver {
    private CachedTagPlaceholderResolver() {
    }

    static TagPlaceholderOutput resolve(PlayerTagData data,
                                        TagRegistry registry,
                                        boolean suppressed,
                                        String lineFormat,
                                        String playerName,
                                        UnaryOperator<String> externalPlaceholders) {
        if (data == null || suppressed || data.isVanished()) {
            return TagPlaceholderOutput.EMPTY;
        }
        String selected = data.getSelectedTag();
        if (selected == null) {
            return TagPlaceholderOutput.EMPTY;
        }
        TagDefinition tag = registry.get(selected);
        if (tag == null) {
            return TagPlaceholderOutput.EMPTY;
        }
        return TagPlaceholderRenderer.render(lineFormat, tag, playerName, externalPlaceholders);
    }
}
