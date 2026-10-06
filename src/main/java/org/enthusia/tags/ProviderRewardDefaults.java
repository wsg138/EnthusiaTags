package org.enthusia.tags;

import java.util.List;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Bounded, once-only source recovery; preserves administrator-owned collections. */
final class ProviderRewardDefaults {
    private static final String VERSION_KEY = "provider-rewards-version";
    private static final int VERSION = 1;
    private static final List<String> REWARD_IDS = List.of(
        "adv_warzone_welcome_to_thunderdome",
        "adv_warzone_first_blood",
        "adv_warzone_victor_spoils",
        "adv_warzone_price_for_peace",
        "adv_warzone_my_house_my_rules",
        "adv_warzone_adapt_and_overcome",
        "adv_warzone_not_even_close",
        "adv_warzone_unstoppable",
        "adv_warzone_gladiator",
        "adv_commend_a_good_word",
        "adv_commend_well_regarded",
        "adv_commend_pillar_of_the_community",
        "adv_commend_kind_soul",
        "adv_commend_generous_spirit",
        "adv_commend_trusted_name",
        "adv_commend_merchant_of_merit",
        "adv_commend_bad_reputation",
        "adv_commend_public_enemy",
        "adv_commend_redemption_arc",
        "adv_express_first_class",
        "adv_express_care_package",
        "adv_express_frequent_shipper",
        "adv_express_postal_legend",
        "adv_express_youve_got_mail",
        "adv_express_parcel_collector",
        "adv_express_return_to_sender",
        "adv_express_pen_pal",
        "adv_express_correspondent",
        "adv_express_read_all_about_it",
        "adv_express_avid_reader",
        "adv_diary_dear_diary",
        "adv_diary_first_entry",
        "adv_diary_prolific_writer",
        "adv_diary_finders_keepers",
        "adv_diary_indestructible",
        "adv_diary_stubborn",
        "adv_diary_void_walker",
        "adv_diary_nice_try"
    );
    private static final List<String> TAG_IDS = List.of(
        "adv_clutch",
        "adv_courier",
        "adv_gladiator",
        "adv_indestructible",
        "adv_infamous",
        "adv_postmaster",
        "adv_public_enemy",
        "adv_redeemed",
        "adv_unstoppable",
        "adv_void_walker",
        "adv_well_regarded",
        "adv_writer"
    );
    private ProviderRewardDefaults() {}
    static boolean needsUpdate(YamlConfiguration target) { return target.getInt(VERSION_KEY, 0) < VERSION; }
    static boolean migrate(String resource, YamlConfiguration target, YamlConfiguration defaults, ConfigMigrator.MigrationReport report) {
        if (!needsUpdate(target) || defaults.getInt(VERSION_KEY, 0) < VERSION) return false;
        List<String> ids = "rewards.yml".equals(resource) ? REWARD_IDS : TAG_IDS;
        String collection = "rewards.yml".equals(resource) ? "rewards." : "tags.";
        for (String id : ids) copyMissing(target, defaults, collection + id, report);
        if ("rewards.yml".equals(resource)) copyMissing(target, defaults, "categories.advancements", report);
        target.set(VERSION_KEY, VERSION);
        report.migrated(resource + ": recovered provider reward defaults");
        return true;
    }
    private static void copyMissing(YamlConfiguration target, YamlConfiguration defaults, String path, ConfigMigrator.MigrationReport report) {
        if (target.contains(path)) return;
        ConfigurationSection source = defaults.getConfigurationSection(path);
        if (source == null) throw new IllegalArgumentException("Missing provider default: " + path);
        copySection(target, source, path);
        report.added("Provider default added: " + path);
    }
    private static void copySection(YamlConfiguration target, ConfigurationSection source, String path) {
        for (String key : source.getKeys(false)) {
            String childPath = path + "." + key;
            ConfigurationSection child = source.getConfigurationSection(key);
            if (child == null) target.set(childPath, source.get(key));
            else copySection(target, child, childPath);
        }
    }
}
