package org.enthusia.tags;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public final class TagListener implements Listener {
    private final TagService tagService;
    private final org.enthusia.tags.rewards.RewardService rewardService;
    private final TagMenu tagMenu;
    private final org.enthusia.tags.rewards.RewardMenu rewardMenu;

    public TagListener(TagService tagService, org.enthusia.tags.rewards.RewardService rewardService) {
        this.tagService = tagService;
        this.rewardService = rewardService;
        this.tagMenu = new TagMenu(tagService);
        this.rewardMenu = new org.enthusia.tags.rewards.RewardMenu(rewardService, tagService);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        tagService.loadPlayer(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        tagService.unloadPlayer(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(tagService.getPlugin(), () -> tagService.updateDisplay(player));
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(tagService.getPlugin(), () -> tagService.updateDisplay(player));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTeleportStart(PlayerTeleportEvent event) {
        if (event.isCancelled()) {
            return;
        }
        tagService.removeDisplay(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeleportEnd(PlayerTeleportEvent event) {
        if (event.isCancelled()) {
            Bukkit.getScheduler().runTask(tagService.getPlugin(),
                () -> tagService.updateDisplay(event.getPlayer()));
            return;
        }
        Bukkit.getScheduler().runTask(tagService.getPlugin(),
            () -> tagService.updateDisplay(event.getPlayer()));
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof TagMenuHolder)) {
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

        if (data.has(tagMenu.getRewardsKey(), PersistentDataType.BYTE)) {
            player.openInventory(rewardMenu.create(player));
            return;
        }

        if (data.has(tagMenu.getClearKey(), PersistentDataType.BYTE)) {
            tagService.setSelectedTag(player, null);
            player.closeInventory();
            player.sendMessage(message("tag-cleared-self"));
            return;
        }

        String tagId = data.get(tagMenu.getTagIdKey(), PersistentDataType.STRING);
        if (tagId == null) {
            return;
        }
        boolean updated = tagService.setSelectedTag(player, tagId);
        if (!updated) {
            player.sendMessage(message("tag-not-owned-self"));
        } else {
            player.sendMessage(message("tag-selected-self"));
        }
        player.closeInventory();
    }

    private Component message(String key) {
        String raw = tagService.getMessages().get(key);
        return LegacyComponentSerializer.legacyAmpersand().deserialize(raw);
    }
}
