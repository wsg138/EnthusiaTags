package org.enthusia.tags.rewards;

import org.enthusia.tags.advancements.domain.CompletionBaseline;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReviewRegressionTest {
    @Test void duplicateHourAndMinuteTokensAreNotDropped() {
        assertEquals(183, PlaytimeTextParser.parseTokenizedPlaytimeMinutes("1h 2h 1m 2m"));
    }
    @Test void repeatedSecondsAreCombinedBeforeRounding() {
        assertEquals(1, PlaytimeTextParser.parseTokenizedPlaytimeMinutes("30s 30s"));
    }
    @Test void completionTransitionIsConsumedExactlyOnce() {
        var baseline = new CompletionBaseline();
        assertFalse(baseline.isLiveCompletion("new", 999));
        assertTrue(baseline.isLiveCompletion("new", 1000));
        assertFalse(baseline.isLiveCompletion("new", 1000));
    }
}
