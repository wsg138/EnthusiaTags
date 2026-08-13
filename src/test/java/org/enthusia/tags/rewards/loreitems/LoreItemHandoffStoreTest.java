package org.enthusia.tags.rewards.loreitems;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LoreItemHandoffStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void intentAndOperationIdentitySurviveStoreRestart() throws Exception {
        UUID player = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        Path database = tempDir.resolve("lore-handoffs.db");
        String operation;

        try (LoreItemHandoffStore store = new LoreItemHandoffStore(database)) {
            LoreItemHandoffRecord prepared = store.prepare(player, "Reward-One", "Action-One", "hourglass", 1000L);
            operation = prepared.externalOperationId();
            assertEquals(LoreItemHandoffState.PENDING, prepared.state());
            assertEquals(0, prepared.attempts());
        }

        try (LoreItemHandoffStore restarted = new LoreItemHandoffStore(database)) {
            LoreItemHandoffRecord replay = restarted.prepare(player, "reward-one", "action-one", "hourglass", 2000L);
            assertEquals(operation, replay.externalOperationId());
            assertEquals(1000L, replay.createdAtEpochMillis());
        }
    }

    @Test
    void changedDefinitionForExistingClaimRequiresReviewInsteadOfNewIdentity() throws Exception {
        UUID player = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        try (LoreItemHandoffStore store = new LoreItemHandoffStore(tempDir.resolve("definition-change.db"))) {
            store.prepare(player, "reward", "action", "hourglass", 1000L);

            assertThrows(SQLException.class,
                () -> store.prepare(player, "reward", "action", "dragon-breath", 2000L));
        }
    }

    @Test
    void retryQueueIsOrderedBoundedAndOnlyReturnsDueRows() throws Exception {
        UUID firstPlayer = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        UUID secondPlayer = UUID.fromString("11111111-2222-3333-4444-555555555555");
        try (LoreItemHandoffStore store = new LoreItemHandoffStore(tempDir.resolve("retry.db"))) {
            LoreItemHandoffRecord first = store.prepare(firstPlayer, "reward-a", "action", "hourglass", 1000L);
            LoreItemHandoffRecord second = store.prepare(secondPlayer, "reward-b", "action", "star", 1100L);
            assertNotEquals(first.externalOperationId(), second.externalOperationId());

            store.recordOutcome(first.externalOperationId(), LoreItemHandoffState.RETRY,
                "SERVICE_UNAVAILABLE", "reload", 5000L, 1200L);
            store.recordOutcome(second.externalOperationId(), LoreItemHandoffState.ACCEPTED,
                "ACCEPTED_QUEUED", "accepted", 0L, 1300L);

            assertEquals(List.of(), store.listDue(4999L, 10));
            List<LoreItemHandoffRecord> due = store.listDue(5000L, 10);
            assertEquals(1, due.size());
            assertEquals(first.externalOperationId(), due.getFirst().externalOperationId());
            assertEquals(1, due.getFirst().attempts());
        }
    }
}
