package org.enthusia.tags.cosmetics;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class CosmeticsMenuHolder implements InventoryHolder {
    private final CosmeticsService cosmeticsService;
    private final String category;
    private final int page;
    private final boolean preview;
    private Inventory inventory;

    public CosmeticsMenuHolder(CosmeticsService service,String category) { this(service,category,0,false); }
    public CosmeticsMenuHolder(CosmeticsService cosmeticsService, String category, int page, boolean preview) {
        this.cosmeticsService = cosmeticsService;
        this.category = category;
        this.page = Math.max(0, page);
        this.preview = preview;
    }

    public CosmeticsService getCosmeticsService() { return cosmeticsService; }
    public String getCategory() { return category; }
    public int getPage() { return page; }
    public boolean isPreview() { return preview; }
    public void setInventory(Inventory inventory) { this.inventory = inventory; }

    @Override
    public Inventory getInventory() { return inventory; }
}
