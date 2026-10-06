# Implementation and safety boundaries

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
