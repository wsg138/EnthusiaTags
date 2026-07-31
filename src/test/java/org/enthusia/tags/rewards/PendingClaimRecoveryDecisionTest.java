package org.enthusia.tags.rewards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PendingClaimRecoveryDecisionTest {
    @Test
    void resumesOnlyWhenNoActionIsAmbiguous() {
        assertEquals(PendingClaimRecoveryDecision.RESUME,
            PendingClaimRecoveryDecision.from(null));
        assertEquals(PendingClaimRecoveryDecision.RESUME,
            PendingClaimRecoveryDecision.from(RewardStatus.DELIVERY_FAILED));
    }

    @Test
    void mapsDurableRecoveryStatesToPlayerResults() {
        assertEquals(RewardClaimResult.SUCCESS,
            PendingClaimRecoveryDecision.from(RewardStatus.CLAIMED).result());
        assertEquals(RewardClaimResult.ITEM_QUEUED,
            PendingClaimRecoveryDecision.from(RewardStatus.ITEM_QUEUED).result());
        assertEquals(RewardClaimResult.RECONCILIATION_REQUIRED,
            PendingClaimRecoveryDecision.from(RewardStatus.REQUIRES_RECONCILIATION).result());
        assertEquals(RewardClaimResult.CLAIM_IN_PROGRESS,
            PendingClaimRecoveryDecision.from(RewardStatus.CLAIM_PENDING).result());
    }

    @Test
    void resumeMustRunTheClaimFlowInsteadOfReturningAResult() {
        assertThrows(IllegalStateException.class,
            () -> PendingClaimRecoveryDecision.RESUME.result());
    }
}
