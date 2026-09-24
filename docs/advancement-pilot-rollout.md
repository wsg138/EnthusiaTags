# Local pilot: current status and deployment guardrails

The local test-server pilot is assembled, not production certified. Gold-only network restrictions, PlayTime read-failure safeguards, explicit Original choices, companion RoseChat presence replacement, native advancement projection, silent historical restoration and the supplied-logo resource pack are implemented. No new challenge definitions have been added. The complete install/rollback checklist is `../../outputs/EnthusiaTags-26.2-test-bundle/TEST-SERVER-README.md`.

## Gold-only network policy

- Existing MONEY actions use Vault and count as the server's Raw Gold currency. ITEM actions for RAW_GOLD and RAW_GOLD_BLOCK also count.
- One account owns the gold payout for a given challenge and IP, including all gold components in that challenge. Other IPs have independent eligibility. This is an IP rule, not reliable identification of individual humans; NAT/shared households and address changes have their normal implications.
- Tags and other non-gold components do not use the IP gate. A challenge with withheld gold can finish its reward claim after its other components settle.
- Gold denial is recorded as WITHHELD_NETWORK_LIMIT in the existing component ledger; it is not recorded as a successful deposit. That component does not become eligible merely by changing IP.
- A missing address or failed database check is retryable, not a permanent denial. Gold reservations are conservative and retained after an uncertain or failed delivery so another account cannot take over during recovery. The owning account can retry a definite delivery failure.
- Old whole-reward reservation records are retained as conservative gold evidence. Login no longer invents history using the current address. Existing bypass-pair records are retained but do not bypass the new gold rule. Existing IP reconciliation commands still refer to the legacy table; inspection additionally shows gold-only reservations.
- Arbitrary COMMAND and LORE_ITEM actions cannot be safely inferred to contain currency. Configure gold with typed MONEY or RAW_GOLD item actions. Audit custom commands/reward bundles before staging; do not use them to bypass this policy.
- Daily rewards are a separate subsystem and were not changed.

## Data and rollback

No production database has been opened or changed. The schema addition is `reward_gold_ip_claims`; existing player, claim, tag and selection rows are not rewritten. Legacy queued item deliveries remain accepted obligations.

Before any future deployment, stop the server and back up the complete EnthusiaTags data directory (including SQLite WAL/SHM files if present), plugin JARs, dependent-plugin data/config and resource pack. Test against a copy first. **Do not downgrade the binary in place after this version writes WITHHELD_NETWORK_LIMIT**: the old enum reader does not understand it. A rollback requires a coordinated data backup/restore and reconciliation of any rewards issued after that backup; never blindly restore stale balances or claim records.

## Required staging checks

1. User confirmed server 26.2, with 26.3 planned. The bundle includes official UltimateAdvancementAPI 2.8.1. Tags and the separate EnthusiaAdvancements/pilot module compile against Paper 26.2.build.124-stable with Java 25; the full Kotlin EnthusiaAdvancements distribution is not used by this pilot. RoseChat retains its existing fork baseline. Actual server/client compatibility remains a staging check; 26.3 is unverified.
2. Two accounts on one IP complete one existing challenge. Both keep completion and tag eligibility, only one receives all gold components. Repeat after restarting.
3. Gold verification unavailable: non-gold delivery persists, retry never duplicates it. Confirm actual proxy forwarding supplies the intended client IP rather than the proxy address.
4. Test full inventory/overflow and uncertain Vault delivery recovery, including abrupt shutdown on either side of a deposit. Staff reconciliation must not replay an already delivered component.
5. Presentation staging: old completions appear silently, verified new online completions toast/announce once, and no rewards are issued by the rendering layer. Abrupt shutdown can lose an already-committed notification; it must never replay rewards. Use default individual UAA teams, not shared teams.
6. RoseChat staging: selected messages replace only the eligible per-viewer original; Original restores configured RoseChat behavior; vanished subjects remain hidden. Test join and quit with staff visibility, multiple configured templates, disabled/default-suppressed messages, custom-message permission removal, independent join/quit selections and reconnect/restart.

## Companion RoseChat presence API

The modified local fork is `../Enthusia-RoseChat`, origin `https://github.com/wsg138/Enthusia-RoseChat.git`. Its new `PresenceMessageEvent` fires once per viewer only after the existing staff visibility checks and template conditions produce non-empty default lines. The subject and viewer are immutable. The selected custom template replaces the aggregated lines; RoseChat parses it in the same subject/viewer context. Empty default audiences remain empty. Choosing Original abstains, preserving every original line.

This feature requires paired Tags and companion RoseChat builds and a full restart, not plugin hot-reloading. With an older RoseChat build, Tags logs one startup compatibility warning and preserves RoseChat defaults rather than broadcasting an unsafe fallback. With no RoseChat, Tags retains Bukkit-message replacement only when the original event message is non-null. Quit selection cleanup now runs at MONITOR after RoseChat's HIGHEST handler. Kill-message mutation no longer runs at MONITOR and respects an already suppressed death message.

Local verification exercises the actual companion event source against Paper, cancellation, replacement and Original, plus lifecycle ordering and fallback suppression. It does not prove live vanish/audience behavior on 26.2; staging remains mandatory. Install the paired JARs from the final test bundle, not earlier intermediate artifacts.

No commit, push, PR, release or deployment is authorized by this checklist.
