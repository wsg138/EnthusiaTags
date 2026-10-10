# Holiday tag icons

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
