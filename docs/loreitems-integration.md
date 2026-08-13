# LoreItems reward integration

EnthusiaTags can deliver a reward through the stable `LoreItemsServiceV1` Bukkit service published by `EnthusiaLoreItems` v1.0.0. The integration is deliberately limited to the public V1 service contract: Tags does not dispatch LoreItems commands, read LoreItems SQLite tables, or import LoreItems implementation/domain classes.

## Reward configuration

A LoreItems reward action uses `type: LORE_ITEM` and a LoreItems definition key:

```yaml
rewards:
  lore-example:
    name: "&6Lore example"
    category: misc
    criteria:
      example:
        type: CUSTOM_COUNTER
        key: example_progress
        amount: 1
        label: "Example progress"
    rewards:
      unique-hourglass:
        action-id: unique-hourglass
        type: LORE_ITEM
        definition-key: hourglass
        label: "&eHourglass"
```

The only accepted LoreItems-action fields are `action-id`, `type`, `definition-key`, and `label`. The definition key is canonicalized to lowercase and must contain 1-64 letters, digits, underscores, or hyphens. Blank, malformed, or unknown fields make that reward unavailable instead of falling back to a different delivery mechanism.

`label` is player-facing text. The GUI and unlock notifications use that label, or the configured generic fallback, and do not expose the LoreItems definition key, caller operation key, or LoreItems internal delivery identifiers to players.

## Runtime and durability model

`EnthusiaLoreItems` is a soft dependency. Tags can enable when LoreItems is missing or temporarily disabled. Lore-item reward configuration remains valid as long as Tags' durable handoff runtime initialized; when the service is absent, a claim persists its handoff intent and stays pending/retryable rather than being marked delivered.

Each logical `(player UUID, reward ID, action ID)` has one deterministic caller-owned external operation ID. Tags stores the definition key and operation ID in `plugins/EnthusiaTags/lore-item-handoffs.db` before invoking LoreItems. The same operation ID is reused after timeout, plugin reload, server restart, or a crash after LoreItems accepted the request but before Tags recorded the response.

The service result mapping is:

| LoreItems V1 result | Tags handoff state | Reward meaning |
| --- | --- | --- |
| `ACCEPTED_QUEUED` | `ACCEPTED` | action delivered/accepted |
| `ALREADY_ACCEPTED` | `ACCEPTED` | idempotent replay success |
| service missing / `SERVICE_UNAVAILABLE` / timeout / transient async failure | `RETRY` | not delivered; retry same operation ID |
| `UNKNOWN_DEFINITION` / `VALIDATION_FAILURE` | `REVIEW` | not delivered; explicit staff review required |
| response operation ID mismatch | `REVIEW` | not delivered; explicit staff review required |

The handoff ledger also records attempt count, last service outcome, last error/detail, next retry time, created time, and updated time. A persisted definition key cannot silently change for an existing logical action; such a change is rejected so staff can reconcile the old operation explicitly.

## Retry and reload behavior

Tags performs bounded asynchronous retry sweeps. A sweep processes at most 50 due operations. Retry delay starts at 5 seconds, doubles per attempt, and is capped at 5 minutes. The LoreItems service completion is bounded to 10 seconds; a timeout is treated as uncertain/retryable and the same operation ID is used again.

The retry worker runs outside the Paper main thread. Reward claims already execute on Tags claim workers, and the LoreItems `CompletionStage` is awaited there, never on the primary server thread. The service adapter re-checks whether the LoreItems plugin is enabled on each call and refreshes its cached adapter if the provider plugin instance changes.

On Tags enable and reload, due handoffs are kicked immediately in addition to the periodic sweep. On a server restart, the SQLite handoff ledger is reopened and the same pending identities resume. A current matching lore-item `CLAIM_PENDING` row is recoverable; unrelated historical pending rows retain the existing Tags reconciliation protections.

## Staff inspection and retry

Permission: `enthusia.tags.rewards.loreitems.admin` (default `op`, inherited by `enthusia.tags.admin`).

```text
/enthusiatags rewards lorestatus <player|uuid> <reward>
/enthusiatags rewards loreretry <player|uuid> <reward> <action-id>
```

`lorestatus` displays the logical action ID, definition key, caller operation ID, state, last outcome, attempts, and last error/detail. This is intentionally privileged audit data. `loreretry` explicitly moves a non-accepted handoff back to an immediately due retry and reuses its existing operation ID; an already accepted handoff is never converted back to pending.

All operator-facing LoreItems messages are configurable in `messages.yml`.

## Build and released API pin

WP-06 compiles against the exact production `EnthusiaLoreItems` v1.0.0 plugin artifact rather than a source checkout or implementation module. CI downloads `EnthusiaLoreItems.jar`, verifies SHA-256

`7c862b0ae545d710a33267ad6e19a4ae26d97323e97f40707c1475c9f9ba7063`

then installs that checksum-verified JAR into the runner's temporary local Maven repository as a provided compile dependency. The test suite independently verifies the same checksum, the V1 API class entries, `API_VERSION == 1`, and the exact published status enum surface.

For a local build, bootstrap the pinned production artifact before the repository's normal build command:

```bash
bash tools/bootstrap_loreitems_release.sh
mvn --batch-mode --no-transfer-progress clean test package
```

## Staged deployment and acceptance

1. Deploy the production `EnthusiaLoreItems` v1.0.0 JAR first and verify its release/checksum against the value above.
2. Back up the Tags data directory, including `rewards.db`; after first WP-06 startup also retain `lore-item-handoffs.db` in normal backups.
3. Deploy the WP-06 Tags build. Confirm both plugins enable without dependency errors and Tags does not report durable LoreItems storage unavailable.
4. Configure a controlled reward with a known LoreItems definition and a clearly recognizable player-facing label.
5. Claim it once and verify `lorestatus` reports `ACCEPTED` with `ACCEPTED_QUEUED`.
6. Retry the exact logical action or exercise the crash/restart recovery path and verify the same caller operation ID reaches `ALREADY_ACCEPTED` rather than producing another LoreItems acceptance.
7. Test LoreItems disabled-before-Tags, LoreItems enabled-after-Tags, Tags reload, and a full server restart. Pending work must remain pending/retryable and later recover without marking a failed handoff delivered.
8. Test an unknown definition in staging. Tags must place it in staff review and must not mark the reward claimed.
9. Keep the exact-head Maven, Codacy, and independent review evidence attached to the WP-06 pull request before production promotion.

## Recovery and rollback

If LoreItems is unavailable, do not delete the Tags handoff database or manually alter reward claim markers. Restore/re-enable LoreItems and allow the existing operation IDs to replay. Use `lorestatus` before `loreretry` when an operation is in `REVIEW`.

If a definition key was changed after a claim started, restore the original definition mapping or reconcile the existing action deliberately; Tags refuses to create a fresh identity for the changed definition because that could duplicate a previously accepted item.

To roll back Tags, stop the server normally, retain a backup of both Tags databases, restore the prior Tags JAR/configuration, and keep the WP-06 handoff database until every started lore-item action has been audited. LoreItems may already have accepted an operation that Tags had not yet finalized, so deleting the handoff ledger removes the caller identity needed for idempotent recovery. Rolling LoreItems back or removing it simply leaves unaccepted Tags handoffs pending; it must not be interpreted as proof that a reward was never accepted.
