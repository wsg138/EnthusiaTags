package org.enthusia.tags.rewards;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class RewardMenuHolder implements InventoryHolder {
    private final RewardService rewardService;
    private final String category;
    private final int page;
    private Inventory inventory;

    public RewardMenuHolder(RewardService rewardService) {
        this(rewardService, null, 0);
    }

    public RewardMenuHolder(RewardService rewardService, String category, int page) {
        this.rewardService = rewardService;
        this.category = category;
        this.page = page;
    }

    public RewardService getRewardService() {
        return rewardService;
    }

    public String getCategory() {
        return category;
    }

    public int getPage() {
        return page;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
