package org.enthusia.tags.rewards;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure parsing of validated provider text; no server or reward-service state. */
final class PlaytimeTextParser {
    private static final Pattern HOURS_PATTERN = Pattern.compile("(?i)(\\d+)\\s*h");
    private static final Pattern MINUTES_PATTERN = Pattern.compile("(?i)(\\d+)\\s*m");
    private static final Pattern SECONDS_PATTERN = Pattern.compile("(?i)(\\d+)\\s*s");
    private static final Pattern NON_DIGIT_PATTERN = Pattern.compile("[^0-9]");
    private PlaytimeTextParser() {}

    static long parse(String resolved, String placeholder) {
        if (resolved == null) {
            return -1L;
        }
        String raw = resolved.trim();
        if (raw.isEmpty()) {
            return -1L;
        }
        try {
            if (raw.matches("(?i)(?:\\d+\\s*[hms]\\s*)+")) {
                return parseTokenizedPlaytimeMinutes(raw);
            }
            if (!raw.matches("(?:\\d+|\\d{1,3}(?:,\\d{3})+)")) return -1L;
            return parseNumericPlaytimeMinutes(raw, placeholder);
        } catch (ArithmeticException | NumberFormatException invalid) {
            return -1L;
        }
    }

    static long parseTokenizedPlaytimeMinutes(String raw) {
        long minutes = 0L;
        boolean matched = false;
        Matcher hours = HOURS_PATTERN.matcher(raw);
        while (hours.find()) {
            minutes = Math.addExact(minutes, Math.multiplyExact(Long.parseLong(hours.group(1)), 60L));
            matched = true;
        }
        Matcher mins = MINUTES_PATTERN.matcher(raw);
        while (mins.find()) {
            minutes = Math.addExact(minutes, Long.parseLong(mins.group(1)));
            matched = true;
        }
        Matcher secs = SECONDS_PATTERN.matcher(raw);
        long seconds = 0L;
        while (secs.find()) {
            seconds = Math.addExact(seconds, Long.parseLong(secs.group(1)));
            matched = true;
        }
        minutes = Math.addExact(minutes, seconds / 60L);
        return matched ? minutes : -1L;
    }

    private static long parseNumericPlaytimeMinutes(String raw, String placeholder) {
        String digits = NON_DIGIT_PATTERN.matcher(raw).replaceAll("");
        if (digits.isEmpty()) {
            return -1L;
        }

        try {
            long value = Long.parseLong(digits);
            String token = placeholder == null ? "" : placeholder.toLowerCase(Locale.ROOT);
            if (token.contains("%playtime_") && !token.contains("formatted")) {
                return value / 60L;
            }
            return value;
        } catch (NumberFormatException ex) {
            return -1L;
        }
    }

}
