# EnthusiaTags

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

The daily GUI has a persistent per-player animation preference. The global animation switch can
disable animations without overwriting individual preferences.

## Unlock notifications

Latched achievements store their unlock marker before the notification is sent. The notification
includes a permission-checked `/rewards open <reward-id>` link and a configurable subtle sound.
Text and sound settings are in `messages.yml` and `config.yml`.

[![Codacy Badge](https://app.codacy.com/project/badge/Grade/9c192e33b3d94e69b212a465ffdc08fb)](https://app.codacy.com/gh/wsg138/EnthusiaTags/dashboard?utm_source=gh&utm_medium=referral&utm_content=&utm_campaign=Badge_grade)

Tags, rewards, and cosmetic menu plugin with PlaceholderAPI and economy integrations.

## Build

```powershell
mvn -q -DskipTests package
```
