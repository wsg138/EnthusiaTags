package org.enthusia.tags.advancements;

import org.enthusia.tags.advancements.domain.DiaryMilestoneProgress;
import org.enthusia.tags.advancements.domain.DiaryMilestoneProgress.Stats;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DiaryProgressTest {
    @Test void historicalCompletionsAreSilent() {
        var progress = new DiaryMilestoneProgress();
        var update = progress.observe(new Stats(true, 25, 10, 1, 1, 1));

        assertEquals(8, update.progress().size());
        assertTrue(update.progress().values().stream().allMatch(value -> value == 1000));
        assertTrue(update.celebrate().isEmpty());
    }

    @Test void liveCrossingsCelebrateOnce() {
        var progress = new DiaryMilestoneProgress();
        progress.observe(new Stats(true, 24, 9, 0, 0, 0));

        var update = progress.observe(new Stats(true, 25, 10, 1, 1, 1));
        assertEquals(Set.of(
            "diary/prolific_writer",
            "diary/stubborn",
            "diary/void_walker",
            "diary/nice_try",
            "diary/finders_keepers"
        ), update.celebrate());
        assertTrue(progress.observe(new Stats(true, 25, 10, 1, 1, 1))
            .celebrate().isEmpty());
    }

    @Test void unavailableAndRegressedEvidenceRetainsProgress() {
        var progress = new DiaryMilestoneProgress();
        var historical = progress.observe(new Stats(true, 12, 5, 0, 0, 0));

        assertEquals(historical.progress(), progress.observe(null).progress());
        var regression = progress.observe(new Stats(false, 1, 0, 0, 0, 0));
        assertEquals(historical.progress(), regression.progress());
        assertTrue(regression.celebrate().isEmpty());
    }

    @Test void invalidEvidenceIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new Stats(false, -1, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
            () -> new Stats(false, 0, -1, 0, 0, 0));
    }
}
