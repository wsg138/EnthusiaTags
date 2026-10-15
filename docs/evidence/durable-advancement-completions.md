# Durable advancement completions - REQ-920 through REQ-922

## Observed report and scope

John_Raidfarms appears twice for First Class in Discord, roughly eleven days apart. Read-only production inspection on 2026-10-15 confirms startup of EnthusiaTags 2.2.2-unique-mail-test.1 and EnthusiaExpress 1.2.1. The precise original transition cannot be reconstructed from these screenshots/logs. Ordinary reconnect is already intended to be silent; Express retention keeps sender history rather than deleting mail rows. No claim of a proven retention/reconnect root cause is made.

Canonical Tags main was fetched at `28048ca64d460534fd8bac2d6295f7283b4aa27d`. The missing cross-session completion guard is independently reproducible: queue the same completed key, reset the session, and queue it again. The original controller calls the renderer twice. `DuplicateAnnouncementTest` failed on that behavioral assertion before implementation (`duplicate-red.log`, local ignored evidence).

## Implementation and proof

The renderer-facing controller now waits for asynchronous completion-history reconciliation, projects the durable completion floor, and calls the renderer only for a newly committed live companion-provider completion. This covers Express, Commend, Diary and Warzone Duels nodes and preserves UUID/key isolation. Ordinary reward nodes retain RewardService's existing durable unlock path; a regression covers the asynchronous reward-callback ordering so the new guard cannot suppress a legitimate reward toast. Historical reconciliation remains silent. A dedicated additive SQLite file avoids changing the live provider/reward schemas; its application interface cannot execute rewards.

Tests cover actual controller requeue/reconnect/reconstruction, SQLite close/reopen, concurrent connection insert winners, independent players and keys, transaction rollback after an injected second-insert failure, unreadable storage, delayed and failed futures, silent historical seeding, and removal of obsolete definitions. Existing projection-linkage, VM-failure and renderer retry tests remain in the full suite. Pending storage writes have no Bukkit callback: quit/close discard the session, preventing late work from notifying a replacement session.

Validation: JDK 25.0.3, Maven 3.9.11, canonical Paper API 26.2.build.124-stable. Final `mvn -B -ntp clean verify`: 261 tests, zero failures/errors/skips, shaded SQLite driver check passed. Rebuilt exact canonical renderer pin `af9cd7772d14fce201d58df28c3ef5c32e335319`: 40 tests passed. Companion pin/source validators, 10 Python bootstrap tests, EARS validation and 12 Node tests passed. Architecture test verifies application/domain dependency direction. Existing artifact/API contracts are unchanged. Local unmerged test JAR: `target/EnthusiaTags.jar`, version `2.2.2-pilot.2`, SHA-256 `10e06690f90b99772076cd56c7f5c92da3071a5266562094865b9f969a3b6645`. This is not a production artifact.

## Delivery and acceptance limits

- This branch is unmerged local/PR source, not a production release. No server upload, restart or reload.
- Production runs additional fork behavior not yet in canonical main. Do not replace it with this standalone canonical-base build: reconcile that source, review/merge, update the network pin, and verify the combined release first.
- First-upgrade backfill can preserve only currently proven completions. Missing old provider evidence and old Discord posts are not automatically imported. The new ledger prevents replay after it has recorded a completion; it cannot promise recovery of every pre-installation history gap.
- Keep `advancement-history.db` in Tags backups. Do not delete it to reset rendering. It stores completion/notification intent only; claim/reset administration is not expanded by this patch.
- Commit-before-announcement favors no replay after process failure. A crash after commit but before delivery can lose a toast. As corrected by REQ-923, provider dispatch authorization is consumed before the renderer call, so an uncertain partial send is never retried; external Discord delivery is not transactional/exactly-once.
- On isolated TEST, verify an existing completion restores silently, a fresh completion announces once, and reconnect plus a later authorized test restart do not repeat it. Test provider-unavailable recovery and confirm unchanged `/rewards` claims. Real Java/Bedrock and Discord acceptance remain pending.

## PR #31 review correction - REQ-923

The new controller-level partial-send regression failed against e1a1c938: a renderer configured to send then throw was called twice. Provider authorization is now consumed immediately before dispatch, leaving unrelated keys eligible. Storage and projection failures before dispatch remain retryable; ordinary `tags/` reward handling is unchanged. A failed renderer invocation can lose a notification even when it failed before sending; its void API cannot prove non-delivery, and avoiding duplicates takes precedence for these provider nodes.

`CompletionHistory.reconcile` delegates write completion, restoration/filtering and new-completion selection to focused helpers instead of exceeding Codacy's method-complexity threshold. No rule suppression or threshold change. Final clean Maven verify passes 263 tests with zero failures/errors/skips; EARS, 12 Node tests, 10 Python bootstrap tests and architecture checks pass. The earlier 261-test artifact record above belongs to the initial PR head, not this correction. Hosted status is separate; no server changes.
