# Requirements — {{PROJECT_NAME}}

**Date:** {{DATE}}
**Status:** Bootstrap (emitted by `/spear:init`; extend via `/spear:spec`)
**EARS subset enforced:** Ubiquitous, Event-driven, State-driven, Unwanted. Optional Feature pattern (`WHERE …`) accepted without validation.

Each requirement carries a stable ID. Tasks reference requirements by ID. New requirements append at the next free integer ID (three-digit padded); IDs are never re-used or renumbered.

---

## Product (what the system is for)

### REQ-001 — Example Ubiquitous requirement

**Ubiquitous.** THE SYSTEM SHALL {{UBIQUITOUS_RESPONSE}}.

### REQ-002 — Example Event-driven requirement

**Event-driven.** WHEN {{EVENT}} THE SYSTEM SHALL {{RESPONSE}}.

### REQ-003 — Example State-driven requirement

**State-driven.** WHILE {{STATE}} THE SYSTEM SHALL {{RESPONSE}}.

### REQ-004 — Example Unwanted-behavior requirement

**Unwanted.** IF {{UNWANTED_CONDITION}} THEN THE SYSTEM SHALL {{RESPONSE}}.

---

## Interfaces & contracts

(Add REQ entries here describing external APIs, CLI surface, file formats, or wire protocols the system commits to.)

---

## Non-functional

(Performance, security, operability. Each REQ must still fit one of the four EARS patterns above.)

---

## Acceptance

### REQ-100 — Example acceptance criterion

**Event-driven.** WHEN {{ACCEPTANCE_TRIGGER}} THE SYSTEM SHALL {{ACCEPTANCE_OUTCOME}}.

---

## Authoring rules

1. Every REQ has a single ID, a heading, and exactly one EARS-formatted sentence under a **pattern label** (Ubiquitous / Event-driven / State-driven / Unwanted / Optional).
2. Use `/spear:spec` to add or revise REQ entries — it runs the EARS validator (`tools/spear/ears.mjs`) and assigns the next free ID.
3. Never reuse an ID. When a requirement is obsolete, strike it through and note the deprecation date; do not renumber.
