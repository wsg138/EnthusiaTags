package org.enthusia.tags.rewards;

import java.util.Locale;

/** Browser state only. Never alters reward definitions or saved completion. */
public record RewardMenuState(View view, String category, int page, Filter filter,
                              Sort sort, Group group, String focusedReward) {
    public static final String PLAYTIME_CATEGORY = "playtime";
    private static final int FIRST_PAGE = 0;
    public enum View { DASHBOARD, CATEGORY, READY }
    public enum Filter {
        ALL("All"), READY("Ready to Claim"), UNCLAIMED("Unclaimed"), CLAIMED("Claimed");
        private final String displayLabel;
        Filter(String displayLabel) { this.displayLabel = displayLabel; }
        public String label() { return displayLabel; }
        public Filter cycle(boolean reverse) { return values()[Math.floorMod(ordinal() + (reverse ? -1 : 1), values().length)]; }
    }
    public enum Sort {
        PROGRESSION("Progression"), CLOSEST("Closest to Completion"), NAME("Name");
        private final String displayLabel;
        Sort(String displayLabel) { this.displayLabel = displayLabel; }
        public String label() { return displayLabel; }
        public Sort cycle(boolean reverse) { return values()[Math.floorMod(ordinal() + (reverse ? -1 : 1), values().length)]; }
    }
    public enum Group {
        ALL("All Playtime"), TOTAL("Total Playtime"), ACTIVE("Active Playtime"), ACCESS("Access Unlocks"), SPECIAL("Special Challenges");
        private final String displayLabel;
        Group(String displayLabel) { this.displayLabel = displayLabel; }
        public String label() { return displayLabel; }
        public Group cycle(boolean reverse) { return values()[Math.floorMod(ordinal() + (reverse ? -1 : 1), values().length)]; }
    }
    public RewardMenuState {
        if (view == null || filter == null || sort == null || group == null) throw new IllegalArgumentException("Missing browser state");
        category = category == null ? null : category.toLowerCase(Locale.ROOT);
        focusedReward = focusedReward == null ? null : focusedReward.toLowerCase(Locale.ROOT);
        page = Math.max(FIRST_PAGE, page);
        if (view == View.CATEGORY && (category == null || category.isBlank())) throw new IllegalArgumentException("Missing category");
        if (view == View.READY) filter = Filter.READY;
        if (!PLAYTIME_CATEGORY.equals(category)) group = Group.ALL;
    }
    public static RewardMenuState dashboard() { return new RewardMenuState(View.DASHBOARD, null, 0, Filter.ALL, Sort.PROGRESSION, Group.ALL, null); }
    public static RewardMenuState category(String id) { return new RewardMenuState(View.CATEGORY, id, 0, Filter.ALL, Sort.PROGRESSION, Group.ALL, null); }
    public static RewardMenuState ready() { return new RewardMenuState(View.READY, null, 0, Filter.READY, Sort.PROGRESSION, Group.ALL, null); }
    public RewardMenuState withPage(int value) { return new RewardMenuState(view, category, value, filter, sort, group, focusedReward); }
    public RewardMenuState withFilter(Filter value) { return new RewardMenuState(view, category, 0, value, sort, group, null); }
    public RewardMenuState withSort(Sort value) { return new RewardMenuState(view, category, 0, filter, value, group, null); }
    public RewardMenuState withGroup(Group value) { return new RewardMenuState(view, category, 0, filter, sort, value, null); }
    public RewardMenuState focus(String id) { return new RewardMenuState(view, category, page, filter, sort, group, id); }
}
