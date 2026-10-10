package org.enthusia.tags.rewards;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import org.bukkit.Server;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.enthusia.tags.EnthusiaTagsPlugin;
import org.enthusia.tags.TagService;
import org.enthusia.tags.Messages;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;

class KothRewardsNavigationTest {
    @Test void portalQueuesNavigationWithoutInvokingTagsClaimsAndIgnoresBottomCopies() {
        RewardService service=mock(RewardService.class); RewardMenu menu=mock(RewardMenu.class);
        RewardListener listener=new RewardListener(service,menu); Player player=mock(Player.class);
        RewardMenuHolder holder=new RewardMenuHolder(service); Inventory top=mock(Inventory.class);
        InventoryView view=mock(InventoryView.class); InventoryClickEvent event=mock(InventoryClickEvent.class);
        ItemStack item=mock(ItemStack.class); ItemMeta meta=mock(ItemMeta.class); PersistentDataContainer data=mock(PersistentDataContainer.class);
        NamespacedKey key=new NamespacedKey("enthusiatags","reward_koth_menu");
        when(menu.getKothKey()).thenReturn(key); when(top.getHolder()).thenReturn(holder);
        when(view.getTopInventory()).thenReturn(top); when(event.getView()).thenReturn(view);
        when(event.getWhoClicked()).thenReturn(player); when(event.getClickedInventory()).thenReturn(top);
        when(event.getCurrentItem()).thenReturn(item); when(item.hasItemMeta()).thenReturn(true);
        when(item.getItemMeta()).thenReturn(meta); when(meta.getPersistentDataContainer()).thenReturn(data);
        when(data.has(key,PersistentDataType.BYTE)).thenReturn(true);
        listener.onInventoryClick(event); verify(menu).queueKoth(player,holder); verifyNoInteractions(service);
        clearInvocations(menu); when(event.getClickedInventory()).thenReturn(mock(Inventory.class));
        listener.onInventoryClick(event); verifyNoInteractions(menu); verify(event,times(2)).setCancelled(true);
    }
    @Test void rewardMenusCancelDragging() {
        RewardService service=mock(RewardService.class); RewardListener listener=new RewardListener(service,mock(RewardMenu.class));
        Inventory top=mock(Inventory.class); InventoryView view=mock(InventoryView.class); InventoryDragEvent event=mock(InventoryDragEvent.class);
        when(top.getHolder()).thenReturn(new RewardMenuHolder(service)); when(view.getTopInventory()).thenReturn(top); when(event.getView()).thenReturn(view);
        listener.onInventoryDrag(event); verify(event).setCancelled(true); verifyNoInteractions(service);
    }
    @Test void queuedPortalRechecksPermissionAndCurrentMenu() {
        EnthusiaTagsPlugin plugin=mock(EnthusiaTagsPlugin.class); TagService tags=mock(TagService.class);
        Server server=mock(Server.class); BukkitScheduler scheduler=mock(BukkitScheduler.class); Player player=mock(Player.class);
        when(plugin.getName()).thenReturn("EnthusiaTags"); when(plugin.namespace()).thenReturn("enthusiatags"); when(plugin.getServer()).thenReturn(server); when(plugin.isEnabled()).thenReturn(true);
        when(tags.getPlugin()).thenReturn(plugin); when(server.getScheduler()).thenReturn(scheduler);
        when(player.isOnline()).thenReturn(true); when(player.hasPermission("enthusia.tags.rewards")).thenReturn(true);
        RewardMenu menu=new RewardMenu(mock(RewardService.class),tags); RewardMenuHolder holder=new RewardMenuHolder(mock(RewardService.class));
        Inventory top=mock(Inventory.class); InventoryView view=mock(InventoryView.class);
        when(player.getOpenInventory()).thenReturn(view); when(view.getTopInventory()).thenReturn(top); when(top.getHolder()).thenReturn(holder);
        ArgumentCaptor<Runnable> task=ArgumentCaptor.forClass(Runnable.class);
        menu.queueKoth(player,holder); verify(scheduler).runTask(eq(plugin),task.capture());
        when(player.hasPermission("enthusia.tags.rewards")).thenReturn(false); task.getValue().run();
        verify(server,never()).getPluginManager();
        when(player.hasPermission("enthusia.tags.rewards")).thenReturn(true); when(top.getHolder()).thenReturn(null);
        task.getValue().run(); verify(server,never()).getPluginManager();
    }
    @Test void kothShortcutRemainsIndependentOfTagsClaimServiceAvailability() {
        EnthusiaTagsPlugin plugin=mock(EnthusiaTagsPlugin.class); TagService tags=mock(TagService.class);
        Server server=mock(Server.class); PluginManager plugins=mock(PluginManager.class); Player player=mock(Player.class);
        when(plugin.getName()).thenReturn("EnthusiaTags"); when(plugin.namespace()).thenReturn("enthusiatags"); when(plugin.getServer()).thenReturn(server); when(tags.getPlugin()).thenReturn(plugin);
        when(server.getPluginManager()).thenReturn(plugins); when(player.isOnline()).thenReturn(true);
        RewardService service=mock(RewardService.class);
        RewardsCommand command=new RewardsCommand(service,tags,mock(Messages.class),plugin);
        command.onCommand(player,null,"rewards",new String[]{"koth","challenges"});
        verifyNoInteractions(service); verify(player).sendMessage(org.mockito.ArgumentMatchers.any(net.kyori.adventure.text.Component.class));
        verify(plugins).getPlugin("EnthusiaKOTH");
    }
}
