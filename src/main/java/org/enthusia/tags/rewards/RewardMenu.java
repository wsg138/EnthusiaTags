package org.enthusia.tags.rewards;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.enthusia.tags.TagService;
import static org.enthusia.tags.rewards.RewardMenuAction.Type.*;

public final class RewardMenu implements AutoCloseable {
    private static final int BROWSER_SIZE = 54;
    private static final String KOTH_CATEGORY = "koth";
    private final RewardService service;
    private final Plugin plugin;
    private final TagService tags;
    private final RewardMenuItems items;
    private final NamespacedKey rewardKey;
    private final NamespacedKey categoryKey;
    private final NamespacedKey backKey;
    private final NamespacedKey nextKey;
    private final NamespacedKey prevKey;
    private final Set<ClaimKey> inFlight = ConcurrentHashMap.newKeySet();
    private BukkitTask refreshTask;
    private boolean closed;
    private long warningAfter;
    private record ClaimKey(UUID player, String reward) {}
    private record Snapshot(List<RewardCategory> categories, List<RewardMenuModel.Entry> entries) {}
    public RewardMenu(RewardService service, TagService tags) {
        this.service = service; this.plugin = tags.getPlugin(); this.tags = tags; this.items = new RewardMenuItems(service, tags);
        rewardKey = new NamespacedKey(plugin,"reward_id"); categoryKey = new NamespacedKey(plugin,"reward_category");
        backKey = new NamespacedKey(plugin,"reward_back"); nextKey = new NamespacedKey(plugin,"reward_next"); prevKey = new NamespacedKey(plugin,"reward_prev");
    }
    public void openKoth(Player player, String page) {
        if (!new KothRewardsHook(plugin.getServer()).open(player,page)) player.sendMessage(net.kyori.adventure.text.Component.text("KOTH rewards are unavailable. Ask staff to check the KOTH menu integration."));
    }
    public Inventory create(Player player) { return create(player, RewardMenuState.dashboard()); }
    public Inventory createCategory(Player player, String category) { return create(player, RewardMenuState.category(category)); }
    public Inventory createCategory(Player player, String category, int page) { return create(player, RewardMenuState.category(category).withPage(page)); }
    public Inventory createFocused(Player player, RewardDefinition target) {
        Snapshot snapshot = snapshot(player);
        RewardMenuState state = RewardMenuState.category(target.getCategory()).focus(target.getId());
        List<RewardMenuModel.Entry> selected = RewardMenuModel.select(snapshot.entries(), state);
        int index = 0;
        while (index < selected.size() && !selected.get(index).id().equalsIgnoreCase(target.getId())) index++;
        return create(player, state.withPage(index < selected.size() ? index / 21 : 0), snapshot);
    }
    public Inventory create(Player player, RewardMenuState state) { return create(player, state, snapshot(player)); }
    private Inventory create(Player player, RewardMenuState state, Snapshot snapshot) {
        RewardMenuHolder holder = new RewardMenuHolder(service, state);
        Inventory inventory = Bukkit.createInventory(holder, state.view() == RewardMenuState.View.DASHBOARD ? 45 : BROWSER_SIZE,
            RewardMenuText.component(title(state)));
        holder.setInventory(inventory);
        render(player, holder, snapshot, false);
        return inventory;
    }
    /** Called outside inventory-click processing, on the main thread. */
    public void navigate(Player player, RewardMenuHolder holder, RewardMenuState next) {
        int size = next.view() == RewardMenuState.View.DASHBOARD ? 45 : BROWSER_SIZE;
        if (holder.getInventory().getSize() != size) { player.openInventory(create(player,next)); return; }
        holder.state(next); holder.clearNotices(); render(player,holder,snapshot(player),false);
    }
    public void refresh(Player player, RewardMenuHolder holder, boolean preserveSlots) {
        if (holder.getRewardService() != service) return;
        render(player,holder,snapshot(player),preserveSlots);
    }
    public void nextTick(Runnable work) { Bukkit.getScheduler().runTask(plugin,work); }
    public void startRefresh() {
        if (closed || refreshTask != null) return;
        refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshOpenViews, 40L, 40L);
    }
    private void refreshOpenViews() {
        if (closed || !service.isAvailable()) return;
        for (Player player : Bukkit.getOnlinePlayers()) refreshOwnedView(player);
    }
    private void refreshOwnedView(Player player) {
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof RewardMenuHolder holder
            && holder.getRewardService() == service) {
            try { refresh(player,holder,true); }
            catch (RuntimeException error) { warn(error); }
        }
    }
    public boolean beginClaim(UUID player, String id) { return inFlight.add(new ClaimKey(player,id.toLowerCase(Locale.ROOT))); }
    public void endClaim(UUID player, String id) { inFlight.remove(new ClaimKey(player,id.toLowerCase(Locale.ROOT))); }
    public boolean claiming(UUID player, String id) { return inFlight.contains(new ClaimKey(player,id.toLowerCase(Locale.ROOT))); }
    private Snapshot snapshot(Player player) {
        var categories = new LinkedHashMap<String,RewardCategory>();
        var configured = service.getConfig().categories();
        for (String id : List.of("playtime","advancements","supporter","legacy","events","mining","combat","deaths","economy","exploration","misc")) {
            if (configured.containsKey(id)) categories.put(id,configured.get(id));
        }
        configured.values().stream().sorted(java.util.Comparator.comparing(RewardCategory::id))
            .forEach(c -> categories.putIfAbsent(c.id().toLowerCase(Locale.ROOT),c));
        Map<String,RewardDefinition> rewards = service.getRewards();
        for (RewardDefinition reward : rewards.values()) {
            String category = reward.getCategory().toLowerCase(Locale.ROOT);
            categories.putIfAbsent(category,defaultCategory(category));
        }
        if (plugin.getServer() != null && plugin.getServer().getPluginManager() != null && new KothRewardsHook(plugin.getServer()).installed()) categories.put(KOTH_CATEGORY,new RewardCategory(KOTH_CATEGORY,"KOTH",Material.NETHER_STAR));
        var ids = new ArrayList<>(categories.keySet());
        var progress = service.getProgressSnapshot(player);
        var rows = new ArrayList<RewardMenuModel.Entry>();
        int index = 0;
        for (RewardDefinition reward : rewards.values()) {
            rows.add(readEntry(player, reward, progress, index++, ids.indexOf(reward.getCategory().toLowerCase(Locale.ROOT))));
        }
        return new Snapshot(List.copyOf(categories.values()),List.copyOf(rows));
    }
    private static RewardCategory defaultCategory(String category) {
        return new RewardCategory(category, RewardMenuText.titleCase(category), Material.PAPER);
    }
    private RewardMenuModel.Entry readEntry(Player player, RewardDefinition reward, RewardService.ProgressSnapshot progress, int index, int categoryIndex) {
        RewardEvaluation evaluation;
        var readings = new ArrayList<RewardMenuModel.Reading>();
        try {
            evaluation = service.evaluate(player,reward,progress);
            for (RewardCriterion criterion : reward.getCriteria()) readings.add(readCriterion(player, criterion, progress));
        } catch (RuntimeException error) {
            warn(error); evaluation = new RewardEvaluation(RewardStatus.LOCKED,Map.of(),false,false,"Progress unavailable");
            readings.clear();
        }
        return new RewardMenuModel.Entry(reward,evaluation,readings,index,categoryIndex);
    }
    private RewardMenuModel.Reading readCriterion(Player player, RewardCriterion criterion, RewardService.ProgressSnapshot progress) {
        return new RewardMenuModel.Reading(criterion, service.getVerifiedMenuProgress(player,criterion,progress));
    }
    private void render(Player player, RewardMenuHolder holder, Snapshot snapshot, boolean preserveSlots) {
        long start = System.nanoTime();
        Inventory inventory = holder.getInventory();
        inventory.clear(); holder.clearActions();
        frame(inventory);
        if (holder.state().view() == RewardMenuState.View.DASHBOARD) dashboard(holder,snapshot);
        else browser(player,holder,snapshot,preserveSlots);
        if (plugin instanceof org.enthusia.tags.EnthusiaTagsPlugin tags) {
            tags.getPerformanceMonitor().add("rewards.gui.items-rendered",holder.visibleRewards().size());
            tags.getPerformanceMonitor().recordDurationMillis("rewards.gui.render",java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));
        }
    }
    private void frame(Inventory inventory) {
        ItemStack border = RewardMenuItems.item(Material.BLACK_STAINED_GLASS_PANE," ");
        int lastRow = inventory.getSize() / 9 - 1;
        for(int slot=0;slot<inventory.getSize();slot++) {
            int row=slot/9;
            int column=slot%9;
            if(row==0 || row==lastRow || column==0 || column==8) inventory.setItem(slot,border);
        }
        if(inventory.getSize()==BROWSER_SIZE) {
            ItemStack utility = RewardMenuItems.item(Material.GRAY_STAINED_GLASS_PANE," ");
            for(int slot=9;slot<=17;slot++) inventory.setItem(slot,utility);
            ItemStack accent = RewardMenuItems.item(Material.ORANGE_STAINED_GLASS_PANE," ");
            inventory.setItem(3,accent); inventory.setItem(5,accent);
        } else {
            ItemStack accent = RewardMenuItems.item(Material.ORANGE_STAINED_GLASS_PANE," ");
            inventory.setItem(0,accent); inventory.setItem(8,accent);
        }
    }
    private void dashboard(RewardMenuHolder holder, Snapshot snapshot) {
        var summary = RewardMenuModel.summary(snapshot.entries());
        put(holder,4,items.summary("Your Progress",summary,true),REFRESH);
        var roots = snapshot.categories().stream().filter(category -> category.parent() == null).toList();
        var page = RewardMenuModel.page(roots,holder.getPage(),7);
        holder.page(List.of(),page.count(),page.index());
        for(int i=0;i<page.entries().size();i++) {
            RewardCategory category = page.entries().get(i);
            putCategory(holder,RewardMenuModel.DASHBOARD_SLOTS.get(i),category,summaryFor(snapshot,category.id()),false);
        }
        if(snapshot.categories().isEmpty()) holder.getInventory().setItem(22,RewardMenuItems.item(Material.PAPER,"&fNo rewards configured","&7There are no reward categories to browse."));
        holder.getInventory().setItem(36,RewardMenuItems.item(Material.BOOK,"&fHow Rewards Work",
            "&7Browse a category and complete its requirements.","&7Ready rewards glow and show a claim prompt.","&7Click each reward to claim it once.","","&7Browsing never starts a new reward claim.","&7Some item deliveries may need inventory space."));
        put(holder,37,RewardMenuItems.item(Material.NAME_TAG,"&bTags","&7Browse and equip your tags.","&eClick to open"),TAGS);
        if(page.hasPrevious()) put(holder,38,RewardMenuItems.item(Material.ARROW,"&fPrevious Categories"),PREVIOUS);
        if(page.hasNext()) put(holder,42,RewardMenuItems.item(Material.ARROW,"&fNext Categories"),NEXT);
        put(holder,43,RewardMenuItems.item(Material.FEATHER,"&bCosmetics","&7Browse and equip your cosmetics.","&eClick to open"),COSMETICS);
        putReady(holder,40,summary.ready());
        put(holder,44,RewardMenuItems.item(Material.BARRIER,"&cClose"),CLOSE);
    }
    private void browser(Player player, RewardMenuHolder holder, Snapshot snapshot, boolean preserveSlots) {
        RewardMenuState state = holder.state();
        RewardCategory current = state.view()==RewardMenuState.View.READY ? null : snapshot.categories().stream()
            .filter(c->c.id().equalsIgnoreCase(state.category())).findFirst().orElse(null);
        String name = state.view()==RewardMenuState.View.READY ? "Ready to Claim"
            : current == null ? "Rewards" : RewardMenuText.categoryName(current);
        var groupRows = snapshot.entries().stream()
            .filter(e->state.category()==null || e.reward().getCategory().equalsIgnoreCase(state.category())).toList();
        RewardMenuModel.Summary viewSummary = RewardMenuModel.summary(groupRows);
        Material headerIcon = state.view()==RewardMenuState.View.READY ? Material.CHEST
            : current == null ? Material.PAPER : current.icon();
        put(holder,4,items.browserHeader(headerIcon,name,viewSummary,state.view()==RewardMenuState.View.READY),REFRESH);

        if (current != null && (!current.tags().isEmpty() || snapshot.categories().stream().anyMatch(child -> current.id().equals(child.parent())))) {
            holidayCatalog(player, holder, snapshot, current);
            return;
        }
        browserControls(holder, snapshot);
        browserPage(holder, snapshot, preserveSlots);
        browserRows(player, holder, snapshot);
        browserFooter(holder);
    }
    /** Seasonal entries use the production browser frame and its existing item grid. */
    private void holidayCatalog(Player player, RewardMenuHolder holder, Snapshot snapshot, RewardCategory current) {
        var entries = new ArrayList<java.util.function.Supplier<ItemStack>>();
        var actions = new ArrayList<RewardMenuAction>();
        for (RewardCategory child : service.getConfig().categories().values()) {
            if (!current.id().equals(child.parent())) continue;
            entries.add(() -> items.category(child, summaryFor(snapshot, child.id()), false));
            actions.add(new RewardMenuAction(CATEGORY, child.id()));
        }
        for (String id : current.tags()) {
            var tag = tags.getRegistry().get(id);
            if (tag == null) continue;
            entries.add(() -> holidayTag(player, tag));
            actions.add(null);
        }
        var page = RewardMenuModel.page(entries, holder.getPage(), RewardMenuModel.REWARD_SLOTS.size());
        holder.page(List.of(), page.count(), page.index());
        browserFooter(holder);
        holder.getInventory().setItem(31, RewardMenuItems.item(Material.BLACK_STAINED_GLASS_PANE, " "));
        int start = page.index() * RewardMenuModel.REWARD_SLOTS.size();
        for (int i = 0; i < page.entries().size(); i++) {
            int slot = RewardMenuModel.REWARD_SLOTS.get(i);
            holder.getInventory().setItem(slot, page.entries().get(i).get());
            RewardMenuAction action = actions.get(start + i);
            if (action != null) holder.action(slot, action);
        }
        if (page.entries().isEmpty()) holder.getInventory().setItem(31,
            RewardMenuItems.item(Material.PAPER, "&fNo holiday tags configured"));
        if (current.parent() != null) put(holder,45,
            RewardMenuItems.item(Material.BOOK,"&fBack to Holidays","&7Return to the holiday categories."),BACK);
    }
    private ItemStack holidayTag(Player player, org.enthusia.tags.TagDefinition tag) {
        ItemStack stack = new ItemStack(tag.getIcon());
        var meta = stack.getItemMeta();
        meta.displayName(org.enthusia.tags.TagTextFormat.deserializeCompat(tag.getDisplayName()));
        var lore = new ArrayList<net.kyori.adventure.text.Component>();
        for (String line : tag.getDescription()) lore.add(org.enthusia.tags.TagTextFormat.deserializeCompat(line));
        boolean owned = tags.getPlayerData(player.getUniqueId()).getOwnedTags().contains(tag.getId().toLowerCase(Locale.ROOT));
        lore.add(org.enthusia.tags.TagTextFormat.deserializeCompat(service.getMessage(owned ? "rewards-holiday-owned" : "rewards-holiday-locked")));
        lore.add(org.enthusia.tags.TagTextFormat.deserializeCompat(service.getMessage("rewards-holiday-event")));
        meta.lore(lore); stack.setItemMeta(meta); return stack;
    }
    public RewardMenuState parentState(String categoryId) {
        RewardCategory category = categoryId == null ? null : service.getConfig().categories().get(categoryId);
        return category == null || category.parent() == null ? RewardMenuState.dashboard() : RewardMenuState.category(category.parent());
    }
    private void browserControls(RewardMenuHolder holder, Snapshot snapshot) {
        RewardMenuState state = holder.state();
        if(state.view()!=RewardMenuState.View.READY) {
            putReady(holder,10,RewardMenuModel.summary(snapshot.entries()).ready());
            if("playtime".equals(state.category())) put(holder,12,choice(Material.BOOK,"Group",state.group().label(),
                java.util.Arrays.stream(RewardMenuState.Group.values()).map(RewardMenuState.Group::label).toList()),GROUP);
            put(holder,15,choice(Material.HOPPER,"Filter",state.filter().label(),
                java.util.Arrays.stream(RewardMenuState.Filter.values()).map(RewardMenuState.Filter::label).toList()),FILTER);
        }
        put(holder,16,choice(Material.COMPARATOR,"Sort",state.sort().label(),
            java.util.Arrays.stream(RewardMenuState.Sort.values()).map(RewardMenuState.Sort::label).toList()),SORT);

    }
    private static void browserPage(RewardMenuHolder holder, Snapshot snapshot, boolean preserveSlots) {
        RewardMenuState state = holder.state();
        if(!preserveSlots) {
            var selected=RewardMenuModel.select(snapshot.entries(),state);
            var page=RewardMenuModel.page(selected,state.page(),21);
            holder.page(page.entries().stream().map(RewardMenuModel.Entry::id).toList(),page.count(),page.index());
        }
    }
    private void browserRows(Player player, RewardMenuHolder holder, Snapshot snapshot) {
        var byId=new LinkedHashMap<String,RewardMenuModel.Entry>(); snapshot.entries().forEach(e->byId.put(e.id(),e));
        for(int i=0;i<holder.visibleRewards().size();i++) {
            String id=holder.visibleRewards().get(i); var row=byId.get(id); if(row==null) continue;
            renderReward(player, holder, row, id, RewardMenuModel.REWARD_SLOTS.get(i));
        }
    }
    private void renderReward(Player player, RewardMenuHolder holder, RewardMenuModel.Entry row, String id, int slot) {
        RewardMenuState state = holder.state();
            ItemStack item=items.reward(row,claiming(player.getUniqueId(),id),id.equalsIgnoreCase(state.focusedReward()),holder.notice(id));
            var meta=item.getItemMeta(); meta.getPersistentDataContainer().set(rewardKey,PersistentDataType.STRING,id); item.setItemMeta(meta);
            put(holder,slot,item,new RewardMenuAction(CLAIM,id));
    }
    private void browserFooter(RewardMenuHolder holder) {
        RewardMenuState state = holder.state();
        if(holder.visibleRewards().isEmpty()) holder.getInventory().setItem(31,RewardMenuItems.item(Material.PAPER,
            state.view()==RewardMenuState.View.READY ? "&fNo rewards ready to claim" : "&fNo matching rewards",
            "&7Try another filter or category.","&7Click the header to refresh."));
        put(holder,45,RewardMenuItems.item(Material.BOOK,"&fCategories","&7Return to the rewards dashboard."),BACK);
        put(holder,46,RewardMenuItems.item(Material.NAME_TAG,"&bTags","&7Open your tags."),TAGS);
        if(holder.getPage()>0) put(holder,47,RewardMenuItems.item(Material.ARROW,"&fPrevious Page"),PREVIOUS);
        put(holder,49,RewardMenuItems.item(Material.PAPER,"&fPage "+(holder.getPage()+1)+" &8/ &f"+holder.pageCount(),
            "&7Progress refreshes without moving these items.","&eClick to refresh."),REFRESH);
        if(holder.getPage()+1<holder.pageCount()) put(holder,51,RewardMenuItems.item(Material.ARROW,"&fNext Page"),NEXT);
        put(holder,52,RewardMenuItems.item(Material.FEATHER,"&bCosmetics","&7Open your cosmetics."),COSMETICS);
        put(holder,53,RewardMenuItems.item(Material.BARRIER,"&cClose"),CLOSE);
    }
    private String title(RewardMenuState state) {
        if(state.view()==RewardMenuState.View.DASHBOARD) return "&6Enthusia &8• &fRewards";
        if(state.view()==RewardMenuState.View.READY) return "&6Enthusia &8• &aReady to Claim";
        return "&6Enthusia &8• &f"+RewardMenuText.titleCase(state.category()==null ? "Rewards" : state.category());
    }
    private RewardMenuModel.Summary summaryFor(Snapshot snapshot,String category) {
        return RewardMenuModel.summary(snapshot.entries().stream().filter(e->e.reward().getCategory().equalsIgnoreCase(category)).toList());
    }
    private ItemStack choice(Material material,String title,String selected,List<String> options) {
        var lore=new ArrayList<String>();
        for(String option:options) lore.add((option.equals(selected)?"&6› ":"&7  ")+option);
        lore.add("");lore.add("&eLeft-click: next");lore.add("&7Right-click: previous");
        return RewardMenuItems.item(material,"&f"+title+": &6"+selected,lore,false);
    }
    private void putReady(RewardMenuHolder holder,int slot,int count) {
        put(holder,slot,RewardMenuItems.item(Material.CHEST,"&aReady to Claim &7("+count+")",
            List.of("&7Eligible rewards from every category.","&7Each reward is claimed individually.","","&eClick to browse"),count>0),READY);
    }
    private void putCategory(RewardMenuHolder holder,int slot,RewardCategory category,RewardMenuModel.Summary summary,boolean selected) {
        ItemStack item=KOTH_CATEGORY.equals(category.id()) ? RewardMenuItems.item(Material.NETHER_STAR,"&6KOTH","&7Challenges, earned rewards and match results.","&7KOTH owns progress and claims.","&eClick to open") : items.category(category,summary,selected);
        var meta=item.getItemMeta();meta.getPersistentDataContainer().set(categoryKey,PersistentDataType.STRING,category.id());item.setItemMeta(meta);
        put(holder,slot,item,new RewardMenuAction(KOTH_CATEGORY.equals(category.id()) ? KOTH : CATEGORY,category.id()));
    }
    private void put(RewardMenuHolder holder,int slot,ItemStack item,RewardMenuAction.Type type) { put(holder,slot,item,new RewardMenuAction(type)); }
    private void put(RewardMenuHolder holder,int slot,ItemStack item,RewardMenuAction action) { holder.getInventory().setItem(slot,item);holder.action(slot,action); }
    @Override public void close() {
        if (closed) return;
        closed = true;
        if (refreshTask != null) refreshTask.cancel();
        inFlight.clear();
    }
    public void openTags(Player player) {
        if (!player.hasPermission("enthusia.tags.use")) return;
        player.openInventory(new org.enthusia.tags.TagMenu(tags).create(player));
    }
    public void openCosmetics(Player player) {
        if (!player.hasPermission("enthusia.cosmetics.use")) return;
        if (plugin instanceof org.enthusia.tags.EnthusiaTagsPlugin tagsPlugin) {
            player.openInventory(new org.enthusia.tags.cosmetics.CosmeticsMenu(
                tagsPlugin.getCosmeticsService(), tags, tagsPlugin.getMessages()).createMain(player));
        }
    }
    private void warn(RuntimeException error) {
        if(System.currentTimeMillis()<warningAfter)return;
        warningAfter=System.currentTimeMillis()+60000;
        plugin.getLogger().warning("Rewards GUI could not refresh verified progress: "+error.getClass().getSimpleName()+": "+error.getMessage());
    }
    public NamespacedKey getRewardKey(){return rewardKey;}
    public NamespacedKey getCategoryKey(){return categoryKey;}
    public NamespacedKey getBackKey(){return backKey;}
    public NamespacedKey getNextKey(){return nextKey;}
    public NamespacedKey getPrevKey(){return prevKey;}
}
