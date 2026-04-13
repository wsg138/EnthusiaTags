package org.enthusia.tags;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class TagMenuHolder implements InventoryHolder {
    private final TagService tagService;
    private Inventory inventory;

    public TagMenuHolder(TagService tagService) {
        this.tagService = tagService;
    }

    public TagService getTagService() {
        return tagService;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
