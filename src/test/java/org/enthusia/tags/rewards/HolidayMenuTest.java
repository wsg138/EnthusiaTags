package org.enthusia.tags.rewards;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.enthusia.tags.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HolidayMenuTest {
    @Test void holidaysExtendProductionFrameAndReturnToTheirParentWithoutClaims() {
        var categories = new LinkedHashMap<String, RewardCategory>();
        for (String id : List.of("playtime","advancements","supporter","legacy","events","mining","combat","deaths"))
            categories.put(id,new RewardCategory(id,id,Material.PAPER));
        categories.put("holidays",new RewardCategory("holidays","Holidays",Material.FIREWORK_ROCKET));
        categories.put("halloween",new RewardCategory("halloween","Halloween",Material.JACK_O_LANTERN,"holidays",List.of("pumpkin_hunter")));
        categories.put("christmas",new RewardCategory("christmas","Christmas",Material.SPRUCE_SAPLING,"holidays",List.of()));
        var plugin=mock(EnthusiaTagsPlugin.class);
        when(plugin.getName()).thenReturn("EnthusiaTags"); when(plugin.namespace()).thenReturn("enthusiatags");
        when(plugin.getPerformanceMonitor()).thenReturn(new PerformanceMonitor(null));
        var tags=mock(TagService.class); when(tags.getPlugin()).thenReturn(plugin);
        var registry=new TagRegistry(); registry.register(new TagDefinition("pumpkin_hunter","<bold>Pumpkin Hunter","<bold>Pumpkin Hunter",Material.PUMPKIN,List.of("Find 15 pumpkins")));
        when(tags.getRegistry()).thenReturn(registry);
        var data=new PlayerTagData(); when(tags.getPlayerData(any())).thenReturn(data);
        var service=mock(RewardService.class); var config=mock(RewardsConfig.class);
        when(service.getConfig()).thenReturn(config); when(config.categories()).thenReturn(categories); when(service.getRewards()).thenReturn(Map.of());
        when(service.getMessage(anyString())).thenAnswer(call -> switch((String)call.getArgument(0)) {
            case "rewards-holiday-owned" -> "Earned"; case "rewards-holiday-locked" -> "Not earned"; default -> "Event reward";
        });
        var player=mock(Player.class); when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        var slots=new HashMap<Integer,ItemStack>(); var held=new RewardMenuHolder[1]; var size=new int[1];
        try(var bukkit=mockStatic(Bukkit.class); var items=mockConstruction(ItemStack.class,(item,context)->{
            var meta=mock(ItemMeta.class); when(meta.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class)); when(item.getItemMeta()).thenReturn(meta);
            if(!context.arguments().isEmpty() && context.arguments().get(0) instanceof Material material) when(item.getType()).thenReturn(material);
        })) {
            bukkit.when(()->Bukkit.createInventory(any(InventoryHolder.class),anyInt(),any(net.kyori.adventure.text.Component.class))).thenAnswer(call->{
                slots.clear(); held[0]=call.getArgument(0); size[0]=call.getArgument(1); var inventory=mock(Inventory.class);
                when(inventory.getSize()).thenReturn(size[0]); doAnswer(set->{slots.put(set.getArgument(0),set.getArgument(1));return null;}).when(inventory).setItem(anyInt(),any());
                doAnswer(clear->{slots.clear();return null;}).when(inventory).clear(); return inventory;
            });
            var menu=new RewardMenu(service,tags);
            menu.create(player); assertEquals(45,size[0]); assertEquals(Material.ORANGE_STAINED_GLASS_PANE,slots.get(0).getType());
            assertEquals(RewardMenuAction.Type.TAGS,held[0].action(37).type()); assertEquals(RewardMenuAction.Type.COSMETICS,held[0].action(43).type());
            assertEquals(RewardMenuAction.Type.READY,held[0].action(40).type()); assertEquals(RewardMenuAction.Type.CLOSE,held[0].action(44).type());
            menu.create(player,RewardMenuState.dashboard().withPage(1));
            assertTrue(java.util.stream.IntStream.range(0,45).mapToObj(held[0]::action).filter(Objects::nonNull).anyMatch(a->"holidays".equals(a.value())));
            assertFalse(java.util.stream.IntStream.range(0,45).mapToObj(held[0]::action).filter(Objects::nonNull).anyMatch(a->"halloween".equals(a.value())));
            menu.createCategory(player,"holidays"); assertEquals(54,size[0]);
            assertEquals(new RewardMenuAction(RewardMenuAction.Type.CATEGORY,"halloween"),held[0].action(19));
            assertEquals(new RewardMenuAction(RewardMenuAction.Type.CATEGORY,"christmas"),held[0].action(20));
            assertEquals(Material.BLACK_STAINED_GLASS_PANE,slots.get(0).getType());
            menu.createCategory(player,"halloween"); assertEquals(54,size[0]); assertNull(held[0].action(19));
            verify(slots.get(19).getItemMeta()).lore(argThat(lines->lines.stream().anyMatch(line->net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(line).contains("Not earned"))));
            data.getOwnedTags().add("pumpkin_hunter"); menu.createCategory(player,"halloween");
            verify(slots.get(19).getItemMeta()).lore(argThat(lines->lines.stream().anyMatch(line->net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(line).contains("Earned"))));
            assertEquals("holidays",menu.parentState("halloween").category()); assertEquals(RewardMenuState.View.DASHBOARD,menu.parentState("holidays").view());
            var server=mock(Server.class);var plugins=mock(org.bukkit.plugin.PluginManager.class);
            when(plugin.getServer()).thenReturn(server);when(server.getPluginManager()).thenReturn(plugins);
            when(plugins.getPlugin("EnthusiaKOTH")).thenReturn(mock(org.bukkit.plugin.Plugin.class));
            menu.create(player,RewardMenuState.dashboard().withPage(1));
            assertTrue(java.util.stream.IntStream.range(0,45).mapToObj(held[0]::action).filter(Objects::nonNull).anyMatch(a->a.type()==RewardMenuAction.Type.KOTH));
            var criterion=new RewardCriterion(RewardCriterionType.CUSTOM_COUNTER,1,null,"advancement_reward:reputation/a_good_word",0,"Commend");
            var reward=new RewardDefinition("adv_commend_a_good_word","A Good Word",List.of(),Material.EMERALD,List.of(criterion),List.of(),"advancements");
            when(service.getRewards()).thenReturn(Map.of(reward.getId(),reward));
            when(service.evaluate(any(),any(),any())).thenReturn(new RewardEvaluation(RewardStatus.LOCKED,Map.of(),false,false,"Requirements not reached"));
            when(service.getVerifiedMenuProgress(any(),any(),any())).thenReturn(java.util.OptionalLong.of(0));
            menu.createCategory(player,"advancements");
            assertEquals(new RewardMenuAction(RewardMenuAction.Type.CATEGORY,"advancements/commend"),held[0].action(19));
            assertFalse(java.util.stream.IntStream.range(0,54).mapToObj(held[0]::action).filter(Objects::nonNull).anyMatch(a->a.type()==RewardMenuAction.Type.CLAIM));
            menu.createCategory(player,"advancements/commend");
            assertEquals(new RewardMenuAction(RewardMenuAction.Type.CLAIM,reward.getId()),held[0].action(19));
            assertEquals("advancements",menu.parentState("advancements/commend").category());
            menu.createFocused(player,reward); assertEquals("advancements/commend",held[0].state().category());
            verify(service,never()).claimAsync(any(),any());
        }
    }
}
