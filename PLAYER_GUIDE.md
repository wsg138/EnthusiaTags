# EnthusiaTags — SMP Player Guide

This file documents the player-facing behavior of EnthusiaTags on Enthusia SMP. The main [`README.md`](README.md) contains deeper implementation, recovery, migration, and build details.

The production values below were checked against the live Enthusia configuration on August 22, 2026. The exact achievement/reward definitions remain authoritative in `rewards.yml`, because the server currently contains about 100 separately configured rewards.

## Main player commands

```text
/tags
/rewards
/cosmetics
/daily
```

All four are ordinary player commands on Enthusia.

## Tags

`/tags` opens **Your Tags**, a 54-slot menu containing the tags the player has actually unlocked/been granted.

- Clicking an owned tag equips it.
- Only **one tag is selected at a time**.
- The Clear Tag item removes the currently selected tag.
- If the player owns no tags, the menu shows that state rather than listing tags they cannot use.
- The menu contains a shortcut into the rewards/achievement menu, since many tags are earned through achievements.

A selected tag is persisted and is used by Enthusia's tag/nametag integrations. The tag display is refreshed after joins, respawns, world changes, teleports, and invisibility-state changes so stale nametags are less likely to remain visible.

The live tag collection includes progression/achievement tags such as **Block Game Addict**, **No Life**, **Grass? Never Heard of It**, **Professional Tree Puncher**, **Diamond Hands**, **No Mercy**, **Silent Killer**, **High Roller**, and many others. The exact available set is configured under `tags:` in the production `config.yml`; players only see tags they own in `/tags`.

## Achievements and rewards

`/rewards` opens the progression/reward menu. The current reward catalog is split into these player-facing categories:

- **Playtime**
- **Mining**
- **Combat**
- **Deaths**
- **Economy**
- **Misc**

The production catalog contains roughly 100 configured rewards. Rewards can grant one or more of:

- tags;
- Raw Gold/economy payouts;
- normal Minecraft items;
- Lore Items through EnthusiaLoreItems;
- permission-based unlocks such as cosmetics or other server features.

Examples from the current production catalog include:

- **Trail Starter** — walk 20,000 blocks → unlocks a trail cosmetic;
- **First Blood FX** — 10 player kills → unlocks a kill effect;
- **Arrow Flair** — 50 projectile hits → unlocks a projectile cosmetic;
- **GG Messages** — 5 player kills → unlocks a kill-message cosmetic;
- **Block Game Addict** — 24 active-playtime hours → tag + 500 Raw Gold;
- **Payday** — 5 total-playtime hours → 150 Raw Gold;
- **Starter Pack** — mine 250 stone → 2 Golden Apples;
- **High Roller** — hold 100,000 currency → tag + 500 Raw Gold;
- **Market Access** — 10 total-playtime hours → market-stall access permission;
- **Reputation Unlocked** — 5 active-playtime hours → reputation permission;
- higher playtime, mining, combat, death, economy, and miscellaneous milestones continue beyond those examples.

The exact threshold and payout for every configured reward is defined in `rewards.yml` and should be used when producing a full wiki reward table rather than guessing from names.

### Automatic unlocks

Many achievements are evaluated automatically as the relevant stat changes. When a latched achievement is earned, the plugin records the unlock first and then sends the player an unlock notification with a clickable link to the reward entry.

Depending on the reward definition, a player may need to open/claim the reward from the rewards menu rather than assuming every configured payout is silently placed into the inventory at the instant the criterion is met.

### Active/AFK playtime rewards

Playtime achievements use the live **EnthusiaPlaytime** service. Active, AFK, total, consecutive-active, and underground-active progression can therefore be distinct criteria.

Examples:

- **Block Game Addict** uses active time, not merely connection time;
- **Payday** uses total time;
- **Sleeps in Minecraft** requires 12 consecutive hours of active time;
- **I Yearn for the Mines** tracks 10 hours of active time underground, with the production underground cutoff at Y=56.

### PvP anti-farming

Player-kill-based reward progress cannot be farmed indefinitely from the same victim.

The current anti-farm system limits qualifying credit to **five kills per killer/victim pair in a rolling 60-minute window**. That same eligibility filter applies to related PvP progression such as kill totals, kill streaks, quick kills, full-armor kills, low-health kills, and PvP death-streak rewards.

This affects **achievement progress only**; it is not a ban/punishment system.

### Natural ore/block tracking

Mining rewards that are intended to require natural resources track natural blocks rather than allowing a player to place the same ore and mine it repeatedly.

Player-placed tracked blocks do not count, including after restart and piston movement. Existing historical mining progress was baselined during the migration so old legitimate progress was not simply erased.

## Daily rewards

```text
/daily
```

opens a seven-slot daily-reward progression menu with the player's current streak and best streak.

The current schedule is:

| Consecutive claim day | Raw Gold |
| --- | ---: |
| Day 1 | 5 |
| Day 2 | 10 |
| Day 3 | 15 |
| Day 4 | 20 |
| Day 5 | 30 |
| Day 6 | 40 |
| Day 7+ | 50 per day |

The daily date boundary uses **America/Indiana/Indianapolis** time.

Missing a calendar day resets the next successful claim to **Day 1**. After Day 7 the streak continues upward, but the repeating payout remains the configured Day-7-and-later amount (currently 50 Raw Gold).

The menu distinguishes already claimed, currently claimable, and upcoming days. The current server also uses an opening animation and a separate successful-claim sound.

### One daily claim per shared IP by default

The current production configuration has the daily **IP limit enabled**. A successful—or transactionally uncertain—claim reserves that IP for the current server date so another unrelated account on the same IP cannot also claim that day.

Staff can configure sibling/shared-household exceptions so legitimate players on the same household connection may each claim. Those relationships are server-managed; ordinary players do not need a separate sibling command.

## Cosmetics

`/cosmetics` opens the cosmetic category menu. The current production categories are:

- **Projectiles**
- **Kill Messages**
- **Kill Effects**
- **Death Effects**
- **Trails**
- **Join Messages**
- **Quit Messages**
- **Misc**

A player can have **one selected cosmetic per category at a time**. Clicking an available cosmetic selects it for that category; clicking the already-active cosmetic again turns that category selection off.

Locked cosmetics remain visible in the menu but cannot be enabled until the player has the required permission/unlock. Available and active items are visually distinguished, with active selections receiving the menu's enchanted/glint marker.

The cosmetics menu links back to Tags, so players can move between the two systems without closing and retyping commands.

### What cosmetic types do

Current definitions include:

- **projectile trails** — particles follow launched projectiles; Wind Charges are intentionally excluded from this cosmetic trail handler;
- **kill effects** — particles/item-rain effects at the defeated player's location;
- **death effects** — particles or configured visual effects when the owner dies;
- **trails** — particles follow the player while moving/active;
- **kill messages** — replace the normal PvP death message with the selected cosmetic message;
- **join/quit messages** — replace that player's normal join/quit message when the corresponding cosmetic is active.

Cosmetic selections persist across reconnects. If a player no longer has permission for a previously stored cosmetic, it does not remain active merely because the old selection is still stored.

## Rank and achievement cosmetic unlocks

Cosmetics are permission-based. Some are bundled into rank permission groups and others are granted by progression rewards.

The plugin currently defines cosmetic bundles for the Avid and Devotee rank permission groups as well as individual achievement unlocks. The cosmetic GUI itself is the authoritative way for a player to see which configured cosmetics they currently have access to.

## Raw Gold payouts

Achievement and daily money payouts go through Vault/Enthusia's economy provider and are presented to players as **Raw Gold**.

Achievement money actions have a configured maximum of **5,000 Raw Gold per individual payout action**. The catalog can also award physical items or Lore Items; Raw Gold payouts should not be confused with giving literal raw-gold item stacks.

## Important player-facing commands

```text
/tags          # view/equip/clear owned tags
/rewards       # browse achievement progression and rewards
/cosmetics     # choose unlocked cosmetics
/daily         # view and claim the daily reward
```

Administrative `/tag ...`, `/enthusiatags ...`, reward-recovery, sibling-IP-management, reload, and performance commands are intentionally omitted from this player guide.

## Source-of-truth files

For future wiki generation:

- `PLAYER_GUIDE.md` — system behavior and current SMP-facing rules;
- `rewards.yml` — exact reward names, criteria, thresholds, and payouts;
- `config.yml` — exact tag definitions, daily settings, and general behavior;
- `cosmetics.yml` — exact cosmetic names/categories/effects/permissions;
- `README.md` — technical architecture, persistence/recovery, integrations, and build details.
