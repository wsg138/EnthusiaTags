# Implementation — {{PROJECT_NAME}}

**Date:** {{DATE}}
**Status:** Bootstrap (emitted by `/spear:init`; extend as components land)
**Owner:** {{OWNER}}

## 1. Repo layout (canonical)

```
{{PROJECT_ROOT}}/
├── {{SRC_ROOT}}/
│   ├── domain/             # rules of the game — zero framework imports
│   ├── application/        # use cases — imports domain only
│   └── infrastructure/     # adapters — imports anything
├── {{TEST_ROOT}}/
│   └── architecture/       # Konsist / import-linter / dependency-cruiser tests
├── docs/
│   ├── tech-stack.md
│   ├── requirements.md
│   ├── implementation.md
│   └── tasks.md
└── {{BUILD_FILE}}
```

## 2. Layer Dependency Rules

The three-layer discipline SPEAR enforces. `/spear:arch` reads this exact section and blocks on violations.

| Layer | Concrete files | May depend on |
|---|---|---|
| `domain/` (rules-of-the-game) | `{{SRC_ROOT}}/domain/**` | nothing outside `domain/` + language stdlib |
| `application/` (use cases / workflow) | `{{SRC_ROOT}}/application/**` | `domain/` only |
| `infrastructure/` (adapters, frameworks, I/O) | `{{SRC_ROOT}}/infrastructure/**` | anything |

Violations are reported as `file:line:symbol`. Suggested fixes: move the offending type, introduce a port interface in `domain/`, or relocate framework wiring to `infrastructure/`.

## Forbidden Domain Annotations

Framework annotations that must NOT appear on any type under `domain/**`. `/spear:arch` scans for these; the default denylist covers common JVM offenders. Extend the YAML list below for project-specific additions.

```yaml
# Default denylist (always active on JVM projects):
#   org.springframework.*
#   jakarta.persistence.*
#   javax.persistence.*
#   com.fasterxml.jackson.*
#   io.micronaut.*
#   lombok.*
#
# Add project-specific patterns here. Glob-style; checked as prefix match.
forbidden: []
```

## 3. Component design

### 3.1 {{COMPONENT_1_NAME}}

{{COMPONENT_1_DESCRIPTION}}

- Layer: {{COMPONENT_1_LAYER}}
- Ports / interfaces: {{COMPONENT_1_PORTS}}
- Adapters: {{COMPONENT_1_ADAPTERS}}
- Evidence sources consulted: {{COMPONENT_1_EVIDENCE}}

(Add one subsection per component as the system grows. Each subsection states which layer it lives in, what ports it exposes, and what adapters plug in.)

## 4. Data flows

(One numbered-step narrative per end-to-end flow — request → response, event → side effect, etc. Stays abstract enough that reviewers can check the layer discipline without reading code.)

## 5. Briefing contract for subagent dispatch

Every worker dispatch (`Agent` tool call) for implementation work carries:

- Exact file paths to create / modify.
- Pre-verified signatures (from context7, library source on disk, or `mgrep`).
- The failing test (path + test name) for TDD tasks.
- Acceptance criteria — which test goes green; which files MUST NOT change.
- Forbidden actions — scope fences.
- The task's `Evidence:` block verbatim.

Tasks whose full briefing exceeds ~1500 tokens are decomposed further by `/spear:spec` before dispatch.

## 6. Versioning

{{VERSIONING_SCHEME}} — default: semantic versioning. Start at `0.1.0`. Bump major on breaking public-API or state-file-schema change.

## 7. Out of scope (this doc)

- Per-component code-level docs — owned by each component's own `README.md` or KDoc/JSDoc.
- CI configuration — owned by `tech-stack.md` §CI and the workflow file itself.

## Architecture test selection

Use LayerRulesTest.java with the existing JUnit harness for Java-only projects.
Use LayerRulesTest.kt only when Kotlin compilation and Konsist are already declared by the project.
Do not add an unconfigured Kotlin source or a new framework dependency to a Java-only project.
