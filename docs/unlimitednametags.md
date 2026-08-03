# UnlimitedNametags integration

EnthusiaTags owns tag definitions, ownership, selection, menus, rewards, cosmetics, storage, and the public `TagVisibilityService`. It no longer creates or mounts floating display entities. UnlimitedNametags is the sole owner of above-player nametags.

Add the selected tag to the appropriate UnlimitedNametags display group as an ordinary row:

```yaml
- text: "%enthusiatags_selected_mm%"
```

The exact surrounding UnlimitedNametags YAML structure depends on the server's display-group configuration. EnthusiaTags never replaces the complete display-group configuration.

## Placeholders

- `%enthusiatags_selected_mm%` — complete selected line in MiniMessage
- `%enthusiatags_selected_plain%` — complete selected line without formatting
- `%enthusiatags_selected_id%` — selected tag ID
- `%enthusiatags_selected_legacy%` — legacy ampersand compatibility output

All four values are empty while no valid tag is selected, while player data is still loading, when the selected definition was removed, when the owner is vanished under EnthusiaTags' existing visibility rules, or while `TagVisibilityService` suppression is active.

## Configuration migration

`config-version: 5` converts only `line-format` and each configured tag's `display-name` and `tag-text` from legacy ampersand formatting to MiniMessage. Custom tags and unknown tag fields are retained. Existing descriptions, messages, rewards, cosmetics, and daily-reward configuration are not rewritten. The obsolete `display-offset` setting is removed after a migration backup because UnlimitedNametags now controls positioning.

Legacy tag values remain accepted at runtime, so an installation remains readable if a migration cannot be saved. Player-derived placeholder values are escaped before MiniMessage parsing, and external PlaceholderAPI tokens are evaluated once to prevent recursive expansion.

## Optional dependency behavior

UnlimitedNametags is a soft dependency. If it is absent or its API is incompatible, EnthusiaTags still enables and all non-display functionality remains available. No fallback hologram is created. The server log contains one warning that selected tags need a compatible nametag consumer before they can appear above players.
