package org.enthusia.tags.cosmetics;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.enthusia.tags.TagMenu;
import org.enthusia.tags.TagService;
import org.enthusia.tags.rewards.RewardService;

import java.util.Locale;

public final class CosmeticsListener implements Listener {
    private final CosmeticsService cosmeticsService;
    private final TagService tagService;
    private final CosmeticsMenu cosmeticsMenu;
    private final TagMenu tagMenu;
    private final RewardService rewardService;
    private final java.util.function.BooleanSupplier roseChatOwnsPresence;

    public CosmeticsListener(CosmeticsService cosmeticsService,
                             TagService tagService,
                             org.enthusia.tags.Messages messages,
                             RewardService rewardService) {
        this.cosmeticsService = cosmeticsService;
        this.tagService = tagService;
        this.cosmeticsMenu = new CosmeticsMenu(cosmeticsService, tagService, messages);
        this.tagMenu = new TagMenu(tagService);
        this.rewardService = rewardService;
        this.roseChatOwnsPresence = () -> org.bukkit.Bukkit.getPluginManager().isPluginEnabled("RoseChat");
    }

    @EventHandler
    public void onAsyncPreLogin(AsyncPlayerPreLoginEvent event) {
        cosmeticsService.preloadPlayerBlocking(event.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoinLoad(PlayerJoinEvent event) {
        cosmeticsService.loadPlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        if (roseChatOwnsPresence != null && roseChatOwnsPresence.getAsBoolean())
            return;
        if (event.joinMessage() == null)
            return;
        String message = cosmeticsService.getJoinMessage(event.getPlayer());
        if (message != null && !message.isBlank()) {
            event.joinMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(message));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent event) {
        if (roseChatOwnsPresence != null && roseChatOwnsPresence.getAsBoolean())
            return;
        if (event.quitMessage() == null)
            return;
        String message = cosmeticsService.getQuitMessage(event.getPlayer());
        if (message != null && !message.isBlank()) {
            event.quitMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(message));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuitCleanup(PlayerQuitEvent event) {
        cosmeticsService.unloadPlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        cosmeticsService.applyDeathEffect(victim);
        Player killer = victim.getKiller();
        if (killer != null) {
            cosmeticsService.applyKillEffect(killer, victim);
            String message = cosmeticsService.getKillMessage(killer, victim);
            if (event.deathMessage() != null && message != null && !message.isBlank()) {
                event.deathMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(message));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        Projectile projectile = event.getEntity();
        if (!(projectile.getShooter() instanceof Player player)) {
            return;
        }
        if (projectile.getType() == org.bukkit.entity.EntityType.WIND_CHARGE) {
            return;
        }
        String selected = cosmeticsService.getActiveSelection(player.getUniqueId(), "projectile", player);
        if (selected == null) {
            return;
        }
        CosmeticDefinition cosmetic = cosmeticsService.getCosmetics().get(selected.toLowerCase(Locale.ROOT));
        if (cosmetic == null || cosmetic.getType() != CosmeticType.PROJECTILE_TRAIL) {
            return;
        }
        cosmeticsService.registerProjectile(projectile, cosmetic);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onProjectileHit(ProjectileHitEvent event) {
        cosmeticsService.unregisterProjectile(event.getEntity());
    }

    @EventHandler
    public void onInventoryDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if(event.getView().getTopInventory().getHolder() instanceof CosmeticsMenuHolder) event.setCancelled(true);
    }
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        org.bukkit.inventory.Inventory top = event.getView().getTopInventory();
        InventoryHolder rawHolder = top.getHolder();
        if (!(rawHolder instanceof CosmeticsMenuHolder holder)
            || holder.getCosmeticsService() != cosmeticsService) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !player.hasPermission("enthusia.cosmetics.use")) return;
        if (event.getClick()!=org.bukkit.event.inventory.ClickType.LEFT) return;
        if (event.getClickedInventory() != top || event.getRawSlot() < 0 || event.getRawSlot() >= top.getSize()) return;

        ItemStack clicked = top.getItem(event.getRawSlot());
        if (clicked == null || !clicked.hasItemMeta()) return;
        ItemMeta meta = clicked.getItemMeta();
        PersistentDataContainer data = meta.getPersistentDataContainer();

        org.bukkit.Bukkit.getScheduler().runTask(tagService.getPlugin(), () -> {
            if(!player.isOnline() || !player.hasPermission("enthusia.cosmetics.use") || player.getOpenInventory().getTopInventory()!=top) return;
            if (data.has(cosmeticsMenu.getCloseKey(), PersistentDataType.BYTE)) {
                player.closeInventory();
                return;
            }
            if (data.has(cosmeticsMenu.getRewardsKey(), PersistentDataType.BYTE)) {
                if (!rewardService.isAvailable()) {
                    player.sendMessage(LegacyComponentSerializer.legacyAmpersand()
                        .deserialize(cosmeticsService.formatMessage("rewards-service-unavailable")));
                    return;
                }
                player.performCommand("rewards");
                return;
            }
            if (data.has(cosmeticsMenu.getTagsKey(), PersistentDataType.BYTE)) {
                if(player.hasPermission("enthusia.tags.use")) player.openInventory(tagMenu.create(player, holder.isPreview()));
                return;
            }
            if (data.has(cosmeticsMenu.getPreviewKey(), PersistentDataType.BYTE)) {
                if (!player.hasPermission("enthusia.tags.admin")) {
                    player.sendMessage(LegacyComponentSerializer.legacyAmpersand()
                        .deserialize(cosmeticsService.formatMessage("no-permission")));
                    return;
                }
                if (holder.getCategory() == null) {
                    player.openInventory(cosmeticsMenu.createMain(player, !holder.isPreview()));
                } else {
                    player.openInventory(cosmeticsMenu.createCategory(
                        player, holder.getCategory(), holder.getPage(), !holder.isPreview()));
                }
                return;
            }
            if (data.has(cosmeticsMenu.getBackKey(), PersistentDataType.BYTE)) {
                player.openInventory(cosmeticsMenu.createMain(player, holder.isPreview()));
                return;
            }
            if (data.has(cosmeticsMenu.getPrevKey(), PersistentDataType.BYTE)) {
                player.openInventory(holder.getCategory()==null ? cosmeticsMenu.createMain(player,holder.getPage()-1,holder.isPreview()) : cosmeticsMenu.createCategory(player,holder.getCategory(),holder.getPage()-1,holder.isPreview()));
                return;
            }
            if (data.has(cosmeticsMenu.getNextKey(), PersistentDataType.BYTE)) {
                player.openInventory(holder.getCategory()==null ? cosmeticsMenu.createMain(player,holder.getPage()+1,holder.isPreview()) : cosmeticsMenu.createCategory(player,holder.getCategory(),holder.getPage()+1,holder.isPreview()));
                return;
            }

            String categoryId = data.get(cosmeticsMenu.getCategoryKey(), PersistentDataType.STRING);
            if (categoryId != null) {
                player.openInventory(cosmeticsMenu.createCategory(player, categoryId, 0, holder.isPreview()));
                return;
            }

            String cosmeticId = data.get(cosmeticsMenu.getCosmeticKey(), PersistentDataType.STRING);
            if (cosmeticId == null) return;
            if (holder.isPreview()) {
                player.sendMessage(LegacyComponentSerializer.legacyAmpersand()
                    .deserialize(cosmeticsService.formatMessage("admin-preview-readonly")));
                return;
            }

            CosmeticDefinition cosmetic = cosmeticsService.getCosmetics().get(cosmeticId.toLowerCase(Locale.ROOT));
            if (cosmetic == null) return;
            boolean ok = cosmeticsService.toggleCosmetic(player, cosmetic);
            if (!ok) {
                player.sendMessage(LegacyComponentSerializer.legacyAmpersand()
                    .deserialize(cosmeticsService.formatMessage("cosmetics-locked-msg")));
                return;
            }
            player.openInventory(cosmeticsMenu.createCategory(
                player, cosmetic.getCategory(), holder.getPage(), false));
        });
    }
}
