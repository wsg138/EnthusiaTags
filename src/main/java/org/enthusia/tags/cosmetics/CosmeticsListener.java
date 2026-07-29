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
    private final CosmeticsMenu cosmeticsMenu;
    private final TagMenu tagMenu;
    private final RewardService rewardService;

    public CosmeticsListener(CosmeticsService cosmeticsService,
                             TagService tagService,
                             org.enthusia.tags.Messages messages,
                             RewardService rewardService) {
        this.cosmeticsService = cosmeticsService;
        this.cosmeticsMenu = new CosmeticsMenu(cosmeticsService, tagService, messages);
        this.tagMenu = new TagMenu(tagService);
        this.rewardService = rewardService;
    }

    @EventHandler
    public void onAsyncPreLogin(AsyncPlayerPreLoginEvent event) {
        cosmeticsService.preloadPlayerBlocking(event.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        cosmeticsService.loadPlayer(event.getPlayer());
        String message = cosmeticsService.getJoinMessage(event.getPlayer());
        if (message != null && !message.isBlank()) {
            event.joinMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(message));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent event) {
        String message = cosmeticsService.getQuitMessage(event.getPlayer());
        if (message != null && !message.isBlank()) {
            event.quitMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(message));
        }
        cosmeticsService.unloadPlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        cosmeticsService.applyDeathEffect(victim);
        Player killer = victim.getKiller();
        if (killer != null) {
            cosmeticsService.applyKillEffect(killer, victim);
            String message = cosmeticsService.getKillMessage(killer, victim);
            if (message != null && !message.isBlank()) {
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
    public void onInventoryClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (!(holder instanceof CosmeticsMenuHolder)) {
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
        if (data.has(cosmeticsMenu.getBackKey(), PersistentDataType.BYTE)) {
            player.openInventory(cosmeticsMenu.createMain(player));
            return;
        }
        if (data.has(cosmeticsMenu.getTagsKey(), PersistentDataType.BYTE)) {
            player.openInventory(tagMenu.create(player));
            return;
        }
        String categoryId = data.get(cosmeticsMenu.getCategoryKey(), PersistentDataType.STRING);
        if (categoryId != null) {
            player.openInventory(cosmeticsMenu.createCategory(player, categoryId));
            return;
        }
        String cosmeticId = data.get(cosmeticsMenu.getCosmeticKey(), PersistentDataType.STRING);
        if (cosmeticId == null) {
            return;
        }
        CosmeticDefinition cosmetic = cosmeticsService.getCosmetics().get(cosmeticId.toLowerCase(Locale.ROOT));
        if (cosmetic == null) {
            return;
        }
        boolean ok = cosmeticsService.toggleCosmetic(player, cosmetic);
        if (!ok) {
            player.sendMessage(LegacyComponentSerializer.legacyAmpersand()
                .deserialize(cosmeticsService.formatMessage("cosmetics-locked-msg")));
            return;
        }
        player.openInventory(cosmeticsMenu.createCategory(player, cosmetic.getCategory()));
    }
}
