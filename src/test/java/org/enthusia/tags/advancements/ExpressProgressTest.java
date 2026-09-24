package org.enthusia.tags.advancements;

import org.enthusia.tags.advancements.domain.ExpressMilestoneProgress;
import org.enthusia.tags.advancements.domain.ExpressMilestoneProgress.Stats;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ExpressProgressTest {
    @Test void historicalCompletionsAreSilent() {
        var progress = new ExpressMilestoneProgress();
        var update = progress.observe(new Stats(50, 10, 10, 10, 64, 1));
        assertEquals(11, update.progress().size());
        assertTrue(update.progress().values().stream().allMatch(value -> value == 1000));
        assertTrue(update.celebrate().isEmpty());
    }

    @Test void liveThresholdsCelebrateOnce() {
        var progress = new ExpressMilestoneProgress();
        progress.observe(new Stats(9, 9, 9, 9, 63, 0));

        var update = progress.observe(new Stats(10, 10, 10, 10, 64, 1));
        assertEquals(Set.of(
            "express/care_package",
            "express/frequent_shipper",
            "express/correspondent",
            "express/parcel_collector",
            "express/avid_reader",
            "express/return_to_sender"
        ), update.celebrate());
        assertTrue(progress.observe(new Stats(10, 10, 10, 10, 64, 1))
            .celebrate().isEmpty());
    }

    @Test void unavailableOrRegressedObservationsRetainProgress() {
        var progress = new ExpressMilestoneProgress();
        var historical = progress.observe(new Stats(25, 4, 3, 2, 32, 0));
        assertEquals(500, historical.progress().get("express/postal_legend"));
        assertEquals(500, historical.progress().get("express/care_package"));

        assertEquals(historical.progress(), progress.observe(null).progress());
        var regression = progress.observe(new Stats(1, 0, 0, 0, 1, 0));
        assertEquals(historical.progress(), regression.progress());
        assertTrue(regression.celebrate().isEmpty());
    }

    @Test void invalidStatisticsAreRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new Stats(-1, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
            () -> new Stats(0, 0, 0, 0, -1, 0));
        assertThrows(IllegalArgumentException.class,
            () -> new Stats(0, 0, 0, 0, 0, -1));
    }
}
