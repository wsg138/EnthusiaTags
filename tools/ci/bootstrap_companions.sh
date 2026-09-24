#!/usr/bin/env bash
# Fixed orchestration; the validator checks data before it becomes an argument.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
PYTHON_BIN="$(command -v "${PYTHON:-python3}")"
GIT_BIN="$(command -v git)"
MVN_BIN="$(command -v mvn)"
CURL_BIN="$(command -v curl)"
metadata="$("$PYTHON_BIN" tools/ci/bootstrap_companions.py resolve)"
mapfile -t fields <<< "$metadata"
if [[ "${#fields[@]}" -ne 3 ]]; then
    printf '%s\n' 'Expected exactly three validated companion fields' >&2
    exit 1
fi
renderer_url="${fields[0]}"
renderer_sha="${fields[1]}"
presence_url="${fields[2]}"
mkdir -p .ci-deps/renderer .ci-deps/rosechat
if [[ ! -e .ci-deps/renderer/.git ]]; then
    "$GIT_BIN" init .ci-deps/renderer
fi
# Fetch the validated HTTPS repository directly, never a mutable stored remote.
"$GIT_BIN" -C .ci-deps/renderer fetch --depth=1 "$renderer_url" "$renderer_sha"
"$GIT_BIN" -C .ci-deps/renderer checkout --detach "$renderer_sha"
actual_sha="$("$GIT_BIN" -C .ci-deps/renderer rev-parse HEAD)"
"$PYTHON_BIN" tools/ci/bootstrap_companions.py verify-renderer "$actual_sha"
"$MVN_BIN" -B -ntp -f .ci-deps/renderer/pilot/pom.xml clean install
"$CURL_BIN" --fail --silent --show-error --proto '=https' --tlsv1.2 \
    --max-time 60 --max-filesize 2097152 \
    --output .ci-deps/rosechat/PresenceMessageEvent.java "$presence_url"
"$PYTHON_BIN" tools/ci/bootstrap_companions.py verify-presence
printf 'Verified renderer %s and checksum-pinned presence contract\n' "$renderer_sha"
