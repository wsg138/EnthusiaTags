package org.enthusia.tags.daily;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class DailyMenuModelTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 30);
    private static final List<Double> PAYOUTS = List.of(5D, 10D, 15D, 20D, 30D, 40D, 50D);

    @Test
    void firstVisitShowsOnlyDayOneAsClaimable() {
        DailyMenuModel.View view = build(DailyState.empty(true), DailyMenuModel.LedgerState.NONE);

        assertAll(
            () -> assertEquals(1, view.activeDay()),
            () -> assertEquals(0, view.claimIndex()),
            () -> assertEquals(DailyMenuModel.Status.CLAIMABLE, view.days().get(0).status()),
            () -> assertEquals(DailyMenuModel.Status.UPCOMING, view.days().get(1).status())
        );
    }

    @Test
    void sameDayClaimCannotBeClaimedAgain() {
        DailyMenuModel.View view = build(state(TODAY, 3, 5), DailyMenuModel.LedgerState.DELIVERED);

        assertAll(
            () -> assertEquals(-1, view.claimIndex()),
            () -> assertEquals(DailyMenuModel.Status.CLAIMED, view.days().get(0).status()),
            () -> assertEquals(DailyMenuModel.Status.CLAIMED, view.days().get(2).status()),
            () -> assertEquals(DailyMenuModel.Status.UPCOMING, view.days().get(3).status())
        );
    }

    @Test
    void consecutiveDayMakesNextRewardClaimable() {
        DailyMenuModel.View view = build(state(TODAY.minusDays(1), 3, 5),
            DailyMenuModel.LedgerState.NONE);

        assertAll(
            () -> assertEquals(4, view.activeDay()),
            () -> assertEquals(3, view.claimIndex()),
            () -> assertEquals(DailyMenuModel.Status.CLAIMABLE, view.days().get(3).status())
        );
    }

    @Test
    void missedDayResetsVisibleTrackAndClaimToDayOne() {
        DailyMenuModel.View view = build(state(TODAY.minusDays(2), 10, 10),
            DailyMenuModel.LedgerState.NONE);

        assertAll(
            () -> assertEquals(1, view.activeDay()),
            () -> assertEquals(0, view.claimIndex()),
            () -> assertEquals(DailyMenuModel.Status.CLAIMABLE, view.days().get(0).status()),
            () -> assertFalse(view.days().stream().anyMatch(
                day -> day.status() == DailyMenuModel.Status.CLAIMED))
        );
    }

    @Test
    void failedDepositCanBeRetriedFromSameSlot() {
        DailyMenuModel.View view = build(state(TODAY.minusDays(1), 2, 4),
            DailyMenuModel.LedgerState.FAILED);

        assertAll(
            () -> assertEquals(2, view.claimIndex()),
            () -> assertEquals(DailyMenuModel.Status.RETRY, view.days().get(2).status()),
            () -> assertTrue(view.days().get(2).status().claimable())
        );
    }

    @Test
    void uncertainAndInProgressTransactionsBlockAdditionalDeposits() {
        DailyState state = state(TODAY.minusDays(1), 2, 4);
        DailyMenuModel.View uncertain = build(state, DailyMenuModel.LedgerState.UNCERTAIN);
        DailyMenuModel.View depositing = build(state, DailyMenuModel.LedgerState.DEPOSITING);

        assertAll(
            () -> assertEquals(-1, uncertain.claimIndex()),
            () -> assertEquals(DailyMenuModel.Status.RECONCILIATION,
                uncertain.days().get(2).status()),
            () -> assertEquals(-1, depositing.claimIndex()),
            () -> assertEquals(DailyMenuModel.Status.PROCESSING,
                depositing.days().get(2).status())
        );
    }

    @Test
    void deliveredLedgerWithoutAppliedStateRequiresReconciliation() {
        DailyMenuModel.View view = build(state(TODAY.minusDays(1), 2, 4),
            DailyMenuModel.LedgerState.DELIVERED);

        assertAll(
            () -> assertEquals(-1, view.claimIndex()),
            () -> assertEquals(DailyMenuModel.Status.RECONCILIATION,
                view.days().get(2).status())
        );
    }

    @Test
    void dayEightUsesRollingSeventhSlot() {
        DailyMenuModel.View view = build(state(TODAY.minusDays(1), 7, 7),
            DailyMenuModel.LedgerState.NONE);
        DailyMenuModel.Day rolling = view.days().get(6);

        assertAll(
            () -> assertEquals(8, view.activeDay()),
            () -> assertEquals(6, view.claimIndex()),
            () -> assertTrue(rolling.rolling()),
            () -> assertEquals(8, rolling.number()),
            () -> assertEquals(50D, rolling.amount()),
            () -> assertEquals(DailyMenuModel.Status.CLAIMABLE, rolling.status())
        );
    }

    @Test
    void dayTenShowsClaimedRollingRewardAfterClaim() {
        DailyMenuModel.View view = build(state(TODAY, 10, 10),
            DailyMenuModel.LedgerState.DELIVERED);
        DailyMenuModel.Day rolling = view.days().get(6);

        assertAll(
            () -> assertEquals(-1, view.claimIndex()),
            () -> assertTrue(rolling.rolling()),
            () -> assertEquals(10, rolling.number()),
            () -> assertEquals(DailyMenuModel.Status.CLAIMED, rolling.status())
        );
    }

    private DailyMenuModel.View build(DailyState state, DailyMenuModel.LedgerState ledgerState) {
        return DailyMenuModel.build(state, TODAY, PAYOUTS, ledgerState);
    }

    private DailyState state(LocalDate lastClaim, int current, int best) {
        return new DailyState(lastClaim, current, best, current, 0D, true);
    }
}
