#!/usr/bin/env python3
"""Require the current pull-request head to pass Codacy's GitHub check."""

from __future__ import annotations

import http.client
import json
import os
import re
import sys
import time

API_HOST = "api.github.com"
API_VERSION = "2022-11-28"
CHECK_NAME = "Codacy Static Code Analysis"
POLL_SECONDS = 10
TIMEOUT_SECONDS = 300
REQUEST_TIMEOUT_SECONDS = 30
REPOSITORY_PATTERN = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")


def request_json(path: str) -> object:
    if not path.startswith("/repos/"):
        raise ValueError("GitHub API path must target a repository")
    connection = http.client.HTTPSConnection(API_HOST, timeout=REQUEST_TIMEOUT_SECONDS)
    headers = {
        "Accept": "application/vnd.github+json",
        "Authorization": f"Bearer {os.environ['GITHUB_TOKEN']}",
        "X-GitHub-Api-Version": API_VERSION,
        "User-Agent": "enthusia-tags-ci",
    }
    try:
        connection.request("GET", path, headers=headers)
        response = connection.getresponse()
        body = response.read().decode("utf-8", errors="replace")
        if response.status >= http.client.BAD_REQUEST:
            raise RuntimeError(f"GitHub API request failed with {response.status}: {body}")
        return json.loads(body)
    finally:
        connection.close()


def require_current_head(repository: str, pull_request: int, expected_head: str) -> None:
    payload = request_json(f"/repos/{repository}/pulls/{pull_request}")
    if not isinstance(payload, dict):
        raise RuntimeError("Unexpected pull-request response")
    head = payload.get("head")
    actual = head.get("sha") if isinstance(head, dict) else None
    if actual != expected_head:
        raise RuntimeError(
            f"Stale pull-request run: expected current head {expected_head}, GitHub reports {actual}"
        )


def find_codacy_check(repository: str, head_sha: str) -> dict[str, object] | None:
    payload = request_json(
        f"/repos/{repository}/commits/{head_sha}/check-runs?per_page=100"
    )
    if not isinstance(payload, dict):
        raise RuntimeError("Unexpected check-runs response")
    check_runs = payload.get("check_runs", [])
    if not isinstance(check_runs, list):
        raise RuntimeError("Unexpected check-runs collection")
    matching = [
        check
        for check in check_runs
        if isinstance(check, dict) and check.get("name") == CHECK_NAME
    ]
    return max(matching, key=lambda check: str(check.get("started_at", ""))) if matching else None


def fetch_annotations(repository: str, check_run_id: int) -> list[dict[str, object]]:
    results: list[dict[str, object]] = []
    page = 1
    while True:
        payload = request_json(
            f"/repos/{repository}/check-runs/{check_run_id}/annotations?per_page=100&page={page}"
        )
        if not isinstance(payload, list):
            raise RuntimeError("Unexpected check annotations response")
        page_results = [entry for entry in payload if isinstance(entry, dict)]
        results.extend(page_results)
        if len(page_results) < 100:
            return results
        page += 1


def wait_for_check(repository: str, head_sha: str) -> dict[str, object] | None:
    deadline = time.monotonic() + TIMEOUT_SECONDS
    while time.monotonic() < deadline:
        check = find_codacy_check(repository, head_sha)
        if check is not None and check.get("status") == "completed":
            return check
        time.sleep(POLL_SECONDS)
    return None


def print_annotations(entries: list[dict[str, object]]) -> None:
    if not entries:
        print("Codacy returned no GitHub annotations.", file=sys.stderr)
        return
    print(f"Codacy returned {len(entries)} annotation(s):", file=sys.stderr)
    for entry in entries:
        path = entry.get("path", "<unknown>")
        line = entry.get("start_line") or entry.get("end_line") or "?"
        level = entry.get("annotation_level", "notice")
        title = entry.get("title") or "Codacy issue"
        message = entry.get("message") or entry.get("raw_details") or ""
        print(f"{path}:{line}: [{level}] {title}: {message}", file=sys.stderr)


def load_context() -> tuple[str, int, str]:
    repository = os.environ.get("GITHUB_REPOSITORY", "")
    head_sha = os.environ.get("PULL_REQUEST_HEAD_SHA", "")
    pull_request_text = os.environ.get("PULL_REQUEST_NUMBER", "")
    if not repository or not head_sha or not pull_request_text:
        raise ValueError("Missing repository, pull-request number, or pull-request head SHA.")
    if not REPOSITORY_PATTERN.fullmatch(repository):
        raise ValueError("GITHUB_REPOSITORY is not a valid owner/repository name")
    return repository, int(pull_request_text), head_sha


def evaluate_check(
    repository: str,
    head_sha: str,
    check: dict[str, object] | None,
) -> int:
    if check is None:
        print(f"Timed out waiting for {CHECK_NAME} on {head_sha}.", file=sys.stderr)
        return 1
    check_id = check.get("id")
    if not isinstance(check_id, int):
        print("Codacy check did not expose a valid check-run id.", file=sys.stderr)
        return 1
    try:
        annotations = fetch_annotations(repository, check_id)
    except RuntimeError as exc:
        print(str(exc), file=sys.stderr)
        return 1
    conclusion = check.get("conclusion")
    if conclusion == "success" and not annotations:
        print(f"{CHECK_NAME} passed on current exact head {head_sha}.")
        return 0
    print(f"{CHECK_NAME} concluded {conclusion!r} on exact head {head_sha}.", file=sys.stderr)
    print_annotations(annotations)
    return 1


def main() -> int:
    try:
        repository, pull_request, head_sha = load_context()
        require_current_head(repository, pull_request, head_sha)
        check = wait_for_check(repository, head_sha)
        require_current_head(repository, pull_request, head_sha)
    except (RuntimeError, ValueError) as exc:
        print(str(exc), file=sys.stderr)
        return 1
    return evaluate_check(repository, head_sha, check)


if __name__ == "__main__":
    raise SystemExit(main())
