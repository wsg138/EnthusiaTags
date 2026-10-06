package org.enthusia.tags.rewards;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.enthusia.tags.PerformanceMonitor;
import org.enthusia.tags.advancements.domain.GuideGoalPolicy;
import org.enthusia.tags.advancements.domain.GoldEligibility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GuidePreviewTest {
    private static final String ADDRESS = "192.0.2.10";
    private static final RewardAction GOLD = new RewardAction("gold", RewardActionType.MONEY,
        "", 100, "Gold", null, 0, null, List.of(), true);

    @Test void unknownAndSettledGoalsAreNotFreshOpportunities() {
        assertTrue(GuideGoalPolicy.supportsCounter("stone_mined"));
        assertTrue(GuideGoalPolicy.supportsCounter("logs_mined"));
        assertFalse(GuideGoalPolicy.supportsCounter(null));
        assertFalse(GuideGoalPolicy.supportsCounter("diary_edits"));
        assertTrue(GuideGoalPolicy.supports("LOCKED", true));
        assertTrue(GuideGoalPolicy.supports("UNLOCKED", true));
        assertFalse(GuideGoalPolicy.supports("LOCKED", false));
        for (String state : List.of("CLAIMED", "ITEM_QUEUED", "CLAIM_PENDING",
                "REQUIRES_RECONCILIATION", "DELIVERY_FAILED")) {
            assertFalse(GuideGoalPolicy.supports(state, true));
        }
    }

    @Test void previewNeverReservesOrWithholdsAndMatchesOwnership(@TempDir Path directory) throws Exception {
        RewardStorage storage = new RewardStorage(directory.resolve("rewards.db").toFile(), new PerformanceMonitor(null));
        storage.init();
        try {
            UUID owner = UUID.randomUUID(), other = UUID.randomUUID();
            assertEquals(GoldEligibility.ALLOWED, storage.previewGoldActionNow(owner, "goal", GOLD, "fp", ADDRESS));
            assertTrue(storage.listGoldIpClaimsNow(owner, "goal").isEmpty());
            assertTrue(storage.loadActionLedgerNow(owner, "goal").isEmpty());
            assertTrue(storage.reserveGoldActionNow(owner, "goal", GOLD, "fp", ADDRESS));
            assertEquals(GoldEligibility.BLOCKED, storage.previewGoldActionNow(other, "goal", GOLD, "fp", ADDRESS));
            assertTrue(storage.listGoldIpClaimsNow(other, "goal").isEmpty());
            assertTrue(storage.loadActionLedgerNow(other, "goal").isEmpty());
            assertFalse(storage.reserveGoldActionNow(other, "goal", GOLD, "fp", ADDRESS),
                "The real claim must still enforce ownership after a read-only preview");
            storage.addIpBypassPairNow(owner, other);
            assertEquals(GoldEligibility.BLOCKED, storage.previewGoldActionNow(other, "goal", GOLD, "fp", ADDRESS));
            assertEquals(GoldEligibility.UNKNOWN, storage.previewGoldActionNow(owner, "goal", GOLD, "fp", ""));
            storage.saveActionLedgerNow(owner, "goal", GOLD, "changed", RewardStatus.CLAIM_PENDING, null, null);
            storage.saveActionLedgerNow(owner, "goal", GOLD, "changed", RewardStatus.DELIVERY_FAILED, null, null);
            assertEquals(GoldEligibility.UNKNOWN, storage.previewGoldActionNow(owner, "goal", GOLD, "fp", ADDRESS));
        } finally { storage.close(); }
    }

    @Test void legacyOwnershipAndTerminalWithholdingRemainAuthoritative(@TempDir Path directory) throws Exception {
        RewardStorage storage = new RewardStorage(directory.resolve("rewards.db").toFile(), new PerformanceMonitor(null));
        storage.init();
        try {
            UUID owner = UUID.randomUUID(), other = UUID.randomUUID();
            assertEquals(GoldEligibility.ALLOWED, storage.previewGoldActionNow(other, "race", GOLD, "fp", ADDRESS));
            assertTrue(storage.reserveGoldActionNow(owner, "race", GOLD, "fp", ADDRESS));
            assertFalse(storage.reserveGoldActionNow(other, "race", GOLD, "fp", ADDRESS),
                "An allowed preview cannot bypass a competing reservation made before claim");
            assertTrue(storage.reserveIpClaimNow(owner, "goal", ADDRESS));
            assertEquals(GoldEligibility.BLOCKED, storage.previewGoldActionNow(other, "goal", GOLD, "fp", ADDRESS));
            storage.saveActionLedgerNow(other, "goal", GOLD, "fp", RewardStatus.WITHHELD_NETWORK_LIMIT, null, null);
            assertEquals(GoldEligibility.BLOCKED, storage.previewGoldActionNow(other, "goal", GOLD, "fp", "192.0.2.11"));
            assertEquals(1, storage.listIpClaimsNow(owner, "goal").size());
            assertTrue(storage.listGoldIpClaimsNow(other, "goal").isEmpty());
        } finally { storage.close(); }
    }
}
