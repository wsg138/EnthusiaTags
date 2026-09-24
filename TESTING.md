# EnthusiaTags testing guide

This file is the maintainer and worker guide for repository-local automated tests. Handwritten Tags tests live in this repository; Sentinel Sim is a separate runtime/compatibility layer and does not replace these tests.

## What this test-hardening branch adds

### `TagTextFormatTest`

Protects formatting and user-controlled text boundaries:

- null/empty behavior;
- existing MiniMessage preservation;
- legacy `&` formatting conversion;
- legacy hex conversion;
- stable visible text across conversions;
- escaping user-controlled MiniMessage tags so dynamic values cannot inject formatting;
- canonicalization of legacy-formatted dynamic values.

A failure here can indicate a visible formatting regression or an unsafe dynamic-text interpolation change.

### `CosmeticsStorageTest`

Protects the SQLite cosmetic-selection ledger:

- selections are isolated by player and category;
- insert and update/upsert behavior;
- clearing one category does not delete other categories or another player's state;
- async work fails closed after storage shutdown;
- success metrics are recorded.

This test uses a temporary SQLite file only. It must never use production data.

### `FullFeatureCoverageContractTest`

This is a coverage-inventory guard. It maps major production feature families to concrete regression tests so coverage cannot silently disappear when tests are removed or reorganized.

It currently guards tag formatting, placeholders, nametag refresh/rendering, config migration, cosmetics persistence, daily rewards, anti-farm/natural-block accounting, reward persistence/recovery, money/config policy and LoreItems handoff/API behavior.

**It is not proof that a feature is correct merely because a file exists.** When adding or changing a feature, first add behavioral assertions that can fail for the real regression, then update the coverage map if the feature family or evidence path changed.

## Running tests

Java 21 is the expected toolchain.

Full repository test/package gate:

```bash
mvn --batch-mode --no-transfer-progress clean test package
```

Normal focused test run:

```bash
mvn --batch-mode --no-transfer-progress test
```

Individual classes:

```bash
mvn -Dtest=TagTextFormatTest test
mvn -Dtest=CosmeticsStorageTest test
mvn -Dtest=FullFeatureCoverageContractTest test
```

The repository's Sentinel artifact workflow also runs the full Maven test/package command against the exact pull-request head before publishing an artifact.

## Where results are

Maven Surefire output:

- `target/surefire-reports/*.txt`
- `target/surefire-reports/TEST-*.xml`

GitHub Actions shows the same failures in the failing build/test step. For exact-head evidence, record the PR head SHA and the workflow run/job that tested that SHA.

## How to review a failure

Classify the failure before editing anything:

1. **Behavior regression** — an assertion reached production code and the returned state/output is wrong. Fix the owning production behavior or update the assertion only when the intended contract genuinely changed.
2. **Coverage-contract failure** — a required test/evidence path disappeared or a feature surface changed. Find the replacement behavioral evidence; do not point the contract at an unrelated file just to make it green.
3. **Harness/build failure** — compilation, dependency resolution, Maven configuration or CI failed before the behavior ran. Fix the harness/build issue without weakening the assertion.
4. **Sentinel/runtime boundary** — repository tests pass but a built plugin fails a Sentinel/MockBukkit/Paper lifecycle check. Keep that evidence separate and follow the Sentinel runbook.
5. **Infrastructure failure** — no runner/zero steps/transient GitHub infrastructure. Do not call it a test pass and do not change product code merely to create activity.

## Adding or changing a feature

For every material behavior change, review at least:

- success path;
- permission/eligibility rejection when applicable;
- malformed or hostile input;
- duplicate/retry/idempotency behavior;
- persistence and restart behavior when state is durable;
- shutdown/closed-resource behavior for async storage;
- PlaceholderAPI/dynamic text escaping for user-controlled strings;
- LoreItems availability/reload/idempotency when that integration is involved.

Add the narrowest deterministic repository-local test first. Use Sentinel for plugin loading, simulated player sequences and compatibility; use real Paper when behavior depends on server internals that MockBukkit cannot model honestly.

## Security and privacy

Tests must not contain production SQLite databases, player records, secrets, tokens, webhooks, credentials or reconstructable private evidence. Generate temporary/fake data in the test itself.

## Worker coordination

Before modifying tests, reconcile current `main`, open PRs and changed paths. This test-hardening PR is intentionally test/documentation-only. If it discovers a real product defect that overlaps another active worker, publish the finding and repair it in the correct owning branch rather than hiding unrelated production changes here.
