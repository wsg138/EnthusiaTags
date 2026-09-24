package org.enthusia.tags.rewards;

import org.enthusia.tags.advancements.domain.CompletionBaseline;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CompletionBaselineTest {
    @Test void firstCompleteReadAndReconnectAreHistorical() {
        var baseline = new CompletionBaseline();
        assertFalse(baseline.isLiveCompletion("old", 1000));
        assertFalse(baseline.isLiveCompletion("old", 1000));
        assertFalse(new CompletionBaseline().isLiveCompletion("old", 1000));
    }
    @Test void unavailableDoesNotCreateAFalseZeroBaseline() {
        var baseline = new CompletionBaseline();
        assertFalse(baseline.isLiveCompletion("old", -1));
        assertFalse(baseline.isLiveCompletion("old", 1000));
    }
    @Test void verifiedIncompleteThenCompleteIsLiveIncludingAfterTransientFailure() {
        var baseline = new CompletionBaseline();
        assertFalse(baseline.isLiveCompletion("new", 999));
        assertFalse(baseline.isLiveCompletion("new", -1));
        assertTrue(baseline.isLiveCompletion("new", 1000));
        assertFalse(baseline.isLiveCompletion("another", 1000));
    }
}
