package org.enthusia.tags;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HolidayTagCatalogTest {
    @Test
    void catalogHasTheEnthusiaHolidaysRewardTags() {
        Set<String> ids = new HashSet<>();
        for (HolidayTagCatalog.HolidayTag tag : HolidayTagCatalog.tags()) {
            assertTrue(ids.add(tag.id()), "duplicate tag id: " + tag.id());
            assertEquals(tag.id().toLowerCase(), tag.id(), "tag give lowercases ids");
        }
        // Event reward ids plus the staff-awarded seasonal builder recognition.
        assertEquals(Set.of("pumpkin_hunter", "no_pumpkin_left_behind", "present_seeker",
            "home_for_the_holidays", "advent_keeper", "secret_santa", "seen_the_watcher", "pumpkin_king", "pumpkin_patcher", "remembered"), ids);
    }

    @Test
    void pumpkinKingWearsThePumpkinGlyphOnBothSides() {
        HolidayTagCatalog.HolidayTag king = HolidayTagCatalog.tags().stream()
            .filter(tag -> tag.id().equals("pumpkin_king")).findFirst().orElseThrow();
        // U+A0A0 is the pumpkin_king_icon glyph in resourcepack/holiday-tags (Nexo); kept outside <bold> so it isn't drawn twice.
        assertTrue(king.displayName().startsWith("\uA0A0<bold>"), king.displayName());
        assertTrue(king.displayName().endsWith("</bold>\uA0A0"), king.displayName());
        assertTrue(king.displayName().contains("Pumpkin King"), king.displayName());
    }

    @Test
    void installAddsMissingHolidayTagsWithoutOverwritingCustomization() {
        EnthusiaTagsPlugin plugin = mock(EnthusiaTagsPlugin.class);
        YamlConfiguration config = new YamlConfiguration();
        config.set("tags.secret_santa.display-name", "<red>Custom Santa");
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("HolidayTagCatalogTest"));

        assertTrue(HolidayTagCatalog.ensureInstalled(plugin));

        assertEquals("<red>Custom Santa", config.getString("tags.secret_santa.display-name"));
        assertEquals("CHEST", config.getString("tags.secret_santa.icon"));
        assertEquals(List.of("&7Sent a Secret Santa gift"), config.getStringList("tags.secret_santa.description"));
        assertEquals("CARVED_PUMPKIN", config.getString("tags.no_pumpkin_left_behind.icon"));
        // Holiday tags are granted directly; they are not permission entitlements.
        assertNull(config.getString("tags.no_pumpkin_left_behind.entitlement-permission"));
        verify(plugin).saveConfig();

        assertFalse(HolidayTagCatalog.ensureInstalled(plugin));
        verify(plugin).saveConfig();
        verify(plugin, never()).reloadConfig();
    }

    @Test
    void christmasTagsNamePeppermintsAndUpgradeUneditedOldDefaults() {
        EnthusiaTagsPlugin plugin = mock(EnthusiaTagsPlugin.class);
        YamlConfiguration config = new YamlConfiguration();
        // A server installed before the rename still has the untouched present wording.
        config.set("tags.present_seeker.display-name", "<gradient:#c0392b:#27ae60>Present Seeker</gradient>");
        config.set("tags.present_seeker.tag-text", "<gradient:#c0392b:#27ae60>Present Seeker</gradient>");
        config.set("tags.present_seeker.description", List.of("&7Found 12 presents in a Christmas hunt"));
        config.set("tags.present_seeker.icon", "CHEST_MINECART");
        // An administrator's own wording is kept.
        config.set("tags.home_for_the_holidays.description", List.of("&7Our own words"));
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("HolidayTagCatalogTest"));

        assertTrue(HolidayTagCatalog.ensureInstalled(plugin));

        assertEquals("<gradient:#c0392b:#27ae60>Mint Condition</gradient>", config.getString("tags.present_seeker.display-name"));
        assertEquals("<gradient:#c0392b:#27ae60>Mint Condition</gradient>", config.getString("tags.present_seeker.tag-text"));
        assertEquals(List.of("&7Found 12 peppermints in a Christmas hunt"), config.getStringList("tags.present_seeker.description"));
        assertEquals(List.of("&7Our own words"), config.getStringList("tags.home_for_the_holidays.description"));
        assertFalse(HolidayTagCatalog.ensureInstalled(plugin), "the upgrade runs once");
    }

    @Test
    void anEditedPresentSeekerNameIsNotRenamed() {
        EnthusiaTagsPlugin plugin = mock(EnthusiaTagsPlugin.class);
        YamlConfiguration config = new YamlConfiguration();
        config.set("tags.present_seeker.display-name", "<green>Gift Finder");
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("HolidayTagCatalogTest"));

        HolidayTagCatalog.ensureInstalled(plugin);

        assertEquals("<green>Gift Finder", config.getString("tags.present_seeker.display-name"));
    }
}
