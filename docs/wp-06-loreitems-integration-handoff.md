# WP-06 LoreItems integration handoff

## Package state
- Active package: WP-06 — EnthusiaTags integration with LoreItems service API.
- Status: `IN_PROGRESS`.
- Canonical branch: `agent/wp-06-loreitems-integration`.
- Canonical PR: #15 — `WP-06: integrate EnthusiaTags with LoreItems service API` (draft at claim verification).
- Exact EnthusiaTags `main` SHA at claim: `36bd6c51b7db6a94c866e5ce938b08e696050235`.
- Exact claim checkpoint head: `bf3bd3762e0483dfde5fe69db57b463a7660e5b9`.
- Exact implementation/evidence head being checkpointed: `36bd6c51b7db6a94c866e5ce938b08e696050235` (claim base; no WP-06 implementation changes existed before the claim metadata commit).
- Exact EnthusiaLoreItems live `main` reconciled for dependency routing: `ed91b1d46751544ed86fa7fa7de43cc769fc68a6`.

## Routing and dependency reconciliation
- LoreItems WP-01 through WP-05 canonical branches are contained in LoreItems live `main`; WP-05 is merged by normal merge commit `ed91b1d46751544ed86fa7fa7de43cc769fc68a6`.
- Production LoreItems `v1.0.0` is published as a non-draft, non-prerelease release targeting that merged live-main SHA; the release includes the production plugin JAR and checksum/evidence assets.
- LoreItems has no `docs/wp-06-complete` or `agent/wp-06-loreitems-api-blocker` ref at claim time.
- EnthusiaTags had no open pull requests and no `agent/wp-06-loreitems-integration` branch before this claim.
- Existing open LoreItems PRs #21, #23, #24, and #25 were reconciled as documentation/review-only work rather than unfinished canonical package locks; inspected review threads were resolved and they do not reopen WP-05.
- Therefore WP-06 is the lowest READY package and this Tags branch/PR pair is the sole canonical WP-06 primary lock.
- GitHub initially rejected draft PR creation on the zero-diff branch (`No commits between main and agent/wp-06-loreitems-integration`). The required claim checkpoint was therefore the first branch commit, and draft PR #15 was opened immediately afterward from that checkpoint.

## Selected contract and integration boundary
The live WP-06 contract in `wsg138/EnthusiaLoreItems/ai-agents/work-packages/WP-06-enthusiatags-integration.md` requires Tags to use the released stable Bukkit service boundary only:

- `net.enthusia.loreitems.api.v1.LoreItemsServiceV1` via Bukkit `ServicesManager`;
- asynchronous `queueDelivery(definitionKey, playerId, externalOperationId)`;
- caller-owned external operation IDs reused for the same logical claim/action across retry, reload, restart, timeout, and uncertain caller-side result;
- no LoreItems implementation-class imports, SQLite access, or `/loreitems` command dispatch;
- `ACCEPTED_QUEUED` and `ALREADY_ACCEPTED` are success outcomes;
- unknown definition, validation failure, service unavailable, missing service, timeout, reload, and transient failures must not be marked delivered.

## Pre-implementation findings
- Tags currently supports `TAG`, `MONEY`, `COMMAND`, and `ITEM` reward action types; `LORE_ITEM` does not exist yet.
- The reward service already has an asynchronous claim executor and durable per-action ledger/state machinery. WP-06 should extend that state machine rather than create an unrelated delivery subsystem.
- Tags `plugin.yml` does not yet soft-depend on `EnthusiaLoreItems`.
- The current Tags build workflow runs `mvn --batch-mode --no-transfer-progress clean test package` but does not yet implement the contract-required exact-head stale-PR rejection and exact-head Codacy verification.
- The released LoreItems V1 API is explicitly asynchronous and documents Bukkit service lookup plus idempotent replay semantics.

## Completed acceptance progress
- [x] Reconciled LoreItems live merge/release state and verified the WP-06 dependency is satisfied.
- [x] Reconciled all canonical LoreItems package branches and WP-06 support refs.
- [x] Reconciled EnthusiaTags live `main`, open PRs, and canonical WP-06 branch absence.
- [x] Read the live WP-06 package contract and the relevant LoreItems requirements, architecture, implementation plan, public API, service signature, and outcome enum.
- [x] Read Tags README/build metadata, plugin metadata, reward action model, and current reward-service execution/recovery structure sufficiently to establish the integration direction.
- [x] Created the canonical WP-06 branch from exact Tags `main` SHA `36bd6c51b7db6a94c866e5ce938b08e696050235`.
- [x] Opened canonical draft PR #15 with the required package/scope/migration/risk/validation/rollback fields.

## Remaining package criteria
- Add validated `LORE_ITEM` configuration/action support.
- Add typed LoreItems V1 service discovery and asynchronous handoff without main-thread blocking.
- Persist a stable external operation key and lore-handoff state/attempt/outcome/error audit per reward action.
- Make crash/reload/restart replay idempotent and bounded when LoreItems is unavailable.
- Add privileged status/retry administration, permissions, configured messages, and no player exposure of LoreItems internal IDs.
- Add soft dependency, examples, compatibility, staged deployment, recovery, and rollback documentation.
- Upgrade Tags PR workflow with exact-head stale rejection and Codacy verification.
- Add unit, adapter, persistence/restart, and released-artifact end-to-end tests.
- Run the full Maven build, exact-head CI/Codacy/cross-plugin gates, independent review, and resolve all actionable findings.
- Merge Tags normally only after every gate passes, verify Tags `main`, then perform the mandated LoreItems finalization PR and stop after that normal merge.

## Tests and results
- No WP-06 implementation test result is claimed by this claim checkpoint.
- Existing Tags `main` documents the required Maven build command, but historical `main` evidence is not reused as WP-06 exact-head evidence.

## Known findings / blocker
- No external blocker is established.
- The first zero-diff draft-PR attempt failed only because GitHub requires a branch delta before PR creation; the canonical lock now exists durably as branch + draft PR #15.

## Exact next action
Re-fetch canonical branch and PR #15 to verify they still agree with this claim, then implement the first coherent WP-06 section: `LORE_ITEM` action/config validation plus the stable LoreItems service adapter and persistence-compatible handoff model. Publish implementation and tests to this branch before stopping.