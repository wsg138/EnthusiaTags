# Tech Stack — {{PROJECT_NAME}}

**Date:** {{DATE}}
**Status:** Bootstrap (emitted by `/spear:init` — fill placeholders, commit, then revise as the project evolves)
**Owner:** {{OWNER}}

## 1. What this project is

{{PROJECT_SUMMARY}} — one paragraph stating the output artifact (library, service, CLI, plugin, …), who consumes it, and the deployment target.

## 2. Runtimes & languages

| Layer | Language / Tool | Min version | Reason |
|---|---|---|---|
| {{PRIMARY_LAYER}} | {{PRIMARY_LANGUAGE}} | {{LANGUAGE_VERSION}} | {{LANGUAGE_REASON}} |
| Build tool | {{BUILD_TOOL}} | {{BUILD_TOOL_VERSION}} | {{BUILD_TOOL_REASON}} |
| Test framework | {{TEST_FRAMEWORK}} | {{TEST_FRAMEWORK_VERSION}} | {{TEST_FRAMEWORK_REASON}} |
| JVM (if applicable) | JDK {{JVM_VERSION}} | — | — |
| CI runner | {{CI_RUNNER}} | — | — |

Detected by `/spear:init` from `{{DETECTED_BUILD_FILE}}` ({{DETECTION_SOURCE}}).

## 3. Runtime dependencies

Top-level direct dependencies with pinned versions. Transitive pins live in the lockfile ({{LOCKFILE}}).

| Package | Version | Why |
|---|---|---|
| {{DEP_1}} | {{DEP_1_VERSION}} | {{DEP_1_REASON}} |
| {{DEP_2}} | {{DEP_2_VERSION}} | {{DEP_2_REASON}} |

## 4. Pinned external schemas

External APIs and on-the-wire schemas this project depends on. Snapshot each in `docs/refs/` so a breaking upstream change is caught in review, not in production.

| Schema | Source of truth | Snapshot location |
|---|---|---|
| {{SCHEMA_1}} | {{SCHEMA_1_URL}} | `docs/refs/{{SCHEMA_1_SLUG}}.md` |

## 5. AI / agent rules

1. **Verify, don't guess.** Before writing code, confirm library APIs via context7 MCP, library source on disk, official docs via WebFetch, or `mgrep`/`Read`/`Glob` in that order. Record consulted sources in the task's `Evidence:` block.
2. **Use context7 MCP** for up-to-date library docs; prefer it over re-reading large source trees.
3. **Use mgrep** for code search when the skill is available.
4. **Use semgrep** for pattern / security scans.
5. **Briefing contract.** Any subagent dispatch carries: file paths, pre-verified signatures, the failing test (for TDD tasks), acceptance criteria, forbidden actions, and the task's Evidence block.
6. **Task sizing.** If a worker briefing exceeds ~1500 tokens, `/spear:spec` decomposes the task further before dispatch.

## 6. Versioning

Semantic versioning. Project starts at `0.1.0`; bump major on breaking public-API change.

## 7. CI

{{CI_SYSTEM}} — single workflow at `{{CI_WORKFLOW_PATH}}`.

1. Build / compile
2. Unit tests
3. Architecture tests ({{ARCH_TEST_TOOL}} on JVM; skipped otherwise)
4. Lint / formatter
5. Manifest / schema validation (when applicable)

## 8. Out of stack

Explicit non-goals for the toolchain — frameworks, languages, or infrastructure this project will NOT adopt without a spec change.

- {{OUT_OF_STACK_1}}
- {{OUT_OF_STACK_2}}
