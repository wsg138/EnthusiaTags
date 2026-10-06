package org.enthusia.tags.rewards;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalLong;

/** Pure presentation rules; RewardService remains the sole authority for claiming. */
public final class RewardMenuModel {
    public static final List<Integer> REWARD_SLOTS = List.of(19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43);
    public static final List<Integer> DASHBOARD_SLOTS = List.of(10,12,14,16,20,22,24);
    private static final int SINGLE_CRITERION = 1;
    private RewardMenuModel() {}
    public enum DisplayState { NOT_STARTED, IN_PROGRESS, READY, CLAIMED, PENDING, QUEUED, RETRY, REVIEW, WITHHELD, UNAVAILABLE }
    private static final java.util.Map<RewardStatus, DisplayState> TERMINAL_STATES = java.util.Map.of(
        RewardStatus.CLAIMED, DisplayState.CLAIMED, RewardStatus.CLAIM_PENDING, DisplayState.PENDING,
        RewardStatus.ITEM_QUEUED, DisplayState.QUEUED, RewardStatus.DELIVERY_FAILED, DisplayState.RETRY,
        RewardStatus.REQUIRES_RECONCILIATION, DisplayState.REVIEW,
        RewardStatus.WITHHELD_NETWORK_LIMIT, DisplayState.WITHHELD);
    public record Reading(RewardCriterion criterion, OptionalLong value) {
        public Reading { if (criterion == null || value == null) throw new IllegalArgumentException("Missing progress reading"); }
    }
    public record Entry(RewardDefinition reward, RewardEvaluation evaluation, List<Reading> readings,
                        int order, int categoryOrder) {
        public Entry { readings = List.copyOf(readings); }
        public String id() { return reward.getId().toLowerCase(java.util.Locale.ROOT); }
        public boolean claimed() { return evaluation.status() == RewardStatus.CLAIMED; }
        public boolean ready() { return evaluation.status() == RewardStatus.UNLOCKED && evaluation.claimable(); }
        public boolean progressKnown() {
            return !readings.isEmpty() && readings.stream().allMatch(r -> r.criterion().isValid()
                && r.criterion().getAmount() > 0 && r.value().isPresent() && r.value().getAsLong() >= 0);
        }
        public double fraction() {
            if (claimed() || ready()) return 1;
            if (!progressKnown()) return -1;
            return readings.stream().mapToDouble(r -> Math.min(1, (double) r.value().getAsLong() / r.criterion().getAmount())).min().orElse(-1);
        }
        public DisplayState displayState() {
            if (evaluation.status() == RewardStatus.LOCKED) return lockedState();
            if (evaluation.status() == RewardStatus.UNLOCKED) return ready() ? DisplayState.READY : DisplayState.UNAVAILABLE;
            return TERMINAL_STATES.get(evaluation.status());
        }
        private DisplayState lockedState() {
            if (!progressKnown() || (!evaluation.diagnosticReason().isBlank()
                && !evaluation.diagnosticReason().equals("Requirements not reached"))) return DisplayState.UNAVAILABLE;
            return readings.stream().anyMatch(r -> r.value().orElse(0) > 0) ? DisplayState.IN_PROGRESS : DisplayState.NOT_STARTED;
        }
        public RewardMenuState.Group group() { return groupFor(reward); }
    }
    public record Summary(int total, int claimed, int ready, int unavailable, int attention) {
        public int earned() { return claimed + ready; }
    }
    public record Page<T>(int index, int count, List<T> entries) {
        public Page { entries = List.copyOf(entries); }
        public boolean hasPrevious() { return index > 0; }
        public boolean hasNext() { return index + 1 < count; }
    }
    public static <T> Page<T> page(List<T> values, int requested, int size) {
        if (size <= 0) throw new IllegalArgumentException("Invalid page size");
        int count = Math.max(1, (int) ((values.size() + (long) size - 1) / size));
        int index = Math.max(0, Math.min(count - 1, requested));
        int start = index * size;
        return new Page<>(index, count, values.subList(start, (int) Math.min(values.size(), start + (long) size)));
    }
    public static Summary summary(List<Entry> rows) {
        int claimed=0;
        int ready=0;
        int unavailable=0;
        int attention=0;
        for (Entry row : rows) {
            if (row.claimed()) claimed++;
            if (row.ready()) ready++;
            if (row.displayState() == DisplayState.UNAVAILABLE) unavailable++;
            if (switch(row.displayState()) { case PENDING, QUEUED, RETRY, REVIEW, WITHHELD -> true; default -> false; }) attention++;
        }
        return new Summary(rows.size(), claimed, ready, unavailable, attention);
    }
    public static List<Entry> select(List<Entry> rows, RewardMenuState state) {
        List<Entry> selected = new ArrayList<>();
        for (Entry row : rows) {
            if (included(row, state)) selected.add(row);
        }
        selected.sort(comparator(state.sort(), progressionOrder()));
        return List.copyOf(selected);
    }
    private static boolean included(Entry row, RewardMenuState state) {
        if (state.view() == RewardMenuState.View.CATEGORY && !row.reward().getCategory().equalsIgnoreCase(state.category())) return false;
        if (!matches(row, state.filter())) return false;
        return state.group() == RewardMenuState.Group.ALL || row.group() == state.group();
    }
    private static Comparator<Entry> progressionOrder() {
        Comparator<Entry> fallback = Comparator.comparingInt(Entry::categoryOrder)
            .thenComparingInt(e -> RewardMenuState.PLAYTIME_CATEGORY.equalsIgnoreCase(e.reward().getCategory()) ? e.group().ordinal() : 0)
            .thenComparingLong(e -> RewardMenuState.PLAYTIME_CATEGORY.equalsIgnoreCase(e.reward().getCategory()) && e.reward().getCriteria().size() == SINGLE_CRITERION
                ? e.reward().getCriteria().getFirst().getAmount() : e.order())
            .thenComparingInt(Entry::order).thenComparing(Entry::id);
        return fallback;
    }
    private static Comparator<Entry> comparator(RewardMenuState.Sort sort, Comparator<Entry> fallback) {
        return switch(sort) {
            case PROGRESSION -> fallback;
            case NAME -> Comparator.comparing((Entry e) -> RewardMenuText.plain(e.reward().getName()), String.CASE_INSENSITIVE_ORDER).thenComparing(fallback);
            case CLOSEST -> Comparator.comparing(Entry::claimed)
                .thenComparing(Comparator.comparingDouble(Entry::fraction).reversed()).thenComparing(fallback);
        };
    }
    private static boolean matches(Entry row, RewardMenuState.Filter filter) {
        return switch(filter) { case ALL -> true; case READY -> row.ready(); case UNCLAIMED -> !row.claimed(); case CLAIMED -> row.claimed(); };
    }
    public static RewardMenuState.Group groupFor(RewardDefinition reward) {
        if (!RewardMenuState.PLAYTIME_CATEGORY.equalsIgnoreCase(reward.getCategory())) return RewardMenuState.Group.ALL;
        if (reward.getActions().stream().anyMatch(a -> a.getType() == RewardActionType.COMMAND)) return RewardMenuState.Group.ACCESS;
        if (reward.getCriteria().size() != SINGLE_CRITERION) return RewardMenuState.Group.SPECIAL;
        return switch (reward.getCriteria().getFirst().getType()) {
            case PLAYTIME_TOTAL_MINUTES -> RewardMenuState.Group.TOTAL;
            case PLAYTIME_ACTIVE_MINUTES -> RewardMenuState.Group.ACTIVE;
            default -> RewardMenuState.Group.SPECIAL;
        };
    }
}
