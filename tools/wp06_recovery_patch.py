#!/usr/bin/env python3
"""Apply assertion-guarded WP-06 accepted-handoff recovery changes."""

from pathlib import Path


def replace_once(path: str, old: str, new: str, label: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one target, found {count}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


replace_once(
    "src/main/java/org/enthusia/tags/rewards/loreitems/LoreItemHandoffStore.java",
    '''    public synchronized void markRewardFinalized(
        UUID playerId,
        String rewardId,
        long nowEpochMillis) throws SQLException {
        Objects.requireNonNull(playerId, PARAM_PLAYER_ID);
        String reward = canonicalId(rewardId, PARAM_REWARD_ID);
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE lore_item_handoffs
               SET reward_finalized = 1, updated_at = ?
             WHERE player_uuid = ? AND reward_id = ? AND state = 'ACCEPTED'
            """)) {
            statement.setLong(1, nowEpochMillis);
            statement.setString(2, playerId.toString());
            statement.setString(3, reward);
            statement.executeUpdate();
        }
    }
''',
    '''    public synchronized void markRewardFinalized(
        String externalOperationId,
        long nowEpochMillis) throws SQLException {
        String operationId = requiredText(externalOperationId, "externalOperationId");
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE lore_item_handoffs
               SET reward_finalized = 1, updated_at = ?
             WHERE external_operation_id = ? AND state = 'ACCEPTED' AND reward_finalized = 0
            """)) {
            statement.setLong(1, nowEpochMillis);
            statement.setString(2, operationId);
            int updated = statement.executeUpdate();
            if (updated == EXPECTED_SINGLE_ROW) {
                return;
            }
        }
        LoreItemHandoffRecord existing = loadByOperationId(operationId);
        if (existing == null) {
            throw new SQLException("Lore-item handoff operation was not found: " + operationId);
        }
        if (existing.state() != LoreItemHandoffState.ACCEPTED) {
            throw new SQLException("Lore-item handoff is not accepted: " + operationId);
        }
        if (!existing.rewardFinalized()) {
            throw new SQLException("Lore-item handoff finalization marker was not persisted: " + operationId);
        }
    }
''',
    "per-operation handoff finalization",
)

replace_once(
    "src/main/java/org/enthusia/tags/rewards/loreitems/LoreItemRewardRuntime.java",
    '''    public CompletionStage<LoreItemHandoffRecord> requestRetry(
        UUID playerId,
        String rewardId,
        String actionId) {
''',
    '''    public CompletionStage<List<LoreItemHandoffRecord>> acceptedPendingFinalization(int requestedLimit) {
        if (!open.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("LoreItems reward runtime is closed"));
        }
        int limit = Math.max(0, Math.min(requestedLimit, LoreItemHandoffCoordinator.MAX_RETRY_BATCH));
        if (limit == 0) {
            return CompletableFuture.completedFuture(List.of());
        }
        return CompletableFuture.supplyAsync(() -> listAcceptedPendingFinalization(limit), executor);
    }

    public CompletionStage<Void> markRewardFinalized(String externalOperationId) {
        if (!open.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("LoreItems reward runtime is closed"));
        }
        return CompletableFuture.runAsync(() -> {
            try {
                store.markRewardFinalized(externalOperationId, System.currentTimeMillis());
            } catch (SQLException ex) {
                throw new LoreItemHandoffCoordinator.LoreItemHandoffException(
                    "Could not persist LoreItems reward finalization acknowledgement", ex);
            }
        }, executor);
    }

    private List<LoreItemHandoffRecord> listAcceptedPendingFinalization(int limit) {
        try {
            return store.listAcceptedPendingFinalization(limit);
        } catch (SQLException ex) {
            throw new LoreItemHandoffCoordinator.LoreItemHandoffException(
                "Could not load accepted LoreItems handoffs awaiting Tags finalization", ex);
        }
    }

    public CompletionStage<LoreItemHandoffRecord> requestRetry(
        UUID playerId,
        String rewardId,
        String actionId) {
''',
    "runtime accepted-finalization APIs",
)

replace_once(
    "src/main/java/org/enthusia/tags/rewards/RewardStorage.java",
    '''    public RewardStatus reconcileActionNow(UUID playerId, String rewardId, String actionId,
                                           RewardStatus newStatus, String auditReason) throws SQLException {
''',
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

    public RewardStatus reconcileActionNow(UUID playerId, String rewardId, String actionId,
                                           RewardStatus newStatus, String auditReason) throws SQLException {
''',
    "reward-ledger accepted LoreItems recovery",
)

replace_once(
    "src/main/java/org/enthusia/tags/rewards/RewardService.java",
    '''    private static final int MAX_UNLOCK_CHECKS_PER_RUN = 25;
''',
    '''    private static final int MAX_UNLOCK_CHECKS_PER_RUN = 25;
    private static final int MAX_LORE_ITEM_FINALIZATIONS_PER_SWEEP = 50;
''',
    "RewardService finalization bound",
)

replace_once(
    "src/main/java/org/enthusia/tags/rewards/RewardService.java",
    '''    private final AtomicBoolean reloadQueued = new AtomicBoolean(false);
''',
    '''    private final AtomicBoolean reloadQueued = new AtomicBoolean(false);
    private final AtomicBoolean loreItemFinalizationRunning = new AtomicBoolean(false);
''',
    "RewardService finalization guard",
)

replace_once(
    "src/main/java/org/enthusia/tags/rewards/RewardService.java",
    '''    private BukkitTask unlockCheckTask;
''',
    '''    private BukkitTask unlockCheckTask;
    private BukkitTask loreItemFinalizationTask;
''',
    "RewardService finalization task field",
)

replace_once(
    "src/main/java/org/enthusia/tags/rewards/RewardService.java",
    '''        stopUnlockCheckTask();
        if (claimExecutor != null) {
''',
    '''        stopUnlockCheckTask();
        stopLoreItemFinalizationTask();
        if (claimExecutor != null) {
''',
    "RewardService finalization task shutdown",
)

replace_once(
    "src/main/java/org/enthusia/tags/rewards/RewardService.java",
    '''        startFlushTask();
        startGlobalScanTask();
        for (Player player : Bukkit.getOnlinePlayers()) {
''',
    '''        startFlushTask();
        startGlobalScanTask();
        startLoreItemFinalizationTask();
        for (Player player : Bukkit.getOnlinePlayers()) {
''',
    "RewardService finalization task startup",
)

replace_once(
    "src/main/java/org/enthusia/tags/rewards/RewardService.java",
    '''    private void recoverPendingRewards(UUID playerId) {
''',
    '''    private void startLoreItemFinalizationTask() {
        stopLoreItemFinalizationTask();
        if (loreItemRewardRuntime == null || !loreItemRewardRuntime.isOpen()) {
            return;
        }
        loreItemFinalizationTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
            plugin,
            this::queueAcceptedLoreItemFinalization,
            20L,
            100L);
    }

    private void stopLoreItemFinalizationTask() {
        if (loreItemFinalizationTask != null) {
            loreItemFinalizationTask.cancel();
            loreItemFinalizationTask = null;
        }
        loreItemFinalizationRunning.set(false);
    }

    private void queueAcceptedLoreItemFinalization() {
        if (lifecycle.get() != ServiceLifecycle.RUNNING
            || claimExecutor == null
            || claimExecutor.isShutdown()
            || !loreItemFinalizationRunning.compareAndSet(false, true)) {
            return;
        }
        loreItemRewardRuntime.acceptedPendingFinalization(MAX_LORE_ITEM_FINALIZATIONS_PER_SWEEP)
            .whenComplete((records, failure) -> {
                if (failure != null) {
                    loreItemFinalizationRunning.set(false);
                    plugin.getLogger().warning(
                        "Failed to load accepted LoreItems handoffs for Tags finalization: "
                            + safeThrowableMessage(failure));
                    return;
                }
                try {
                    claimExecutor.execute(() -> finalizeAcceptedLoreItemBatch(records));
                } catch (RuntimeException ex) {
                    loreItemFinalizationRunning.set(false);
                    plugin.getLogger().warning(
                        "Could not queue accepted LoreItems finalization: " + ex.getMessage());
                }
            });
    }

    private void finalizeAcceptedLoreItemBatch(List<LoreItemHandoffRecord> records) {
        try {
            if (records == null) {
                return;
            }
            for (LoreItemHandoffRecord record : records) {
                finalizeAcceptedLoreItem(record);
            }
        } finally {
            loreItemFinalizationRunning.set(false);
        }
    }

    private void finalizeAcceptedLoreItem(LoreItemHandoffRecord record) {
        String claimKey = record.playerId() + ":" + record.rewardId();
        if (!inFlightClaims.add(claimKey)) {
            return;
        }
        boolean resumeRemainingActions = false;
        try {
            RewardDefinition reward = rewards.get(record.rewardId());
            RewardAction action = findLoreItemAction(reward, record);
            if (action == null) {
                plugin.getLogger().warning(
                    "Accepted LoreItems handoff needs staff review because its Tags reward/action changed: "
                        + record.externalOperationId());
                return;
            }
            String fingerprint = actionFingerprint(action);
            String evidence = "Recovered accepted LoreItems handoff operation=" + record.externalOperationId()
                + " outcome=" + blankAuditValue(record.lastOutcome())
                + " attempts=" + record.attempts()
                + " error=" + blankAuditValue(record.lastError());
            if (!storage.acceptLoreItemHandoffNow(
                record.playerId(), record.rewardId(), action, fingerprint, evidence)) {
                plugin.getLogger().warning(
                    "Accepted LoreItems handoff could not be reconciled automatically with the Tags action ledger: "
                        + record.externalOperationId());
                return;
            }
            RewardStatus refreshed = refreshOverallAfterReconciliation(record.playerId(), record.rewardId());
            loreItemRewardRuntime.markRewardFinalized(record.externalOperationId())
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
            resumeRemainingActions = refreshed == null;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            plugin.getLogger().warning(
                "Interrupted while acknowledging accepted LoreItems handoff " + record.externalOperationId());
        } catch (ExecutionException | TimeoutException | SQLException ex) {
            plugin.getLogger().warning(
                "Accepted LoreItems handoff remains pending Tags finalization " + record.externalOperationId()
                    + ": " + safeThrowableMessage(ex));
        } finally {
            inFlightClaims.remove(claimKey);
        }
        if (resumeRemainingActions) {
            resumeClaimAfterItem(record.playerId(), record.rewardId());
        }
    }

    private RewardAction findLoreItemAction(RewardDefinition reward, LoreItemHandoffRecord record) {
        if (reward == null) {
            return null;
        }
        return reward.getActions().stream()
            .filter(action -> action.getType() == RewardActionType.LORE_ITEM)
            .filter(action -> action.getActionId().equals(record.actionId()))
            .filter(action -> action.getValue().equals(record.definitionKey()))
            .findFirst()
            .orElse(null);
    }

    private void recoverPendingRewards(UUID playerId) {
''',
    "RewardService accepted handoff finalization bridge",
)

replace_once(
    "src/test/java/org/enthusia/tags/rewards/loreitems/LoreItemHandoffStoreTest.java",
    '''    private static final String ACTION = "action";
    private static final String HOURGLASS = "hourglass";
''',
    '''    private static final String ACTION = "action";
    private static final String REWARD = "reward";
    private static final String HOURGLASS = "hourglass";
''',
    "handoff store test reward constant",
)

for path in [
    "src/test/java/org/enthusia/tags/rewards/loreitems/LoreItemHandoffStoreTest.java",
    "src/test/java/org/enthusia/tags/rewards/loreitems/LoreItemHandoffCoordinatorTest.java",
    "src/test/java/org/enthusia/tags/rewards/loreitems/LoreItemOperationKeyTest.java",
]:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    if path.endswith("LoreItemHandoffCoordinatorTest.java"):
        marker = '    private static final String ACTION = "action";\n'
        if 'private static final String REWARD = "reward";' not in text:
            text = text.replace(marker, marker + '    private static final String REWARD = "reward";\n', 1)
    elif path.endswith("LoreItemOperationKeyTest.java"):
        marker = '    private static final String ACTION = "action";\n'
        if 'private static final String REWARD = "reward";' not in text:
            text = text.replace(marker, marker + '    private static final String REWARD = "reward";\n', 1)
    text = text.replace('"reward"', 'REWARD')
    text = text.replace('private static final String REWARD = REWARD;', 'private static final String REWARD = "reward";')
    file.write_text(text, encoding="utf-8")

replace_once(
    "src/test/java/org/enthusia/tags/rewards/loreitems/LoreItemHandoffStoreTest.java",
    '''    @Test
    void staffRetryCanRequeueReviewButNeverReopensAcceptedOperation() throws Exception {
''',
    '''    @Test
    void finalizationAcknowledgesOnlyTheExactAcceptedOperation() throws Exception {
        try (LoreItemHandoffStore store = new LoreItemHandoffStore(tempDir.resolve("finalization.db"))) {
            LoreItemHandoffRecord first = store.prepare(PLAYER, REWARD, "first", HOURGLASS, 1000L);
            LoreItemHandoffRecord second = store.prepare(PLAYER, REWARD, "second", "star", 1001L);
            store.recordOutcome(first.externalOperationId(), LoreItemHandoffState.ACCEPTED,
                "ACCEPTED_QUEUED", "accepted first", 0L, 1100L);
            store.recordOutcome(second.externalOperationId(), LoreItemHandoffState.ACCEPTED,
                "ACCEPTED_QUEUED", "accepted second", 0L, 1101L);

            assertEquals(2, store.listAcceptedPendingFinalization(10).size());
            store.markRewardFinalized(first.externalOperationId(), 1200L);
            store.markRewardFinalized(first.externalOperationId(), 1201L);

            List<LoreItemHandoffRecord> remaining = store.listAcceptedPendingFinalization(10);
            assertEquals(1, remaining.size());
            assertEquals(second.externalOperationId(), remaining.getFirst().externalOperationId());
            assertEquals(true, store.loadByOperationId(first.externalOperationId()).rewardFinalized());
            assertEquals(false, store.loadByOperationId(second.externalOperationId()).rewardFinalized());
        }
    }

    @Test
    void staffRetryCanRequeueReviewButNeverReopensAcceptedOperation() throws Exception {
''',
    "per-operation finalization persistence test",
)
