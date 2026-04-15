# EnthusiaTags Testing Checklist

## Status Summary
- Build status: `mvn -q -DskipTests package` passes.
- Current scope: tags, cosmetics, rewards, offline admin actions, GUI flows, reload handling, integration gating, and persistence hardening are in place for manual server testing.
- Recommended next step: test on a staging server with the exact plugin stack you expect in production before general release.

## Known Acceptable Limitations
- Reward counter progress is flushed periodically and on quit/disable. A hard crash can still lose a small amount of very recent reward-counter progress.
- Offline player admin commands depend on UUIDs or players that the server has already seen/cached. Unknown names that have never joined the server are not guaranteed to resolve.
- Heavy particle usage can still be expensive if many players deliberately enable dense kill/projectile effects at the same time.
- Missing required integrations intentionally block affected reward paths instead of trying to guess or partially award them.

## Recommended Testing / Release Approach
1. Test first on a staging server with a copy of the intended configs.
2. Run one pass without integrations, then one pass with `Vault`, `EnthusiaCurrency`, `EnthusiaPlaytime`, and `PlaceholderAPI`.
3. Test both clean startup and `/enthusiatags reload`.
4. Test both normal quits and full stop/start restarts.
5. Only move to production after at least one restart cycle and one reload cycle complete without data loss or console errors.

## Manual Testing Checklist
1. Start the server with only this plugin and confirm the plugin enables cleanly with no startup exceptions.
2. Confirm `plugin.yml` commands register: `/tags`, `/tag`, `/rewards`, `/cosmetics`, `/enthusiatags`.
3. Join with a fresh player and confirm:
   - no tag is shown by default
   - `/tags` opens the tags GUI
   - `/rewards` opens the rewards GUI
   - `/cosmetics` opens the cosmetics GUI
4. Give a tag to an online player with `/tag give <player> <tag>` and confirm:
   - command reports success
   - tag appears in `/tags`
   - selecting it updates the floating display
5. Clear the selected tag from the GUI and confirm the floating display disappears immediately.
6. Use `/tag set <player> <tag>` and `/tag clear <player>` on an online player and confirm display updates without relogging.
7. Use `/tag revoke <player> <tag>` on a selected tag and confirm:
   - the tag is removed from ownership
   - the selected tag is cleared
   - the floating display disappears
8. Use `/tag list <player>` and confirm owned tags and selected tag are accurate.
9. Test offline admin handling:
   - have a player join once, leave, then run `/tag give|set|clear|revoke|list` while they are offline
   - rejoin and confirm the expected state applies correctly
10. Test `/tag create` and `/tag edit` as convenience tools and confirm:
   - the config updates
   - the new/edited tag is available immediately
   - `/enthusiatags reload` preserves the changes
11. Set `display-offset` with `/tag offset <value>` and confirm the visual position changes correctly.
12. Open the tags GUI and click around while interacting with your own inventory to confirm there is no bottom-inventory menu desync.
13. Test vanish behavior with your actual vanish plugin and confirm the tag hides while vanished and returns after unvanish.
14. Test world changes, respawn, and teleports and confirm the tag display reattaches correctly.
15. Test cosmetics category navigation and confirm:
   - main menu opens all configured categories
   - back button returns correctly
   - clicking bottom inventory while GUI is open does not trigger menu actions
16. Test permission-based cosmetics:
   - select a cosmetic you have permission for
   - remove the permission
   - confirm it remains saved but inactive
   - restore the permission
   - confirm it becomes active again if no replacement was chosen
17. Test join message cosmetics and quit message cosmetics with multiple players online to confirm formatted messages broadcast correctly.
18. Test kill message cosmetics and confirm custom death messages only apply when expected.
19. Test kill effects, death effects, trail effects, and projectile trail effects and confirm:
   - correct effect type is shown
   - projectile trails stop after impact
   - trail effects do not continue after logout
20. Confirm stationary players with trail cosmetics are not producing obvious repeated effects while idle.
21. Test rewards without integrations loaded and confirm:
   - affected rewards are shown but cannot be claimed
   - staff with `enthusia.tags.admin` receive warning messages on join
22. Install `Vault` and `EnthusiaCurrency` and confirm:
   - `BALANCE_AT_LEAST` rewards progress correctly
   - `MONEY` rewards claim correctly
   - baltop rewards check against the configured plugin
23. Install `EnthusiaPlaytime` and confirm:
   - playtime rewards progress correctly
   - active/afk/total minute criteria match the API values you expect
   - consecutive active and underground active criteria progress only while appropriate
24. If using `PlaceholderAPI`, confirm tag placeholders in tag text still resolve correctly.
25. Claim several rewards and confirm:
   - claimed rewards stay claimed after relog
   - tag rewards actually grant tags
   - command rewards execute once
   - money rewards do not double-award
26. During active gameplay, stop the server cleanly and restart it, then confirm:
   - tags persist
   - cosmetic selections persist
   - reward claim state persists
   - recent reward counter progress is still present after a normal shutdown
27. Run `/enthusiatags reload` while players are online and confirm:
   - commands still work after reload
   - GUIs reopen and reflect updated config content
   - tag displays remain correct
   - no duplicate scheduled tasks appear
   - no console spam or task exceptions appear

## Edge Cases To Verify
- Player joins during server load spikes while data is warming up.
- Player logs out immediately after reward progress changes.
- Offline `/tag set` for a tag the player does not own reports the correct failure.
- Missing or invalid config materials/particles fall back safely instead of breaking startup.
- A selected cosmetic with lost permission is not shown as active in the GUI.
- Reward with `MONEY` action is blocked when Vault is unavailable.
- Reward with playtime criteria is blocked when playtime integration is unavailable and placeholder fallback is disabled.
- Reward with baltop criteria is blocked when the baltop hook plugin is unavailable.

## Reload / Restart / Shutdown Checks
- `reload` path:
  - modify `config.yml`, `rewards.yml`, and `cosmetics.yml`
  - run `/enthusiatags reload`
  - confirm changes apply without requiring full restart
- `restart` path:
  - stop server normally
  - start again
  - confirm all persisted state reloads
- `shutdown safety`:
  - stop the server shortly after gaining reward progress
  - confirm progress persists after restart

## Integration Checks
- `Vault` present and functional.
- `EnthusiaCurrency` present and matches configured baltop plugin name.
- `EnthusiaPlaytime` present and registered through Bukkit services.
- `PlaceholderAPI` present if you rely on external placeholders in tag text or playtime fallback placeholders.
- Actual vanish plugin present if hidden players should suppress tag displays.

## Performance / Hot-Path Checks
- With 20-40 test players or bots if available, watch TPS and tick timings while many players:
  - use trail cosmetics
  - use projectile cosmetics
  - fight and trigger kill/death effects
- Watch for:
  - repeated console errors
  - scheduler task buildup
  - visible display desync after teleports/deaths
  - reward lag after many rapid events
- If you see particle-related lag, reduce cosmetic counts/spreads before production rollout.

## Final Release Decision
- Ready for staged release if:
  - all command flows work
  - offline admin actions behave correctly
  - restart and reload both complete without errors or state loss
  - required integrations gate rewards correctly
  - no GUI desync issues are found
  - effect-heavy testing does not create unacceptable tick impact
- Do not release yet if:
  - offline player resolution is unreliable for your staff workflow
  - reward progress is lost on clean stop/start
  - reload leaves duplicate tasks, stale displays, or broken GUIs
  - integrations silently fail without staff warning
