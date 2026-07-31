# EnthusiaTags

[![Build](https://github.com/wsg138/EnthusiaTags/actions/workflows/build.yml/badge.svg)](https://github.com/wsg138/EnthusiaTags/actions/workflows/build.yml)
[![Codacy Badge](https://app.codacy.com/project/badge/Grade/9c192e33b3d94e69b212a465ffdc08fb)](https://app.codacy.com/gh/wsg138/EnthusiaTags/dashboard?utm_source=gh&utm_medium=referral&utm_content=&utm_campaign=Badge_grade)

Tags, rewards, cosmetics, and daily-reward plugin with PlaceholderAPI and Vault economy integrations.

| Download | Version | Runtime |
| --- | --- | --- |
| [EnthusiaTags.jar](https://github.com/wsg138/EnthusiaTags/releases/download/latest/EnthusiaTags.jar) | rolling `main` build (`2.1.0`) | Java 21, Paper/Leaf 1.21.11 |

The `latest` download is rebuilt from `main` after every merged commit. Pull requests and branch pushes also run the full Maven test/package workflow and retain a downloadable Actions artifact.

## Reward configuration compatibility

Upgrades preserve administrator-owned configuration. Existing values win, unknown rewards and tags
are retained, and missing bundled reward or tag entries are not forced into an existing collection.
`rewards.yml` version 5 changes only recognized unchanged payout values and known physical-gold
reward actions. Structural migrations create a timestamped backup in `plugins/EnthusiaTags/backups`.

The bundled configuration contains 100 rewards. Currency actions use `type: MONEY`, are deposited
through Vault, and are displayed as raw gold. Achievement rewards never deliver gold nuggets,
ingots, blocks, raw-gold items, or raw-gold blocks as currency. The bundled payout scale ranges from
50 to 5,000 raw gold, and the loader rejects any money action above the 5,000 per-action maximum.
Only Two Thousand Hours, Ten Million Steps, and Ultimate Survivor use the maximum.

## Daily rewards

`/daily` uses calendar dates in `America/Indiana/Indianapolis` by default. The timezone, displayed
currency label, and entire payout list are configurable under `daily`. The bundled schedule pays
5, 10, 15, 20, 30, 40, then 50 raw gold for day seven and every consecutive day afterward.
Missing a calendar day resets the next successful claim to day one. Invalid, negative, or non-finite
payout values are rejected and replaced with the safe bundled schedule before Vault is called.

The daily ledger persists `PREPARED`, `DEPOSITING`, `DELIVERED`, `FAILED`, and `UNCERTAIN`
transaction states. It also records the requested and returned amounts, Vault response type and
message, and observed balances around the economy call. A server restart during `DEPOSITING`
changes the entry to `UNCERTAIN` and does not automatically deposit again or advance the streak.

Vault economy providers do not expose a universal idempotency key or reliable transaction lookup.
Therefore exact-once recovery across a crash during the external deposit cannot be guaranteed.
An uncertain entry must be reviewed and reconciled by an administrator after checking the economy
provider's records and the player's balance. A failure before Vault is invoked is marked retryable
instead of leaving the player with a permanently processing claim.

The daily menu shows only the current streak, best streak, and seven payout slots. Days already
completed are marked claimed, the next day is clickable, and later days are shown as upcoming.
After day seven, the seventh slot becomes a distinct rolling item such as `Day 10` while retaining
the configured day-seven-and-later payout.

The opening animation is controlled globally under `daily.animation`; there is no player-facing
animation toggle. It renders a configurable multi-frame GUI sequence with a sound on every frame.
A successful claim only plays the separate sound configured under `daily.claim-sound` and then
refreshes the seven-day menu. If the animation completion task cannot be scheduled, the service
falls back to opening the normal daily menu instead of leaving an unusable animation inventory.

A reload pauses new daily claims while an in-flight claim finishes. It retries with a short backoff
for up to 20 seconds and logs a warning instead of creating an unbounded every-tick retry loop.

## Unlock notifications

Latched achievements store their unlock marker before the notification is sent. The notification
includes a permission-checked `/rewards open <reward-id>` link and a configurable subtle sound.
Text and sound settings are in `messages.yml` and `config.yml`.

## Build

Use JDK 21 and Maven:

```bash
mvn --batch-mode --no-transfer-progress clean test package
```

The server-ready output is `target/EnthusiaTags.jar`.

Every push and pull request runs the build workflow and uploads the JAR as a GitHub Actions artifact.
Every push to `main` also replaces the rolling `latest` prerelease asset used by the download table.


## Reward anti-farming and natural ores

- Player-kill reward credit is limited per killer/victim pair by a configurable cooldown and rolling-window cap.
- The same filter applies to kill totals, kill streaks, quick kills, armored kills, low-health kills, and PvP death-streak rewards.
- Existing kill progress is snapshotted once when a player first joins after the update.
- Ore and ancient-debris milestones use durable natural-block counters. Player-placed tracked blocks do not count, including after a restart or piston move.
- Existing mining progress is snapshotted once before natural-only tracking takes over.

The default anti-farm values are configured under `rewards.anti-farm.kills` in `config.yml`. Minecraft does not retain origin metadata for blocks placed before this update, so the one-time baseline preserves existing mining totals; strict natural-only tracking applies to blocks placed and mined after the upgrade.

## Daily IP limits

A successful or uncertain `/daily` transaction reserves the player's IP for that server date. Unrelated accounts on the same IP cannot claim another daily reward that day. Definite pre-Vault failures release the reservation safely.

Shared-household exceptions are managed with:

```text
/enthusiatags daily sibling add <player1> <player2>
/enthusiatags daily sibling remove <player1> <player2>
/enthusiatags daily sibling list <player>
```

Sibling relationships are transitive, so a connected household group may all claim from the same IP.
