#!/usr/bin/env python3
"""Apply assertion-guarded final WP-06 static-analysis cleanup."""

from pathlib import Path


def replace_once(path: str, old: str, new: str, label: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one target, found {count}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


replace_once(
    "src/main/java/org/enthusia/tags/rewards/RewardService.java",
    '''    private void queueAcceptedLoreItemFinalization() {
''',
    '''    // Paper plugins are not J2EE webapps. This dispatch uses the existing dedicated claim
    // executor so accepted-handoff recovery never blocks or mutates gameplay state on the main thread.
    @SuppressWarnings("PMD.DoNotUseThreads")
    private void queueAcceptedLoreItemFinalization() {
''',
    "Paper claim-executor suppression",
)

replace_once(
    "src/main/java/org/enthusia/tags/rewards/RewardStorage.java",
    '''public final class RewardStorage {
''',
    '''public final class RewardStorage {
    private static final int EXPECTED_SINGLE_ROW = 1;
''',
    "single-row constant",
)

replace_once(
    "src/main/java/org/enthusia/tags/rewards/RewardStorage.java",
    '''    public boolean acceptLoreItemHandoffNow(
        UUID playerId,
        String rewardId,
        RewardAction action,
        String expectedFingerprint,
        String evidence) throws SQLException {
        if (action == null || action.getType() != RewardActionType.LORE_ITEM) {
            throw new IllegalArgumentException("action must be a LORE_ITEM reward action");
        }
        return executeBlockingMeasured("storage.rewards.loreitems-accepted", () -> {
            connection.setAutoCommit(false);
            try {
                ActionLedgerEntry current = selectActionEntry(playerId, rewardId, action.getActionId());
                if (current == null
                    || !RewardActionType.LORE_ITEM.name().equals(current.actionType())
                    || !expectedFingerprint.equals(current.fingerprint())) {
                    connection.rollback();
                    return false;
                }
                if (current.status() == RewardStatus.CLAIMED) {
                    connection.rollback();
                    return true;
                }
                if (current.status() != RewardStatus.CLAIM_PENDING
                    && current.status() != RewardStatus.DELIVERY_FAILED) {
                    connection.rollback();
                    return false;
                }
                try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE reward_action_ledger
                       SET status='CLAIMED', error_message=?, updated_at=?
                     WHERE player_uuid=? AND reward_id=? AND action_id=?
                       AND action_type='LORE_ITEM' AND fingerprint=? AND status=?
                    """)) {
                    update.setString(1, evidence);
                    update.setLong(2, System.currentTimeMillis());
                    update.setString(3, playerId.toString());
                    update.setString(4, rewardId);
                    update.setString(5, action.getActionId());
                    update.setString(6, expectedFingerprint);
                    update.setString(7, current.status().name());
                    if (update.executeUpdate() != 1) {
                        throw new SQLException("Concurrent LoreItems reward recovery change");
                    }
                }
                insertActionHistory(playerId, rewardId, action.getActionId(), current.status(),
                    RewardStatus.CLAIMED, expectedFingerprint, null, evidence);
                connection.commit();
                return true;
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(true);
            }
        });
    }
''',
    '''    public boolean acceptLoreItemHandoffNow(
        UUID playerId,
        String rewardId,
        RewardAction action,
        String expectedFingerprint,
        String evidence) throws SQLException {
        requireLoreItemAction(action);
        return executeBlockingMeasured(
            "storage.rewards.loreitems-accepted",
            () -> acceptLoreItemHandoffDirect(playerId, rewardId, action, expectedFingerprint, evidence));
    }

    private boolean acceptLoreItemHandoffDirect(
        UUID playerId,
        String rewardId,
        RewardAction action,
        String expectedFingerprint,
        String evidence) throws SQLException {
        connection.setAutoCommit(false);
        try {
            ActionLedgerEntry current = selectActionEntry(playerId, rewardId, action.getActionId());
            if (!matchesLoreItemRecoveryIdentity(current, expectedFingerprint)) {
                connection.rollback();
                return false;
            }
            if (current.status() == RewardStatus.CLAIMED) {
                connection.rollback();
                return true;
            }
            if (!isRecoverableLoreItemStatus(current.status())) {
                connection.rollback();
                return false;
            }
            updateAcceptedLoreItemAction(playerId, rewardId, action, expectedFingerprint, evidence, current);
            connection.commit();
            return true;
        } catch (SQLException ex) {
            connection.rollback();
            throw ex;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private void updateAcceptedLoreItemAction(
        UUID playerId,
        String rewardId,
        RewardAction action,
        String expectedFingerprint,
        String evidence,
        ActionLedgerEntry current) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
            UPDATE reward_action_ledger
               SET status='CLAIMED', error_message=?, updated_at=?
             WHERE player_uuid=? AND reward_id=? AND action_id=?
               AND action_type='LORE_ITEM' AND fingerprint=? AND status=?
            """)) {
            update.setString(1, evidence);
            update.setLong(2, System.currentTimeMillis());
            update.setString(3, playerId.toString());
            update.setString(4, rewardId);
            update.setString(5, action.getActionId());
            update.setString(6, expectedFingerprint);
            update.setString(7, current.status().name());
            if (update.executeUpdate() != EXPECTED_SINGLE_ROW) {
                throw new SQLException("Concurrent LoreItems reward recovery change");
            }
        }
        insertActionHistory(playerId, rewardId, action.getActionId(), current.status(),
            RewardStatus.CLAIMED, expectedFingerprint, null, evidence);
    }

    private static void requireLoreItemAction(RewardAction action) {
        if (action == null || action.getType() != RewardActionType.LORE_ITEM) {
            throw new IllegalArgumentException("action must be a LORE_ITEM reward action");
        }
    }

    private static boolean matchesLoreItemRecoveryIdentity(
        ActionLedgerEntry current,
        String expectedFingerprint) {
        return current != null
            && RewardActionType.LORE_ITEM.name().equals(current.actionType())
            && expectedFingerprint.equals(current.fingerprint());
    }

    private static boolean isRecoverableLoreItemStatus(RewardStatus status) {
        return status == RewardStatus.CLAIM_PENDING || status == RewardStatus.DELIVERY_FAILED;
    }
''',
    "RewardStorage accepted-handoff extraction",
)
