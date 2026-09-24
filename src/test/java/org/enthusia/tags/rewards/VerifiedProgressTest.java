package org.enthusia.tags.rewards;

import org.enthusia.tags.advancements.domain.VerifiedProgress;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VerifiedProgressTest {
    @Test
    void unavailableReadRetainsDisplayButCannotProveCompletion() {
        var retained = VerifiedProgress.resolve(6000L, OptionalLong.empty());
        assertEquals(6000L, retained.displayedValue());
        assertFalse(retained.available());
    }

    @Test
    void unknownDiffersFromAuthoritativeZeroAndRecoveryReplacesStaleValue() {
        assertEquals(new VerifiedProgress(-1, false), VerifiedProgress.resolve(null, OptionalLong.empty()));
        assertEquals(new VerifiedProgress(0, true), VerifiedProgress.resolve(null, OptionalLong.of(0)));
        assertEquals(new VerifiedProgress(40, true), VerifiedProgress.resolve(30L, OptionalLong.of(40)));
        assertEquals(new VerifiedProgress(30, false), VerifiedProgress.resolve(30L, OptionalLong.of(-1)));
    }
}
