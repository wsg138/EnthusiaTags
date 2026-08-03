package org.enthusia.tags;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CachedTagPlaceholderResolverTest {
    private static final String LINE_FORMAT = "<gray>[{tag}<gray>]";

    @Test
    void returnsMiniMessagePlainIdAndLegacyFromCachedSelection() {
        TagRegistry registry = registryWithHero();
        PlayerTagData data = selectedHero();

        TagPlaceholderOutput output = CachedTagPlaceholderResolver.resolve(
            data, registry, false, LINE_FORMAT, "Lincoln", value -> value);

        assertEquals("<gray>[<red><bold>Hero<gray>]", output.miniMessage());
        assertEquals("[Hero]", output.plain());
        assertEquals("hero", output.id());
        assertTrue(output.legacy().contains("Hero"));
        assertEquals(output.miniMessage(), output.value("selected_mm"));
        assertEquals(output.plain(), output.value("selected_plain"));
        assertEquals(output.id(), output.value("selected_id"));
        assertEquals(output.legacy(), output.value("selected_legacy"));
        assertNull(output.value("unknown"));
    }

    @Test
    void returnsEmptyBeforeDataLoadsAndPopulatesAfterLoad() {
        TagRegistry registry = registryWithHero();
        assertSame(TagPlaceholderOutput.EMPTY, CachedTagPlaceholderResolver.resolve(
            null, registry, false, LINE_FORMAT, "Lincoln", value -> value));

        TagPlaceholderOutput loaded = CachedTagPlaceholderResolver.resolve(
            selectedHero(), registry, false, LINE_FORMAT, "Lincoln", value -> value);
        assertEquals("hero", loaded.id());
    }

    @Test
    void returnsEmptyWithoutSelection() {
        assertSame(TagPlaceholderOutput.EMPTY, CachedTagPlaceholderResolver.resolve(
            new PlayerTagData(), registryWithHero(), false, LINE_FORMAT, "Lincoln", value -> value));
    }

    @Test
    void returnsEmptyWhenSelectedTagWasDeleted() {
        PlayerTagData data = selectedHero();
        assertSame(TagPlaceholderOutput.EMPTY, CachedTagPlaceholderResolver.resolve(
            data, new TagRegistry(), false, LINE_FORMAT, "Lincoln", value -> value));
    }

    @Test
    void suppressionAndUnsuppressionChangeOutput() {
        TagRegistry registry = registryWithHero();
        PlayerTagData data = selectedHero();
        assertSame(TagPlaceholderOutput.EMPTY, CachedTagPlaceholderResolver.resolve(
            data, registry, true, LINE_FORMAT, "Lincoln", value -> value));
        assertEquals("hero", CachedTagPlaceholderResolver.resolve(
            data, registry, false, LINE_FORMAT, "Lincoln", value -> value).id());
    }

    @Test
    void vanishedPlayerReturnsEmpty() {
        PlayerTagData data = selectedHero();
        data.setVanished(true);
        assertSame(TagPlaceholderOutput.EMPTY, CachedTagPlaceholderResolver.resolve(
            data, registryWithHero(), false, LINE_FORMAT, "Lincoln", value -> value));
    }

    @Test
    void playerControlledTextCannotInjectMiniMessage() {
        TagRegistry registry = new TagRegistry();
        registry.register(new TagDefinition("name", "Name", "{player}", Material.NAME_TAG, List.of()));
        PlayerTagData data = new PlayerTagData();
        data.getOwnedTags().add("name");
        data.setSelectedTag("name");

        TagPlaceholderOutput output = CachedTagPlaceholderResolver.resolve(
            data, registry, false, "{tag}", "<red>Injected", value -> value);

        assertEquals("<red>Injected", output.plain());
        assertNotEquals("<red>Injected", output.miniMessage());
        assertTrue(output.miniMessage().contains("\\<red>"));
    }

    @Test
    void legacyConfiguredTagsStillRenderDuringMigration() {
        TagRegistry registry = new TagRegistry();
        registry.register(new TagDefinition("legacy", "&aLegacy", "&#39c5ff&lLegacy",
            Material.NAME_TAG, List.of()));
        PlayerTagData data = new PlayerTagData();
        data.getOwnedTags().add("legacy");
        data.setSelectedTag("legacy");

        TagPlaceholderOutput output = CachedTagPlaceholderResolver.resolve(
            data, registry, false, "&7[{tag}&7]", "Lincoln", value -> value);

        assertEquals("[Legacy]", output.plain());
        assertTrue(output.miniMessage().toLowerCase(java.util.Locale.ROOT).contains("#39c5ff"));
        assertTrue(output.miniMessage().contains("bold"));
    }

    private static TagRegistry registryWithHero() {
        TagRegistry registry = new TagRegistry();
        registry.register(new TagDefinition("hero", "<red><bold>Hero", "<red><bold>Hero",
            Material.NAME_TAG, List.of()));
        return registry;
    }

    private static PlayerTagData selectedHero() {
        PlayerTagData data = new PlayerTagData();
        data.getOwnedTags().add("hero");
        data.setSelectedTag("hero");
        return data;
    }
}
