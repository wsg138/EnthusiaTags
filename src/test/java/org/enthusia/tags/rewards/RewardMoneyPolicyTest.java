package org.enthusia.tags.rewards;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class RewardMoneyPolicyTest {
    @Test
    void acceptsPositiveAmountsThroughTheMaximum() {
        assertNull(RewardMoneyPolicy.validationError(1D));
        assertNull(RewardMoneyPolicy.validationError(5000D));
    }

    @Test
    void rejectsInvalidOrExcessiveAmounts() {
        assertNotNull(RewardMoneyPolicy.validationError(0D));
        assertNotNull(RewardMoneyPolicy.validationError(-1D));
        assertNotNull(RewardMoneyPolicy.validationError(Double.NaN));
        assertNotNull(RewardMoneyPolicy.validationError(Double.POSITIVE_INFINITY));
        assertNotNull(RewardMoneyPolicy.validationError(5000.01D));
    }
}
