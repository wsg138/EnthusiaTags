#!/usr/bin/env bash
# Compatibility wrapper: all readers/writers share Node validation, lock, and atomic replacement.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
exec "${NODE:-node}" "$ROOT/state.mjs" "$@"
