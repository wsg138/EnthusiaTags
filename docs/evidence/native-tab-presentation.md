# Enthusia tab presentation regression

REQ-1120 / T-1120. Approved working source: merged FainNeito/EnthusiaTags main 111d95af, retaining parent main 28048ca6. Production is read-only for this fix.

## Observed failure

The production catalog has 148 reward cards and 38 native provider nodes. Forty-eight rewards are not in the original fixed catalog. Advancement-backed Commend, Warzone, Express and Diary reward cards therefore inherit an unbounded fallback chain, separate from their native branches. The screenshot's compass/sword/chest/paper row is that fallback chain. PAPER CMD 815001 also relies on a shared material selector, despite the scoped `enthusia:advancement/logo` model and original orange texture already being present in the pack source.

## Behavioral proof

After extracting the unchanged behavior into NativeTabPresentation, five focused tests produced four assertion failures and zero errors: missing scoped model selection, provider-parent connection, bounded extra rows and unique positions. The unchanged fixed-node case passed. This is behavioral red, not a compiler/setup failure.

The implementation passes seven focused tests and full Maven verification: 285 tests, zero failures/errors/skips. EARS and twelve project tooling tests pass. A public presentation fixture reconstructed all 186 production nodes; every node key is retained, positions are unique and parent-first graph validation passes.

## Implementation and architecture

The root icon uses the configurable scoped item model; explicitly setting `advancements.logo-item-model` to an empty string retains CMD-only behavior. Scoped mode requires the existing logo pack. No root texture is replaced.

Existing fixed reward branches retain their positions. Each available provider gets a compact band with a separate row for each reward card, connected to the advancement identified by its single `advancement_reward:` criterion. Unavailable providers and custom/legacy/supporter rewards use deterministic four-column branches. Browser inspection corrected the first layout attempt so claim cards cannot appear inline as prerequisites for subsequent native advancements.

The composer preserves node keys, text, icons, custom model data, frames and item-model metadata. No completion criterion, reward action, database, permission, pending celebration or manual-claim authority changes. Multiple-criterion rewards are not attributed to a single provider. Tests cover missing providers, multiple claims for a source, all bundled rewards with every provider, and explicit legacy logo mode.

## Visual and delivery limits

The responsive owner-private preview uses Java-exported before/after coordinates and source logo assets. A 390px browser check confirms no document overflow and working branch navigation. Browser controls and highlights are simulations; flat block/entity texture representations are approximations. Native Minecraft texture rendering, client pack selection and gameplay acceptance remain separate TEST checks.

No production upload, configuration change, pack reload or server restart was performed for this fix. Source PR and exact-head hosted results are recorded in the delivery report; merge and Network pin reconciliation are required before any future production replacement.

[Interactive owner-private preview](https://enthusia-advancement-layout-preview.awareyak.chatgpt.site) (sign-in may be required). Deployment appgdep_6ad4318c20e48191a8957da42e051437 succeeded. Static source b97b118957729f489e4d9398ad6291d525480494 uses the Java-exported coordinates. Local static packaging helper requires unavailable Bash, so publication used the supported source-backed remote-build fallback.
