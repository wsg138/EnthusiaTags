# EnthusiaTags advancement pilot

Users: existing and new Enthusia players, including alternate accounts; server administrators operating existing live databases.

- REQ-001: THE SYSTEM SHALL preserve existing player identifiers, challenge identifiers, earned tags, cosmetic selections, claim history and reward delivery recovery records.
- REQ-002: WHEN a player qualifies for an existing challenge THE SYSTEM SHALL allow completion independently of another account sharing the player's IP address.
- REQ-003: WHEN a player claims a challenge THE SYSTEM SHALL restrict only its Raw Gold components to one account per IP while allowing its other eligible reward components.
- REQ-004: IF a Raw Gold network reservation cannot be verified THEN THE SYSTEM SHALL withhold its delivery without treating the storage failure as an established claim by another account.
- REQ-005: WHEN a component has already been delivered or durably withheld for the network limit THE SYSTEM SHALL avoid replaying that component during retries or recovery.
- REQ-006: THE SYSTEM SHALL preserve legacy IP reservation evidence without manufacturing historical claims from a player's current login address.
- REQ-007: THE SYSTEM SHALL display existing challenges in an Enthusia vanilla advancement tree with requirements, reward descriptions and the supplied logo through a compatible resource-pack item icon.
- REQ-008: WHEN a player newly completes a challenge THE SYSTEM SHALL emit one native advancement toast and advancement-style announcement.
- REQ-009: WHEN historical completion is reconciled THE SYSTEM SHALL restore advancement display silently without granting rewards again.
- REQ-010: IF an authoritative progress provider fails THEN THE SYSTEM SHALL retain known progress and retry rather than substitute zero or infer completion.
- REQ-011: THE SYSTEM SHALL support configurable kill, join and leave message rewards with independent player selections and an Original option.
- REQ-012: WHEN a player selects Original for join or leave messages THE SYSTEM SHALL restore RoseChat's configured default behavior.
- REQ-013: WHEN a selected custom presence message replaces RoseChat's message THE SYSTEM SHALL preserve RoseChat's recipient and visibility restrictions without duplicate delivery.
- REQ-014: THE SYSTEM SHALL keep future plugin advancement providers extensible without adding Guild, Death Duels, Market or other new challenge definitions in this pilot.
- REQ-015: THE SYSTEM SHALL preserve manual claim behavior unless explicitly configured otherwise and distinguish completion from reward delivery.

## Policy interpretation for this pilot

- REQ-016: IF any configured reward action conflicts with its saved fingerprint THEN THE SYSTEM SHALL persist reconciliation-required status before reserving or delivering any component.
- REQ-017: IF the advancement provider throws during tree removal on shutdown THEN THE SYSTEM SHALL log the failure and clear controller state without interrupting remaining plugin shutdown.
- REQ-018: WHEN RoseChat initializes on Paper year-based version strings THE SYSTEM SHALL resolve the Minecraft major and minor versions without interpreting build metadata as a number.
- REQ-019: WHEN existing consecutive-active, underground-active or maximum-ping criteria omit an explicit counter key THE SYSTEM SHALL resolve their established stored counters without modifying challenge thresholds, rewards, claims or administrator-specified keys.
- REQ-020: WHEN RoseChat decorates a clickable message THE SYSTEM SHALL preserve each configured click action on Adventure 4 and 5 runtimes without binary linkage errors.

## Approved follow-on: Warzone Duels statistics slice

REQ-014 remains the original pilot boundary; the user explicitly authorized Warzone Duels on 2026-09-18. Guild and Market advancements remain excluded.

- REQ-021: WHEN the optional Warzone Duels statistics bridge is enabled THE SYSTEM SHALL append Arena Initiate (1 win), Arena Win Streak (best streak 5), and The Gladiator (50 wins) to the existing Enthusia tree using read-only persisted WarzoneDuels statistics without issuing rewards or modifying either plugin's player data.
- REQ-022: WHEN an online player's first valid duel snapshot is observed THE SYSTEM SHALL project historical progress silently and celebrate only subsequent newly observed completions during that session.
- REQ-023: IF duel statistics are missing, unreadable or malformed THEN THE SYSTEM SHALL retain known progress and retry without substituting zero or announcing completion.
- REQ-024: THE SYSTEM SHALL keep the duel bridge disabled by default and perform statistics file reads off the server thread while retaining fixed advancement identifiers and keeping all guild integrations excluded.

The existing MONEY action is the server's Vault-backed Raw Gold currency (see RewardMoneyPolicy). Explicit RAW_GOLD and RAW_GOLD_BLOCK item actions also count as gold. Arbitrary reward commands are not parsed as currency: administrators must use typed gold actions for gold payouts; otherwise commands cannot be safely classified. Existing whole-reward IP reservations are conservative evidence for gold only. No historical rewards are removed or replayed.

A network-limited component is durably withheld for that account; changing IP later must not make it claimable again. Missing IP or database failure is retryable, not a permanent rejection. No implicit account whitelist exception is introduced for gold.

## Approved follow-on: Warzone Duels event-backed achievements

The user explicitly authorized the remaining non-guild WarzoneDuels advancement roadmap on 2026-09-18. Guild-war/champion integration remains excluded. Betting achievements remain deferred because the current WarzoneDuels repository has participant wagers but no spectator-betting subsystem.

- REQ-025: WHEN a player sends a valid Warzone Duel challenge THE SYSTEM SHALL complete Welcome to the Thunderdome from a provider-owned durable achievement counter.
- REQ-026: WHEN a duel winner successfully withdraws at least one captured spoils item THE SYSTEM SHALL complete To the Victor Go the Spoils from a provider-owned durable achievement counter.
- REQ-027: WHEN every surviving participant agrees to a draw THE SYSTEM SHALL complete A Price for Peace for those agreeing participants from provider-owned durable achievement counters.
- REQ-028: WHEN a one-versus-one challenger wins a duel using a non-default ruleset THE SYSTEM SHALL complete My House, My Rules for that challenger from a provider-owned durable achievement counter.
- REQ-029: WHEN a player wins a duel with both Ender Pearls and Wind Charges disabled THE SYSTEM SHALL complete Adapt and Overcome from a provider-owned durable achievement counter.
- REQ-030: WHEN a player wins a one-versus-one kill result with less than four health points remaining THE SYSTEM SHALL complete Not Even Close from a provider-owned durable achievement counter.
- REQ-031: THE SYSTEM SHALL project provider-owned WarzoneDuels achievement counters into the Enthusia advancement tree with silent historical reconciliation, live one-time celebration, off-thread reads, monotonic session progress and no reward payout.
- REQ-032: THE SYSTEM SHALL leave Champion of the Realm, Place Your Bets, Eye for Talent, High Roller and the proposed hidden duel achievements unimplemented until their required guild-war, spectator-betting or missing durable provider evidence is separately approved and available.

## Advancement presentation layout

- REQ-033: WHEN the native Enthusia advancement tree is rendered THE SYSTEM SHALL place all bundled challenge nodes in fixed compact category branches, preserve existing challenge identifiers and requirements, keep future unknown rewards on a deterministic fallback layout, and present Warzone Duels as three branches for mastery, spoils/peace, and special-condition victories.

## Approved follow-on: EnthusiaCommend reputation advancements

- REQ-034: WHEN EnthusiaCommend is available THE SYSTEM SHALL append A Good Word, Well Regarded, Pillar of the Community, Bad Reputation, Public Enemy and Redemption Arc using provider-owned durable reputation evidence without issuing rewards.
- REQ-035: WHEN positive category high-water evidence reaches five THE SYSTEM SHALL support Kind Soul for WAS_KIND, Generous Spirit for GAVE_ITEMS, Trusted Name for TRUSTWORTHY and Merchant of Merit for GOOD_STALL, while leaving negative-behavior category achievements unimplemented.
- REQ-036: THE SYSTEM SHALL treat +10 and +20 as the overall positive milestones, -10 and -25 as the overall negative milestones, and recovery from -12-or-lower back to zero-or-higher as Redemption Arc.
- REQ-037: WHEN historical reputation evidence is first observed for a session THE SYSTEM SHALL restore completed reputation advancements silently and celebrate only later newly observed completions once.
- REQ-038: IF reputation evidence is missing, unreadable or malformed THE SYSTEM SHALL retain known session progress and retry rather than substituting zero or inferring historical category peaks.

## Approved follow-on: EnthusiaExpress mail-history advancements

- REQ-039: WHEN EnthusiaExpress is available THE SYSTEM SHALL append display-only mail advancements using its persisted mail.db history without modifying the provider database or paying rewards.
- REQ-040: THE SYSTEM SHALL recognize First Class at one sent package, Care Package at a package containing at least 64 packed items, Frequent Shipper at 10 sent packages, and Postal Legend at 50 sent packages.
- REQ-041: THE SYSTEM SHALL recognize Pen Pal at one sent letter, Correspondent at 10 sent letters, Read All About It at one read received letter, and Avid Reader at 10 read received letters.
- REQ-042: THE SYSTEM SHALL recognize You've Got Mail at one successfully delivered claimed package, Parcel Collector at 10 such claims, and Return to Sender when the original sender successfully collects a returned package.
- REQ-043: WHEN historical mail rows are first observed in a player session THE SYSTEM SHALL restore completed Express advancements silently and celebrate only later threshold crossings once.
- REQ-044: IF mail.db is unavailable, malformed or temporarily unreadable THE SYSTEM SHALL retain known session progress and retry rather than substituting zero.
- REQ-045: THE SYSTEM SHALL read EnthusiaExpress SQLite history off the server thread and SHALL use a read-only connection, including compatibility with legacy rows that predate delivery_pending.

## Approved follow-on: DiaryKeeper advancements

- REQ-046: WHEN DiaryKeeper is available THE SYSTEM SHALL append Dear Diary, First Entry, Prolific Writer, Indestructible, Stubborn, Void Walker, Nice Try and Finders Keepers using provider-owned persisted evidence without paying rewards.
- REQ-047: THE SYSTEM SHALL require one diary edit for First Entry, 25 edits for Prolific Writer, one destruction attempt for Indestructible, 10 destruction attempts for Stubborn, and one matching provider event for each remaining diary action milestone.
- REQ-048: WHEN upgrading legacy DiaryKeeper data THE SYSTEM SHALL credit Dear Diary from a persisted issuance timestamp and may seed edit, destruction, void-return, container-attempt and pickup counters only from retained provider analytics that directly prove those actions.
- REQ-049: WHEN historical diary evidence is first observed in a player session THE SYSTEM SHALL restore completed diary advancements silently and celebrate only later newly observed completions once.
- REQ-050: IF DiaryKeeper evidence is missing, unreadable or malformed THE SYSTEM SHALL retain known session progress and retry rather than substituting zero or inventing history.
- REQ-051: WHEN the DiaryKeeper branch is rendered THE SYSTEM SHALL use the supplied journal-and-quill artwork as the Dear Diary advancement icon through PAPER custom-model-data 815002, matching the same Nexo/PAPER mapping path already proven by the Enthusia advancement tab logo.

## PR cleanup safety and verification

- REQ-901: WHEN a live completion is observed THE SYSTEM SHALL recognize its transition once while retaining unacknowledged live evidence across retryable persistence failures.
- REQ-902: IF the renderer rejects a pending celebration THEN THE SYSTEM SHALL retain that pending work for a later attempt.
- REQ-903: WHEN RoseChat enables after Tags THE SYSTEM SHALL install its per-viewer presence binding once while preserving RoseChat audience restrictions and defaults when the API is unavailable.
- REQ-904: WHEN repeated playtime unit tokens are parsed THE SYSTEM SHALL count every occurrence with checked arithmetic and combine seconds before rounding.
- REQ-905: WHEN local workflow tooling reads or modifies task state THE SYSTEM SHALL reject malformed state, serialize concurrent operations, enforce required evidence, and replace files atomically.

- REQ-906: IF a companion linkage error occurs during projection or tree removal THEN THE SYSTEM SHALL contain and log that integration failure while preserving retry/cleanup behavior without swallowing fatal virtual-machine errors.
