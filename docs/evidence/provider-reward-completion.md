# Provider completion and reward eligibility — October 26, 2026

## Scope and provenance

User requests a shared fix after report1563592825398886450 showed Correspondent announcing completion while /rewards remained0/1. Parent main28048ca and fork main6d1acc9 were freshly fetched. New isolated branch starts from parent main, retains combined candidate e3b6f76 (PR15 presentation/Diary plus exact custom sounds), and ports only the completion-tracking seam from proposed upstream PR27 head4e9f174. Existing branches/worktrees are untouched. This is an unmerged local testing candidate, not a production release.

## SPEAR proof

- `provider-completion-red.log`: real native-controller/provider-callback path plus real temporary RewardStorage reproduces all38 missing completion counters (one aggregate assertion failure containing38 subfailures, no errors). Provider callbacks are mocked; this is consumer integration proof, not a complete live provider run.
- `provider-custom-counter-red.log`: PR27's unknown-prefix handling incorrectly marks an existing administrator-owned counter unavailable (expected4, actual-1). Restricting special handling to the supported allowlist preserves ordinary custom counters.
- `provider-completion-verify.log`: Java25 canonical Maven clean verify passes305 tests, zero failures/errors/skips. Includes all38 completion counters and ordinary criterion evaluation, repeat observations, persisted state and existing claims, actual read-only provider outage/pre-join/reload fencing, loaded-state/session rejection, reward/IP/lifecycle/presentation/Diary/sound regressions.
- EARS validator,12 Node tooling tests and11 Python companion bootstrap tests pass. Python dependency was installed using the repository's exact required hash. The final shaded artifact reports `SHADED_SQLITE_READ_ONLY_OK`.
- Compilation-only setup initially needed the declared Python dependency; a temporary new test used a package-private loader from another package and was corrected to the existing public criterion constructor. Neither harness failure is counted as behavioral red.

## Architecture and limits

Domain policy uses only JDK imports; architecture suite passes. Bukkit and provider/file adapters remain infrastructure. One tracker owns existing read-only readers, independent of the renderer. RewardService keeps claim/payment authority. No automatic payout is introduced. No new tracks, criteria, catalog defaults, permission nodes or database schema are introduced. Historical completion is recovered only when retained provider evidence still proves it; no invented player credit or payout replay.

The combined build preserves prior unmerged fixes but does not imply their merge, production approval or client acceptance. No TEST/production files, player records, payouts, restart/reload or client operations occurred. Existing provider reader paths and startup enablement require operator validation before activation. TEST acceptance must verify historical completion without a new toast, a fresh completion, one manual claim and duplicate-claim rejection, reconnect/reload, provider outage recovery, and retention of Diary/single-node/custom-sound behavior.

Initial candidate: `2.2.5-provider-rewards-test.1`, SHA256 `3219d3da37d99b5ffd34d6a15f800b042fdf3bf995c7a9a8cd0298fbfebd8fad`.

Hosted first-head verify and artifact jobs passed; Codacy reported three style issues (heading spacing and two qualified class references). Formatting-only refinement fixes these; `provider-completion-refined.log` repeats305 passing tests. Refined candidate SHA256 `02a3821985c2c33fc5ecd3eb9cf4f7b360d78fb77567e747fba1f4bf06ede1b3`. Updated-head hosted checks remain separate; CodeRabbit skipped automatic review, not a completed independent review.
