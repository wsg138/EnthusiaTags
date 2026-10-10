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

Reward categories may specify `parent` and `tags` in rewards.yml. Root categories and direct children are paged in the existing 54-slot inventory; Back follows the parent chain. Invalid or cyclic chains fail configuration validation. Catalog tag items show descriptions and cached ownership with no claim action; EnthusiaHolidays remains the award owner. The existing holiday catalog installs missing definitions, preserves custom crown assets and text, and uses bold Pumpkin Hunter defaults; version 7 migration adds bold to existing Hunter names without replacing their colors. Item rendering remains limited to the visible page.

EnthusiaTags remains authoritative. Native advancement progress is a rebuildable projection. Historical reconciliation is silent; live completion is celebrated once. Native advancement criteria do not invoke reward commands. Existing claim UI remains accessible because vanilla advancement clicks are not an ordinary server-side claim interface. Missing dependencies disable the projection without disabling claims.

## Presence integration

Use a supported per-viewer integration point after RoseChat visibility filtering and before original message delivery. Original means abstaining from replacement, not reconstructing a guessed default. Preserve selections until quit message delivery has completed. Never broadcast a replacement outside the owner plugin's audience.

## Warzone Duels statistics bridge

The opt-in bridge reads the enabled WarzoneDuels plugin's `stats.yml` off-thread every five seconds. It never writes that file or accesses Tags reward tables. A strict parser requires explicit nonnegative wins and best-win-streak fields per UUID; a failed snapshot is omitted, not zero. Absence of a UUID in a valid complete snapshot proves zero history; absence of the file does not. Initial observations (including after reconnect/restart) wait for a read started after joining and are silent; later threshold crossings celebrate once per online session. Known progress never regresses during a session. An interrupted server may lose a toast, never replay payments. Existing WarzoneDuels statistics include party victories; these achievements use that same definition of a win, not guild membership. Source support is verified against the local WarzoneDuels 1.0.3 checkout; incompatible schemas fail closed.

Nine milestones ship: first win, five-win best streak and 50 wins use existing statistics; valid challenges, captured-spoils withdrawals, mutual draws, challenger custom-rules wins, restricted-mobility wins and low-health wins use six durable event counters. A missing optional advancements section is accepted for legacy rows, but a present scalar or list rejects the whole snapshot. No new monetary or cosmetic rewards are configured. Guild-war champion and spectator-betting achievements remain deferred; their completion is not inferred from ordinary wins or participant wagers. Enable/disable requires a server restart.

## Verification and rollout

SPEAR cycles: spec, prove (behavioral red), engine (green), architecture, refine. Record test commands and results per task. Validate existing databases through temporary SQLite fixtures, concurrency/restart/recovery tests, then produce a local test artifact only after package verification. Staging must check the actual Paper/client versions, logo pack, toast timing, RoseChat visibility and restart behavior. No live approval can be inferred from unit tests.


## Reward browser source recovery (T-945)

This isolated source recovery starts from canonical main 28048ca and recovers the preserved candidate reward browser, not supporter entitlement ownership, tag/cosmetic menus, configuration migrations or provider reward definitions. RewardMenuState stores transient browser state; RewardMenuModel adapts existing platform-bearing reward definitions/evaluations into presentation rows. These remain in the legacy infrastructure package, with no new domain persistence/payment policy. Formatting/items remain Paper adapters; RewardService supplies verified immutable observations and exclusively owns claims, IP/Gold enforcement, action fingerprints, queued delivery and reconciliation.

The browser restores the category dashboard, ready view, filters, name/progression/closest sorts, playtime grouping, paging and focused goal highlighting. Refresh updates status without moving the visible reward IDs, except explicit navigation or manual refresh. Missing evidence is represented by OptionalLong.empty rather than zero. Existing manual /rewards open navigation and holder compatibility constructors remain available for the guide; no new commands or permissions are added.

Holder-owned slot actions control clicks. Player items and PDC metadata are not accepted as actions. Bottom clicks, shift/double/offhand/number-key operations and right-click claims are cancelled without executing. Ordinary interactions are deferred one tick, single-flight per view, and recheck session identity, permission, service, current inventory and current slot action. Claims delegate to the current configured RewardDefinition through RewardService; per-player/reward guards release on every completion or failure. Result callbacks abstain for a new Player session and do not reopen a closed or different inventory. Existing inventory-close queued-item retry remains intact. Tags/cosmetics shortcuts enforce their existing command permissions.

One owner-scoped refresh timer operates only while the reward service is available and only for owned open views, using existing cached main-thread progress and no added SQL/provider reads. Plugin disable explicitly closes the menu, cancels refresh and clears transient guards. GUI render duration/item metrics are preserved from the candidate. No database schemas, payout amounts, reward identifiers, claims, ownership or resource-pack models are changed.

Verification includes actual two-case baseline interaction failures, delivery-state/unknown-progress policy tests, real focused inventory rendering and stable refresh slots, deferred stale-session/permission/view/slot rejection, ordinary claim delegation, failure guard cleanup and lifecycle cancellation. Full existing storage/IP/lifecycle/companion checks remain mandatory. This branch is independent of provider/guide source recovery; release must reconcile all required branches and remaining supporter/menu source gaps before a production-equivalent build. Live Java/Bedrock paging, focus, refresh, claim/withholding/queued/review states, effective permissions and provider failure acceptance remain separate. No deployment or client acceptance is implied.

Collection menus share the production 54-slot frame and 21-entry content grid through CollectionMenuLayout. Owned tags sort by id; cosmetics retain Original-first ordering and permission/selection checks. Navigation is next-tick and checks current holder/permission; top-only interaction and drag cancellation prevent inventory items being interpreted as buttons. KOTH uses an optional owner-bound Bukkit adapter and a server-side dashboard action; no progress or payout authority crosses into Tags.

T-970: Production category names/icons are bundled defaults. Existing reward catalogs remain administrator-owned. AdvancementCategories performs presentation-only grouping from advancement_reward provider keys, with Commend mapped from reputation and mixed/unknown providers retained under Other Advancements. No stored reward category, criterion, action, payout or claim ID changes. Focused links open leaf groups; Ready remains global. Parent navigation uses existing server-side holder actions. Nested default traversal now reads the current section rather than repeating root keys.

T-980 supersedes the simplified collection presentation: restore production TagMenu/CosmeticsMenu and holder state from preserved production source, verified against unique-mail-test.1 binary APIs and user screenshots. Tags retains All/Legacy/Supporter/Advancement-Event filters, preview at 17, Rewards45, Cosmetics47, Page49, Clear51, Close53; Cosmetics retains dashboard category slots19/21/23/25/29/31/33 and production header/navigation/help. Additional custom cosmetic categories page seven at a time. Preview requires admin permission at render/toggle and cannot equip or clear. Inventory handlers read the real top slot, reject unsupported clicks, defer actions one tick and recheck online/view/use permission. Existing tag ownership and cosmetic permission/selection services remain authoritative. CollectionSources is read-only metadata from existing entitlements.yml or bundled menu-sources.yml definitions, with holiday event classification; it introduces no grants, storage schema or qualification engine. Existing reward browser and provider catalogs remain unchanged.
