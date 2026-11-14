# Single provider card and Dear Diary model (T-1125)

## Spec and provenance

Authoritative upstream main: wsg138/EnthusiaTags 28048ca64d460534fd8bac2d6295f7283b4aa27d.
Preserved production fork base: FainNeito/EnthusiaTags 6d1acc9b72c81cb375402bb22f30be4beb688668 (upstream is an ancestor).
New isolated branch: fix/single-entry-diary-icon. Existing release checkouts/PRs are not edited.
Renderer: BadgersMC/EnthusiaAdvancements 01fac426152737c76cd4d2e570e07a33dde99798.
The compiled ProjectionService and Node class bytes match the read-only downloaded production renderer exactly; cached artifact archive hashes differ and are not represented as identical binaries.

October 25 GET-only configuration audit found 149 reward definitions and 38 provider mirrors (11 Express, 10 Commend, 8 Diary, 9 Duels), not duplicate stable keys or proof of double payment. Production diary_book.yml names WRITABLE_BOOK/nexo:diary_book; diary_book.png is 535 bytes, SHA256 d96ed460a6f02af919bbb16e965466f4f1f0b843de2b460861270ac63e81a691. The old bridge hardcodes PAPER/815002. No player database was exported.

## Prove and engine

Initial new-API regression compilation failed because the presentation adapter and configurable diary model overload did not exist (red.log). This is not claimed as an assertion RED.
After implementation, an independent unchanged 6d1acc9 baseline worktree was checked with the same real-controller notification regression: one assertion failure, zero errors, because it announced the mirrored reward. The independent diary baseline also failed exactly one assertion (expected WRITABLE_BOOK, actual PAPER). These baseline confirmations were run after engine implementation, not backdated as pre-implementation tests. Logs: ../tags-single-entry-baseline/baseline-red.log and diary-baseline-red.log.

Final regression coverage includes early/late reward callback ordering around provider completion, historical progress max-merge, missing-provider fallback, multi-criterion/higher-threshold preservation, multiple aliases, stable descendants/metadata, no title-based merging, named/custom/legacy diary models, existing Melody ownership and no claim invocation.

Java 25 canonical clean verify passes 296 tests with zero failures/errors/skips. EARS, all 12 Node tooling tests, all 11 Python companion validation tests and the presence-source checksum pass. Missing local defusedxml was resolved using the repository's hash-pinned requirements in ignored checkout-local tooling; no test claim is based on that prerequisite failure.

## Architecture and delivery gates

Presentation only; reward definitions and provider databases are read-only inputs, RewardService owns all manual claims and payouts. No existing player records, permissions, claims or counter amounts are changed. The earlier durable-completion PR #31 remains a separate open upstream change; this presentation correction does not claim to solve uncertain-delivery or restart-history failures.

Private preview: [Interactive presentation simulation](https://enthusia-diary-single-milestone-preview.awareyak.chatgpt.site).
Sites source 41cfe3daf71136c95c9788927ba818262f259e96; owner-private deployment succeeded. Browser checked at 390px and 320px with no horizontal overflow; real diary image loads and before/after, menu and provider-unavailable controls work. The preview is a simulation, not client acceptance.

Roundtable SHARED request 1388 and EXPRESS owner message 1389 seek matching Tags publication ownership acknowledgment. No acknowledgment received at the last check; publication remains pending. No live TEST/prod command, upload, reload, restart, merge, monorepo pin or player/client mutation is authorized or performed. Hosted exact-head checks and native/Discord acceptance remain pending.

## Release follow-up

The user subsequently authorized PR/review, merge if checks pass, monorepo release and production staging without restart/reload, and explicitly confirmed taking over this release while leaving other branches/worktrees untouched. Roundtable takeover is recorded as SHARED1419/EXPRESS1420. That supersedes the earlier pending-ack status, not the production release gates. Existing upstream and network permissions are read-only; maintainer approval/merge access remains necessary. The refined release candidate passes 297 Java tests, zero failures/errors/skips (release-verify.log).

Review found that an explicitly non-counter criterion source can override the legacy criterion type. A new regression failed one assertion before narrowing alias matching to LEGACY/CUSTOM_COUNTER sources. The release candidate version is 2.2.4, still unmerged and not deployable merely because its version lacks a test suffix. The preserved c58f00f candidate and its earlier artifact/test receipts remain historical evidence.

Fork PR #15 at 04264c8 passed hosted verification and artifact jobs; Codacy reported a bare Markdown link and mirrorTarget complexity 13/10. The link is now descriptive Markdown and counter validation is split into a small predicate without changing behavior. CodeRabbit reported automatic review skipped, not a completed code review. The user redirected staging to SMP Test before production changes; no production action was performed. TEST staging requires an acknowledged handoff from the current message/sound task and a later separately authorized restart for activation.
