# Holiday tag icons

## Pumpkin Patcher

`pumpkin_patcher_seeds` is the original two-seed badge, U+A0A1 (`ꂡ`), 12x10 PNG,
font height 10/ascent 8. The approved bold sage-to-cream title has one cluster on
each side, outside bold. It does not reuse or modify Pumpkin King's U+A0A0.

The default tag entry is in config.yml; read-only source metadata in
menu-sources.yml and Halloween catalogue membership in rewards.yml. Existing
servers require an additive scoped patch to config.yml, entitlements.yml and
categories.halloween.tags; these defaults do not overwrite administrator files.
There is no automatic qualification or public reward rule.

Copy the new glyph YAML into plugins/Nexo/glyphs/enthusia/ and its PNG into the
matching Nexo pack path. Rebuild the pack with `/nexo reload pack`, then reload
Tags using `/enthusiatags reload`. Verify the glyph before awarding with
`/tag give ToxikFlame11 pumpkin_patcher` and `/tag give Bobsuit pumpkin_patcher`.
These grants do not change either player's selected tag. No server restart or
plugin JAR update is required. Do not delete, replace or reassign existing glyphs.

Run `python resourcepack/holiday-tags/verify_pumpkin_patcher.py` with PyYAML
available to check the exact static packet. See docs/evidence/pumpkin-patcher.md.

## Pumpkin King

Glyphs drawn inside holiday reward tags, e.g. `pumpkin_king`:
`ꂠ` + bold "Pumpkin King" (#FF3A00 → #FF5A00 gradient) + `ꂠ`.

- `pumpkin.png` is the jack o'lantern from the LumaGuilds Halloween menu icon set
  (`lg_emoji_halloween`, `lumaguilds:enthusia/halloween/emoji`, 16x16) with its sculk glow recoloured to candlelight.
- `pumpkin_sculk_alt.png` is the original, sculk-glow icon, kept as an alternative.

Nexo install:

1. Copy `Nexo/glyphs/enthusia_holiday_tags.yml` to `plugins/Nexo/glyphs/enthusia/`.
2. Copy `Nexo/pack/assets/enthusia/textures/font/tags/pumpkin.png` to the same path under `plugins/Nexo/pack/`.
3. Run `/nexo reload all` and have clients accept the rebuilt pack.

The glyph uses U+A0A0, next to the LumaGuilds glyphs (U+A040–U+A080), and is written straight into the tag text,
so it renders in chat, TAB and name tags without a glyph placeholder. Without the pack (or on Bedrock) it shows as `ꂠ`.
