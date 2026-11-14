package org.enthusia.tags;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
/** Production browser presentation shared by owned collections. */
public final class CollectionMenuLayout {
 public static final List<Integer> SLOTS=List.of(19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43);
 private CollectionMenuLayout() {}
 public static int pages(int count) { return Math.max(1,(count+20)/21); }
 public static int page(int requested,int count) { return Math.max(0,Math.min(requested,pages(count)-1)); }
 public static NamespacedKey actionKey(Plugin plugin) { return new NamespacedKey(plugin,"collection_action"); }
 public static ItemStack item(Material material,String title) {
  var item=new ItemStack(material);var meta=item.getItemMeta();meta.displayName(TagTextFormat.deserializeCompat(title));item.setItemMeta(meta);return item;
 }
 public static void button(Inventory inventory,Plugin plugin,int slot,Material material,String title,String action) {
  var item=item(material,title);var meta=item.getItemMeta();meta.getPersistentDataContainer().set(actionKey(plugin),PersistentDataType.STRING,action);item.setItemMeta(meta);inventory.setItem(slot,item);
 }
 public static void frame(Inventory inventory,String title,int page,int count) {
  for(int slot=0;slot<54;slot++) if(slot<18 || slot>=45 || slot%9==0 || slot%9==8) inventory.setItem(slot,item(slot>=9 && slot<18 ? Material.GRAY_STAINED_GLASS_PANE : Material.BLACK_STAINED_GLASS_PANE," "));
  inventory.setItem(3,item(Material.ORANGE_STAINED_GLASS_PANE," "));inventory.setItem(5,item(Material.ORANGE_STAINED_GLASS_PANE," "));
  inventory.setItem(4,item(Material.BOOK,"&6"+title));inventory.setItem(49,item(Material.PAPER,"&fPage "+(page+1)+" / "+pages(count)));
 }
 public static void footer(Inventory inventory,Plugin plugin,int page,int count) {
  if(page>0) button(inventory,plugin,47,Material.ARROW,"&fPrevious","page:"+(page-1));
  if(page+1<pages(count)) button(inventory,plugin,51,Material.ARROW,"&fNext","page:"+(page+1));
  button(inventory,plugin,53,Material.BARRIER,"&cClose","close");
 }
}
