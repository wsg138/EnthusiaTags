# KOTH rewards navigation

Base: canonical wsg138/EnthusiaTags main 28048ca, fetched 2026-10-10 in an isolated worktree. Existing holiday-reward work is preserved.

`/rewards` keeps all seven configured native categories and adds KOTH in free slot 22 when the plugin is installed. `/rewards koth [challenges|claims|results]` resolves the same provider directly. No KOTH reward definitions, grants or storage are added to Tags. KOTH exclusively checks and redeems its claims.

The adapter loads KothRewardsMenuV1 from the enabled KOTH owner's classloader, resolves the exact ServicesManager registration on each click, and validates the owner. The only payload is the caller's UUID plus an allowlisted page. Missing/disabled/old/incompatible providers fail clearly. Fatal VM errors are not swallowed. The queued portal rechecks plugin enablement, player online state, rewards permission and holder identity.

Proof: observed red enabled-owner assertion followed by green. Full Maven verification: 256 tests, zero failures/errors/skips; Node: 12 pass; hashed companion bootstrap tests: 10 pass. Canonical LoreItems release checksum verified by Maven; existing pinned Advancements/presence contracts retained. Final shaded SQLite read-only probe passes. KothMenuArtifactProbe passed against the actual KOTH shadow JAR and Tags final JAR, not a mirrored/bundled KOTH API.

Architecture: optional Bukkit/reflection adapter stays in existing infrastructure rewards package. LayerRulesTest passes. Domain, payout/eligibility/IP ownership, exclusive claims and native categories are unchanged. No network/plugin load-order dependency cycle was added: lookup is lazy after KOTH enablement.

Interactive schematic: https://enthusia-koth-setup-preview.awareyak.chatgpt.site/cleanup.html. Owner-private sign-in; local 390x844 layout checks pass. Website example states do not issue rewards; actual server/client and mobile sign-in acceptance remain pending. No server upload or restart occurred for this change.
