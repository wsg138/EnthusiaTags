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

Companion-provider native nodes (Express, Commend, Diary and Warzone Duels) pass through an additive `advancement-history.db` ledger keyed by player UUID and stable advancement key. Ordinary `tags/` reward nodes retain their existing durable `reward_unlocks` path: silently seeding those ahead of RewardService's asynchronous callback would suppress a legitimate new reward toast. `CompletionLedger` is an application port; `CompletionHistory` polls its futures without blocking; the SQLite adapter performs file/SQL work on one dedicated executor. Historical completions are inserted silently; newly observed live completions are eligible only if this transaction wins insertion. Previously saved completion floors the rendered progress, even when a provider regresses or is temporarily unavailable. Removed definitions are not reintroduced into the renderer.

The controller never delivers a notification until the ledger transaction commits. Failed reads/transactions and pre-dispatch projection failures retain pending work and retry. Provider session authorization is consumed immediately before calling the renderer: a thrown renderer call has uncertain delivery and must not be replayed. This deliberately narrows REQ-902 for companion-provider nodes (REQ-923); ordinary Tags rewards retain their existing retry path. Quit and close discard session authorization; queued transactions may finish, but have no asynchronous Bukkit callback and cannot target a later session. Restart intentionally does not replay committed notification intent: a crash between commit and toast may lose that notification. A renderer failure before its first side effect can also lose the notification once dispatch begins. This is at-most-once provider dispatch intent, not an exactly-once delivery guarantee across external Discord failures.

The ledger does not pay rewards, change criteria, touch provider databases, or alter existing claim/unlock tables. First-upgrade backfill uses only currently proven completed progress; it cannot reconstruct absent historical evidence or reliably import Discord posts. Back up the new ledger with Tags data; deleting it or rolling back to an older binary removes this new safeguard. Existing server data is not modified during local validation. No GUI, layout or color change is introduced.

## Presence integration

Use a supported per-viewer integration point after RoseChat visibility filtering and before original message delivery. Original means abstaining from replacement, not reconstructing a guessed default. Preserve selections until quit message delivery has completed. Never broadcast a replacement outside the owner plugin's audience.

## Warzone Duels statistics bridge

The opt-in bridge reads the enabled WarzoneDuels plugin's `stats.yml` off-thread every five seconds. It never writes that file or accesses Tags reward tables. A strict parser requires explicit nonnegative wins and best-win-streak fields per UUID; a failed snapshot is omitted, not zero. Absence of a UUID in a valid complete snapshot proves zero history; absence of the file does not. Initial observations (including after reconnect/restart) wait for a read started after joining and are silent; later threshold crossings celebrate once per online session. Known progress never regresses during a session. An interrupted server may lose a toast, never replay payments. Existing WarzoneDuels statistics include party victories; these achievements use that same definition of a win, not guild membership. Source support is verified against the local WarzoneDuels 1.0.3 checkout; incompatible schemas fail closed.

Nine milestones ship: first win, five-win best streak and 50 wins use existing statistics; valid challenges, captured-spoils withdrawals, mutual draws, challenger custom-rules wins, restricted-mobility wins and low-health wins use six durable event counters. A missing optional advancements section is accepted for legacy rows, but a present scalar or list rejects the whole snapshot. No new monetary or cosmetic rewards are configured. Guild-war champion and spectator-betting achievements remain deferred; their completion is not inferred from ordinary wins or participant wagers. Enable/disable requires a server restart.

## Verification and rollout

SPEAR cycles: spec, prove (behavioral red), engine (green), architecture, refine. Record test commands and results per task. Validate existing databases through temporary SQLite fixtures, concurrency/restart/recovery tests, then produce a local test artifact only after package verification. Staging must check the actual Paper/client versions, logo pack, toast timing, RoseChat visibility and restart behavior. No live approval can be inferred from unit tests.
