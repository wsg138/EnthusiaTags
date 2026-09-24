"""Regression tests for the companion bootstrap's data trust boundary."""

import hashlib
import json
import pathlib
import tempfile
import unittest

from defusedxml.common import DefusedXmlException

import bootstrap_companions as bootstrap


class BootstrapCompanionsTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = pathlib.Path(temporary.name)
        self.pins = {
            "renderer": {"repository": "FainNeito/EnthusiaAdvancements", "sha": "a" * 40,
                         "version": "1.0.0-pilot.4"},
            "rosechat": {"repository": "FainNeito/Enthusia-RoseChat", "sha": "b" * 40,
                        "path": bootstrap.PRESENCE_SOURCE,
                        "sha256": hashlib.sha256(b"public contract").hexdigest()},
        }
        self.write("tools/ci/companions.json", json.dumps(self.pins))

    def write(self, name, text):
        file = self.root / name
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(text, encoding="utf-8")
        return file

    def poms(self, version="1.0.0-pilot.4"):
        prefix = '<project xmlns="http://maven.apache.org/POM/4.0.0">'
        self.write(".ci-deps/renderer/pilot/pom.xml", prefix + "<version>" + version + "</version></project>")
        self.write("pom.xml", prefix + "<dependencies><dependency>"
                   "<artifactId>EnthusiaAdvancements-pilot</artifactId>"
                   "<version>1.0.0-pilot.4</version></dependency></dependencies></project>")

    def test_expected_pins_produce_only_approved_https_destinations(self):
        pins = bootstrap.load_pins(self.root)
        repository, sha, source = bootstrap.resolved_fields(pins)
        self.assertEqual(repository, "https://github.com/FainNeito/EnthusiaAdvancements.git")
        self.assertEqual(sha, "a" * 40)
        self.assertEqual(source, "https://raw.githubusercontent.com/FainNeito/Enthusia-RoseChat/"
                         + "b" * 40 + "/" + bootstrap.PRESENCE_SOURCE)

    def test_repository_sha_and_contract_path_cannot_change_command_shape(self):
        for name, field, value in (
            ("renderer", "repository", "other/Untrusted"),
            ("renderer", "sha", "--upload-pack=bad"),
            ("rosechat", "path", "../secret"),
            ("rosechat", "sha256", "short"),
        ):
            with self.subTest(field=field):
                pins = json.loads(json.dumps(self.pins))
                pins[name][field] = value
                self.write("tools/ci/companions.json", json.dumps(pins))
                with self.assertRaises(ValueError):
                    bootstrap.load_pins(self.root)

    def test_workspace_escape_is_rejected(self):
        with self.assertRaises(ValueError):
            bootstrap.bounded_file(self.root, "../outside.xml")

    def test_dtd_and_external_entities_are_rejected(self):
        for declaration in (
            '<!DOCTYPE project [<!ENTITY payload "expanded">]>',
            '<!DOCTYPE project SYSTEM "file:///nonexistent-private-file">',
        ):
            xml = self.write("pom.xml", declaration + "<project/>")
            with self.assertRaises(DefusedXmlException):
                bootstrap.parse_pom(xml)

    def test_pinned_versions_and_commit_must_match(self):
        self.poms()
        bootstrap.verify_renderer(self.root, self.pins, "a" * 40)
        with self.assertRaises(ValueError):
            bootstrap.verify_renderer(self.root, self.pins, "c" * 40)
        self.poms("1.0.0-pilot.5")
        with self.assertRaises(ValueError):
            bootstrap.verify_renderer(self.root, self.pins, "a" * 40)

    def test_presence_tampering_is_rejected(self):
        self.write(".ci-deps/rosechat/PresenceMessageEvent.java", "public contract")
        bootstrap.verify_presence(self.root, self.pins)
        self.write(".ci-deps/rosechat/PresenceMessageEvent.java", "modified contract")
        with self.assertRaises(ValueError):
            bootstrap.verify_presence(self.root, self.pins)

    def test_input_size_is_bounded(self):
        file = self.write("oversize.xml", "x" * (bootstrap.MAX_INPUT_BYTES + 1))
        with self.assertRaises(ValueError):
            bootstrap.read_bounded(file)

    def test_missing_version_is_rejected(self):
        document = bootstrap.parse_pom(self.write("empty.xml", "<project/>"))
        with self.assertRaises(ValueError):
            bootstrap.required_text(document, "m:version")


if __name__ == "__main__":
    unittest.main()
