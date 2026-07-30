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
`rewards.yml` version 4 only changes a known field when its complete old bundled value is still
present. Structural migrations create a timestamped backup in `plugins/EnthusiaTags/backups`.

## Daily rewards

`/daily` uses calendar dates in `America/Indiana/Indianapolis` by default. The timezone and the
entire payout list are configurable under `daily`. The bundled schedule pays $5, $10, $15, $20,
$30, $40, then $50 for day seven and every consecutive day afterward. Missing a calendar day
resets the next successful claim to day one.

The daily ledger persists `PREPARED`, `DEPOSITING`, `DELIVERED`, `FAILED`, and `UNCERTAIN`
transaction states. It also records the requested and returned amounts, Vault response type and
message, and observed balances around the economy call. A server restart during `DEPOSITING`
changes the entry to `UNCERTAIN` and does not automatically deposit again or advance the streak.

Vault economy providers do not expose a universal idempotency key or reliable transaction lookup.
Therefore exact-once recovery across a crash during the external deposit cannot be guaranteed.
An uncertain entry must be reviewed and reconciled by an administrator after checking the economy
provider's records and the player's balance.

The daily menu shows only the current streak, best streak, and seven payout slots. Days already
completed are marked claimed, the next day is clickable, and later days are shown as upcoming.
After day seven, the seventh slot becomes a distinct rolling item such as `Day 10` while retaining
the configured day-seven-and-later payout.

The opening animation is controlled globally under `daily.animation`; there is no player-facing
animation toggle. It renders a configurable multi-frame GUI sequence with a sound on every frame.
A successful claim only plays the separate sound configured under `daily.claim-sound` and then
refreshes the seven-day menu.

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
