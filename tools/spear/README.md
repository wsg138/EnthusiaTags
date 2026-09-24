# SPEAR project tooling

Upstream: <https://github.com/BadgersMC/spear-plugin> at 2c91bae.
ears.mjs is copied unchanged from hooks/lib/ears.mjs. See UPSTREAM-NOTICE.md for the upstream MIT declaration and provenance.
Skills were read from the existing ItemSignature workspace's .agents/skills/spear-* directories.
state.mjs is a project adaptation of state.sh for Windows without Bash/jq, using the same state fields and phase names with additional transition checks.

Example:
```sh
node tools/spear/ears.mjs docs/requirements.md
node tools/spear/state.mjs state_task TDD-002 REQ-016
node tools/spear/state.mjs state_assert_phase idle
node tools/spear/state.mjs state_set_phase spec
```

Continue with each skill's phase gates. Record red/green only after observing the test result. State files under .claude are transient and ignored; durable evidence lives in docs/evidence.
The native global-plugin installation and session hooks are not installed. The user's explicit no-commit/no-PR instruction overrides upstream init/refine commit steps, including in this Git checkout.
