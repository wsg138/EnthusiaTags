package org.enthusia.tags.rewards;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import java.util.UUID;
import java.util.ArrayList;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class RewardMenuInteractionTest {
    @Test void forgedPlayerItemCannotTriggerAClaim() {
        Fixture f = new Fixture();
        when(f.event.getClickedInventory()).thenReturn(mock(Inventory.class));
        when(f.event.getRawSlot()).thenReturn(55);
        f.listener.onInventoryClick(f.event);
        verify(f.service, never()).claimAsync(any(Player.class), any(RewardDefinition.class));
        verify(f.event).setCancelled(true);
    }
    @Test void nonOrdinaryClickCannotTriggerAClaim() {
        Fixture f = new Fixture();
        when(f.event.getClick()).thenReturn(ClickType.SHIFT_LEFT);
        f.listener.onInventoryClick(f.event);
        verify(f.service, never()).claimAsync(any(Player.class), any(RewardDefinition.class));
    }
    @Test void claimIsDeferredSingleFlightAndCannotRunAfterViewOrPermissionChanges() {
        Fixture f = new Fixture();
        f.listener.onInventoryClick(f.event);
        f.listener.onInventoryClick(f.event);
        org.junit.jupiter.api.Assertions.assertEquals(1, f.callbacks.size());
        when(f.player.hasPermission("enthusia.tags.rewards")).thenReturn(false);
        f.callbacks.removeFirst().run();
        verify(f.service, never()).claimAsync(any(Player.class), any(RewardDefinition.class));
        when(f.player.hasPermission("enthusia.tags.rewards")).thenReturn(true);
        f.listener.onInventoryClick(f.event);
        when(f.player.getOpenInventory()).thenReturn(mock(InventoryView.class));
        f.callbacks.removeFirst().run();
        verify(f.service, never()).claimAsync(any(Player.class), any(RewardDefinition.class));
    }
    @Test void legitimateClaimUsesExistingServiceAndReleasesGuardOnFailure() {
        Fixture f = new Fixture();
        f.listener.onInventoryClick(f.event);
        f.callbacks.removeFirst().run();
        verify(f.service).claimAsync(eq(f.player), any(RewardDefinition.class));
        f.claim.completeExceptionally(new IllegalStateException("Delivery interrupted"));
        verify(f.menu).endClaim(f.id, "goal");
        verify(f.player, never()).openInventory(any(Inventory.class));
    }
    @Test void staleSessionAndChangedSlotCannotRunDeferredActions() {
        Fixture f = new Fixture();
        f.listener.onInventoryClick(f.event);
        when(f.server.getPlayer(f.id)).thenReturn(mock(Player.class));
        f.callbacks.removeFirst().run();
        verify(f.service, never()).claimAsync(any(Player.class), any(RewardDefinition.class));
        when(f.server.getPlayer(f.id)).thenReturn(f.player);
        f.listener.onInventoryClick(f.event);
        f.holder.clearActions();
        f.callbacks.removeFirst().run();
        verify(f.service, never()).claimAsync(any(Player.class), any(RewardDefinition.class));
    }
    @Test void foreignViewsAndRightClickClaimsAreNotHandled() {
        Fixture f = new Fixture();
        when(f.event.getClick()).thenReturn(ClickType.RIGHT);
        f.listener.onInventoryClick(f.event);
        org.junit.jupiter.api.Assertions.assertTrue(f.callbacks.isEmpty());
        when(f.event.getClick()).thenReturn(ClickType.LEFT);
        when(f.top.getHolder()).thenReturn(new RewardMenuHolder(mock(RewardService.class)));
        f.listener.onInventoryClick(f.event);
        org.junit.jupiter.api.Assertions.assertTrue(f.callbacks.isEmpty());
        verify(f.service, never()).claimAsync(any(Player.class), any(RewardDefinition.class));
    }
    @Test void ownedDragIsCancelledAndExistingQueuedRetryIsPreserved() {
        Fixture f = new Fixture();
        var drag = mock(org.bukkit.event.inventory.InventoryDragEvent.class);
        var view = f.event.getView();
        when(drag.getView()).thenReturn(view);
        f.listener.onInventoryDrag(drag);
        verify(drag).setCancelled(true);
        var close = mock(org.bukkit.event.inventory.InventoryCloseEvent.class);
        when(close.getPlayer()).thenReturn(f.player);
        f.listener.onInventoryClose(close);
        verify(f.service).retryQueuedItems(f.player);
    }
    private static class Fixture {
        final RewardService service = mock(RewardService.class);
        final RewardMenu menu = mock(RewardMenu.class);
        final Player player = mock(Player.class);
        final Inventory top = mock(Inventory.class);
        final InventoryClickEvent event = mock(InventoryClickEvent.class);
        final UUID id = UUID.randomUUID();
        final Server server = mock(Server.class);
        final RewardMenuHolder holder = new RewardMenuHolder(service, "misc", 0);
        final List<Runnable> callbacks = new ArrayList<>();
        final CompletableFuture<RewardClaimResult> claim = new CompletableFuture<>();
        final RewardListener listener = new RewardListener(service, menu);
        Fixture() {
            holder.action(19, new RewardMenuAction(RewardMenuAction.Type.CLAIM, "goal"));
            when(player.getUniqueId()).thenReturn(id);
            when(player.isOnline()).thenReturn(true);
            when(player.hasPermission("enthusia.tags.rewards")).thenReturn(true);
            when(player.getServer()).thenReturn(server);
            when(server.getPlayer(id)).thenReturn(player);
            when(service.isAvailable()).thenReturn(true);
            when(menu.beginClaim(id, "goal")).thenReturn(true);
            doAnswer(call -> { callbacks.add(call.getArgument(0)); return null; }).when(menu).nextTick(any(Runnable.class));
            holder.setInventory(top);
            when(top.getHolder()).thenReturn(holder);
            when(top.getSize()).thenReturn(54);
            var view = mock(InventoryView.class);
            when(view.getTopInventory()).thenReturn(top);
            when(event.getView()).thenReturn(view);
            when(player.getOpenInventory()).thenReturn(view);
            when(event.getWhoClicked()).thenReturn(player);
            when(event.getClickedInventory()).thenReturn(top);
            when(event.getRawSlot()).thenReturn(19);
            when(event.getClick()).thenReturn(ClickType.LEFT);
            var item = mock(ItemStack.class);
            var meta = mock(ItemMeta.class);
            var data = mock(PersistentDataContainer.class);
            when(event.getCurrentItem()).thenReturn(item);
            when(item.hasItemMeta()).thenReturn(true);
            when(item.getItemMeta()).thenReturn(meta);
            when(meta.getPersistentDataContainer()).thenReturn(data);
            when(menu.getRewardKey()).thenReturn(new NamespacedKey("test", "reward"));
            when(menu.getBackKey()).thenReturn(new NamespacedKey("test", "back"));
            when(menu.getNextKey()).thenReturn(new NamespacedKey("test", "next"));
            when(menu.getPrevKey()).thenReturn(new NamespacedKey("test", "previous"));
            when(menu.getCategoryKey()).thenReturn(new NamespacedKey("test", "category"));
            when(data.get(menu.getRewardKey(), PersistentDataType.STRING)).thenReturn("goal");
            var reward = new RewardDefinition("goal", "Goal", List.of(), null, List.of(), List.of(), "misc");
            when(service.getRewards()).thenReturn(Map.of("goal", reward));
            when(service.claimAsync(player, reward)).thenReturn(claim);
        }
    }
}
