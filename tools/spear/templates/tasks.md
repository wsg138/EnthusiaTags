# Tasks — {{PROJECT_NAME}}

**Date:** {{DATE}}
**Status:** Bootstrap (emitted by `/spear:init`; extend via `/spear:spec`)

Tags: `TDD` (failing test before code), `DOC` (markdown / template authoring), `INFRA` (manifests, CI, repo plumbing).
State legend: `[ ]` not started, `[~]` in progress, `[x]` done, `[!]` blocked.

Each task carries `References:` (REQ-IDs + spec sections consulted) and `Evidence:` (sources consulted as work proceeds — an empty block blocks advancement past `spec-done` per REQ-030).

Tasks are ordered to honour state-machine and architectural dependencies. Independent tasks within a milestone may be parallelised.

---

## Milestone {{MILESTONE_NAME}} — {{MILESTONE_GOAL}}

### INFRA tasks

- [ ] **INFRA-01** — {{INFRA_TASK_TITLE}}
  References: REQ-{{REQ_ID}}
  Tag: INFRA
  Description: {{INFRA_TASK_DESCRIPTION}}
  Evidence: ` `

### TDD tasks

- [ ] **TDD-10** — {{TDD_TASK_TITLE}}
  References: REQ-{{REQ_ID}}
  Tag: TDD
  Description: Write a failing test in `{{TEST_FILE}}` asserting {{ASSERTION}}. Run it, confirm red. Record `testStatus: red` via `state.sh state_record_test`.
  Evidence: ` `

- [ ] **TDD-11** — {{TDD_TASK_TITLE_2}}
  References: REQ-{{REQ_ID}}
  Tag: TDD
  Description: Implement minimum code to flip the test from `TDD-10` to green. No behaviour not asserted by the test.
  Evidence: ` `

### DOC tasks

- [ ] **DOC-20** — {{DOC_TASK_TITLE}}
  References: REQ-{{REQ_ID}}
  Tag: DOC
  Description: {{DOC_TASK_DESCRIPTION}}
  Evidence: ` `

---

## Task authoring rules

1. Every task has exactly ONE tag (`TDD`, `DOC`, or `INFRA`).
2. `References:` cites at least one REQ-ID from `requirements.md`. If the REQ doesn't exist, run `/spear:spec` first.
3. The Evidence field starts empty. It must be filled before any skill past `spec-done` will run (REQ-030). Each line is a verified source, such as `context7:react@19/useEffect`, `src/domain/Order.kt:42`, or `docs/implementation.md#3.1`.
4. Task size ceiling: ~1500 tokens of full briefing. If larger, split.
5. A task MUST be achievable by a single SPEAR cycle (`spec → prove → engine → arch → refine` for TDD; `spec → arch → refine` for DOC/INFRA).
6. Mark state as work proceeds: `[~]` when entering `spec`; `[x]` only when `/spear:refine` has cleared state to `idle`.
