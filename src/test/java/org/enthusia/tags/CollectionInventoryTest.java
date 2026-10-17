package org.enthusia.tags;
import java.util.*;
import org.bukkit.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.enthusia.tags.cosmetics.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class CollectionInventoryTest {
 @Test void tagsAndCosmeticCategoriesKeepAllEntriesOnFramedPages() {
  var plugin=mock(EnthusiaTagsPlugin.class);when(plugin.getName()).thenReturn("EnthusiaTags");when(plugin.namespace()).thenReturn("enthusiatags");
  var tags=mock(TagService.class);when(tags.getPlugin()).thenReturn(plugin);when(tags.getGuiTitle()).thenReturn("Tags");when(tags.getClearItemName()).thenReturn("Clear");
  var messages=mock(Messages.class);when(messages.get(anyString())).thenAnswer(c->c.getArgument(0));when(tags.getMessages()).thenReturn(messages);
  var data=new PlayerTagData();var registry=new TagRegistry();
  var categories=new LinkedHashMap<String,CosmeticsCategory>();
  for(int i=0;i<25;i++){String id=String.format("tag%02d",i);data.getOwnedTags().add(id);registry.register(new TagDefinition(id,id,id,Material.PUMPKIN,List.of()));categories.put(id,new CosmeticsCategory(id,id,Material.FEATHER));}
  when(tags.getRegistry()).thenReturn(registry);when(tags.getPlayerData(any())).thenReturn(data);
  var player=mock(org.bukkit.entity.Player.class);when(player.getUniqueId()).thenReturn(UUID.randomUUID());
  var cosmetics=mock(CosmeticsService.class);when(cosmetics.getCategories()).thenReturn(categories);
  var slots=new HashMap<Integer,ItemStack>();
  try(var bukkit=mockStatic(Bukkit.class);var items=mockConstruction(ItemStack.class,(item,context)->{
   var meta=mock(ItemMeta.class);when(meta.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));when(item.getItemMeta()).thenReturn(meta);when(item.getType()).thenReturn((Material)context.arguments().get(0));
  })) {
   bukkit.when(()->Bukkit.createInventory(any(InventoryHolder.class),eq(54),any(net.kyori.adventure.text.Component.class))).thenAnswer(c->{slots.clear();var inv=mock(Inventory.class);when(inv.getHolder()).thenReturn(c.getArgument(0));doAnswer(a->{slots.put(a.getArgument(0),a.getArgument(1));return null;}).when(inv).setItem(anyInt(),any());return inv;});
   var menu=new TagMenu(tags);menu.create(player);
   assertEquals(21,CollectionMenuLayout.SLOTS.stream().filter(i->slots.get(i)!=null).count());
   assertEquals(Material.BLACK_STAINED_GLASS_PANE,slots.get(0).getType());assertEquals(Material.ORANGE_STAINED_GLASS_PANE,slots.get(3).getType());
   assertNotNull(slots.get(10),"production All Tags filter");assertEquals(Material.NAME_TAG,slots.get(10).getType());
   assertEquals(Material.CHEST,slots.get(45).getType());assertEquals(Material.BARRIER,slots.get(51).getType());assertEquals(Material.ARROW,slots.get(52).getType());
   menu.create(player,1);assertEquals(4,CollectionMenuLayout.SLOTS.stream().filter(i->slots.get(i)!=null).count());assertEquals(Material.ARROW,slots.get(46).getType());
   when(player.hasPermission("enthusia.tags.admin")).thenReturn(true);
   var preview=menu.create(player,true);assertTrue(((TagMenuHolder)preview.getHolder()).isPreview());assertEquals(Material.SPYGLASS,slots.get(17).getType());
   when(player.hasPermission("enthusia.tags.admin")).thenReturn(false);assertFalse(((TagMenuHolder)menu.create(player,true).getHolder()).isPreview());
   var cosmeticMenu=new CosmeticsMenu(cosmetics,tags,messages);cosmeticMenu.createMain(player,1);
   assertEquals(7,CollectionMenuLayout.SLOTS.stream().filter(i->slots.get(i)!=null).count());assertEquals(Material.NAME_TAG,slots.get(47).getType());assertEquals(Material.CHEST,slots.get(45).getType());
   verify(cosmetics,never()).toggleCosmetic(any(),any());verify(tags,never()).setSelectedTag(any(),any());
  }
 }
}
