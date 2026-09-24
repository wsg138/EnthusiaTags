# Warzone Duels advancement integration

This optional, display-only track adds nine branched nodes to the existing Enthusia advancement tab.

| Display name | Stable key | Requirement |
| --- | --- | --- |
| Welcome to the Thunderdome | warzone_duels/welcome_to_thunderdome | Send a valid duel challenge |
| Arena Initiate | warzone_duels/first_blood | Win 1 duel |
| Arena Win Streak | warzone_duels/unstoppable | Reach a best win streak of 5 |
| The Gladiator | warzone_duels/gladiator | Win 50 duels |
| To the Victor Go the Spoils | warzone_duels/victor_spoils | Withdraw at least one captured item from the duel vault |
| A Price for Peace | warzone_duels/price_for_peace | Complete a duel by mutual draw agreement |
| My House, My Rules | warzone_duels/my_house_my_rules | As the 1v1 challenger, win a kill-result duel with non-default rules |
| Adapt and Overcome | warzone_duels/adapt_and_overcome | Win a kill-result duel with Ender Pearls and Wind Charges disabled |
| Not Even Close | warzone_duels/not_even_close | Win a 1v1 kill-result duel below four health points, before healing |

Arena Initiate and Arena Win Streak deliberately use different display names from the ordinary combat challenges. Stable keys are unchanged. Ordinary win totals follow the provider's individual and Duel Party statistics; conditional milestones use the restrictions in the table. No additional currency or cosmetic rewards are granted.

## Dependencies and enabling

Use the reviewed WarzoneDuels 1.0.3 provider with six durable event counters (FainNeito/WarzoneDuels PR #1), the owner-aware EnthusiaAdvancements pilot.4 renderer, compatible UltimateAdvancementAPI, and the Tags build from PR #2. Do not assume an older provider supplies the new event counters. Replace required companion JARs together while the test server is fully stopped, retain existing player data and configuration, and enable the existing key:

```yaml
advancements:
  enabled: true
  warzone-duels-enabled: true
```

The option defaults to false. Do not duplicate the advancements YAML section or leave multiple versions of the same plugin installed. Restart to apply enable/disable changes. This checklist is not a deployment instruction executed by PR cleanup.

## Safety and history

The bridge reads stats.yml asynchronously every five seconds without modifying it or touching reward ledgers. Explicit nonnegative integer wins and best-win-streak are required per UUID. Six optional counters live under advancements: challenges-sent, spoils-claims, mutual-draws, custom-rules-wins, restricted-mobility-wins, and low-health-wins.

Legacy missing counters remain zero; historical conditional accomplishments are not guessed from win totals. A present wrong-shaped advancement section is rejected, not treated as absent. Missing/unreadable/malformed snapshots retain known session progress and cannot create a false zero baseline. An absent UUID in a valid complete snapshot means no recorded history.

The first valid post-join snapshot restores provable history silently. Later threshold crossings celebrate once. Events before the first baseline or during shutdown may appear silently; unit tests do not prove live toast delivery.

## Verification provenance

The initial three-node pilot had a historical 156-test run and an older provider schema. Those figures are not the current nine-node acceptance result. The previous cleanup at df3dd31 passed 173 Java tests plus 8 separate tooling tests. Current follow-up validation is recorded in docs/pr-stack-verification.md after clean verification; GitHub Actions checks the exact pushed head and Codacy is a separate gate.

No guild-war champion, spectator-betting, or hidden-interaction advancement is added here. Nexo icon troubleshooting remains outside this pass.
