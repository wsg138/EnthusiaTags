package org.enthusia.tags.rewards;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public final class RewardListener implements Listener {
    private final RewardService rewardService;
    private final RewardMenu rewardMenu;

    public RewardListener(RewardService rewardService, RewardMenu rewardMenu) {
        this.rewardService = rewardService;
        this.rewardMenu = rewardMenu;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (!(holder instanceof RewardMenuHolder rewardHolder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) {
            return;
        }
        ItemMeta meta = clicked.getItemMeta();
        PersistentDataContainer data = meta.getPersistentDataContainer();
        if (data.has(rewardMenu.getBackKey(), PersistentDataType.BYTE)) {
            player.openInventory(rewardMenu.create(player));
            return;
        }
        if (data.has(rewardMenu.getNextKey(), PersistentDataType.BYTE)) {
            if (rewardHolder.getCategory() == null) {
                return;
            }
            int next = rewardHolder.getPage() + 1;
            player.openInventory(rewardMenu.createCategory(player, rewardHolder.getCategory(), next));
            return;
        }
        if (data.has(rewardMenu.getPrevKey(), PersistentDataType.BYTE)) {
            if (rewardHolder.getCategory() == null) {
                return;
            }
            int prev = Math.max(0, rewardHolder.getPage() - 1);
            player.openInventory(rewardMenu.createCategory(player, rewardHolder.getCategory(), prev));
            return;
        }
        String categoryId = data.get(rewardMenu.getCategoryKey(), PersistentDataType.STRING);
        if (categoryId != null && !categoryId.isBlank()) {
            player.openInventory(rewardMenu.createCategory(player, categoryId));
            return;
        }
        String rewardId = data.get(rewardMenu.getRewardKey(), PersistentDataType.STRING);
        if (rewardId == null) {
            return;
        }
        RewardDefinition reward = rewardService.getRewards().get(rewardId.toLowerCase());
        if (reward == null) {
            return;
        }
        RewardClaimResult result = rewardService.claim(player, reward);
        switch (result) {
            case SUCCESS -> {
                player.sendMessage(LegacyComponentSerializer.legacyAmpersand()
                    .deserialize(rewardService.getMessage("rewards-claimed")));
                player.openInventory(rewardMenu.create(player));
            }
            case LOADING -> player.sendMessage(LegacyComponentSerializer.legacyAmpersand()
                .deserialize(rewardService.getMessage("rewards-loading")));
            case ALREADY_CLAIMED -> player.sendMessage(LegacyComponentSerializer.legacyAmpersand()
                .deserialize(rewardService.getMessage("rewards-already-claimed")));
            case NOT_READY -> player.sendMessage(LegacyComponentSerializer.legacyAmpersand()
                .deserialize(rewardService.getMessage("rewards-not-ready")));
            case IP_ALREADY_CLAIMED -> player.sendMessage(LegacyComponentSerializer.legacyAmpersand()
                .deserialize(rewardService.getMessage("rewards-ip-already-claimed")));
        }
    }
}
