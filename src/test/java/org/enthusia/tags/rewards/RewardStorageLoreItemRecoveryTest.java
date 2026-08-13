package org.enthusia.tags.rewards;

import org.enthusia.tags.PerformanceMonitor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RewardStorageLoreItemRecoveryTest {
    private static final String REWARD = "lore-reward";
    private static final String ACTION = "unique-hourglass";
    private static final String FINGERPRINT = "v1:lore-item:hourglass";

    @Test
    void acceptedHandoffRecoversFailedLoreItemActionOnlyForMatchingFingerprint(@TempDir Path tempDir)
        throws Exception {
        UUID playerId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        RewardAction action = new RewardAction(
            ACTION,
            RewardActionType.LORE_ITEM,
            "hourglass",
            0.0D,
            "Hourglass",
            null,
            0,
            null,
            List.of(),
            true);

        RewardStorage storage = new RewardStorage(
            tempDir.resolve("rewards.db").toFile(),
            new PerformanceMonitor(null));
        storage.init();
        try {
            storage.saveActionLedgerNow(
                playerId, REWARD, action, FINGERPRINT, RewardStatus.CLAIM_PENDING, null, null);
            storage.saveActionLedgerNow(
                playerId, REWARD, action, FINGERPRINT, RewardStatus.DELIVERY_FAILED, null,
                "LoreItems service unavailable");

            assertFalse(storage.acceptLoreItemHandoffNow(
                playerId, REWARD, action, "different-fingerprint", "accepted externally"));
            assertEquals(
                RewardStatus.DELIVERY_FAILED,
                storage.loadActionLedgerNow(playerId, REWARD).get(ACTION).status());

            assertTrue(storage.acceptLoreItemHandoffNow(
                playerId, REWARD, action, FINGERPRINT, "accepted externally"));
            Map<String, RewardStorage.ActionLedgerEntry> recovered =
                storage.loadActionLedgerNow(playerId, REWARD);
            assertEquals(RewardStatus.CLAIMED, recovered.get(ACTION).status());
            assertEquals("accepted externally", recovered.get(ACTION).errorMessage());

            assertTrue(storage.acceptLoreItemHandoffNow(
                playerId, REWARD, action, FINGERPRINT, "replayed accepted handoff"));
            assertEquals(
                RewardStatus.CLAIMED,
                storage.loadActionLedgerNow(playerId, REWARD).get(ACTION).status());
        } finally {
            storage.close();
        }
    }
}
