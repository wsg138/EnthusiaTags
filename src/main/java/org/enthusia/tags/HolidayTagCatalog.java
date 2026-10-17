package org.enthusia.tags;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

/**
 * Tags that EnthusiaHolidays events grant with {@code tag give <player> <id>} (REQ-910).
 * Ids are year-agnostic so next year's events reuse them. Missing values are installed
 * without overwriting administrator edits, like {@link FrontierPortableTagCatalog}.
 */
final class HolidayTagCatalog {
    private static final List<HolidayTag> DEFINITIONS = List.of(
        tag("pumpkin_hunter", "<bold><gradient:#ff7b00:#ffb347>Pumpkin Hunter</gradient></bold>", "PUMPKIN",
            "&7Found 15 pumpkins in a Halloween hunt"),
        tag("no_pumpkin_left_behind", "<bold><gradient:#ff7b00:#8a2be2>No Pumpkin Left Behind</gradient>",
            "CARVED_PUMPKIN", "&7Found every pumpkin in a Halloween hunt"),
        tag("present_seeker", "<gradient:#c0392b:#27ae60>Present Seeker</gradient>", "CHEST_MINECART",
            "&7Found 12 presents in a Christmas hunt"),
        tag("home_for_the_holidays", "<bold><gradient:#c0392b:#27ae60>Home for the Holidays</gradient>",
            "CAMPFIRE", "&7Found every present in a Christmas hunt"),
        tag("advent_keeper", "<#f4d03f>Advent Keeper", "CLOCK",
            "&7Opened the last door of the Advent calendar"),
        tag("secret_santa", "<#e74c3c>Secret <#27ae60>Santa", "CHEST",
            "&7Sent a Secret Santa gift"),
        tag("seen_the_watcher", "<#9aa0a6>Seen the <#f5f5f5>Watcher", "ENDER_EYE",
            "&7Caught a glimpse of the Watcher in the dark"),
        // \uA0A0 is the pumpkin_king_icon glyph (resourcepack/holiday-tags), outside <bold> so it isn't drawn twice.
        tag("pumpkin_king", "\uA0A0<bold><gradient:#FF3A00:#FF5A00>Pumpkin King</gradient></bold>\uA0A0", "JACK_O_LANTERN",
            "&7The first to find every pumpkin in a Halloween hunt")
    );

    private HolidayTagCatalog() {
    }

    static boolean ensureInstalled(EnthusiaTagsPlugin plugin) {
        FileConfiguration config = plugin.getConfig();
        boolean changed = false;
        for (HolidayTag tag : DEFINITIONS) {
            String root = "tags." + tag.id();
            changed |= setIfMissing(config, root + ".display-name", tag.displayName());
            changed |= setIfMissing(config, root + ".tag-text", tag.displayName());
            changed |= setIfMissing(config, root + ".icon", tag.icon());
            changed |= setIfMissing(config, root + ".description", List.of(tag.description()));
        }
        if (changed) {
            plugin.saveConfig();
            plugin.getLogger().info("Installed missing holiday reward tag definitions.");
        }
        return changed;
    }

    static List<HolidayTag> tags() {
        return DEFINITIONS;
    }

    private static boolean setIfMissing(FileConfiguration config, String path, Object value) {
        if (config.contains(path)) {
            return false;
        }
        config.set(path, value);
        return true;
    }

    private static HolidayTag tag(String id, String displayName, String icon, String description) {
        return new HolidayTag(id, displayName, icon, description);
    }

    record HolidayTag(String id, String displayName, String icon, String description) {
    }
}
