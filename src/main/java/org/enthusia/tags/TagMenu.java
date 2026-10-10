package org.enthusia.tags;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class TagMenu {
    private final TagService tagService;
    private final NamespacedKey tagIdKey;
    private final NamespacedKey clearKey;
    private final NamespacedKey rewardsKey;

    public TagMenu(TagService tagService) {
        this.tagService = tagService;
        this.tagIdKey = new NamespacedKey(tagService.getPlugin(), "tag_id");
        this.clearKey = new NamespacedKey(tagService.getPlugin(), "clear_tag");
        this.rewardsKey = new NamespacedKey(tagService.getPlugin(), "open_rewards");
    }

    public Inventory create(Player player) { return create(player,0); }
    public Inventory create(Player player,int requestedPage) {
        TagMenuHolder holder=new TagMenuHolder(tagService);
        Inventory inventory=Bukkit.createInventory(holder,54,TagTextFormat.deserializeCompat(tagService.getGuiTitle()));holder.setInventory(inventory);
        var definitions=tagService.getPlayerData(player.getUniqueId()).getOwnedTags().stream().sorted()
            .map(id->tagService.getRegistry().get(id)).filter(java.util.Objects::nonNull).toList();
        int page=CollectionMenuLayout.page(requestedPage,definitions.size());
        CollectionMenuLayout.frame(inventory,"Tags",page,definitions.size());
        for(int i=page*21;i<Math.min(definitions.size(),(page+1)*21);i++) inventory.setItem(CollectionMenuLayout.SLOTS.get(i-page*21),createTagItem(definitions.get(i),tagService.getPlayerData(player.getUniqueId()).getSelectedTag()));
        if(definitions.isEmpty()) inventory.setItem(31,createNoTagsItem());
        inventory.setItem(45,createRewardsItem());inventory.setItem(46,createClearItem());
        CollectionMenuLayout.button(inventory,tagService.getPlugin(),52,Material.FEATHER,"&bCosmetics","cosmetics");
        CollectionMenuLayout.footer(inventory,tagService.getPlugin(),page,definitions.size());return inventory;
    }

    public NamespacedKey getTagIdKey() {
        return tagIdKey;
    }

    public NamespacedKey getClearKey() {
        return clearKey;
    }

    public NamespacedKey getRewardsKey() {
        return rewardsKey;
    }

    private ItemStack createTagItem(TagDefinition tag,String selected) {
        ItemStack stack = new ItemStack(tag.getIcon());
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(TagTextFormat.deserializeCompat(tag.getDisplayName()));
        meta.setEnchantmentGlintOverride(tag.getId().equalsIgnoreCase(selected));
        if (!tag.getDescription().isEmpty()) {
            List<Component> lore = new ArrayList<>();
            for (String line : tag.getDescription()) {
                lore.add(TagTextFormat.deserializeCompat(line));
            }
            meta.lore(lore);
        }
        meta.getPersistentDataContainer().set(tagIdKey, PersistentDataType.STRING, tag.getId().toLowerCase(Locale.ROOT));
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack createClearItem() {
        ItemStack stack = new ItemStack(Material.BARRIER);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(TagTextFormat.deserializeCompat(tagService.getClearItemName()));
        meta.getPersistentDataContainer().set(clearKey, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack createRewardsItem() {
        ItemStack stack = new ItemStack(Material.CHEST);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(LegacyComponentSerializer.legacyAmpersand()
            .deserialize(tagService.getMessages().get("tags-rewards-item")));
        meta.getPersistentDataContainer().set(rewardsKey, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack createNoTagsItem() {
        ItemStack stack = new ItemStack(Material.BARRIER);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(TagTextFormat.deserializeCompat(tagService.getNoTagsItemName()));
        stack.setItemMeta(meta);
        return stack;
    }
}
