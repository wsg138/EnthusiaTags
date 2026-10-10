package org.enthusia.tags.rewards;

import java.util.List;
import java.util.Locale;
import org.bukkit.Material;

/** Presentation-only grouping; reward IDs, criteria and claim ownership stay intact. */
final class AdvancementCategories {
    static final String ROOT = "advancements";
    static final List<RewardCategory> CHILDREN = List.of(
        child("commend","Commend",Material.EMERALD),
        child("warzone_duels","Warzone Duels",Material.IRON_SWORD),
        child("express","Express",Material.CHEST),
        child("diary","DiaryKeeper",Material.WRITABLE_BOOK),
        child("other","Other Advancements",Material.NETHER_STAR));
    private AdvancementCategories() {}
    private static RewardCategory child(String id,String name,Material icon) {
        return new RewardCategory(ROOT+"/"+id,name,icon,ROOT,List.of());
    }
    static String category(RewardDefinition reward) {
        if (!ROOT.equalsIgnoreCase(reward.getCategory())) return reward.getCategory();
        var providers = new java.util.HashSet<String>();
        for (var criterion : reward.getCriteria()) {
            String key=criterion.getKey();
            if (key==null || !key.startsWith("advancement_reward:")) continue;
            String provider=key.substring("advancement_reward:".length()).split("/",2)[0].toLowerCase(Locale.ROOT);
            providers.add(provider.equals("reputation") ? "commend" : provider);
        }
        String provider=providers.size()==1 ? providers.iterator().next() : "other";
        String id=ROOT+"/"+provider;
        return CHILDREN.stream().anyMatch(child->child.id().equals(id)) ? id : ROOT+"/other";
    }
    static boolean matches(RewardDefinition reward,String category) {
        return reward.getCategory().equalsIgnoreCase(category) || category(reward).equalsIgnoreCase(category);
    }
}
