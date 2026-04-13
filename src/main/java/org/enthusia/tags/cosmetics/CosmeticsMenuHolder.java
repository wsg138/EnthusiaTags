package org.enthusia.tags.cosmetics;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class CosmeticsMenuHolder implements InventoryHolder {
    private final CosmeticsService cosmeticsService;
    private final String category;
    private Inventory inventory;

    public CosmeticsMenuHolder(CosmeticsService cosmeticsService, String category) {
        this.cosmeticsService = cosmeticsService;
        this.category = category;
    }

    public CosmeticsService getCosmeticsService() {
        return cosmeticsService;
    }

    public String getCategory() {
        return category;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
