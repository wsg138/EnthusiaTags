# Forgotten Melody: one celebration owner

## Specification and cause

REQ-1123 / REQ-1124, T-1121. Base: Tags release fork main
`a2d7d9e`; Holidays canonical main `a5bfa75`.
Holidays `WatcherController.rewardAtEnd` grants the configured
`holidays/forgotten_melody` reward and increments `watcher_theme_heard`.
Holidays `AdvancementGranter` celebrates its newly earned node. The Tags
reward `watcher_melody` consumes that counter, and its native notification
callback also celebrated a distinct Tags node with the same title. These are
two producers, not evidence of duplicated disc payment.

## Prove, engine, architecture and refinement

The controller regression exercises the real reward-notification callback and
projection tick with the actual bundled Melody ancestry. After correcting an
initial incomplete test fixture, the unchanged production code failed exactly
one test with `NeverWantedButInvoked` on the Melody celebration. The other five
controller tests passed. `melody-red.log` retains that actual run locally.

The adapter now consults the pure RewardCelebrationOwnership policy before
queueing celebrations. Only `watcher_melody` is delegated when Holidays is
enabled. Native completion still projects; manual reward claims, counters,
SQLite stores, reward actions, title colors and all other reward IDs remain
unchanged. No schema/API/config migration or scheduler is added. When Holidays
is disabled or absent, the existing Tags celebration remains.

Three controller regressions cover delegated celebration plus preserved
completion/no claim execution, standalone Tags, and an unrelated reward with
the same display title. Focused suite: six passing. Canonical offline
`mvn clean verify`: 288 tests, zero failures/errors/skips, Java25 and the pinned
Paper26.2 API. EARS validation, 12 Node tooling tests, 11 Python companion
validation tests, checksum-pinned presence contract and final shaded-JAR
SQLiteArtifactProbe all pass (`SHADED_SQLITE_READ_ONLY_OK`).

Initial build prerequisites were missing in the isolated worktree; the first
build stopped before tests. Verified LoreItems input and presence contract
were copied from the prior checkout; the canonical checksum checks passed.
Python defusedxml was installed into ignored `.ci-deps/python` using the
workflow's hash-pinned requirements before the successful tooling run.
No prerequisite/fixture error is presented as the behavioral RED.

The source was reviewed against both producer paths and the unchanged native
projection/manual claim paths. Arbitrary administrator aliases or a setup
that removes the Holidays advancement grant are outside the shipped contract.
No global title/time-based chat suppression is introduced.

## Delivery boundary

Unmerged local TEST artifact SHA256:
`767e748898eed40b9c5cfc760df0489d8812c449bfe6609993e20ca8b9582328`.
This is not a production release. No live server command, reset, deployment,
merge, reload, restart or client test was performed for this fix. Native
acceptance must observe one complete natural listen and one chat announcement;
no existing player's earned records were cleared to manufacture that proof.
Network dependency pin and clean merged-source build remain release steps.

Rollback: the change has no persisted data migration; before deployment,
retain the prior binary and follow the existing Tags release/data safeguards.
