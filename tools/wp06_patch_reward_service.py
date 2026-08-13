#!/usr/bin/env python3
"""One-shot exact patch for WP-06 RewardService static-analysis findings."""

from pathlib import Path

PATH = Path("src/main/java/org/enthusia/tags/rewards/RewardService.java")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one target, found {count}")
    return text.replace(old, new, 1)


def main() -> None:
    text = PATH.read_text(encoding="utf-8")

    old_delivery = '''    private LoreItemDeliveryAttempt deliverLoreItem(
        UUID playerId,
        String rewardId,
        RewardAction action) {
        if (loreItemRewardRuntime == null || !loreItemRewardRuntime.isOpen()) {
            return new LoreItemDeliveryAttempt(false, false,
                "Lore-item runtime is unavailable; handoff was not attempted");
        }
        try {
            LoreItemHandoffRecord record = loreItemRewardRuntime
                .handoff(playerId, rewardId, action.getActionId(), action.getValue())
                .toCompletableFuture()
                .get(12, TimeUnit.SECONDS);
            if (record == null) {
                return new LoreItemDeliveryAttempt(false, false,
                    "Lore-item runtime completed without a durable handoff record");
            }
            String evidence = "operation=" + record.externalOperationId()
                + " state=" + record.state()
                + " outcome=" + blankAuditValue(record.lastOutcome())
                + " attempts=" + record.attempts()
                + " error=" + blankAuditValue(record.lastError());
            if (record.state() == LoreItemHandoffState.ACCEPTED) {
                return new LoreItemDeliveryAttempt(true, false, evidence);
            }
            if (record.state() == LoreItemHandoffState.REVIEW) {
                return new LoreItemDeliveryAttempt(false, true, evidence);
            }
            return new LoreItemDeliveryAttempt(false, false, evidence);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new LoreItemDeliveryAttempt(false, false,
                "Interrupted waiting for durable LoreItems handoff; retry uses the same operation identity");
        } catch (ExecutionException | TimeoutException ex) {
            Throwable cause = ex instanceof ExecutionException && ex.getCause() != null ? ex.getCause() : ex;
            return new LoreItemDeliveryAttempt(false, false,
                "LoreItems handoff did not complete in the claim worker: " + safeThrowableMessage(cause));
        }
    }
'''
    new_delivery = '''    private LoreItemDeliveryAttempt deliverLoreItem(
        UUID playerId,
        String rewardId,
        RewardAction action) {
        if (loreItemRewardRuntime == null || !loreItemRewardRuntime.isOpen()) {
            return new LoreItemDeliveryAttempt(false, false,
                "Lore-item runtime is unavailable; handoff was not attempted");
        }
        try {
            LoreItemHandoffRecord record = loreItemRewardRuntime
                .handoff(playerId, rewardId, action.getActionId(), action.getValue())
                .toCompletableFuture()
                .get(12, TimeUnit.SECONDS);
            return mapLoreItemHandoff(record);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new LoreItemDeliveryAttempt(false, false,
                "Interrupted waiting for durable LoreItems handoff; retry uses the same operation identity");
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            return retryableLoreItemFailure(cause);
        } catch (TimeoutException ex) {
            return retryableLoreItemFailure(ex);
        }
    }

    private LoreItemDeliveryAttempt mapLoreItemHandoff(LoreItemHandoffRecord record) {
        if (record == null) {
            return new LoreItemDeliveryAttempt(false, false,
                "Lore-item runtime completed without a durable handoff record");
        }
        String evidence = "operation=" + record.externalOperationId()
            + " state=" + record.state()
            + " outcome=" + blankAuditValue(record.lastOutcome())
            + " attempts=" + record.attempts()
            + " error=" + blankAuditValue(record.lastError());
        return switch (record.state()) {
            case ACCEPTED -> new LoreItemDeliveryAttempt(true, false, evidence);
            case REVIEW -> new LoreItemDeliveryAttempt(false, true, evidence);
            default -> new LoreItemDeliveryAttempt(false, false, evidence);
        };
    }

    private LoreItemDeliveryAttempt retryableLoreItemFailure(Throwable cause) {
        return new LoreItemDeliveryAttempt(false, false,
            "LoreItems handoff did not complete in the claim worker: " + safeThrowableMessage(cause));
    }
'''
    text = replace_once(text, old_delivery, new_delivery, "deliverLoreItem")

    old_cause_loop = '''        while (current.getCause() != null && current != current.getCause()) {
            current = current.getCause();
        }
'''
    new_cause_loop = '''        while (current.getCause() != null && !java.util.Objects.equals(current, current.getCause())) {
            current = current.getCause();
        }
'''
    text = replace_once(text, old_cause_loop, new_cause_loop, "safeThrowableMessage")

    old_validation_call = '''        boolean valid = validateCriterionSource(resolved.sourceType(), resolved.statistic(), resolved.material(), entityType,
            resolved.key(), maxY, path);
'''
    new_validation_call = '''        boolean valid = validateCriterionSource(resolved.sourceType(), resolved.statistic(), resolved.material(), entityType,
            resolved.key(), path);
'''
    text = replace_once(text, old_validation_call, new_validation_call, "validateCriterionSource call")

    old_validation_signature = '''    private boolean validateCriterionSource(RewardSourceType sourceType,
                                            Statistic statistic,
                                            Material material,
                                            EntityType entityType,
                                            String key,
                                            int maxY,
                                            String path) {
'''
    new_validation_signature = '''    private boolean validateCriterionSource(RewardSourceType sourceType,
                                            Statistic statistic,
                                            Material material,
                                            EntityType entityType,
                                            String key,
                                            String path) {
'''
    text = replace_once(text, old_validation_signature, new_validation_signature, "validateCriterionSource signature")

    PATH.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    main()
