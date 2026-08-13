# WP-06 LoreItems integration handoff

## Package state
- Active package: WP-06 — EnthusiaTags integration with LoreItems service API.
- Status: `IN_REVIEW`.
- Canonical branch: `agent/wp-06-loreitems-integration`.
- Canonical PR: #15 — `WP-06: integrate EnthusiaTags with LoreItems service API`.
- Exact EnthusiaTags `main` dependency/base SHA: `36bd6c51b7db6a94c866e5ce938b08e696050235`.
- Exact implementation/CI head immediately before this checkpoint: `09b8a6e2026f6489d12cf04dc9af8418972a44bf`.
- Exact EnthusiaLoreItems live `main` dependency SHA: `ed91b1d46751544ed86fa7fa7de43cc769fc68a6`.
- Production LoreItems release pin: `v1.0.0`, JAR SHA-256 `7c862b0ae545d710a33267ad6e19a4ae26d97323e97f40707c1475c9f9ba7063`.

## Routing and dependency reconciliation
- LoreItems WP-01 through WP-05 are contained in live `main`; WP-05 was normally merged as `ed91b1d46751544ed86fa7fa7de43cc769fc68a6`.
- Production LoreItems `v1.0.0` is a published non-draft, non-prerelease release targeting that exact live-main merge commit. LoreItems post-merge `main` CI and release workflow succeeded before WP-06 resumed.
- No `docs/wp-06-complete` or `agent/wp-06-loreitems-api-blocker` ref existed when WP-06 resumed.
- Tags PR #15 / `agent/wp-06-loreitems-integration` was the sole unfinished canonical package lock and was resumed. No second package or competing canonical WP-06 branch was created.

## Completed implementation
- Added `LORE_ITEM` as a first-class Tags reward action with strict configuration validation. Accepted action fields are only `action-id`, `type`, `definition-key`, and `label`; definition keys are canonicalized and bounded to the released V1 grammar.
- Added typed `LoreItemsServiceV1` discovery through Bukkit `ServicesManager`. Tags imports only the released `net.enthusia.loreitems.api.v1` surface and contains no LoreItems command fallback, direct LoreItems database access, or implementation/domain coupling.
- Added deterministic caller-owned operation identity derived from `(player UUID, reward ID, action ID)` using length-delimited canonical parts. The same operation ID is reused across retry, timeout, reload, restart, and crash recovery.
- Added `lore-item-handoffs.db` as a Tags-owned durable handoff ledger. Intent is stored before the cross-plugin request; the ledger records definition, operation ID, state, outcome, attempts, error/detail, retry time, exact-operation Tags-finalization marker, and timestamps.
- Added bounded retry behavior: at most 50 due handoffs per sweep, 5-second exponential retry delay capped at 5 minutes, 10-second LoreItems service-stage timeout, immediate retry kick on enable/reload, and persisted restart recovery.
- Added reload/provider-order resilience. Tags can enable before LoreItems; the adapter rechecks provider enablement and replaces its typed client if the provider plugin instance changes.
- Added reward-claim outcome mapping: `ACCEPTED_QUEUED` / `ALREADY_ACCEPTED` are accepted; missing/unavailable/timeout/transient failures are retryable; unknown definition, validation failure, and operation-ID mismatch require staff review.
- Added recovery for a substantive pre-review defect: a background handoff retry can become accepted after the original Tags claim returned `DELIVERY_FAILED`. A bounded finalization sweep verifies the current reward/action/fingerprint, durably transitions that exact Tags action to `CLAIMED`, refreshes normal overall reward state, and only then marks that exact external operation finalized in the handoff ledger. A crash before the marker safely replays the reconciliation. Changed/missing configuration remains unfinalized and operator-visible instead of being silently credited.
- Finalization is per external operation, not per reward, so multiple LoreItems actions under one reward cannot acknowledge one another. The claim-key guard prevents the background finalizer from racing an active claim for the same reward.
- Added privileged `lorestatus` and `loreretry` administration under `enthusia.tags.rewards.loreitems.admin`. Accepted operations cannot be reopened. `lorestatus` includes `tags-finalized=true|false`, distinguishing LoreItems acceptance from completion of Tags reward-ledger reconciliation.
- Added `EnthusiaLoreItems` soft dependency and operator documentation for configuration, service ordering, automatic recovery, staged deployment, restart/reload, audit, rollback, and the accepted-but-not-finalized state.
- Pinned compilation/tests to the exact production LoreItems v1.0.0 JAR and checksum rather than a source checkout. Added checksum bootstrap and released-artifact V1 contract tests.
- PR CI verifies exact checkout and requires an exact-head Codacy result before uploading the package artifact. The exact-head verifier uses a fixed GitHub API domain, validates repository/PR/SHA inputs, confirms the live PR head before and after polling, requires Codacy success with zero annotations, and now retries bounded transient GitHub API transport failures rather than terminating the gate prematurely.

## Validation evidence
- `74991125dd517a5a91433e73fb5db06b62774411`: production-artifact bootstrap and Maven test/package passed; only Codacy failed.
- `5bccad29112fd67602f7da8bd80646ffc3cd728b`: Maven test/package passed after major adapter/runtime/admin/store refactors; only Codacy failed.
- Codacy findings were reduced from 64 to 19 and then to the final small exact-head set without disabling the project gate. Narrow PMD suppressions remain only where generic J2EE/synchronization rules conflict with intentional Paper plugin threading and single-connection SQLite serialization, with inline justification.
- `ebd613da262d90bed249bb2e3df6af4f8b0c89d8`: `Codacy Static Code Analysis` succeeded with zero annotations. That bot-authored self-cleaning patch commit did not execute the full GitHub Actions build, so no build pass is claimed for it.
- `e66d16bd8e7d38b649eea8536ec148244b8d1715`: exact checkout, pinned release bootstrap, and Maven clean/test/package passed. Codacy exposed four remaining findings, and independent pre-review then found the accepted-handoff/Tags-ledger resumption defect.
- `015b992aad2faca7ede36e1b7dc6dfc42c59170e`: after the recovery bridge, per-operation finalization test, shell exact-head verifier, and end-to-end idempotency test, the push exact-head build/test/package/JAR path succeeded; PR Maven also succeeded.
- `8211f72230ce821cd77559d06fee3c8188f4ed89`: exact checkout, production-release bootstrap, and Maven test/package passed including direct `RewardStorage` recovery coverage.
- `dbcaca2e3d528d5f36e9897ff8708f797b29bf29`: exact checkout, production-release bootstrap, and Maven clean/test/package passed. Fresh exact-head Codacy returned six concrete findings. They were resolved narrowly with a documented Paper-only PMD suppression on the existing off-main claim executor, storage recovery method extraction plus a named single-row constant, a runtime closed-message constant, and distinct idempotency-test counter names.
- The final large-file cleanup used an assertion-guarded self-cleaning workflow because the connector has no partial patch API for the large service/storage files. Comparing pre-helper `eccfd22f35dcdc7e4d7a1999f06636b60ecb74fb` to bot result `714b2f5047a1dfb6999e40e451a7a1408d369a9d` shows the aggregate diff contains exactly `RewardService.java` and `RewardStorage.java`; both temporary helper/workflow files cancel completely.
- `cd5df2a6379969504d20db19564392e3fe99257f`: exact checkout, pinned production release bootstrap, and Maven clean/test/package all passed. The PR workflow's Codacy-verifier step terminated after about 22 seconds while GitHub continued to report the exact Codacy check itself as `in_progress` with zero annotations. Therefore this was a CI polling-transport failure, not a Codacy/static verdict; no Codacy pass or failure is claimed for that head.
- `09b8a6e2026f6489d12cf04dc9af8418972a44bf`: hardened only the verifier transport with bounded `curl` connection/request timeouts and retries for transient errors while keeping the same exact-head/staleness/zero-annotation acceptance rules.
- This checkpoint intentionally creates a normal PR head after the verifier fix. No exact-head build/static pass is claimed for the checkpoint commit until GitHub Actions and Codacy finish on that exact SHA.

## Acceptance coverage implemented
- Released V1 JAR checksum/API/status-enum contract.
- Strict LoreItems action configuration and unknown-field rejection.
- Deterministic operation-key normalization, delimiter/collision-boundary, distinct-input, blank-input, and maximum-length behavior.
- Durable handoff store restart, retry ordering, staff retry, accepted-operation protection, and exact-operation finalization persistence.
- Coordinator transient retry/backoff, crash-after-acceptance replay, permanent-review outcome, and no-repeat-after-accepted behavior.
- Adapter accepted/replay/transient/timeout/review/operation-mismatch behavior.
- Reload-order/provider replacement behavior.
- Architecture guards against implementation-package imports, command fallback, synchronous service-adapter waits, Bukkit gameplay access in the adapter, and main-thread LoreItems waiting.
- End-to-end idempotency against a fake released-V1 service: a repeated logical claim after persisted acceptance makes one service request and one physical award; a crash after service acceptance but before Tags records it replays the same external operation, receives `ALREADY_ACCEPTED`, makes two logical service invocations but still records one physical award.
- Direct `RewardStorage` recovery: a failed LoreItems action cannot be credited with a mismatched fingerprint; the matching accepted handoff moves it durably to `CLAIMED`; replay of the same accepted handoff remains idempotent.

## Review findings resolved during this session
- Fixed a test seam mismatch where reload-order tests required injectable provider/client suppliers but production exposed only the `JavaPlugin` constructor.
- Fixed an overbroad architecture assertion that banned every `.get(` substring, replacing it with specific synchronous `CompletionStage`/`Future` wait checks.
- Refactored adapter, coordinator, runtime, admin, store, verifier, tests, and the LoreItems portions of `RewardService`/`RewardStorage` to satisfy concrete Codacy findings while preserving service discovery, durability, idempotency, bounds, transaction ordering, and Paper threading semantics.
- Replaced the Python exact-head Codacy verifier after the verifier itself triggered static warnings; the shell verifier retains the exact-head/staleness guarantees without a dynamic-network API surface.
- Hardened the shell verifier against a reproduced transient polling failure without relaxing or short-circuiting the Codacy requirement.
- Independent pre-review found and fixed the accepted-handoff recovery defect. Recovery uses the existing Tags reward ledger rather than a parallel reward state machine.
- Finalization acknowledgement was narrowed from reward-wide to exact-operation scope and tested with two accepted LoreItems actions under the same reward.
- Staff audit output exposes whether an accepted operation has been reconciled into Tags (`tags-finalized`).
- The final static cleanup preserved the same recovery transaction semantics while lowering analyzer complexity; it did not broaden suppression or relax the static gate.
- Temporary assertion-guarded one-shot GitHub Actions helpers used solely because the connector lacks a large-file patch action self-deleted. Aggregate comparisons confirmed no helper/workflow remains in the canonical PR.
- No submitted requested-changes review or unresolved review thread was present at the latest reconciliation. CodeRabbit is intentionally skipped while PR #15 remains draft; that skip is not counted as independent review.

## Remaining package criteria
1. Require the exact checkpoint head produced by this handoff commit to pass: exact checkout, production-artifact bootstrap, Maven clean/test/package, zero-annotation exact-head Codacy, and JAR upload.
2. Move PR #15 out of draft only after that exact-head gate is clean. Obtain the independent WP-06 review and inspect every review, thread, PR comment, and relevant check. Resolve every actionable finding on the same branch.
3. Re-run exact-head gates after any review fix. Merge PR #15 with a normal merge commit only when exact-head build/static analysis and independent review are all clean; no squash, rebase, force-push, or auto-merge.
4. Verify the exact Tags `main` merge commit and its post-merge Build.
5. Create LoreItems `docs/wp-06-complete` from exact live `main`, open exact-title PR `WP-06: record final remaining-work completion`, and change only `ai-agents/WORKSPACE-STATE.md`, `ai-agents/WORK-QUEUE.md`, and `ai-agents/reports/agent-handoffs/latest.md`.
6. Verify that LoreItems finalization PR on its exact head, normally merge it, verify LoreItems live `main`, and stop. Do not reopen WP-01 through WP-05.

## Known blocker
- None declared. The package is not externally blocked. The incomplete gates are exact-head build/static/artifact validation on this checkpoint, independent review, normal merge/main verification, and the mandated LoreItems finalization PR.

## Exact next action
Re-fetch PR #15 and the checkpoint SHA produced by this commit. Require the full exact-head PR Build and a fresh `Codacy Static Code Analysis` check with zero annotations. If clean, mark PR #15 ready for review, obtain the independent review, resolve all findings, and continue only through the WP-06 normal-merge/main-verification/LoreItems-finalization sequence above.
