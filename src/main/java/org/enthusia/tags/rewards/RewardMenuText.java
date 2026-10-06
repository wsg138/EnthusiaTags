package org.enthusia.tags.rewards;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** Formatting is display-only: stored counters and their units are never changed. */
public final class RewardMenuText {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
        .character('&')
        .hexColors()
        .build();
    private static final PlainTextComponentSerializer TEXT_SERIALIZER = PlainTextComponentSerializer.plainText();

    private static final String GENERAL_CATEGORY = "misc";
    private static final String GENERAL_NAME = "Misc";
    private RewardMenuText() {}
    public static String plain(String value) {
        if (value == null) return "";
        return TEXT_SERIALIZER.serialize(LEGACY.deserialize(value.replace('§','&')));
    }
    public static Component component(String value) {
        return withoutItalics(LEGACY.deserialize(value.replace('§','&')));
    }
    private static Component withoutItalics(Component value) {
        return value.decoration(TextDecoration.ITALIC, false)
            .children(value.children().stream().map(RewardMenuText::withoutItalics).toList());
    }
    public static String number(long value) { return NumberFormat.getIntegerInstance(Locale.US).format(value); }
    public static String money(double value) {
        var format = NumberFormat.getNumberInstance(Locale.US);
        format.setMaximumFractionDigits(Math.max(0, Math.min(8, BigDecimal.valueOf(value).stripTrailingZeros().scale())));
        return "$" + format.format(value);
    }
    public static String duration(long minutes) {
        return number(Math.max(0, minutes) / 60) + "h " + String.format(Locale.ROOT, "%02d", Math.max(0, minutes) % 60) + "m";
    }
    public static boolean time(RewardCriterion criterion) {
        return switch(criterion.getType()) {
            case PLAYTIME_ACTIVE_MINUTES, PLAYTIME_AFK_MINUTES, PLAYTIME_TOTAL_MINUTES,
                PLAYTIME_CONSECUTIVE_ACTIVE_MINUTES, UNDERGROUND_ACTIVE_MINUTES -> true;
            default -> false;
        };
    }
    public static String value(long amount, RewardCriterion criterion) {
        if (time(criterion)) return duration(amount);
        if (criterion.getType() == RewardCriterionType.BALANCE_AT_LEAST) return "$" + number(amount);
        if (criterion.getType() == RewardCriterionType.PING_MS_AT_LEAST) return number(amount) + " ms";
        return number(amount);
    }
    public static String criterionLabel(RewardCriterion criterion) {
        return switch (criterion.getType()) {
            case PLAYTIME_ACTIVE_MINUTES -> "Active playtime";
            case PLAYTIME_AFK_MINUTES -> "AFK playtime";
            case PLAYTIME_TOTAL_MINUTES -> "Total playtime";
            case PLAYTIME_CONSECUTIVE_ACTIVE_MINUTES -> "Consecutive active time";
            case UNDERGROUND_ACTIVE_MINUTES -> "Underground active time";
            default -> plain(criterion.getLabel());
        };
    }
    public static int percent(double fraction) {
        if (fraction < 0 || !Double.isFinite(fraction)) return 0;
        return Math.min(100, Math.max(0, (int) Math.floor(fraction * 100)));
    }
    public static String bar(double fraction) {
        int filled = Math.min(10, percent(fraction) / 10);
        return "&6" + "■".repeat(filled) + "&8" + "□".repeat(10 - filled) + " &7" + percent(fraction) + "%";
    }
    public static String categoryName(RewardCategory category) {
        if (GENERAL_CATEGORY.equalsIgnoreCase(category.id()) && plain(category.name()).equalsIgnoreCase(GENERAL_NAME)) return "General";
        return plain(category.name());
    }
    public static String titleCase(String value) {
        StringBuilder result = new StringBuilder();
        for (String word : value.toLowerCase(Locale.ROOT).split("[_ ]+")) {
            if (word.isBlank()) continue;
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }
    public static List<String> wrap(String value, int width) {
        return java.util.Arrays.stream(plain(value).split("\n", -1))
            .flatMap(paragraph -> wrapParagraph(paragraph, width).stream()).toList();
    }
    private static List<String> wrapParagraph(String paragraph, int width) {
        if (paragraph.isBlank()) return List.of("");
        List<String> result = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : paragraph.split("\s+")) appendWord(result, line, word, width);
        if (!line.isEmpty()) result.add(line.toString());
        return List.copyOf(result);
    }
    private static void appendWord(List<String> result, StringBuilder line, String word, int width) {
        if (!line.isEmpty() && line.length() + word.length() + 1 > width) {
            result.add(line.toString()); line.setLength(0);
        }
        if (!line.isEmpty()) line.append(' ');
        line.append(word);
    }
    private static final java.util.Map<RewardClaimResult, String> RESULT_KEYS = java.util.Map.ofEntries(
        java.util.Map.entry(RewardClaimResult.SUCCESS, "rewards-claimed"),
        java.util.Map.entry(RewardClaimResult.SUCCESS_GOLD_WITHHELD, "rewards-gold-withheld"),
        java.util.Map.entry(RewardClaimResult.GOLD_VERIFICATION_UNAVAILABLE, "rewards-gold-verification-unavailable"),
        java.util.Map.entry(RewardClaimResult.LOADING, "rewards-loading"),
        java.util.Map.entry(RewardClaimResult.ALREADY_CLAIMED, "rewards-already-claimed"),
        java.util.Map.entry(RewardClaimResult.NOT_READY, "rewards-not-ready"),
        java.util.Map.entry(RewardClaimResult.IP_ALREADY_CLAIMED, "rewards-ip-already-claimed"),
        java.util.Map.entry(RewardClaimResult.DELIVERY_FAILED, "rewards-delivery-failed"),
        java.util.Map.entry(RewardClaimResult.ITEM_QUEUED, "rewards-item-queued"),
        java.util.Map.entry(RewardClaimResult.RECONCILIATION_REQUIRED, "rewards-reconciliation-required"),
        java.util.Map.entry(RewardClaimResult.CLAIM_IN_PROGRESS, "rewards-claim-in-progress"),
        java.util.Map.entry(RewardClaimResult.SERVICE_UNAVAILABLE, "rewards-service-unavailable")
    );
    public static String resultMessageKey(RewardClaimResult result) {
        return RESULT_KEYS.get(result);
    }
    private static final java.util.Map<RewardClaimResult, String> NOTICES = java.util.Map.ofEntries(
        java.util.Map.entry(RewardClaimResult.SUCCESS, ""),
        java.util.Map.entry(RewardClaimResult.ALREADY_CLAIMED, ""),
        java.util.Map.entry(RewardClaimResult.SUCCESS_GOLD_WITHHELD, "Gold portion withheld by network policy."),
        java.util.Map.entry(RewardClaimResult.GOLD_VERIFICATION_UNAVAILABLE, "Gold verification unavailable; try later."),
        java.util.Map.entry(RewardClaimResult.LOADING, "Your reward data is still loading."),
        java.util.Map.entry(RewardClaimResult.NOT_READY, "Requirements are not currently met."),
        java.util.Map.entry(RewardClaimResult.IP_ALREADY_CLAIMED, "Network claim restriction applies."),
        java.util.Map.entry(RewardClaimResult.DELIVERY_FAILED, "Delivery failed; retry safely."),
        java.util.Map.entry(RewardClaimResult.ITEM_QUEUED, "Item delivery queued; free inventory space."),
        java.util.Map.entry(RewardClaimResult.RECONCILIATION_REQUIRED, "Delivery needs staff review."),
        java.util.Map.entry(RewardClaimResult.CLAIM_IN_PROGRESS, "A claim is already in progress."),
        java.util.Map.entry(RewardClaimResult.SERVICE_UNAVAILABLE, "Reward service temporarily unavailable.")
    );
    public static String notice(RewardClaimResult result) {
        return result == null ? "" : NOTICES.get(result);
    }
}
