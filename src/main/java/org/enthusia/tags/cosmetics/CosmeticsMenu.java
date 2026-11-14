package org.enthusia.tags.cosmetics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.enthusia.tags.EnthusiaTagsPlugin;
import org.enthusia.tags.Messages;
import org.enthusia.tags.TagService;
import org.enthusia.tags.TagTextFormat;
import org.enthusia.tags.CollectionSources.Source;
import org.enthusia.tags.CollectionSources;

public final class CosmeticsMenu {
    public static final List<Integer> CONTENT_SLOTS = List.of(
        19,20,21,22,23,24,25,
        28,29,30,31,32,33,34,
        37,38,39,40,41,42,43);
    private static final int PAGE_SIZE = CONTENT_SLOTS.size();
    private static final List<Integer> DASHBOARD_SLOTS = List.of(19, 21, 23, 25, 29, 31, 33);
    private static final String ADMIN_PERMISSION = "enthusia.tags.admin";
    private static final String COUNT_SEPARATOR = " &8/ &f";
    private static final String CLICK_OPEN = "&eClick to open";
    private static final String CLICK = "&eClick";

    private final CosmeticsService cosmeticsService;
    private final TagService tagService;
    private final EnthusiaTagsPlugin plugin;
    private final CollectionSources entitlements;
    private final NamespacedKey cosmeticKey;
    private final NamespacedKey categoryKey;
    private final NamespacedKey backKey;
    private final NamespacedKey tagsKey;
    private final NamespacedKey rewardsKey;
    private final NamespacedKey previewKey;
    private final NamespacedKey prevKey;
    private final NamespacedKey nextKey;
    private final NamespacedKey closeKey;

    public CosmeticsMenu(CosmeticsService cosmeticsService, TagService tagService, Messages messages) {
        this.cosmeticsService = cosmeticsService;
        this.tagService = tagService;
        this.plugin = tagService.getPlugin();
        this.entitlements = new CollectionSources(plugin);
        this.cosmeticKey = key("cosmetic_id");
        this.categoryKey = key("cosmetic_category");
        this.backKey = key("cosmetics_back");
        this.tagsKey = key("cosmetics_tags");
        this.rewardsKey = key("cosmetics_rewards");
        this.previewKey = key("cosmetics_preview");
        this.prevKey = key("cosmetics_prev");
        this.nextKey = key("cosmetics_next");
        this.closeKey = key("cosmetics_close");
    }

    public Inventory createMain(Player player) {
        return createMain(player, false);
    }

    public Inventory createMain(Player player, int page) { return createMain(player, page, false); }
    public Inventory createMain(Player player, boolean preview) { return createMain(player, 0, preview); }
    public Inventory createMain(Player player, int requestedPage, boolean preview) {
        boolean adminPreview = preview && player.hasPermission(ADMIN_PERMISSION);
        int pageCount=Math.max(1,(cosmeticsService.getCategories().size()+6)/7);
        int page=Math.max(0,Math.min(requestedPage,pageCount-1));
        CosmeticsMenuHolder holder = new CosmeticsMenuHolder(cosmeticsService, null, page, adminPreview);
        Inventory inventory = Bukkit.createInventory(holder, 54, TagTextFormat.deserializeCompat(
            adminPreview ? "&6Enthusia &8• &eCosmetics Preview" : "&6Enthusia &8• &fCosmetics"));
        holder.setInventory(inventory);
        frame(inventory);

        populateMainHeader(player, inventory, adminPreview);
        populateMainNavigation(player, inventory, adminPreview);
        populateDashboard(player, inventory, page, adminPreview);
        populateMainFooter(inventory, page, pageCount);
        return inventory;
    }

    private void populateMainHeader(Player player, Inventory inventory, boolean adminPreview) {
        int total = cosmeticsService.getCosmetics().size();
        int unlocked = (int) cosmeticsService.getCosmetics().values().stream()
            .filter(cosmetic -> canUse(player, cosmetic)).count();
        long equipped = cosmeticsService.getCategories().keySet().stream()
            .filter(category -> cosmeticsService.getSelection(player.getUniqueId(), category) != null).count();
        long special = cosmeticsService.getCosmetics().values().stream()
            .filter(cosmetic -> !entitlements.definitionsForCosmetic(cosmetic.getId()).isEmpty())
            .count();

        inventory.setItem(4, plain(Material.FEATHER, adminPreview ? "&eCosmetic Catalog Preview" : "&6Your Cosmetics",
            "&7Unlocked: &f" + unlocked + COUNT_SEPARATOR + total,
            "&7Currently Equipped: &f" + equipped,
            "&7Special / Legacy: &f" + special,
            adminPreview ? "" : "&7Choose a category below.",
            adminPreview ? "&eADMIN PREVIEW &7- read-only" : ""));

    }

    private void populateDashboard(Player player, Inventory inventory, int page, boolean adminPreview) {
        List<CosmeticsCategory> categories = new ArrayList<>(cosmeticsService.getCategories().values());
        categories.sort(Comparator.comparing(CosmeticsCategory::id));
        for (int i = 0; i < DASHBOARD_SLOTS.size() && page*7+i < categories.size(); i++) {
            CosmeticsCategory category = categories.get(page*7+i);
            inventory.setItem(DASHBOARD_SLOTS.get(i), createCategoryItem(player, category, adminPreview));
        }

    }

    private void populateMainFooter(Inventory inventory, int page, int pageCount) {
        inventory.setItem(45, action(Material.CHEST, "&6Rewards",
            List.of("&7Browse progression and entitlement rewards.", CLICK_OPEN), rewardsKey));
        inventory.setItem(47, action(Material.NAME_TAG, "&bTags",
            List.of("&7Browse and equip your tags.", CLICK_OPEN), tagsKey));
        inventory.setItem(49, plain(Material.BOOK, "&fHow Cosmetics Work",
            "&7Owned cosmetics can be equipped from category pages.",
            "&7Legacy and supporter cosmetics explain their source.",
            "&7Active-rank cosmetics require the rank to remain active.",
            "",
            "&7Admin preview is read-only."));
        inventory.setItem(53, action(Material.BARRIER, "&cClose", List.of("&7Close this menu."), closeKey));
        if(page>0) inventory.setItem(46,action(Material.ARROW,"&fPrevious Page",List.of(CLICK),prevKey));
        if(page+1<pageCount) inventory.setItem(52,action(Material.ARROW,"&fNext Page",List.of(CLICK),nextKey));
    }

    private void populateMainNavigation(Player player, Inventory inventory, boolean adminPreview) {
        if (player.hasPermission(ADMIN_PERMISSION)) {
            inventory.setItem(14, action(Material.SPYGLASS,
                adminPreview ? "&eAdmin Preview: &aON" : "&eAdmin Preview: &cOFF",
                List.of(adminPreview
                    ? "&7Browsing the complete cosmetic catalog."
                    : "&7Browse every cosmetic without unlocking it.",
                    "&7Preview mode never changes selections or ownership.",
                    "",
                    "&eClick to toggle"), previewKey));
        }
        inventory.setItem(10, action(Material.NAME_TAG, "&bTags",
            List.of("&7Open your tag collection.", CLICK_OPEN), tagsKey));
        inventory.setItem(16, action(Material.CHEST, "&6Rewards",
            List.of("&7Open the rewards browser.", CLICK_OPEN), rewardsKey));
    }

    public Inventory createCategory(Player player, String categoryId) {
        return createCategory(player, categoryId, 0, false);
    }

    public Inventory createCategory(Player player, String categoryId, int page) { return createCategory(player,categoryId,page,false); }
    public Inventory createCategory(Player player, String categoryId, int requestedPage, boolean preview) {
        boolean adminPreview = preview && player.hasPermission(ADMIN_PERMISSION);
        CosmeticsCategory category = cosmeticsService.getCategories().get(categoryId);
        List<CosmeticDefinition> choices = categoryChoices(cosmeticsService.getCosmetics().values(), categoryId);
        int pageCount = Math.max(1, (choices.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.max(0, Math.min(requestedPage, pageCount - 1));

        CosmeticsMenuHolder holder = new CosmeticsMenuHolder(cosmeticsService, categoryId, page, adminPreview);
        String titleText = category == null ? "&6Enthusia &8• &fCosmetics"
            : "&6Enthusia &8• &f" + plainName(category.name());
        Inventory inventory = Bukkit.createInventory(holder, 54, TagTextFormat.deserializeCompat(titleText));
        holder.setInventory(inventory);
        frame(inventory);

        populateCategoryHeader(player, inventory, categoryId, category, choices, adminPreview);
        populateCategoryNavigation(player, inventory, adminPreview);
        populateChoices(player, inventory, choices, page, adminPreview);
        populateCategoryFooter(inventory, choices.size(), page, pageCount);
        return inventory;
    }

    private void populateCategoryHeader(Player player, Inventory inventory, String categoryId,
                                        CosmeticsCategory category, List<CosmeticDefinition> choices, boolean adminPreview) {
        int unlocked = (int) choices.stream().filter(cosmetic -> canUse(player, cosmetic)).count();
        String selectedId = cosmeticsService.getSelection(player.getUniqueId(), categoryId);
        CosmeticDefinition selected = selectedId == null ? null : cosmeticsService.getCosmetics().get(selectedId.toLowerCase(Locale.ROOT));
        inventory.setItem(4, plain(categoryIcon(category),
            category == null ? "&fCosmetics" : category.name(),
            "&7Unlocked: &f" + unlocked + COUNT_SEPARATOR + choices.size(),
            "&7Selected: " + (selected == null ? "&fNone" : selected.getName()),
            adminPreview ? "&eADMIN PREVIEW &7- read-only" : "&7Click an available cosmetic to equip it."));

    }

    private Material categoryIcon(CosmeticsCategory category) {
        return category == null || category.icon() == null ? Material.PAPER : category.icon();
    }

    private void populateCategoryNavigation(Player player, Inventory inventory, boolean adminPreview) {
        if (player.hasPermission(ADMIN_PERMISSION)) {
            inventory.setItem(14, action(Material.SPYGLASS,
                adminPreview ? "&eAdmin Preview: &aON" : "&eAdmin Preview: &cOFF",
                List.of("&7Preview never persists cosmetic selections.", "", "&eClick to toggle"), previewKey));
        }
        inventory.setItem(10, action(Material.NAME_TAG, "&bTags",
            List.of("&7Open your tag collection.", CLICK_OPEN), tagsKey));
        inventory.setItem(16, action(Material.CHEST, "&6Rewards",
            List.of("&7Open the rewards browser.", CLICK_OPEN), rewardsKey));

    }

    private void populateChoices(Player player, Inventory inventory, List<CosmeticDefinition> choices, int page, boolean adminPreview) {
        int from = page * PAGE_SIZE;
        int to = Math.min(choices.size(), from + PAGE_SIZE);
        for (int i = from; i < to; i++) {
            inventory.setItem(CONTENT_SLOTS.get(i - from), createCosmeticItem(player, choices.get(i), adminPreview));
        }
        if (choices.isEmpty()) {
            inventory.setItem(31, plain(Material.PAPER, "&fNo cosmetics configured",
                "&7This category does not contain any cosmetics."));
        }

    }

    private void populateCategoryFooter(Inventory inventory, int choiceCount, int page, int pageCount) {
        inventory.setItem(45, action(Material.BOOK, "&fCategories",
            List.of("&7Return to the cosmetics dashboard.", "&eClick to go back"), backKey));
        if (page > 0) inventory.setItem(47, action(Material.ARROW, "&fPrevious Page", List.of(CLICK), prevKey));
        inventory.setItem(49, plain(Material.PAPER, "&fPage " + (page + 1) + COUNT_SEPARATOR + pageCount,
            "&7" + choiceCount + (choiceCount == 1 ? " cosmetic" : " cosmetics") + " in this category."));
        if (page + 1 < pageCount) inventory.setItem(51, action(Material.ARROW, "&fNext Page", List.of(CLICK), nextKey));
        inventory.setItem(53, action(Material.BARRIER, "&cClose", List.of("&7Close this menu."), closeKey));
    }

    public NamespacedKey getCosmeticKey() { return cosmeticKey; }
    public NamespacedKey getCategoryKey() { return categoryKey; }
    public NamespacedKey getBackKey() { return backKey; }
    public NamespacedKey getTagsKey() { return tagsKey; }
    public NamespacedKey getRewardsKey() { return rewardsKey; }
    public NamespacedKey getPreviewKey() { return previewKey; }
    public NamespacedKey getPrevKey() { return prevKey; }
    public NamespacedKey getNextKey() { return nextKey; }
    public NamespacedKey getCloseKey() { return closeKey; }

    static List<CosmeticDefinition> categoryChoices(java.util.Collection<CosmeticDefinition> cosmetics, String category) {
        return cosmetics.stream().filter(value -> value.getCategory().equalsIgnoreCase(category))
            .sorted(Comparator.comparing(value -> value.getType() != CosmeticType.ORIGINAL))
            .toList();
    }

    private ItemStack createCategoryItem(Player player, CosmeticsCategory category, boolean preview) {
        List<CosmeticDefinition> entries = categoryChoices(cosmeticsService.getCosmetics().values(), category.id());
        int unlocked = (int) entries.stream().filter(cosmetic -> canUse(player, cosmetic)).count();
        String selectedId = cosmeticsService.getSelection(player.getUniqueId(), category.id());
        CosmeticDefinition selected = selectedId == null ? null : cosmeticsService.getCosmetics().get(selectedId.toLowerCase(Locale.ROOT));
        List<String> lore = new ArrayList<>();
        lore.add(categoryDescription(category.id()));
        lore.add("");
        lore.add("&7Unlocked: &f" + unlocked + COUNT_SEPARATOR + entries.size());
        lore.add("&7Selected: " + (selected == null ? "&fNone" : selected.getName()));
        if (preview) lore.add("&ePreviewing full catalog");
        lore.add("");
        lore.add("&eClick to browse");
        ItemStack stack = plain(category.icon() == null ? Material.PAPER : category.icon(), category.name(), lore.toArray(String[]::new));
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(categoryKey, PersistentDataType.STRING, category.id());
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack createCosmeticItem(Player player, CosmeticDefinition cosmetic, boolean preview) {
        boolean has = canUse(player, cosmetic);
        String selected = cosmeticsService.getSelection(player.getUniqueId(), cosmetic.getCategory());
        boolean active = has && selected != null && selected.equalsIgnoreCase(cosmetic.getId());
        List<String> lore = new ArrayList<>();

        addCosmeticSources(lore, cosmetic);
        addCosmeticStatus(lore, active, has, preview);

        ItemStack stack = plain(cosmetic.getIcon(), cosmetic.getName(), lore.toArray(String[]::new));
        ItemMeta meta = stack.getItemMeta();
        meta.setEnchantmentGlintOverride(active);
        meta.getPersistentDataContainer().set(cosmeticKey, PersistentDataType.STRING, cosmetic.getId());
        stack.setItemMeta(meta);
        return stack;
    }

    private void addCosmeticSources(List<String> lore, CosmeticDefinition cosmetic) {
        if (cosmetic.getType() == CosmeticType.ORIGINAL) {
            lore.add("&7Use the server's normal message/effect.");
        }

        List<Source> sources = entitlements.definitionsForCosmetic(cosmetic.getId());
        if (!sources.isEmpty()) {
            lore.add("");
            lore.add("&7Source:");
            for (Source source : sources) lore.add("&8• &f" + source.source());
            if (entitlements.isActiveOnlyCosmetic(cosmetic.getId())) {
                lore.add("&6Active-rank-only cosmetic");
            } else {
                lore.add("&aPermanent unlock once earned");
            }
        }

    }

    private void addCosmeticStatus(List<String> lore, boolean active, boolean has, boolean preview) {
        lore.add("");
        if (active) {
            lore.add("&a✓ Equipped");
        } else if (has && !preview) {
            lore.add("&eClick to equip");
        } else if (has) {
            lore.add("&aOwned");
            lore.add("&7Preview mode is read-only.");
        } else if (preview) {
            lore.add("&ePreview Only");
            lore.add("&7Not available to your account.");
        } else {
            lore.add("&cLocked");
        }

    }

    private boolean canUse(Player player,CosmeticDefinition cosmetic) { return player.hasPermission(cosmetic.getPermission()); }

    private String categoryDescription(String id) {
        return switch (id.toLowerCase(Locale.ROOT)) {
            case "kill" -> "&7Effects displayed when you defeat a player.";
            case "death" -> "&7Effects displayed when you die.";
            case "trail" -> "&7Movement particle effects.";
            case "projectile" -> "&7Particle effects for projectiles.";
            case "kill_message" -> "&7Custom kill-message styles.";
            case "join" -> "&7Custom server arrival messages.";
            case "quit" -> "&7Custom server departure messages.";
            default -> "&7Custom cosmetic options.";
        };
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

    private String plainName(String value) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(TagTextFormat.deserializeCompat(value));
    }

    private NamespacedKey key(String value) { return new NamespacedKey(plugin, value); }
}
