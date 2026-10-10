package org.enthusia.tags.rewards;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.enthusia.tags.TagService;
import org.enthusia.tags.TagTextFormat;

/** Vanilla items only. No model IDs, custom textures or resource-pack dependency. */
final class RewardMenuItems {
    private static final int SINGLE_ENTRY = 1;
    private final RewardService service;
    private final TagService tags;
    RewardMenuItems(RewardService service, TagService tags) { this.service = service; this.tags = tags; }
    static ItemStack item(Material material, String name, List<String> lines, boolean glint) {
        ItemStack item = new ItemStack(material == null || material == Material.AIR || material == Material.CAVE_AIR
            || material == Material.VOID_AIR ? Material.PAPER : material);
        var meta = item.getItemMeta();
        meta.displayName(RewardMenuText.component(name));
        meta.lore(lines.stream().map(RewardMenuText::component).toList());
        meta.setEnchantmentGlintOverride(glint);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        item.setItemMeta(meta);
        return item;
    }
    static ItemStack item(Material material, String name, String... lines) { return item(material, name, List.of(lines), false); }
    ItemStack category(RewardCategory category, RewardMenuModel.Summary summary, boolean selected) {
        List<String> lore = new ArrayList<>();
        lore.add("&7" + categoryDescription(category.id()));
        lore.add("");
        lore.add("&7Claimed: &f" + summary.claimed() + " &7/ &f" + summary.total());
        lore.add("&7Ready to claim: &a" + summary.ready());
        if (summary.attention() > 0) lore.add("&7Pending / needs attention: &6" + summary.attention());
        if (summary.unavailable() > 0) lore.add("&7Temporarily unavailable: &6" + summary.unavailable());
        lore.add("");
        lore.add(selected ? "&6Selected category" : "&eClick to browse");
        return item(category.icon(), (selected ? "&6" : "&f") + RewardMenuText.categoryName(category), lore, false);
    }
    private static final java.util.Map<String, String> CATEGORY_DESCRIPTIONS = java.util.Map.ofEntries(
        java.util.Map.entry("playtime", "Rewards for time spent on Enthusia."),
        java.util.Map.entry("advancements", "Rewards earned through custom advancements."),
        java.util.Map.entry("supporter", "Permanent supporter rewards and current-rank perks."),
        java.util.Map.entry("legacy", "Historical rewards permanently tied to your account."),
        java.util.Map.entry("events", "Champion and special event rewards."),
        java.util.Map.entry("mining", "Gather resources and reach mining milestones."),
        java.util.Map.entry("combat", "Complete combat challenges."),
        java.util.Map.entry("deaths", "Milestones from your less fortunate moments."),
        java.util.Map.entry("economy", "Build your wealth and economic progress."),
        java.util.Map.entry("exploration", "Explore the world and travel farther.")
    );
    private String categoryDescription(String id) {
        return CATEGORY_DESCRIPTIONS.getOrDefault(id.toLowerCase(java.util.Locale.ROOT), "Browse additional server challenges.");
    }
    ItemStack browserHeader(Material icon, String title, RewardMenuModel.Summary summary, boolean readyView) {
        List<String> lore = new ArrayList<>();
        lore.add("&7" + summary.total() + (summary.total() == 1 ? " reward" : " rewards"));
        lore.add("&7Claimed: &f" + summary.claimed() + " &8• &a" + summary.ready() + " ready");
        if (summary.attention() > 0) lore.add("&6" + summary.attention() + " need attention");
        lore.add("");
        lore.add("&7Reward controls are kept in the bar below.");
        lore.add("&eClick to refresh");
        return item(icon, (readyView ? "&a" : "&6") + title, lore, false);
    }
    ItemStack summary(String title, RewardMenuModel.Summary summary, boolean refresh) {
        List<String> lore = new ArrayList<>();
        lore.add("&7Claimed: &f" + summary.claimed() + " &7/ &f" + summary.total());
        lore.add("&7Ready to claim: &a" + summary.ready());
        if (summary.attention() > 0) lore.add("&7Pending / needs attention: &6" + summary.attention());
        if (summary.unavailable() > 0) lore.add("&7Temporarily unavailable: &6" + summary.unavailable());
        lore.add("");
        lore.add("&7Claimed completion");
        lore.add(RewardMenuText.bar(summary.total() == 0 ? 0 : (double) summary.claimed() / summary.total()));
        lore.add("");
        lore.add(refresh ? "&eClick to refresh this view" : "&7Completed requirements do not auto-claim.");
        return item(Material.BOOK, "&6" + title, lore, false);
    }
    ItemStack reward(RewardMenuModel.Entry row, boolean claiming, boolean focused, RewardClaimResult notice) {
        RewardDefinition definition = row.reward();
        List<String> lore = new ArrayList<>();
        if (row.group() != RewardMenuState.Group.ALL) lore.add("&7" + row.group().label());
        for (String line : definition.getDescription()) {
            for (String wrapped : RewardMenuText.wrap(line, 40)) lore.add("&7" + wrapped);
        }
        lore.add("");
        addProgress(lore, row);
        lore.add("");
        addRewards(lore, definition);
        lore.add("");
        addStatus(lore, row.displayState(), claiming);
        addNotice(lore, notice, claiming);
        if (focused) lore.add("&7Opened from your selected goal");
        return item(definition.getIcon(), (row.claimed() ? "&7" : "&f") + RewardMenuText.plain(definition.getName()), lore, row.ready() && !claiming);
    }
    private void addRewards(List<String> lore, RewardDefinition definition) {
        if (definition.getActions().isEmpty()) lore.add("&7Reward: &fRecognition only");
        else if (definition.getActions().size() == SINGLE_ENTRY) lore.add("&7Reward: &f" + action(definition.getActions().getFirst(), definition));
        else {
            lore.add("&7Rewards");
            for (RewardAction action : definition.getActions()) lore.add("&f  " + action(action, definition));
        }
        if (definition.getActions().stream().anyMatch(RewardAction::isGoldNetworkLimited)) {
            for (String line : RewardMenuText.wrap(service.getMessage("rewards-gold-policy"), 40)) lore.add("&7" + line);
        }
    }
    private static void addNotice(List<String> lore, RewardClaimResult notice, boolean claiming) {
        String last = RewardMenuText.notice(notice);
        if (!last.isBlank() && !claiming) for (String line : RewardMenuText.wrap(last,40)) lore.add("&6" + line);
    }
    private void addProgress(List<String> lore, RewardMenuModel.Entry row) {
        if (row.claimed()) { lore.add("&aRequirements completed"); return; }
        if (row.evaluation().previouslyUnlocked() && row.reward().getCompletionMode() == RewardCompletionMode.LATCHED) {
            lore.add("&aRequirements completed previously");
            if (!row.progressKnown()) { lore.add("&7Current progress temporarily unavailable"); return; }
        }
        if (row.readings().isEmpty()) { lore.add("&6Progress temporarily unavailable"); return; }
        boolean single = row.readings().size() == SINGLE_ENTRY;
        if (!single) lore.add("&7Requirements");
        for (RewardMenuModel.Reading reading : row.readings()) addReading(lore, reading, single);
    }
    private static void addReading(List<String> lore, RewardMenuModel.Reading reading, boolean single) {
            RewardCriterion criterion = reading.criterion();
            String label = single ? "Progress" : RewardMenuText.criterionLabel(criterion);
            if (reading.value().isEmpty()) {
                lore.add("&7" + label + ": &6Temporarily unavailable");
                return;
            }
            long current = reading.value().getAsLong();
            long goal = criterion.getAmount();
            if (criterion.getType() == RewardCriterionType.BALTOP_TOP3) {
                lore.add("&7" + (single ? RewardMenuText.criterionLabel(criterion) : label) + ": "
                    + (current >= goal ? "&aReached" : "&fNot reached"));
            } else {
                lore.add("&7" + label + ": &f" + RewardMenuText.value(Math.min(current, Math.max(0,goal)), criterion)
                    + " &7/ &f" + RewardMenuText.value(goal, criterion));
            }
            if (single && goal > 0) lore.add(RewardMenuText.bar(Math.min(1, (double) current / goal)));
    }
    private static final java.util.Map<RewardMenuModel.DisplayState, List<String>> STATUS_LINES = java.util.Map.ofEntries(
        java.util.Map.entry(RewardMenuModel.DisplayState.CLAIMED, List.of("&7Claimed ✓")),
        java.util.Map.entry(RewardMenuModel.DisplayState.READY, List.of("&aREADY TO CLAIM", "&eClick to claim")),
        java.util.Map.entry(RewardMenuModel.DisplayState.NOT_STARTED, List.of("&7Not started")),
        java.util.Map.entry(RewardMenuModel.DisplayState.IN_PROGRESS, List.of("&6In progress")),
        java.util.Map.entry(RewardMenuModel.DisplayState.UNAVAILABLE, List.of("&6Temporarily unavailable", "&7Refresh once the provider is ready.")),
        java.util.Map.entry(RewardMenuModel.DisplayState.PENDING, List.of("&6Delivery pending", "&eClick to check / recover delivery")),
        java.util.Map.entry(RewardMenuModel.DisplayState.QUEUED, List.of("&6Item delivery queued", "&7Free inventory space.", "&eClick to retry item delivery")),
        java.util.Map.entry(RewardMenuModel.DisplayState.RETRY, List.of("&6Delivery failed", "&eClick to retry safely")),
        java.util.Map.entry(RewardMenuModel.DisplayState.REVIEW, List.of("&cDelivery needs review", "&7Contact staff; do not repeat the claim.")),
        java.util.Map.entry(RewardMenuModel.DisplayState.WITHHELD, List.of("&6Gold withheld by network policy", "&7Other reward components remain separate."))
    );
    private static void addStatus(List<String> lore, RewardMenuModel.DisplayState state, boolean claiming) {
        if (claiming) { lore.add("&6Claiming..."); lore.add("&7Please wait; your claim is being processed."); return; }
        lore.addAll(STATUS_LINES.get(state));
    }
    private String action(RewardAction action, RewardDefinition reward) {
        return switch(action.getType()) {
            case MONEY -> RewardMenuText.money(action.getAmount());
            case ITEM -> action.getItemAmount() + " × " + itemName(action);
            case TAG -> tagName(action);
            case COMMAND -> "Unlock: " + label(action, RewardMenuText.plain(reward.getName()));
            case LORE_ITEM -> label(action, "Special item");
        };
    }
    private static String itemName(RewardAction action) {
        if (action.getDisplayName() != null && !action.getDisplayName().isBlank()) return RewardMenuText.plain(action.getDisplayName());
        return action.getMaterial() == null ? "Item" : RewardMenuText.titleCase(action.getMaterial().name());
    }
    private String tagName(RewardAction action) {
        var definition = tags.getRegistry().get(action.getValue());
        return "Tag: " + (definition == null ? RewardMenuText.plain(action.getValue()) : TagTextFormat.legacyText(definition.getDisplayName()));
    }
    private static String label(RewardAction action, String fallback) {
        return action.getLabel() != null && !action.getLabel().isBlank() ? RewardMenuText.plain(action.getLabel()) : fallback;
    }
}
