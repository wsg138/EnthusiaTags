package org.enthusia.tags.daily;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DailyAnimationPlanTest {
    @Test
    void animationProgressesFromLoadingToReady() {
        DailyAnimationPlan.Frame first = DailyAnimationPlan.frame(0, 20);
        DailyAnimationPlan.Frame middle = DailyAnimationPlan.frame(10, 20);
        DailyAnimationPlan.Frame last = DailyAnimationPlan.frame(19, 20);

        assertAll(
            () -> assertEquals(DailyAnimationPlan.CenterStage.LOADING, first.centerStage()),
            () -> assertEquals(0, first.revealedDays()),
            () -> assertEquals(1, first.progressSegments()),
            () -> assertEquals(DailyAnimationPlan.CenterStage.DAY, middle.centerStage()),
            () -> assertEquals(5, middle.revealedDays()),
            () -> assertTrue(middle.showCurrentStreak()),
            () -> assertTrue(middle.showBestStreak()),
            () -> assertEquals(DailyAnimationPlan.CenterStage.READY, last.centerStage()),
            () -> assertEquals(7, last.revealedDays()),
            () -> assertEquals(7, last.progressSegments()),
            () -> assertTrue(last.finalFrame())
        );
    }

    @Test
    void revealAccentOnlyFiresWhenAnotherDayAppears() {
        DailyAnimationPlan.Frame beforeReveal = DailyAnimationPlan.frame(3, 20);
        DailyAnimationPlan.Frame reveal = DailyAnimationPlan.frame(4, 20);
        DailyAnimationPlan.Frame afterReveal = DailyAnimationPlan.frame(5, 20);

        assertAll(
            () -> assertFalse(beforeReveal.revealAccent()),
            () -> assertTrue(reveal.revealAccent()),
            () -> assertFalse(afterReveal.revealAccent())
        );
    }

    @Test
    void requestedFramesAreClampedToValidSequence() {
        DailyAnimationPlan.Frame negative = DailyAnimationPlan.frame(-10, 18);
        DailyAnimationPlan.Frame excessive = DailyAnimationPlan.frame(500, 18);

        assertAll(
            () -> assertEquals(0, negative.number()),
            () -> assertEquals(17, excessive.number()),
            () -> assertTrue(excessive.finalFrame())
        );
    }

    @Test
    void animationRequiresAtLeastTwoFrames() {
        assertThrows(IllegalArgumentException.class, () -> DailyAnimationPlan.frame(0, 1));
    }
}
