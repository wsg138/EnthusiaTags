package org.enthusia.tags.rewards;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class RewardMenuHolder implements InventoryHolder {
    private final RewardService rewardService;
    private RewardMenuState browserState;
    private Inventory inventory;
    @SuppressWarnings("PMD.UseConcurrentHashMap") // Holder state is confined to the server thread.
    private final Map<Integer, RewardMenuAction> actions = new LinkedHashMap<>();
    @SuppressWarnings("PMD.UseConcurrentHashMap") // Holder state is confined to the server thread.
    private final Map<String, RewardClaimResult> notices = new LinkedHashMap<>();
    private List<String> visibleIds = List.of();
    private int totalPages = 1;
    private boolean scheduled;
    public RewardMenuHolder(RewardService service) { this(service, RewardMenuState.dashboard()); }
    public RewardMenuHolder(RewardService service, String category, int page) {
        this(service, category == null ? RewardMenuState.dashboard().withPage(page) : RewardMenuState.category(category).withPage(page));
    }
    public RewardMenuHolder(RewardService service, RewardMenuState state) { this.rewardService = service; this.browserState = state; }
    public RewardService getRewardService() { return rewardService; }
    public String getCategory() { return browserState.category(); }
    public int getPage() { return browserState.page(); }
    public RewardMenuState state() { return browserState; }
    public void state(RewardMenuState state) { this.browserState = state; }
    public int pageCount() { return totalPages; }
    public List<String> visibleRewards() { return visibleIds; }
    public void page(List<String> ids, int pages, int selectedPage) {
        visibleIds = List.copyOf(ids); totalPages = Math.max(1, pages); browserState = browserState.withPage(selectedPage);
    }
    public void clearActions() { actions.clear(); }
    public void action(int slot, RewardMenuAction action) { actions.put(slot, action); }
    public RewardMenuAction action(int slot) { return actions.get(slot); }
    public boolean schedule() { if (scheduled) return false; scheduled = true; return true; }
    public void unschedule() { scheduled = false; }
    public RewardClaimResult notice(String id) { return notices.get(id); }
    public void notice(String id, RewardClaimResult result) { notices.put(id, result); }
    public void clearNotices() { notices.clear(); }
    public void clearNotice(String id) { notices.remove(id); }
    public void setInventory(Inventory value) { inventory = value; }
    @Override public Inventory getInventory() { return inventory; }
}
