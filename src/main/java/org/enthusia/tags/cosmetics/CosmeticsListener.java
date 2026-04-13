package org.enthusia.tags.cosmetics;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.enthusia.tags.TagMenu;
import org.enthusia.tags.TagService;

public final class CosmeticsListener implements Listener {
    private final CosmeticsService cosmeticsService;
    private final CosmeticsMenu cosmeticsMenu;
    private final TagMenu tagMenu;

    public CosmeticsListener(CosmeticsService cosmeticsService, TagService tagService, org.enthusia.tags.Messages messages) {
        this.cosmeticsService = cosmeticsService;
        this.cosmeticsMenu = new CosmeticsMenu(cosmeticsService, tagService, messages);
        this.tagMenu = new TagMenu(tagService);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        cosmeticsService.loadPlayer(event.getPlayer());
        String message = cosmeticsService.getJoinMessage(event.getPlayer());
        if (message != null && !message.isBlank()) {
            event.joinMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(message));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        String message = cosmeticsService.getQuitMessage(event.getPlayer());
        if (message != null && !message.isBlank()) {
            event.quitMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(message));
        }
        cosmeticsService.unloadPlayer(event.getPlayer());
    }

    @EventHandler
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

    @EventHandler
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        Projectile projectile = event.getEntity();
        if (!(projectile.getShooter() instanceof Player player)) {
            return;
        }
        if (projectile.getType() == org.bukkit.entity.EntityType.WIND_CHARGE) {
            return;
        }
        String selected = cosmeticsService.getSelection(player.getUniqueId(), "projectile");
        if (selected == null) {
            return;
        }
        CosmeticDefinition cosmetic = cosmeticsService.getCosmetics().get(selected.toLowerCase());
        if (cosmetic == null || cosmetic.getType() != CosmeticType.PROJECTILE_TRAIL) {
            return;
        }
        if (!player.hasPermission(cosmetic.getPermission())) {
            return;
        }
        cosmeticsService.registerProjectile(projectile, cosmetic);
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        cosmeticsService.unregisterProjectile(event.getEntity());
        if (event.getHitEntity() instanceof Player && event.getEntity().getShooter() instanceof Player shooter) {
            org.enthusia.tags.rewards.RewardService rewardService =
                ((org.enthusia.tags.EnthusiaTagsPlugin) org.bukkit.Bukkit.getPluginManager()
                    .getPlugin("EnthusiaTags")).getRewardService();
            if (rewardService != null) {
                try {
                    rewardService.getStorage().incrementCounter(shooter.getUniqueId(), "projectile_hits", 1);
                } catch (Exception ignored) {
                }
            }
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
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
        CosmeticDefinition cosmetic = cosmeticsService.getCosmetics().get(cosmeticId.toLowerCase());
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
