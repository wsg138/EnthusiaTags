package org.enthusia.tags;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
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
import org.bukkit.potion.PotionEffectType;
import org.enthusia.tags.rewards.RewardMenu;
import org.enthusia.tags.rewards.RewardService;

public final class TagListener implements Listener {
    private final TagService tagService;
    private final TagMenu tagMenu;
    private final RewardMenu rewardMenu;
    private final RewardService rewardService;

    public TagListener(TagService tagService, RewardService rewardService) {
        this.tagService = tagService;
        this.tagMenu = new TagMenu(tagService);
        this.rewardMenu = new RewardMenu(rewardService, tagService);
        this.rewardService = rewardService;
    }

    @EventHandler
    public void onAsyncPreLogin(AsyncPlayerPreLoginEvent event) {
        tagService.preloadPlayerBlocking(event.getUniqueId());
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
        Bukkit.getScheduler().runTask(tagService.getPlugin(), () -> tagService.updateDisplay(event.getPlayer()));
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Bukkit.getScheduler().runTask(tagService.getPlugin(), () -> tagService.updateDisplay(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTeleportStart(PlayerTeleportEvent event) {
        tagService.removeDisplay(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeleportEnd(PlayerTeleportEvent event) {
        Bukkit.getScheduler().runTask(tagService.getPlugin(), () -> tagService.updateDisplay(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInvisibilityChange(EntityPotionEffectEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (event.getModifiedType() != PotionEffectType.INVISIBILITY) {
            return;
        }
        Bukkit.getScheduler().runTask(tagService.getPlugin(), () -> tagService.updateDisplay(player));
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
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
            if (!rewardService.isAvailable()) {
                player.sendMessage(message("rewards-service-unavailable"));
                return;
            }
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
        player.closeInventory();
        player.sendMessage(updated ? message("tag-selected-self") : message("tag-not-owned-self"));
    }

    private Component message(String key) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(tagService.getMessages().get(key));
    }
}
