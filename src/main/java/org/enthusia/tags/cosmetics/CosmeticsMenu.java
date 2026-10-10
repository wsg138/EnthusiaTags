package org.enthusia.tags.cosmetics;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.enthusia.tags.Messages;
import org.enthusia.tags.CollectionMenuLayout;
import org.enthusia.tags.TagService;

import java.util.ArrayList;
import java.util.List;

public final class CosmeticsMenu {
    private static final int INVENTORY_SIZE = 54;
    private static final int NAVIGATION_SLOT = INVENTORY_SIZE - 1;

    private final CosmeticsService cosmeticsService;
    private final TagService tagService;
    private final Messages messages;
    private final NamespacedKey cosmeticKey;
    private final NamespacedKey categoryKey;
    private final NamespacedKey backKey;
    private final NamespacedKey tagsKey;

    public CosmeticsMenu(CosmeticsService cosmeticsService, TagService tagService, Messages messages) {
        this.cosmeticsService = cosmeticsService;
        this.tagService = tagService;
        this.messages = messages;
        this.cosmeticKey = new NamespacedKey(tagService.getPlugin(), "cosmetic_id");
        this.categoryKey = new NamespacedKey(tagService.getPlugin(), "cosmetic_category");
        this.backKey = new NamespacedKey(tagService.getPlugin(), "cosmetics_back");
        this.tagsKey = new NamespacedKey(tagService.getPlugin(), "cosmetics_tags");
    }

    public Inventory createMain(Player player) { return createMain(player,0); }
    public Inventory createMain(Player player,int requestedPage) {
        var choices=new java.util.ArrayList<>(cosmeticsService.getCategories().values());
        var holder=new CosmeticsMenuHolder(cosmeticsService,null);
        var inventory=Bukkit.createInventory(holder,54,LegacyComponentSerializer.legacyAmpersand().deserialize(messages.get("cosmetics-gui-title")));holder.setInventory(inventory);
        int page=CollectionMenuLayout.page(requestedPage,choices.size());holder.setPage(page);
        CollectionMenuLayout.frame(inventory,"Cosmetics",page,choices.size());
        for(int i=page*21;i<Math.min(choices.size(),(page+1)*21);i++) inventory.setItem(CollectionMenuLayout.SLOTS.get(i-page*21),createCategoryItem(choices.get(i)));
        navigation(inventory,page,choices.size());return inventory;
    }
    public Inventory createCategory(Player player,String categoryId) { return createCategory(player,categoryId,0); }
    public Inventory createCategory(Player player,String categoryId,int requestedPage) {
        var choices=categoryChoices(cosmeticsService.getCosmetics().values(),categoryId);
        var category=cosmeticsService.getCategories().get(categoryId);
        String title=category==null ? messages.get("cosmetics-gui-title") : messages.get("cosmetics-category-title").replace("{category}",category.name());
        var holder=new CosmeticsMenuHolder(cosmeticsService,categoryId);
        var inventory=Bukkit.createInventory(holder,54,LegacyComponentSerializer.legacyAmpersand().deserialize(title));holder.setInventory(inventory);
        int page=CollectionMenuLayout.page(requestedPage,choices.size());holder.setPage(page);
        CollectionMenuLayout.frame(inventory,title,page,choices.size());
        for(int i=page*21;i<Math.min(choices.size(),(page+1)*21);i++) inventory.setItem(CollectionMenuLayout.SLOTS.get(i-page*21),createCosmeticItem(player,choices.get(i)));
        navigation(inventory,page,choices.size());inventory.setItem(45,createBackItem());return inventory;
    }
    private void navigation(Inventory inventory,int page,int count) {
        inventory.setItem(46,createTagsItem());
        CollectionMenuLayout.button(inventory,tagService.getPlugin(),52,org.bukkit.Material.CHEST,"&6Rewards","rewards");
        CollectionMenuLayout.footer(inventory,tagService.getPlugin(),page,count);
    }

    public NamespacedKey getCosmeticKey() {
        return cosmeticKey;
    }

    static List<CosmeticDefinition> categoryChoices(java.util.Collection<CosmeticDefinition> cosmetics, String category) {
        return cosmetics.stream().filter(value -> value.getCategory().equalsIgnoreCase(category))
            .sorted(java.util.Comparator.comparing(value -> value.getType() != CosmeticType.ORIGINAL))
            .toList();
    }

    public NamespacedKey getCategoryKey() {
        return categoryKey;
    }

    public NamespacedKey getBackKey() {
        return backKey;
    }

    public NamespacedKey getTagsKey() {
        return tagsKey;
    }

    private ItemStack createCategoryItem(CosmeticsCategory category) {
        ItemStack stack = new ItemStack(category.icon() == null ? org.bukkit.Material.PAPER : category.icon());
        ItemMeta meta = stack.getItemMeta();
        String name = messages.get("cosmetics-category-title").replace("{category}", category.name());
        meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize(name));
        meta.getPersistentDataContainer().set(categoryKey, PersistentDataType.STRING, category.id());
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack createCosmeticItem(Player player, CosmeticDefinition cosmetic) {
        ItemStack stack = new ItemStack(cosmetic.getIcon());
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize(cosmetic.getName()));

        List<Component> lore = new ArrayList<>();
        boolean has = player.hasPermission(cosmetic.getPermission());
        String selected = cosmeticsService.getSelection(player.getUniqueId(), cosmetic.getCategory());
        boolean active = has && selected != null && selected.equalsIgnoreCase(cosmetic.getId());

        if (!has) {
            lore.add(LegacyComponentSerializer.legacyAmpersand().deserialize(messages.get("cosmetics-locked")));
        } else if (active) {
            lore.add(LegacyComponentSerializer.legacyAmpersand().deserialize(messages.get("cosmetics-enabled")));
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        } else {
            lore.add(LegacyComponentSerializer.legacyAmpersand().deserialize(messages.get("cosmetics-available")));
        }
        meta.lore(lore);
        meta.getPersistentDataContainer().set(cosmeticKey, PersistentDataType.STRING, cosmetic.getId());
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack createBackItem() {
        ItemStack stack = new ItemStack(org.bukkit.Material.BARRIER);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(LegacyComponentSerializer.legacyAmpersand()
            .deserialize(messages.get("cosmetics-back")));
        meta.getPersistentDataContainer().set(backKey, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack createTagsItem() {
        ItemStack stack = new ItemStack(org.bukkit.Material.NAME_TAG);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(LegacyComponentSerializer.legacyAmpersand()
            .deserialize(messages.get("cosmetics-tags-item")));
        meta.getPersistentDataContainer().set(tagsKey, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }
}
