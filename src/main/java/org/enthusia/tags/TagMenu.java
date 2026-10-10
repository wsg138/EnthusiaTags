package org.enthusia.tags;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.enthusia.tags.CollectionSources.Source;
import org.enthusia.tags.CollectionSources;

public final class TagMenu {
    public static final List<Integer> CONTENT_SLOTS = List.of(
        19,20,21,22,23,24,25,
        28,29,30,31,32,33,34,
        37,38,39,40,41,42,43);
    private static final int PAGE_SIZE = CONTENT_SLOTS.size();
    private static final String ALL = "all";
    private static final String LEGACY = "legacy";
    private static final String SUPPORTER = "supporter";
    private static final String ADVANCEMENT_EVENT = "advancement_event";

    private final TagService tagService;
    private final EnthusiaTagsPlugin plugin;
    private final CollectionSources entitlements;
    private final NamespacedKey tagIdKey;
    private final NamespacedKey clearKey;
    private final NamespacedKey rewardsKey;
    private final NamespacedKey cosmeticsKey;
    private final NamespacedKey filterKey;
    private final NamespacedKey previewKey;
    private final NamespacedKey prevKey;
    private final NamespacedKey nextKey;
    private final NamespacedKey closeKey;

    public TagMenu(TagService tagService) {
        this.tagService = tagService;
        this.plugin = tagService.getPlugin();
        this.entitlements = new CollectionSources(plugin);
        this.tagIdKey = key("tag_id");
        this.clearKey = key("clear_tag");
        this.rewardsKey = key("open_rewards");
        this.cosmeticsKey = key("open_cosmetics");
        this.filterKey = key("tag_filter");
        this.previewKey = key("tag_preview");
        this.prevKey = key("tag_prev");
        this.nextKey = key("tag_next");
        this.closeKey = key("tag_close");
    }

    public Inventory create(Player player) {
        return create(player, ALL, 0, false);
    }

    public Inventory create(Player player, int page) { return create(player, ALL, page, false); }

    public Inventory create(Player player, boolean preview) {
        return create(player, ALL, 0, preview);
    }

    public Inventory create(Player player, String filter, int requestedPage, boolean preview) {
        boolean adminPreview = preview && player.hasPermission("enthusia.tags.admin");
        String normalizedFilter = normalizeFilter(filter);
        List<TagDefinition> tags = visibleTags(player, adminPreview, normalizedFilter);
        int pageCount = Math.max(1, (tags.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.max(0, Math.min(requestedPage, pageCount - 1));

        TagMenuHolder holder = new TagMenuHolder(tagService, normalizedFilter, page, adminPreview);
        Inventory inventory = Bukkit.createInventory(holder, 54, TagTextFormat.deserializeCompat(
            adminPreview ? "&6Enthusia &8• &eTags Preview" : "&6Enthusia &8• &fYour Tags"));
        holder.setInventory(inventory);

        frame(inventory);
        PlayerTagData data = tagService.getPlayerData(player.getUniqueId());
        inventory.setItem(4, header(data, tags.size(), normalizedFilter, adminPreview));
        populateFilters(player, inventory, normalizedFilter, adminPreview);
        populateTags(inventory, data, tags, page, adminPreview);
        populateFooter(inventory, data, tags.size(), page, pageCount);
        return inventory;
    }

    private void populateFilters(Player player, Inventory inventory, String normalizedFilter, boolean adminPreview) {
        putFilter(inventory, 10, ALL, Material.NAME_TAG, "&fAll Tags", normalizedFilter);
        putFilter(inventory, 12, LEGACY, Material.ECHO_SHARD, "&bLegacy", normalizedFilter);
        putFilter(inventory, 14, SUPPORTER, Material.GOLD_INGOT, "&6Supporter", normalizedFilter);
        putFilter(inventory, 16, ADVANCEMENT_EVENT, Material.NETHER_STAR, "&dAdvancement / Event", normalizedFilter);
        if (player.hasPermission("enthusia.tags.admin")) {
            inventory.setItem(17, action(Material.SPYGLASS,
                adminPreview ? "&eAdmin Preview: &aON" : "&eAdmin Preview: &cOFF",
                List.of(adminPreview
                    ? "&7Viewing the complete tag catalog."
                    : "&7Preview every configured tag without granting it.",
                    "&7Preview mode never changes ownership.",
                    "",
                    "&eClick to toggle"), previewKey));
        }

    }

    private void populateTags(Inventory inventory, PlayerTagData data, List<TagDefinition> tags, int page, boolean adminPreview) {
        int from = page * PAGE_SIZE;
        int to = Math.min(tags.size(), from + PAGE_SIZE);
        for (int i = from; i < to; i++) {
            TagDefinition definition = tags.get(i);
            inventory.setItem(CONTENT_SLOTS.get(i - from), tagItem(data, definition, adminPreview));
        }
        if (tags.isEmpty()) {
            inventory.setItem(31, plain(Material.PAPER, "&fNo tags in this view",
                "&7Try another filter.", adminPreview ? "&7Preview is showing the full catalog." : "&7Earn tags through rewards and events."));
        }

    }

    private void populateFooter(Inventory inventory, PlayerTagData data, int tagCount, int page, int pageCount) {
        inventory.setItem(45, action(Material.CHEST, "&6Rewards",
            List.of("&7Open your reward browser.", "&eClick to open"), rewardsKey));
        if (page > 0) inventory.setItem(46, action(Material.ARROW, "&fPrevious Page", List.of("&eClick"), prevKey));
        inventory.setItem(47, action(Material.FEATHER, "&bCosmetics",
            List.of("&7Open the cosmetics browser.", "&eClick to open"), cosmeticsKey));
        inventory.setItem(49, plain(Material.PAPER, "&fPage " + (page + 1) + " &8/ &f" + pageCount,
            "&7Showing " + tagCount + (tagCount == 1 ? " tag" : " tags") + "."));
        inventory.setItem(51, clearItem(data));
        if (page + 1 < pageCount) inventory.setItem(52, action(Material.ARROW, "&fNext Page", List.of("&eClick"), nextKey));
        inventory.setItem(53, action(Material.BARRIER, "&cClose", List.of("&7Close this menu."), closeKey));
    }

    private void frame(Inventory inventory) {
        ItemStack black = plain(Material.BLACK_STAINED_GLASS_PANE, " ");
        ItemStack gray = plain(Material.GRAY_STAINED_GLASS_PANE, " ");
        ItemStack orange = plain(Material.ORANGE_STAINED_GLASS_PANE, " ");
        for (int slot = 0; slot < 54; slot++) {
            int row = slot / 9;
            int column = slot % 9;
            if (row == 0 || row == 5 || column == 0 || column == 8) inventory.setItem(slot, black);
        }
        for (int slot = 9; slot <= 17; slot++) inventory.setItem(slot, gray);
        inventory.setItem(3, orange);
        inventory.setItem(5, orange);
    }

    private ItemStack header(PlayerTagData data, int visible, String filter, boolean preview) {
        String selected = data.getSelectedTag();
        TagDefinition selectedDefinition = selected == null ? null : tagService.getRegistry().get(selected);
        List<String> lore = new ArrayList<>();
        lore.add("&7Owned: &f" + data.getOwnedTags().size());
        lore.add("&7Selected: " + (selectedDefinition == null ? "&fNone" : "&f" + TagTextFormat.legacyText(selectedDefinition.getDisplayName())));
        lore.add("&7Filter: &f" + filterName(filter));
        lore.add("&7Visible: &f" + visible);
        if (preview) {
            lore.add("");
            lore.add("&eADMIN PREVIEW");
            lore.add("&7All configured tags are visible.");
            lore.add("&7Clicks are read-only.");
        }
        return plain(Material.NAME_TAG, preview ? "&eTag Catalog Preview" : "&6Your Tags", lore.toArray(String[]::new));
    }

    private ItemStack tagItem(PlayerTagData data, TagDefinition tag, boolean preview) {
        boolean owned = data.getOwnedTags().contains(tag.getId().toLowerCase(Locale.ROOT));
        boolean selected = tag.getId().equalsIgnoreCase(data.getSelectedTag());
        List<String> lore = new ArrayList<>(tag.getDescription());
        addTagSources(lore, tag);
        addTagStatus(lore, selected, owned, preview);
        ItemStack item = plain(tag.getIcon(), tag.getDisplayName(), lore.toArray(String[]::new));
        ItemMeta meta = item.getItemMeta();
        meta.setEnchantmentGlintOverride(selected);
        meta.getPersistentDataContainer().set(tagIdKey, PersistentDataType.STRING, tag.getId().toLowerCase(Locale.ROOT));
        item.setItemMeta(meta);
        return item;
    }

    private void addTagSources(List<String> lore, TagDefinition tag) {
        List<Source> sources = entitlements.definitionsForTag(tag.getId());
        if (!sources.isEmpty()) {
            lore.add("");
            lore.add("&7Source:");
            for (Source source : sources) lore.add("&8• &f" + source.source());
        } else if (tag.getId().toLowerCase(Locale.ROOT).startsWith("adv_")) {
            lore.add("");
            lore.add("&7Source: &fCustom Advancement");
        }
    }

    private void addTagStatus(List<String> lore, boolean selected, boolean owned, boolean preview) {
        lore.add("");
        if (selected) {
            lore.add("&a✓ Currently Selected");
        } else if (owned && !preview) {
            lore.add("&eClick to equip");
        } else if (owned) {
            lore.add("&aOwned");
            lore.add("&7Preview mode is read-only.");
        } else if (preview) {
            lore.add("&ePreview Only");
            lore.add("&7Not owned by your account.");
        }
    }

    private ItemStack clearItem(PlayerTagData data) {
        String selected = data.getSelectedTag();
        TagDefinition definition = selected == null ? null : tagService.getRegistry().get(selected);
        return action(Material.BARRIER, "&cClear Active Tag", List.of(
            "&7Selected: " + (definition == null ? "&fNone" : "&f" + TagTextFormat.legacyText(definition.getDisplayName())),
            "&7Remove the tag from your display.",
            "",
            "&aYour ownership is not affected.",
            "&eClick to clear"), clearKey);
    }

    private void putFilter(Inventory inventory, int slot, String id, Material material, String name, String selected) {
        boolean active = id.equals(selected);
        ItemStack item = action(material, (active ? "&6" : "&f") + strip(name), List.of(
            active ? "&6Selected filter" : "&7Show " + filterName(id) + " tags.",
            active ? "" : "&eClick to filter"), filterKey);
        ItemMeta meta = item.getItemMeta();
        meta.setEnchantmentGlintOverride(active);
        meta.getPersistentDataContainer().set(filterKey, PersistentDataType.STRING, id);
        item.setItemMeta(meta);
        inventory.setItem(slot, item);
    }

    private List<TagDefinition> visibleTags(Player player, boolean preview, String filter) {
        Set<String> owned = tagService.getPlayerData(player.getUniqueId()).getOwnedTags();
        return tagService.getRegistry().getAll().stream()
            .filter(tag -> preview || owned.contains(tag.getId().toLowerCase(Locale.ROOT)))
            .filter(tag -> matchesFilter(tag, filter))
            .sorted(Comparator.comparing(TagDefinition::getId, String.CASE_INSENSITIVE_ORDER))
            .toList();
    }

    private boolean matchesFilter(TagDefinition tag, String filter) {
        if (ALL.equals(filter)) return true;
        String id = tag.getId().toLowerCase(Locale.ROOT);
        List<Source> sources = entitlements.definitionsForTag(id);
        String joined = sources.stream().map(Source::source)
            .map(value -> value.toLowerCase(Locale.ROOT)).reduce("", (a, b) -> a + " " + b);
        return switch (filter) {
            case LEGACY -> joined.contains(LEGACY);
            case SUPPORTER -> joined.contains(SUPPORTER) || joined.contains("glorious");
            case ADVANCEMENT_EVENT -> isAdvancementOrEvent(id, joined);
            default -> true;
        };
    }

    private boolean isAdvancementOrEvent(String id, String sources) {
        return id.startsWith("adv_") || sources.contains("event") || sources.contains("achievement");
    }

    private String normalizeFilter(String filter) {
        String value = filter == null ? ALL : filter.toLowerCase(Locale.ROOT);
        return Set.of(ALL, LEGACY, SUPPORTER, ADVANCEMENT_EVENT).contains(value) ? value : ALL;
    }

    private String filterName(String filter) {
        return switch (filter) {
            case LEGACY -> "Legacy";
            case SUPPORTER -> "Supporter";
            case ADVANCEMENT_EVENT -> "Advancement / Event";
            default -> "All";
        };
    }

    private ItemStack action(Material material, String name, List<String> lore, NamespacedKey key) {
        ItemStack item = plain(material, name, lore.toArray(String[]::new));
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack plain(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material == null ? Material.PAPER : material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(TagTextFormat.deserializeCompat(name));
        if (lore.length > 0) meta.lore(java.util.Arrays.stream(lore).map(TagTextFormat::deserializeCompat).toList());
        item.setItemMeta(meta);
        return item;
    }

    private String strip(String value) {
        return value.replaceAll("(?i)&[0-9A-FK-OR]", "");
    }

    private NamespacedKey key(String value) { return new NamespacedKey(plugin, value); }

    public NamespacedKey getTagIdKey() { return tagIdKey; }
    public NamespacedKey getClearKey() { return clearKey; }
    public NamespacedKey getRewardsKey() { return rewardsKey; }
    public NamespacedKey getCosmeticsKey() { return cosmeticsKey; }
    public NamespacedKey getFilterKey() { return filterKey; }
    public NamespacedKey getPreviewKey() { return previewKey; }
    public NamespacedKey getPrevKey() { return prevKey; }
    public NamespacedKey getNextKey() { return nextKey; }
    public NamespacedKey getCloseKey() { return closeKey; }
}
