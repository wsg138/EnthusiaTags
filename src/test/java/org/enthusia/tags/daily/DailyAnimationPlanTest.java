package org.enthusia.tags.daily;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DailyAnimationPlanTest {
    @Test
    void animationProgressesIntoAnExactFinalMenuFrame() {
        DailyAnimationPlan.Frame first = DailyAnimationPlan.frame(0, 20);
        DailyAnimationPlan.Frame middle = DailyAnimationPlan.frame(10, 20);
        DailyAnimationPlan.Frame last = DailyAnimationPlan.frame(19, 20);

        assertAll(
            () -> assertEquals(DailyAnimationPlan.CenterStage.LOADING, first.centerStage()),
            () -> assertEquals(0, first.borderHead()),
            () -> assertEquals(0, first.revealedDays()),
            () -> assertEquals(1, first.progressSegments()),
            () -> assertEquals(DailyAnimationPlan.CenterStage.DAY, middle.centerStage()),
            () -> assertTrue(middle.revealedDays() > 0),
            () -> assertTrue(middle.showCurrentStreak()),
            () -> assertTrue(middle.showBestStreak()),
            () -> assertEquals(DailyAnimationPlan.CenterStage.READY, last.centerStage()),
            () -> assertEquals(19, last.borderHead()),
            () -> assertEquals(7, last.revealedDays()),
            () -> assertEquals(7, last.progressSegments()),
            () -> assertTrue(last.finalFrame())
        );
    }

    @Test
    void minimumConfiguredAnimationStillRevealsAllRewards() {
        DailyAnimationPlan.Frame finalFrame = DailyAnimationPlan.frame(13, 14);
        assertAll(
            () -> assertEquals(7, finalFrame.revealedDays()),
            () -> assertEquals(7, finalFrame.progressSegments()),
            () -> assertEquals(19, finalFrame.borderHead()),
            () -> assertTrue(finalFrame.finalFrame())
        );
    }

    @Test
    void revealAccentOnlyFiresWhenAnotherDayAppears() {
        boolean foundAccent = false;
        for (int frame = 1; frame < 19; frame++) {
            DailyAnimationPlan.Frame current = DailyAnimationPlan.frame(frame, 20);
            DailyAnimationPlan.Frame previous = DailyAnimationPlan.frame(frame - 1, 20);
            if (current.revealedDays() > previous.revealedDays()) {
                foundAccent = true;
                assertTrue(current.revealAccent());
            } else {
                assertFalse(current.revealAccent());
            }
        }
        assertTrue(foundAccent);
    }

    @Test
    void requestedFramesAreClampedToValidSequence() {
        DailyAnimationPlan.Frame negative = DailyAnimationPlan.frame(-10, 18);
        DailyAnimationPlan.Frame excessive = DailyAnimationPlan.frame(500, 18);
        assertAll(
            () -> assertEquals(0, negative.number()),
            () -> assertEquals(17, excessive.number()),
            () -> assertEquals(19, excessive.borderHead()),
            () -> assertTrue(excessive.finalFrame())
        );
    }

    @Test
    void animationRequiresAtLeastTwoFrames() {
        assertThrows(IllegalArgumentException.class, () -> DailyAnimationPlan.frame(0, 1));
    }
}
