package org.enthusia.tags;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitScheduler;
import org.enthusia.tags.cosmetics.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ProductionCollectionPreviewTest {
    @Test void tagPreviewClickNeverSelectsOrClears() throws Exception { previewClick(false); }
    @Test void cosmeticPreviewClickNeverEquips() throws Exception { previewClick(true); }
    private void previewClick(boolean cosmetic) throws Exception {
        var plugin=mock(EnthusiaTagsPlugin.class);
        var tags=mock(TagService.class); when(tags.getPlugin()).thenReturn(plugin);
        var messages=mock(Messages.class); when(messages.get(anyString())).thenReturn("Preview is read-only"); when(tags.getMessages()).thenReturn(messages);
        var cosmetics=mock(CosmeticsService.class); when(cosmetics.formatMessage(anyString())).thenReturn("Preview is read-only");
        var player=mock(Player.class);when(player.getUniqueId()).thenReturn(UUID.randomUUID());when(player.isOnline()).thenReturn(true);when(player.hasPermission(anyString())).thenReturn(true);
        var top=mock(Inventory.class);when(top.getSize()).thenReturn(54);
        var view=mock(InventoryView.class);when(view.getTopInventory()).thenReturn(top);when(player.getOpenInventory()).thenReturn(view);
        var event=mock(InventoryClickEvent.class);when(event.getView()).thenReturn(view);when(event.getWhoClicked()).thenReturn(player);when(event.getClickedInventory()).thenReturn(top);when(event.getRawSlot()).thenReturn(19);when(event.getClick()).thenReturn(ClickType.LEFT);
        var item=mock(ItemStack.class);var meta=mock(ItemMeta.class);var data=mock(PersistentDataContainer.class);
        when(top.getItem(19)).thenReturn(item);when(item.hasItemMeta()).thenReturn(true);when(item.getItemMeta()).thenReturn(meta);when(meta.getPersistentDataContainer()).thenReturn(data);
        var scheduler=mock(BukkitScheduler.class);doAnswer(c->{((Runnable)c.getArgument(1)).run();return null;}).when(scheduler).runTask(eq(plugin),any(Runnable.class));
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            if(cosmetic) {
                when(top.getHolder()).thenReturn(new CosmeticsMenuHolder(cosmetics,"trail",0,true));
                var listener=mock(CosmeticsListener.class,CALLS_REAL_METHODS);
                var menu=mock(CosmeticsMenu.class);var key=new org.bukkit.NamespacedKey("enthusiatags","cosmetic_id");when(menu.getCosmeticKey()).thenReturn(key);when(data.get(eq(key),eq(PersistentDataType.STRING))).thenReturn("example");
                field(listener,"tagService",tags);field(listener,"cosmeticsService",cosmetics);field(listener,"cosmeticsMenu",menu);
                listener.onInventoryClick(event);
            } else {
                when(top.getHolder()).thenReturn(new TagMenuHolder(tags,"all",0,true));
                var listener=mock(TagListener.class,CALLS_REAL_METHODS);
                var menu=mock(TagMenu.class);var key=new org.bukkit.NamespacedKey("enthusiatags","tag_id");when(menu.getTagIdKey()).thenReturn(key);when(data.get(eq(key),eq(PersistentDataType.STRING))).thenReturn("example");
                field(listener,"tagService",tags);field(listener,"tagMenu",menu);
                listener.onInventoryClick(event);
            }
        }
        verify(event).setCancelled(true);
        verify(tags,never()).setSelectedTag(any(),any());
        verify(cosmetics,never()).toggleCosmetic(any(),any());
        verify(player).sendMessage(any(net.kyori.adventure.text.Component.class));
    }
    private static void field(Object object,String name,Object value) throws Exception {
        var field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);
    }
}
