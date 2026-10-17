package org.enthusia.tags.rewards;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RewardBrowserModelTest {
    @Test void deliveryAndMissingEvidenceAreDistinctFromReadyOrZero() {
        assertEquals(RewardMenuModel.DisplayState.NOT_STARTED, row("zero", RewardStatus.LOCKED, false, OptionalLong.of(0)).displayState());
        assertEquals(RewardMenuModel.DisplayState.UNAVAILABLE, row("unknown", RewardStatus.LOCKED, false, OptionalLong.empty()).displayState());
        assertEquals(RewardMenuModel.DisplayState.IN_PROGRESS, row("partial", RewardStatus.LOCKED, false, OptionalLong.of(50)).displayState());
        for (var pair : Map.of(RewardStatus.CLAIM_PENDING, RewardMenuModel.DisplayState.PENDING,
            RewardStatus.ITEM_QUEUED, RewardMenuModel.DisplayState.QUEUED,
            RewardStatus.DELIVERY_FAILED, RewardMenuModel.DisplayState.RETRY,
            RewardStatus.REQUIRES_RECONCILIATION, RewardMenuModel.DisplayState.REVIEW,
            RewardStatus.WITHHELD_NETWORK_LIMIT, RewardMenuModel.DisplayState.WITHHELD).entrySet()) {
            var row = row("attention", pair.getKey(), false, OptionalLong.of(100));
            assertEquals(pair.getValue(), row.displayState());
            assertFalse(row.ready());
            assertEquals(1, RewardMenuModel.summary(List.of(row)).attention());
        }
        assertEquals(RewardMenuModel.DisplayState.UNAVAILABLE, row("integration", RewardStatus.UNLOCKED, false, OptionalLong.empty()).displayState());
        assertTrue(row("ready", RewardStatus.UNLOCKED, true, OptionalLong.of(100)).ready());
    }
    @Test void readyFiltersAndClosestSortNeverPromoteUnavailableOrSettledRewards() {
        var rows = List.of(row("unknown", RewardStatus.LOCKED, false, OptionalLong.empty()),
            row("started", RewardStatus.LOCKED, false, OptionalLong.of(50)),
            row("ready", RewardStatus.UNLOCKED, true, OptionalLong.of(100)),
            row("claimed", RewardStatus.CLAIMED, false, OptionalLong.of(100)));
        assertEquals(List.of("ready"), RewardMenuModel.select(rows, RewardMenuState.ready()).stream().map(RewardMenuModel.Entry::id).toList());
        assertEquals(List.of("ready", "started", "unknown", "claimed"), RewardMenuModel.select(rows,
            RewardMenuState.category("misc").withSort(RewardMenuState.Sort.CLOSEST)).stream().map(RewardMenuModel.Entry::id).toList());
        var summary = RewardMenuModel.summary(rows);
        assertEquals(1, summary.ready()); assertEquals(1, summary.claimed()); assertEquals(1, summary.unavailable());
    }
    @Test void paginationClampsAndFilterChangesResetThePageAndFocus() {
        var values = IntStream.range(0, 45).boxed().toList();
        var page = RewardMenuModel.page(values, 999, 21);
        assertEquals(2, page.index()); assertEquals(3, page.count());
        assertEquals(List.of(42, 43, 44), page.entries());
        assertFalse(page.hasNext()); assertTrue(page.hasPrevious());
        assertEquals(0, RewardMenuModel.page(values, -1, 21).index());
        assertEquals(1, RewardMenuModel.page(List.of(), 9, 21).count());
        var state = RewardMenuState.category("MISC").withPage(2).focus("FOCUS").withFilter(RewardMenuState.Filter.READY);
        assertEquals(0, state.page()); assertNull(state.focusedReward());
        assertEquals(RewardMenuState.Group.ALL, state.withGroup(RewardMenuState.Group.ACTIVE).group());
    }
    private static RewardMenuModel.Entry row(String id, RewardStatus status, boolean claimable, OptionalLong progress) {
        var criterion = new RewardCriterion(RewardCriterionType.CUSTOM_COUNTER, 100, null, "stone_mined", 0, "Stone");
        var reward = new RewardDefinition(id, id, List.of(), null, List.of(criterion), List.of(), "misc");
        return new RewardMenuModel.Entry(reward, new RewardEvaluation(status, Map.of(), false, claimable, "Requirements not reached"),
            List.of(new RewardMenuModel.Reading(criterion, progress)), 0, 0);
    }
}
