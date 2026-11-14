# Guild prestige advancements

Requirements REQ-1150 to REQ-1154, tasks T-1150 and T-1154.

## How it works

LumaGuilds owns prestige. When a guild prestiges, LumaGuilds runs its configured
member commands for every eligible member who is online, and again when an
eligible member joins later (LumaGuilds REQ-152 to REQ-154). The intended
LumaGuilds configuration is:

```yaml
progression:
  prestige:
    member_credits:
      enabled: true
      min_tenure_hours: 24
      commands:
        - "enthusiatags rewards counter {uuid} guild_prestige_{tier} 1"
      first_reach_commands:
        - "enthusiatags rewards counter {uuid} guild_prestige_first_{tier} 1"
```

Tags completes the matching achievement when a counter reaches 1. Completion is
latched, so a repeated credit (LumaGuilds may repeat one after a crash) cannot
complete or pay an achievement twice. The pilot renders the six tier achievements
as a "Guild Prestige" row in the Enthusia advancement tab. The six server-first
achievements are hidden until earned: they stay out of the tab and completion
broadcasts, and appear in `/rewards` for the players who earned them.

| Achievement | Counter | Frame | Tag |
|---|---|---|---|
| Prestige I | `guild_prestige_1` | task | Prestiged |
| Prestige II | `guild_prestige_2` | task | |
| Prestige III | `guild_prestige_3` | goal | Winged |
| Prestige IV | `guild_prestige_4` | goal | |
| Prestige V | `guild_prestige_5` | goal | |
| Crowned (VI) | `guild_prestige_6` | challenge | Crowned |
| First to Prestige I–V | `guild_prestige_first_1..5` | hidden | |
| First Crowned | `guild_prestige_first_6` | hidden | First Crowned |

Rewards are cosmetic tags only. Icons are vanilla stand-ins; the pixel prestige
badges need a per-achievement item model option first.

In the advancement tab the prestige row sits below Misc (`AdvancementLayout`,
y = 29). Adding an eighth category moves the provider rows (Warzone, Reputation,
Express, Diary) down one row, from y = 29 to y = 33.

## Rolling out to an existing server

On upgrade, `ConfigMigrator` copies the new `categories.prestige` entry and the
`advancements.frames.prestige_*` settings into existing files. It never copies
`rewards.*` or `tags.*`, because those are administrator collections. Before the
restart that enables LumaGuilds member credits, copy into production:

1. The `prestige_*` and `prestige_first_*` entries from the bundled `rewards.yml`
   into the `rewards:` section, including `announce-completion: false` on the six
   tier entries.
2. The `prestiged`, `winged`, `crowned` and `first_crowned` entries from the
   bundled `config.yml` into the `tags:` section.

Test on TEST first: prestige a guild with an online member, confirm the counter
(`/enthusiatags rewards inspect <player> prestige_1`), the toast and the tag claim.

## Toast-only celebrations

The tier achievements set `announce-completion: false` (REQ-1154). Each credited
member sees their own toast; there is no chat line or Discord post per member,
because LumaGuilds already announces the prestige publicly. This needs
EnthusiaAdvancements `1.0.0-pilot.7` or later. On an older pilot, Tags logs one
warning and falls back to the full celebration, so nothing breaks.

`announce-completion: false` works on any reward. Only a boolean `false` silences
a reward; a missing or mistyped value keeps the normal announcement.
