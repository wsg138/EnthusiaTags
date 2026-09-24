package org.enthusia.tags.advancements.domain;

import java.util.OptionalLong;

/** Retained display progress is never sufficient evidence for a new completion. */
public record VerifiedProgress(long displayedValue, boolean available) {
    public static VerifiedProgress resolve(Long lastGood, OptionalLong reading) {
        if (reading.isPresent() && reading.getAsLong() >= 0) {
            return new VerifiedProgress(reading.getAsLong(), true);
        }
        return new VerifiedProgress(lastGood == null ? -1L : lastGood, false);
    }
}
