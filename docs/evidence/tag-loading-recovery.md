# Tag ownership loading recovery

## Scope and expected behavior

T-1140 implements REQ-1140 through REQ-1143 against fork main `6efb25f770f0431637e82ff324585b6f6f51c9b0`.
Ownership and selection remain durable storage facts. A presentation read must not turn an unfinished or failed read into verified empty ownership. Older session completions must neither publish stale ownership nor remove a newer load. Grant/revoke operations must preserve previously saved ownership when loading is incomplete; unavailable selection changes must not persist empty placeholders.

Read-only production investigation found stored ownership and valid definitions for the reported case. That supports investigating the loading lifecycle; it does not prove the precise production timing or loaded binary. No production data, configuration or runtime was changed.

## Actual behavioral proof

- Test-only commit `e42ae7f2e4c23cc7d3bb58236a9d139900939360` leaves TagService unchanged. [Hosted run 41270613873](https://github.com/FainNeito/EnthusiaTags/actions/runs/41270613873), verify job `124742444799`, ran 328 tests with six failures, zero errors and zero skips. Six of seven new service tests failed on early-access cache poisoning, retry after failure, stale reconnect publication/removal and synchronously completed future cleanup. Successful empty loading passed.
- Implementation commit `38693a603bae37a36e1433c97f0fdc4e8ff6b63d`: [hosted run 41270859691](https://github.com/FainNeito/EnthusiaTags/actions/runs/41270859691), verify job `124743166429`, passes 332 tests with zero failures/errors/skips. All eleven loading/persistence tests pass, including grant rollback, deferred revoke and unavailable selection safety. EARS, 12 Node tooling tests, pinned companion bootstrap checks and the shaded SQLite read-only probe also pass.
- Automated review on that implementation head reported two heading-spacing notices and one object-comparison warning. Refinement adds the missing spacing and uses CompletableFuture's inherited reference-based equals for the private attempt identity. Refined commit `d47f59675cab7022a6af9b4d65119f556e3b3015` passes [run 41271119252](https://github.com/FainNeito/EnthusiaTags/actions/runs/41271119252): verify job `124743929483` repeats all 332 tests and the SQLite probe; Codacy gate `124744155592` passes on that exact head.

## Architecture and integration

Only successful snapshots enter the cache. A private promise identifies each attempt, installed before any completion callback can run. A shared short lock coordinates cache publication and pending transitions; JDBC, callback completion and platform refresh occur outside the lock. Unload invalidates older callbacks. Mutating operations use the existing storage APIs and rollback behavior after successful load.

TagService remains the existing platform/storage adapter. No new production imports, dependencies, schema, provider criteria, claim semantics, permission rules or GUI presentation changes are introduced. Existing layer checks pass in the full suite. Tests use synthetic identities and existing Mockito seams; no player snapshot is committed.

The TEST version is `2.2.6-tag-recovery-test.1`. It is an unmerged review artifact. Local JVM execution remains prohibited by the shared memory hold; all Java execution above used the explicitly coordinated GitHub hosted capacity.

## Remaining acceptance

Hosted artifact `13069785434` from that refined commit has verified archive SHA-256 `affbd3c9127feff64658c3ac08f9f5d80f4539290c738fef12af3890b5204ab6`. Its shaded JAR descriptor reports `2.2.6-tag-recovery-test.1`; JAR SHA-256 is `be8e0c7fc433dd0655d4aef8371de57f2f6d15dd9d13fbed3b9029172d7ea3e5`. This artifact is unmerged and has not been uploaded or activated.

Native TEST acceptance needs an explicitly coordinated artifact/session handoff and must cover previously claimed ownership/selection across reconnect, an early tag-menu open, and preservation of unrelated saved tags after grant/revoke. Read-only client preflight encountered an imminent scheduled full-network restart warning and stopped before input; no native regression scenario was executed. No source merge, monorepo pin update, production activation or production/client acceptance is implied. Documentation-only follow-up heads still require their own hosted checks before review delivery.
