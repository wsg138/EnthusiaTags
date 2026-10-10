package org.enthusia.tags.rewards;

import java.util.Locale;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

/** Controls the browser, but delegates every claim/recovery to the existing RewardService. */
public final class RewardListener implements Listener {
    private final RewardService service;
    private final RewardMenu menu;
    private final java.util.Map<RewardMenuAction.Type, java.util.function.Consumer<Interaction>> actionHandlers;
    public RewardListener(RewardService service, RewardMenu menu) {
        this.service = service; this.menu = menu; this.actionHandlers = handlers();
    }
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        // Retain the established retry mechanism for already-claimed queued items.
        if(event.getPlayer() instanceof Player player) service.retryQueuedItems(player);
    }
    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if(owned(event.getView().getTopInventory()) != null) event.setCancelled(true);
    }
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        RewardMenuHolder holder = owned(top);
        if(holder == null) return;
        event.setCancelled(true);
        if(!(event.getWhoClicked() instanceof Player player) || !allowed(player)) return;
        // Never interpret a player's own item, a shift-transfer, an offhand swap or a double-click as a GUI button.
        if(event.getClickedInventory() != top || event.getRawSlot() < 0 || event.getRawSlot() >= top.getSize()) return;
        if(event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) return;
        RewardMenuAction action = holder.action(event.getRawSlot());
        if(action == null || (action.type() == RewardMenuAction.Type.CLAIM && event.getClick() != ClickType.LEFT)) return;
        if(!holder.schedule()) return;
        boolean reverse = event.getClick() == ClickType.RIGHT;
        try {
            menu.nextTick(() -> {
                holder.unschedule();
                if(!allowed(player) || player.getServer().getPlayer(player.getUniqueId()) != player
                    || player.getOpenInventory().getTopInventory() != top || !java.util.Objects.equals(holder.action(event.getRawSlot()), action)) return;
                handle(player,holder,action,reverse);
            });
        } catch(RuntimeException error) { holder.unschedule(); throw error; }
    }
    private boolean allowed(Player player) {
        return player.isOnline() && player.hasPermission("enthusia.tags.rewards") && service.isAvailable();
    }
    private RewardMenuHolder owned(Inventory top) {
        return top.getHolder() instanceof RewardMenuHolder holder && holder.getRewardService() == service ? holder : null;
    }
    private record Interaction(Player player, RewardMenuHolder holder, RewardMenuAction action, boolean reverse) {}
    private java.util.Map<RewardMenuAction.Type, java.util.function.Consumer<Interaction>> handlers() {
        return java.util.Map.ofEntries(
            java.util.Map.entry(RewardMenuAction.Type.CATEGORY, input -> { menu.navigate(input.player(),input.holder(),RewardMenuState.category(input.action().value())); }),
            java.util.Map.entry(RewardMenuAction.Type.BACK, input -> { menu.navigate(input.player(),input.holder(),menu.parentState(input.holder().getCategory())); }),
            java.util.Map.entry(RewardMenuAction.Type.READY, input -> { menu.navigate(input.player(),input.holder(),RewardMenuState.ready()); }),
            java.util.Map.entry(RewardMenuAction.Type.FILTER, input -> { menu.navigate(input.player(),input.holder(),input.holder().state().withFilter(input.holder().state().filter().cycle(input.reverse()))); }),
            java.util.Map.entry(RewardMenuAction.Type.SORT, input -> { menu.navigate(input.player(),input.holder(),input.holder().state().withSort(input.holder().state().sort().cycle(input.reverse()))); }),
            java.util.Map.entry(RewardMenuAction.Type.GROUP, input -> { menu.navigate(input.player(),input.holder(),input.holder().state().withGroup(input.holder().state().group().cycle(input.reverse()))); }),
            java.util.Map.entry(RewardMenuAction.Type.PREVIOUS, input -> { if(input.holder().state().page() > 0) menu.navigate(input.player(),input.holder(),input.holder().state().withPage(input.holder().state().page()-1)); }),
            java.util.Map.entry(RewardMenuAction.Type.NEXT, input -> { if(input.holder().state().page()+1 < input.holder().pageCount()) menu.navigate(input.player(),input.holder(),input.holder().state().withPage(input.holder().state().page()+1)); }),
            java.util.Map.entry(RewardMenuAction.Type.CLOSE, input -> { input.player().closeInventory(); }),
            java.util.Map.entry(RewardMenuAction.Type.REFRESH, input -> { input.holder().clearNotices(); menu.refresh(input.player(),input.holder(),false); }),
            java.util.Map.entry(RewardMenuAction.Type.TAGS, input -> { menu.openTags(input.player()); }),
            java.util.Map.entry(RewardMenuAction.Type.COSMETICS, input -> { menu.openCosmetics(input.player()); }),
            java.util.Map.entry(RewardMenuAction.Type.CLAIM, input -> { claim(input.player(),input.holder(),input.action().value()); })
        );
    }
    private void handle(Player player, RewardMenuHolder holder, RewardMenuAction action, boolean reverse) {
        actionHandlers.get(action.type()).accept(new Interaction(player, holder, action, reverse));
    }
    private void claim(Player player, RewardMenuHolder holder, String id) {
        RewardDefinition reward = service.getRewards().get(id.toLowerCase(Locale.ROOT));
        if(reward == null) { menu.refresh(player,holder,false); return; }
        UUID playerId = player.getUniqueId();
        if(!menu.beginClaim(playerId,id)) return;
        holder.clearNotice(id);
        try {
            menu.refresh(player,holder,true);
            service.claimAsync(player,reward).whenComplete((result,error) -> {
                // This release is unconditional, even when the player disconnects or the service is stopping.
                menu.endClaim(playerId,id);
                RewardClaimResult outcome = error != null || result == null ? RewardClaimResult.DELIVERY_FAILED : result;
                service.runForOnlinePlayer(playerId,current -> {
                    if (current != player) return;
                    current.sendMessage(RewardMenuText.component(service.getMessage(RewardMenuText.resultMessageKey(outcome))));
                    // Do not reopen a closed GUI or switch someone away from a different inventory.
                    if(current.getOpenInventory().getTopInventory() == holder.getInventory()) {
                        holder.notice(id,outcome);
                        menu.refresh(current,holder,true);
                    }
                });
            });
        } catch(RuntimeException error) {
            menu.endClaim(playerId,id);
            holder.notice(id,RewardClaimResult.DELIVERY_FAILED);
            player.sendMessage(RewardMenuText.component(service.getMessage("rewards-delivery-failed")));
            menu.refresh(player,holder,true);
        }
    }
}
