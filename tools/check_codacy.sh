#!/usr/bin/env bash
set -euo pipefail

readonly API_ROOT="https://api.github.com"
readonly API_VERSION="2022-11-28"
readonly CHECK_NAME="Codacy Static Code Analysis"
readonly POLL_SECONDS=10
readonly MAX_POLLS=30

repository="${GITHUB_REPOSITORY:-}"
pull_request="${PULL_REQUEST_NUMBER:-}"
head_sha="${PULL_REQUEST_HEAD_SHA:-}"

if [[ ! "$repository" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]]; then
  echo "GITHUB_REPOSITORY is not a valid owner/repository name" >&2
  exit 1
fi
if [[ ! "$pull_request" =~ ^[0-9]+$ ]]; then
  echo "PULL_REQUEST_NUMBER is not numeric" >&2
  exit 1
fi
if [[ ! "$head_sha" =~ ^[0-9a-f]{40}$ ]]; then
  echo "PULL_REQUEST_HEAD_SHA is not a full lowercase commit SHA" >&2
  exit 1
fi
if [[ -z "${GITHUB_TOKEN:-}" ]]; then
  echo "GITHUB_TOKEN is required" >&2
  exit 1
fi
command -v curl >/dev/null
command -v jq >/dev/null

api_get() {
  local path="$1"
  curl --fail-with-body --silent --show-error \
    --proto '=https' --tlsv1.2 \
    --connect-timeout 10 --max-time 30 \
    --retry 5 --retry-delay 2 --retry-max-time 90 --retry-all-errors \
    -H 'Accept: application/vnd.github+json' \
    -H "Authorization: Bearer ${GITHUB_TOKEN}" \
    -H "X-GitHub-Api-Version: ${API_VERSION}" \
    -H 'User-Agent: enthusia-tags-ci' \
    "${API_ROOT}${path}"
}

require_current_head() {
  local actual
  actual="$(api_get "/repos/${repository}/pulls/${pull_request}" | jq -r '.head.sha // empty')"
  if [[ "$actual" != "$head_sha" ]]; then
    echo "Stale pull-request run: expected current head ${head_sha}, GitHub reports ${actual:-<missing>}" >&2
    exit 1
  fi
}

require_current_head

check_json=""
for (( poll=1; poll<=MAX_POLLS; poll++ )); do
  checks="$(api_get "/repos/${repository}/commits/${head_sha}/check-runs?per_page=100")"
  check_json="$(jq -c --arg name "$CHECK_NAME" \
    '[.check_runs[] | select(.name == $name)] | sort_by(.started_at) | last // empty' <<<"$checks")"
  if [[ -n "$check_json" && "$(jq -r '.status' <<<"$check_json")" == "completed" ]]; then
    break
  fi
  check_json=""
  sleep "$POLL_SECONDS"
done

require_current_head

if [[ -z "$check_json" ]]; then
  echo "Timed out waiting for ${CHECK_NAME} on ${head_sha}." >&2
  exit 1
fi

conclusion="$(jq -r '.conclusion // empty' <<<"$check_json")"
check_id="$(jq -r '.id // empty' <<<"$check_json")"
annotation_count="$(jq -r '.output.annotations_count // 0' <<<"$check_json")"

if [[ "$conclusion" == "success" && "$annotation_count" == "0" ]]; then
  echo "${CHECK_NAME} passed on current exact head ${head_sha}."
  exit 0
fi

echo "${CHECK_NAME} concluded '${conclusion:-<missing>}' with ${annotation_count} annotation(s) on exact head ${head_sha}." >&2
if [[ "$check_id" =~ ^[0-9]+$ ]]; then
  api_get "/repos/${repository}/check-runs/${check_id}/annotations?per_page=100" \
    | jq -r '.[] | "\(.path):\(.start_line // .end_line // "?"): [\(.annotation_level // "notice")] \(.title // "Codacy issue"): \(.message // .raw_details // "")"' >&2 || true
fi
exit 1
