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

    public Inventory create(Player player) {
        TagMenuHolder holder = new TagMenuHolder(tagService);
        Component title = LegacyComponentSerializer.legacyAmpersand().deserialize(tagService.getGuiTitle());
        Inventory inventory = Bukkit.createInventory(holder, 54, title);
        holder.setInventory(inventory);

        PlayerTagData data = tagService.getPlayerData(player.getUniqueId());
        Set<String> owned = data.getOwnedTags();
        if (owned.isEmpty()) {
            inventory.setItem(22, createNoTagsItem());
            inventory.setItem(45, createRewardsItem());
            return inventory;
        }

        int slot = 0;
        for (String tagId : owned) {
            TagDefinition definition = tagService.getRegistry().get(tagId);
            if (definition == null) {
                continue;
            }
            inventory.setItem(slot++, createTagItem(definition));
            if (slot >= 53) {
                break;
            }
        }

        inventory.setItem(45, createRewardsItem());
        inventory.setItem(53, createClearItem());
        return inventory;
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

    private ItemStack createTagItem(TagDefinition tag) {
        ItemStack stack = new ItemStack(tag.getIcon());
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize(tag.getDisplayName()));
        if (!tag.getDescription().isEmpty()) {
            List<Component> lore = new ArrayList<>();
            for (String line : tag.getDescription()) {
                lore.add(LegacyComponentSerializer.legacyAmpersand().deserialize(line));
            }
            meta.lore(lore);
        }
        meta.getPersistentDataContainer().set(tagIdKey, PersistentDataType.STRING, tag.getId().toLowerCase());
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack createClearItem() {
        ItemStack stack = new ItemStack(Material.BARRIER);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize(tagService.getClearItemName()));
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
        meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize(tagService.getNoTagsItemName()));
        stack.setItemMeta(meta);
        return stack;
    }
}
