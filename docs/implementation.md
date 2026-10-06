# Implementation and safety boundaries

## Voluntary reward guide

`/rewards guide` adds default-disabled chat path choices to the existing command, with ordinary permission enforcement and existing focused-menu navigation. There is no forced GUI, join message, boss bar, auto-claim, gameplay command, reward definition/payout or schema change. Missing/unverified paths render unavailable instead of a misleading clickable goal. The guide never creates a guild, duel or mail interaction.

Goal selection uses immutable configured definitions and reobserves progress/state on the main thread before rendering. Only fresh LOCKED/UNLOCKED goals with supported criteria and available actions are offered. Native CUSTOM_COUNTER observations support stone_mined/logs_mined and the exact ProviderRewardEvidence allowlist recovered in dependent PR #27. RewardService remains the availability authority: fresh verified zero is a valid incomplete suggestion, missing evidence is unavailable, and earned completion remains eligible through provider outages. Invented counters and settled/interrupted delivery states are omitted. The default positive social and optional combat goals remain configured choices; this integration does not add diary suggestions or negative reputation goals to defaults. Guide output includes the configured description so provider instructions are visible, then progress, eligible reward components and existing manual navigation.

Gold eligibility is a read-only SQL preview of the same action fingerprints/status and legacy/current ownership queries used by reservation. Legacy sibling exemptions do not bypass Gold ownership. Preview does not reserve, pay, withhold or finalize; claim-time policy still rechecks. Blocked/unknown previews omit all typed Gold amounts, retain other configured components and explain eligibility uncertainty. Ordinary reward screens remain authoritative for claim outcomes.

SQL reads use the existing claim/storage executors and immutable identity/action inputs, never Bukkit APIs on workers. Admission is bounded to 64 actual read jobs, with a three-second display timeout that does not release the permit before the underlying read ends. At most one pending display per UUID and 128 displays overall; callback rendering rechecks actual Player session identity, configuration generation, permission, provider readiness and exact definition identity. Reload increments generation; offline/disabled callbacks abstain. No IP addresses enter the domain policy or UI.

Local Java/Paper 26.2 verification is not production/client acceptance. Existing Tags production-only source must be reconciled, canonical code merged and monorepo release pin/combined build verified before a separately authorized clean release. Disable rewards.guide.enabled to roll back discovery; existing reward data/UI/claims are unchanged. Test Java and Bedrock chat click behavior, whitelist subcommands, reload/quit/provider failure, Gold withholding and supported counters on an isolated runtime before activation.

## Layer Dependency Rules

domain <- application <- infrastructure

New pure policy code belongs in `org.enthusia.tags.advancements.domain` and uses Java standard-library types only. Application orchestration may depend on domain; Bukkit, SQL, provider APIs and the existing legacy reward/cosmetics packages are infrastructure. Legacy packages are not relocated in this migration. New provider adapters must not expose database connections or issue rewards from the rendering layer.

## Forbidden Domain Annotations

```yaml
forbidden: []
```

## Persistence

Only additive schema changes. Preserve reward_claims, reward_unlocks and existing action IDs/fingerprints. Gold ownership is per challenge and IP (one account owns all its gold components), atomically serialized by the storage executor and protected by a database uniqueness constraint. Delivery and withholding remain per action. Legacy whole-challenge IP reservations remain read-only evidence for the new claim path. A terminal withheld component records an explicit reason rather than pretending money was deposited. Failed verification must not become permanent withheld status. Gold limits must cover retries, overflow delivery and admin recovery, not merely the first click.

## Presentation

EnthusiaTags remains authoritative. Native advancement progress is a rebuildable projection. Historical reconciliation is silent; live completion is celebrated once. Native advancement criteria do not invoke reward commands. Existing claim UI remains accessible because vanilla advancement clicks are not an ordinary server-side claim interface. Missing dependencies disable the projection without disabling claims.

## Presence integration

Use a supported per-viewer integration point after RoseChat visibility filtering and before original message delivery. Original means abstaining from replacement, not reconstructing a guessed default. Preserve selections until quit message delivery has completed. Never broadcast a replacement outside the owner plugin's audience.

## Warzone Duels statistics bridge

The opt-in bridge reads the enabled WarzoneDuels plugin's `stats.yml` off-thread every five seconds. It never writes that file or accesses Tags reward tables. A strict parser requires explicit nonnegative wins and best-win-streak fields per UUID; a failed snapshot is omitted, not zero. Absence of a UUID in a valid complete snapshot proves zero history; absence of the file does not. Initial observations (including after reconnect/restart) wait for a read started after joining and are silent; later threshold crossings celebrate once per online session. Known progress never regresses during a session. An interrupted server may lose a toast, never replay payments. Existing WarzoneDuels statistics include party victories; these achievements use that same definition of a win, not guild membership. Source support is verified against the local WarzoneDuels 1.0.3 checkout; incompatible schemas fail closed.

Nine milestones ship: first win, five-win best streak and 50 wins use existing statistics; valid challenges, captured-spoils withdrawals, mutual draws, challenger custom-rules wins, restricted-mobility wins and low-health wins use six durable event counters. A missing optional advancements section is accepted for legacy rows, but a present scalar or list rejects the whole snapshot. No new monetary or cosmetic rewards are configured. Guild-war champion and spectator-betting achievements remain deferred; their completion is not inferred from ordinary wins or participant wagers. Enable/disable requires a server restart.

## Verification and rollout

SPEAR cycles: spec, prove (behavioral red), engine (green), architecture, refine. Record test commands and results per task. Validate existing databases through temporary SQLite fixtures, concurrency/restart/recovery tests, then produce a local test artifact only after package verification. Staging must check the actual Paper/client versions, logo pack, toast timing, RoseChat visibility and restart behavior. No live approval can be inferred from unit tests.


## Provider reward recovery (T-937)

Canonical main 28048ca owns the existing reward storage, manual claim flow, action ledger and gold/IP policy. This bounded recovery adds the 38 provider reward definitions and 12 referenced tags preserved in the local production-labelled candidate; that candidate is not a verified reproduction of the currently loaded production binary. Supporter rewards and other source differences are excluded.

ProviderRewardEvidence is a JDK-only allowlist and completion policy. ProviderRewardTracker owns the existing four read-only asynchronous bridges independently of native presentation, with one bounded server-thread observation queue. NativeAdvancementController shares those readers and projects the original provider node IDs, icons and layout rather than duplicating provider rewards into the generic Tags tree. Display tooltips list configured actions and manual claim instructions; gold instructions follow the existing typed action policy.

Only current-session successful reads from enabled providers are eligible to latch a known counter. Missing/failed/pre-join reads remain unavailable; cached display progress is retained separately. Loaded RewardPlayerState owns monotonic completion counters, so earned goals remain complete through outages and reconnects. Unloaded or stale Player instances cannot create counters. Join and configuration reload fence cached/in-flight reads through the bridges' existing read-start/session timestamps. Reload clears transient reward availability; the next fresh read retries after state loading. Reader paths and enable settings are selected at startup; changing those settings requires a restart. No SQL/file work or reward execution is introduced into the native renderer.

ProviderRewardDefaults uses a separate once-only version marker. It adds whole missing provider reward/tag definitions, preserves existing sections including administrator overrides, keeps higher config versions, and leaves the current active-playtime migration intact. Existing rewards/actions/claims and gold reservations are not rewritten. A migration backup precedes changes. Administrators may remove recovered definitions after the marker is installed without having them restored on every reload.

The distinct-person mail contract from PR #26 is included as a prerequisite for enabling monetary mail rewards: repeated mail rows to the same person are not separate social milestones. See express-history-contract.md. This branch intentionally overlaps that PR until its canonical merge; no claim policy or provider database schema changes are included.

Verification includes actual failing assertions for the missing catalog/completion seam, real temporary reward-storage persistence and claim preservation, unavailable-versus-zero progress, renderer-independent tracking, reconnect/reload fences, provider parse failures, unchanged provider bytes, and preserved custom diary icon metadata. Existing claim/IP/lifecycle/companion tests remain required. Live test acceptance must separately verify a historical completion without a toast, a fresh incomplete-to-complete transition, manual claim twice without duplicate payout, gold reservation across two accounts on the same IP, renderer-disabled access, provider outage/recovery, reload and reconnect. No production upload, activation or live acceptance is implied by local or hosted checks.


## Provider guide integration (T-942)

PR #25 now includes exact provider recovery PR #27 head 4e9f174 as a source dependency; after #27 merges its source overlap disappears from the guide diff. Merge conflicts were limited to version metadata and additive task/requirement records; both implementations and current-main safeguards are retained. The only new runtime changes are an allowlist extension in the JDK-only guide policy and configured description rendering in the existing chat adapter. The guide starts no additional readers or async work and performs no provider actions, claims, ownership writes or payments. Custom path lists, permissions, default-disable, stale-callback guards, Gold preview and focused reward screens remain unchanged.

New regression proof uses actual RewardService progress/evaluation with temporary SQLite state: unsupported/missing evidence, verified incomplete evidence, outage after cached zero, durable completion during outage, invented keys, and settled/queued/pending/reconciliation/failure states. A chat test proves the configured provider instruction appears without unknown Gold amounts or automatic claim. Java/Bedrock, effective commands/permissions and real provider client acceptance remain separate from source verification; full network source reconciliation is still required before a production build.
