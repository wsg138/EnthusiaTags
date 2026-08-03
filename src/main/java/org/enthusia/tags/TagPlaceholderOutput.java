package org.enthusia.tags;

public record TagPlaceholderOutput(String miniMessage, String plain, String id, String legacy) {
    public static final TagPlaceholderOutput EMPTY = new TagPlaceholderOutput("", "", "", "");

    public String value(String parameter) {
        return switch (parameter.toLowerCase(java.util.Locale.ROOT)) {
            case "selected_mm" -> miniMessage;
            case "selected_plain" -> plain;
            case "selected_id" -> id;
            case "selected_legacy" -> legacy;
            default -> null;
        };
    }
}
