package org.enthusia.tags.advancements.domain;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Known provider milestones; unrelated or invented progress cannot unlock rewards. */
public final class ProviderRewardEvidence {
    public static final String COUNTER_PREFIX = "advancement_reward:";
    public static final int COMPLETE = 1000;
    private static final Set<String> KEYS = Set.of(
        "warzone_duels/welcome_to_thunderdome",
        "warzone_duels/first_blood",
        "warzone_duels/victor_spoils",
        "warzone_duels/price_for_peace",
        "warzone_duels/my_house_my_rules",
        "warzone_duels/adapt_and_overcome",
        "warzone_duels/not_even_close",
        "warzone_duels/unstoppable",
        "warzone_duels/gladiator",
        "reputation/a_good_word",
        "reputation/well_regarded",
        "reputation/pillar_of_the_community",
        "reputation/kind_soul",
        "reputation/generous_spirit",
        "reputation/trusted_name",
        "reputation/merchant_of_merit",
        "reputation/bad_reputation",
        "reputation/public_enemy",
        "reputation/redemption_arc",
        "express/first_class",
        "express/care_package",
        "express/frequent_shipper",
        "express/postal_legend",
        "express/youve_got_mail",
        "express/parcel_collector",
        "express/return_to_sender",
        "express/pen_pal",
        "express/correspondent",
        "express/read_all_about_it",
        "express/avid_reader",
        "diary/dear_diary",
        "diary/first_entry",
        "diary/prolific_writer",
        "diary/finders_keepers",
        "diary/indestructible",
        "diary/stubborn",
        "diary/void_walker",
        "diary/nice_try"
    );
    private ProviderRewardEvidence() {}
    public static Set<String> keys() { return KEYS; }
    public static boolean supportsCounter(String key) {
        return key != null && key.startsWith(COUNTER_PREFIX) && KEYS.contains(key.substring(COUNTER_PREFIX.length()));
    }
    public static Set<String> completed(Map<String, Integer> progress) {
        Set<String> result = new LinkedHashSet<>();
        if (progress != null) progress.forEach((key, value) -> {
            String normalized = normalize(key);
            if (value != null && value >= COMPLETE && KEYS.contains(normalized)) result.add(normalized);
        });
        return Set.copyOf(result);
    }
    public static String counterKey(String key) {
        String normalized = normalize(key);
        if (!KEYS.contains(normalized)) throw new IllegalArgumentException("Unsupported provider milestone: " + key);
        return COUNTER_PREFIX + normalized;
    }
    private static String normalize(String key) { return key == null ? "" : key.trim().toLowerCase(Locale.ROOT); }
}
