#!/usr/bin/env python3
"""Validate immutable companion pins and bounded inputs, without executing processes."""

import argparse
import hashlib
import json
import pathlib
import re

from defusedxml import ElementTree as safe_xml

ROOT = pathlib.Path(__file__).resolve().parents[2]
NAMESPACE = {"m": "http://maven.apache.org/POM/4.0.0"}
PRESENCE_SOURCE = "src/main/java/dev/rosewood/rosechat/api/event/PresenceMessageEvent.java"
MAX_INPUT_BYTES = 2 * 1024 * 1024


def bounded_file(root, relative):
    """Reject any workspace input whose resolved path escapes the checkout."""
    base = root.resolve()
    result = (base / relative).resolve()
    result.relative_to(base)
    return result


def read_bounded(path):
    with path.open("rb") as stream:
        data = stream.read(MAX_INPUT_BYTES + 1)
    if len(data) > MAX_INPUT_BYTES:
        raise ValueError("Companion metadata exceeds the size limit")
    return data


def require_hex(value, size):
    if not isinstance(value, str) or not re.fullmatch("[0-9a-f]{" + str(size) + "}", value):
        raise ValueError("Companion digest must be lowercase hexadecimal")


def load_pins(root):
    pins = json.loads(read_bounded(bounded_file(root, "tools/ci/companions.json")))
    expected = {
        "renderer": "FainNeito/EnthusiaAdvancements",
        "rosechat": "FainNeito/Enthusia-RoseChat",
    }
    if not isinstance(pins, dict) or set(pins) != set(expected):
        raise ValueError("Exactly the renderer and RoseChat pins are required")
    for name, repository in expected.items():
        pin = pins[name]
        if not isinstance(pin, dict) or pin.get("repository") != repository:
            raise ValueError("Unexpected companion repository")
        require_hex(pin.get("sha"), 40)
    rose = pins["rosechat"]
    if rose.get("path") != PRESENCE_SOURCE:
        raise ValueError("Only the published presence contract is allowed")
    require_hex(rose.get("sha256"), 64)
    version = pins["renderer"].get("version")
    if not isinstance(version, str) or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+-pilot\.[0-9]+", version):
        raise ValueError("Unexpected renderer version")
    return pins


def parse_pom(path):
    return safe_xml.fromstring(
        read_bounded(path), forbid_dtd=True, forbid_entities=True, forbid_external=True
    )


def required_text(document, selector):
    node = document.find(selector, NAMESPACE)
    if node is None or node.text is None:
        raise ValueError("Missing required Maven version")
    return node.text.strip()


def verify_renderer(root, pins, actual_sha):
    if actual_sha != pins["renderer"]["sha"]:
        raise ValueError("Renderer checkout differs from the immutable pin")
    provided = required_text(
        parse_pom(bounded_file(root, ".ci-deps/renderer/pilot/pom.xml")), "m:version"
    )
    required = required_text(
        parse_pom(bounded_file(root, "pom.xml")),
        ".//m:dependency[m:artifactId='EnthusiaAdvancements-pilot']/m:version",
    )
    if provided != pins["renderer"]["version"] or provided != required:
        raise ValueError("Renderer version does not match the consumer POM")


def verify_presence(root, pins):
    source = bounded_file(root, ".ci-deps/rosechat/PresenceMessageEvent.java")
    actual = hashlib.sha256(read_bounded(source)).hexdigest()
    if actual != pins["rosechat"]["sha256"]:
        raise ValueError("RoseChat API source checksum mismatch")


def resolved_fields(pins):
    renderer = pins["renderer"]
    rose = pins["rosechat"]
    return (
        "https://github.com/" + renderer["repository"] + ".git",
        renderer["sha"],
        "https://raw.githubusercontent.com/" + rose["repository"] + "/"
        + rose["sha"] + "/" + PRESENCE_SOURCE,
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("resolve", "verify-renderer", "verify-presence"))
    parser.add_argument("actual_sha", nargs="?")
    args = parser.parse_args()
    pins = load_pins(ROOT)
    if args.action == "resolve":
        bounded_file(ROOT, ".ci-deps/renderer")
        bounded_file(ROOT, ".ci-deps/rosechat/PresenceMessageEvent.java")
        for value in resolved_fields(pins):
            print(value)
    elif args.action == "verify-renderer":
        verify_renderer(ROOT, pins, args.actual_sha)
    else:
        verify_presence(ROOT, pins)


if __name__ == "__main__":
    main()
