package org.enthusia.tags;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class TagMenuHolder implements InventoryHolder {
    private final TagService tagService;
    private final String filter;
    private final int page;
    private final boolean preview;
    private Inventory inventory;

    public TagMenuHolder(TagService service) { this(service,"all",0,false); }
    public TagMenuHolder(TagService tagService, String filter, int page, boolean preview) {
        this.tagService = tagService;
        this.filter = filter == null ? "all" : filter;
        this.page = Math.max(0, page);
        this.preview = preview;
    }

    public TagService getTagService() { return tagService; }
    public String getFilter() { return filter; }
    public int getPage() { return page; }
    public boolean isPreview() { return preview; }
    public void setInventory(Inventory inventory) { this.inventory = inventory; }

    @Override
    public Inventory getInventory() { return inventory; }
}
