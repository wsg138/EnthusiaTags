package org.enthusia.tags.advancements;

import org.enthusia.tags.advancements.domain.ReputationMilestoneProgress;
import org.enthusia.tags.advancements.domain.ReputationMilestoneProgress.Stats;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ReputationProgressTest {
    @Test void historicalCompletionsAreSilent() {
        var progress = new ReputationMilestoneProgress();
        var update = progress.observe(new Stats(true, 20, -25, true, 5, 5, 5, 5));
        assertEquals(10, update.progress().size());
        assertTrue(update.progress().values().stream().allMatch(value -> value == 1000));
        assertTrue(update.celebrate().isEmpty());
    }

    @Test void liveCrossingsCelebrateOnlyOnce() {
        var progress = new ReputationMilestoneProgress();
        progress.observe(new Stats(false, 9, -9, false, 4, 4, 4, 4));
        var update = progress.observe(new Stats(true, 10, -10, false, 5, 4, 4, 4));
        assertEquals(Set.of(
            "reputation/a_good_word",
            "reputation/well_regarded",
            "reputation/bad_reputation",
            "reputation/kind_soul"
        ), update.celebrate());
        assertTrue(progress.observe(new Stats(true, 10, -10, false, 5, 4, 4, 4))
            .celebrate().isEmpty());
    }
    @Test void progressIsMonotonicAcrossRegressionAndUnavailableReads() {
        var progress = new ReputationMilestoneProgress();
        var historical = progress.observe(new Stats(true, 15, -12, false, 5, 2, 1, 0));
        assertEquals(750, historical.progress().get("reputation/pillar_of_the_community"));
        assertEquals(480, historical.progress().get("reputation/public_enemy"));
        assertEquals(historical.progress(), progress.observe(null).progress());

        var regression = progress.observe(new Stats(false, 2, -1, false, 0, 0, 0, 0));
        assertEquals(historical.progress(), regression.progress());
        assertTrue(regression.celebrate().isEmpty());
    }

    @Test void redemptionAndSevereThresholdsUseDurableEvidence() {
        var progress = new ReputationMilestoneProgress();
        progress.observe(new Stats(false, 0, -12, false, 0, 0, 0, 0));

        assertEquals(Set.of("reputation/public_enemy", "reputation/redemption_arc"),
            progress.observe(new Stats(false, 0, -25, true, 0, 0, 0, 0)).celebrate());
    }

    @Test void invalidEvidenceIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new Stats(false, -1, 0, false, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
            () -> new Stats(false, 0, 1, false, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
            () -> new Stats(false, 0, 0, false, -1, 0, 0, 0));
    }
}
