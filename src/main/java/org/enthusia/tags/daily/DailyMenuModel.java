package org.enthusia.tags.daily;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class DailyMenuModel {
    static final int TRACK_LENGTH = 7;

    private DailyMenuModel() {
    }

    static View build(DailyState state, LocalDate currentDate, List<Double> payouts,
                      LedgerState ledgerState) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(currentDate, "currentDate");
        Objects.requireNonNull(payouts, "payouts");
        Objects.requireNonNull(ledgerState, "ledgerState");

        int nextDay = DailyRules.nextStreak(state.lastClaimDate(), currentDate, state.currentStreak());
        int activeDay = nextDay == 0 ? Math.max(1, state.currentStreak()) : nextDay;
        int completedDays = completedDays(state, currentDate);
        List<Day> days = new ArrayList<>(TRACK_LENGTH);
        int claimIndex = -1;

        for (int index = 0; index < TRACK_LENGTH; index++) {
            boolean rolling = index == TRACK_LENGTH - 1 && activeDay > TRACK_LENGTH;
            int dayNumber = rolling ? activeDay : index + 1;
            Status status = statusFor(dayNumber, nextDay, completedDays, ledgerState);
            if (status.claimable()) {
                claimIndex = index;
            }
            days.add(new Day(dayNumber, DailyRules.payout(dayNumber, payouts), rolling, status));
        }

        return new View(completedDays, state.highestStreak(), activeDay, claimIndex, days);
    }

    static LedgerState ledgerState(DailyStorage.Transaction transaction) {
        if (transaction == null) {
            return LedgerState.NONE;
        }
        return switch (transaction.status()) {
            case PREPARED -> LedgerState.PREPARED;
            case DEPOSITING -> LedgerState.DEPOSITING;
            case DELIVERED -> LedgerState.DELIVERED;
            case FAILED -> LedgerState.FAILED;
            case UNCERTAIN -> LedgerState.UNCERTAIN;
            case RECONCILED -> LedgerState.RECONCILED;
            case CANCELLED -> LedgerState.CANCELLED;
        };
    }

    private static int completedDays(DailyState state, LocalDate currentDate) {
        LocalDate lastClaim = state.lastClaimDate();
        if (lastClaim == null) {
            return 0;
        }
        boolean streakStillActive = lastClaim.equals(currentDate)
            || lastClaim.plusDays(1).equals(currentDate);
        return streakStillActive ? Math.max(0, state.currentStreak()) : 0;
    }

    private static Status statusFor(int dayNumber, int nextDay, int completedDays,
                                    LedgerState ledgerState) {
        if (dayNumber <= completedDays) {
            return Status.CLAIMED;
        }
        if (nextDay <= 0 || dayNumber != nextDay) {
            return Status.UPCOMING;
        }
        return switch (ledgerState) {
            case NONE -> Status.CLAIMABLE;
            case FAILED -> Status.RETRY;
            case PREPARED, DEPOSITING, RECONCILED -> Status.PROCESSING;
            case UNCERTAIN, DELIVERED, CANCELLED -> Status.RECONCILIATION;
        };
    }

    record View(int currentStreak, int bestStreak, int activeDay, int claimIndex, List<Day> days) {
        View {
            days = List.copyOf(days);
            if (days.size() != TRACK_LENGTH) {
                throw new IllegalArgumentException("Daily view must contain exactly seven days");
            }
            if (claimIndex < -1 || claimIndex >= TRACK_LENGTH) {
                throw new IllegalArgumentException("Invalid daily claim index");
            }
        }
    }

    record Day(int number, double amount, boolean rolling, Status status) {
        Day {
            if (number < 1) {
                throw new IllegalArgumentException("Daily reward day must be positive");
            }
            Objects.requireNonNull(status, "status");
        }
    }

    enum LedgerState {
        NONE,
        PREPARED,
        DEPOSITING,
        DELIVERED,
        FAILED,
        UNCERTAIN,
        RECONCILED,
        CANCELLED
    }

    enum Status {
        CLAIMED(false),
        CLAIMABLE(true),
        UPCOMING(false),
        RETRY(true),
        PROCESSING(false),
        RECONCILIATION(false);

        private final boolean claimable;

        Status(boolean claimable) {
            this.claimable = claimable;
        }

        boolean claimable() {
            return claimable;
        }
    }
}
