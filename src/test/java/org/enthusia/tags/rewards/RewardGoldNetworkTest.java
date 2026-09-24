package org.enthusia.tags.rewards;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Logger;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.concurrent.TimeUnit;
import org.enthusia.tags.advancements.domain.GoldRewardPolicy;
import org.enthusia.tags.PerformanceMonitor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RewardGoldNetworkTest {
    private static final String ADDRESS = "192.0.2.10";
    private static final RewardAction GOLD = new RewardAction("gold", RewardActionType.MONEY,
        "", 100D, "Gold", null, 0, null, java.util.List.of(), true);

    private RewardService claimService(RewardStorage storage, UUID player) {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("reward-conflict-test"));
        RewardService service = spy(new RewardService(plugin, null, null,
            new PerformanceMonitor(null), storage));
        doReturn(true).when(service).isAvailable();
        service.preloadPlayerBlocking(player);
        return service;
    }

    private RewardStorage open(Path directory) throws Exception {
        RewardStorage storage = new RewardStorage(directory.resolve("rewards.db").toFile(),
            new PerformanceMonitor(null));
        storage.init();
        return storage;
    }

    @Test
    void onlyTypedGoldIsLimited() {
        assertTrue(GoldRewardPolicy.isNetworkLimited("MONEY", null));
        assertTrue(GoldRewardPolicy.isNetworkLimited("ITEM", "RAW_GOLD"));
        assertTrue(GoldRewardPolicy.isNetworkLimited("ITEM", "RAW_GOLD_BLOCK"));
        assertFalse(GoldRewardPolicy.isNetworkLimited("ITEM", "DIAMOND"));
        assertFalse(GoldRewardPolicy.isNetworkLimited("TAG", null));
        assertFalse(GoldRewardPolicy.isNetworkLimited("COMMAND", null));
        assertFalse(GoldRewardPolicy.isNetworkLimited("LORE_ITEM", null));
    }

    @Test
    void fingerprintConflictStopsAllActionsBeforeReservation(@TempDir Path directory) throws Exception {
        RewardStorage storage = open(directory);
        try {
            UUID player = UUID.randomUUID();
            RewardAction earlier = new RewardAction("earlier", RewardActionType.MONEY,
                "", 50D, "Earlier gold", null, 0, null, java.util.List.of(), true);
            storage.saveActionLedgerNow(player, "reward", GOLD, "old-definition",
                RewardStatus.CLAIM_PENDING, null, null);
            storage.saveActionLedgerNow(player, "reward", GOLD, "old-definition",
                RewardStatus.DELIVERY_FAILED, null, "Prior definite failure");
            RewardService service = claimService(storage, player);
            for (var actions : java.util.List.of(java.util.List.of(GOLD), java.util.List.of(earlier, GOLD))) {
                RewardDefinition reward = new RewardDefinition("reward", "Existing", java.util.List.of(),
                    null, java.util.List.of(), actions, "playtime");
                assertEquals(RewardClaimResult.RECONCILIATION_REQUIRED,
                    service.claimInternal(player, "Tester", reward, ADDRESS));
                RewardPlayerState persisted = new RewardPlayerState();
                persisted.hydrate(storage.loadNow(player));
                assertEquals(RewardStatus.REQUIRES_RECONCILIATION.name(), persisted.getState("reward-delivery:reward"));
                assertTrue(storage.listGoldIpClaimsNow(player, "reward").isEmpty());
                assertEquals(java.util.Set.of("gold"), storage.loadActionLedgerNow(player, "reward").keySet());
                assertEquals("old-definition", storage.loadActionLedgerNow(player, "reward").get("gold").fingerprint());
            }
        } finally { storage.close(); }
    }

    @Test
    void sameNetworkDenialSurvivesRestartAndAddressChange(@TempDir Path directory) throws Exception {
        UUID main = UUID.randomUUID();
        UUID alt = UUID.randomUUID();
        RewardStorage storage = open(directory);
        try {
            assertTrue(storage.reserveGoldActionNow(main, "reward", GOLD, "fingerprint", ADDRESS));
            assertTrue(storage.reserveGoldActionNow(main, "reward", GOLD, "fingerprint", ADDRESS));
            assertFalse(storage.reserveGoldActionNow(alt, "reward", GOLD, "fingerprint", ADDRESS));
            assertEquals(RewardStatus.WITHHELD_NETWORK_LIMIT,
                storage.loadActionLedgerNow(alt, "reward").get("gold").status());
            assertTrue(storage.loadNow(alt).claims().isEmpty(), "Gold denial must not finalize the whole reward");
            assertTrue(storage.listIpClaimsNow(main, "reward").isEmpty(), "Must not write legacy reservations");
            assertEquals(1, storage.listGoldIpClaimsNow(main, "reward").size());
            assertTrue(storage.listGoldIpClaimsNow(alt, "reward").isEmpty());
            assertTrue(storage.listKnownRewardIdsNow(main).contains("reward"));
        } finally { storage.close(); }
        storage = open(directory);
        try {
            assertFalse(storage.reserveGoldActionNow(alt, "reward", GOLD, "fingerprint", "192.0.2.99"));
            assertEquals(1, storage.loadActionHistoryNow(alt, "reward", 10).size());
            assertTrue(storage.listGoldIpClaimsNow(alt, "reward").isEmpty(), "Denied retries must roll back new IP reservations");
            assertTrue(storage.reserveGoldActionNow(alt, "different_reward", GOLD, "fingerprint", ADDRESS));
        } finally { storage.close(); }
    }

    @Test
    void legacyEvidenceBlocksOnlyGoldAndDoesNotHonorLegacyBypass(@TempDir Path directory) throws Exception {
        RewardStorage storage = open(directory);
        UUID main = UUID.randomUUID();
        UUID alt = UUID.randomUUID();
        try {
            storage.reserveIpClaimNow(main, "reward", ADDRESS);
            storage.addIpBypassPairNow(main, alt);
            var original = storage.listIpClaimsNow(main, "reward");
            assertFalse(storage.reserveGoldActionNow(alt, "reward", GOLD, "fingerprint", ADDRESS));
            assertEquals(original, storage.listIpClaimsNow(main, "reward"));
            RewardAction tag = new RewardAction("tag", RewardActionType.TAG, "veteran", 0,
                "Veteran", null, 0, null, java.util.List.of(), true);
            storage.saveActionLedgerNow(alt, "reward", tag, "tag-fingerprint", RewardStatus.CLAIM_PENDING, null, null);
            storage.saveActionLedgerNow(alt, "reward", tag, "tag-fingerprint", RewardStatus.CLAIMED, null, null);
            storage.finalizeRewardNow(alt, "reward");
            assertTrue(storage.loadNow(alt).claims().contains("reward"));
            assertEquals(RewardStatus.CLAIMED, storage.loadActionLedgerNow(alt, "reward").get("tag").status());
            assertEquals(RewardStatus.WITHHELD_NETWORK_LIMIT,
                storage.loadActionLedgerNow(alt, "reward").get("gold").status());
        } finally { storage.close(); }
    }

    @Test
    void concurrentAccountsAcrossConnectionsHaveOneWinner(@TempDir Path directory) throws Exception {
        RewardStorage first = open(directory);
        RewardStorage second = open(directory);
        try {
            var a = CompletableFuture.supplyAsync(() -> reserve(first, UUID.randomUUID()));
            var b = CompletableFuture.supplyAsync(() -> reserve(second, UUID.randomUUID()));
            assertNotEquals(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS));
        } finally { first.close(); second.close(); }
    }

    private boolean reserve(RewardStorage storage, UUID player) {
        try { return storage.reserveGoldActionNow(player, "reward", GOLD, "fingerprint", ADDRESS); }
        catch (SQLException ex) { throw new CompletionException(ex); }
    }

    @Test
    void claimOrchestratorFinalizesAltInsteadOfRejectingWholeReward(@TempDir Path directory) throws Exception {
        RewardStorage storage = open(directory);
        UUID main = UUID.randomUUID();
        UUID alt = UUID.randomUUID();
        try {
            storage.reserveIpClaimNow(main, "reward", ADDRESS);
            RewardService service = claimService(storage, alt);
            RewardDefinition reward = new RewardDefinition("reward", "Existing challenge", java.util.List.of(),
                null, java.util.List.of(), java.util.List.of(GOLD), "playtime");
            assertEquals(RewardClaimResult.SUCCESS_GOLD_WITHHELD, service.claimInternal(alt, "Alt", reward, ADDRESS));
            assertTrue(storage.loadNow(alt).claims().contains("reward"));
            assertEquals(RewardClaimResult.ALREADY_CLAIMED, service.claimInternal(alt, "Alt", reward, "192.0.2.99"));
            assertEquals(RewardStatus.WITHHELD_NETWORK_LIMIT,
                storage.loadActionLedgerNow(alt, "reward").get("gold").status());
        } finally { storage.close(); }
    }

    @Test
    void allGoldComponentsShareOneAccountOwnerAndWithholdingCannotBecomePending(@TempDir Path directory) throws Exception {
        RewardStorage storage = open(directory);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        RewardAction bonus = new RewardAction("bonus-gold", RewardActionType.MONEY,
            "", 50D, "Gold bonus", null, 0, null, java.util.List.of(), true);
        try {
            assertTrue(storage.reserveGoldActionNow(first, "reward", GOLD, "fp", ADDRESS));
            assertFalse(storage.reserveGoldActionNow(second, "reward", GOLD, "fp", ADDRESS));
            assertTrue(storage.reserveGoldActionNow(first, "reward", bonus, "bonus", ADDRESS));
            assertFalse(storage.reserveGoldActionNow(second, "reward", bonus, "bonus", ADDRESS));
            assertThrows(SQLException.class, () -> storage.saveActionLedgerNow(second, "reward", GOLD,
                "fp", RewardStatus.CLAIM_PENDING, null, null));
            assertThrows(SQLException.class, () -> storage.reserveGoldActionNow(second, "reward", GOLD,
                "changed-fingerprint", "192.0.2.99"));
            assertThrows(SQLException.class, () -> storage.reconcileActionNow(second, "reward", "gold",
                RewardStatus.DELIVERY_FAILED, "test retry"));
        } finally { storage.close(); }
    }

    @Test
    void unavailableVerificationDoesNotWritePermanentDenial(@TempDir Path directory) throws Exception {
        RewardStorage storage = open(directory);
        UUID player = UUID.randomUUID();
        try {
            assertThrows(SQLException.class, () -> storage.reserveGoldActionNow(player, "reward", GOLD, "fp", ""));
            assertTrue(storage.loadActionLedgerNow(player, "reward").isEmpty());
            assertTrue(storage.reserveGoldActionNow(player, "reward", GOLD, "fp", ADDRESS));
        } finally { storage.close(); }
        assertThrows(SQLException.class, () -> storage.reserveGoldActionNow(player, "reward", GOLD, "fp", ADDRESS));
    }

    @Test
    void pendingAndDeliveredActionsCannotBeReservedForReplay(@TempDir Path directory) throws Exception {
        RewardStorage storage = open(directory);
        UUID player = UUID.randomUUID();
        try {
            assertTrue(storage.reserveGoldActionNow(player, "reward", GOLD, "fp", ADDRESS));
            storage.saveActionLedgerNow(player, "reward", GOLD, "fp", RewardStatus.CLAIM_PENDING, null, null);
            assertThrows(SQLException.class,
                () -> storage.reserveGoldActionNow(player, "reward", GOLD, "fp", ADDRESS));
            storage.saveActionLedgerNow(player, "reward", GOLD, "fp", RewardStatus.CLAIMED, null, null);
            assertThrows(SQLException.class,
                () -> storage.reserveGoldActionNow(player, "reward", GOLD, "fp", "192.0.2.99"));
        } finally { storage.close(); }
    }

    @Test
    void missingAddressCannotAuthorizeCurrency(@TempDir Path directory) throws Exception {
        RewardStorage storage = new RewardStorage(directory.resolve("rewards.db").toFile(),
            new PerformanceMonitor(null));
        storage.init();
        try {
            assertThrows(SQLException.class,
                () -> storage.reserveIpClaimNow(UUID.randomUUID(), "reward", ""));
        } finally {
            storage.close();
        }
    }
}
