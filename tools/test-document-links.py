#!/usr/bin/env python3
"""Exercise documentation target classification with isolated synthetic trees."""
import importlib.util
import hashlib
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("document_links", Path(__file__).with_name("check-document-links.py"))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class DocumentLinksTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="dsha-doc-links-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def write(self, path, text=""):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")

    def check(self, path="README.md", **options):
        return gate.inspect(self.root, [path], **options)

    def test_current_missing_target_fails(self):
        self.write("README.md", "[guide](docs/missing.md) [image](docs/image.png)")
        self.assertEqual(self.check()["counts"]["error"], 2)

    def test_reference_html_and_encoded_space_targets(self):
        self.write("README.md", '[a][guide]\n[guide]: <docs/a%20b.md>\n<img src="docs/icon.svg"><source srcset="docs/icon.svg 1x, docs/icon2.svg 2x">')
        for name in ("a b.md", "icon.svg", "icon2.svg"):
            self.write("docs/" + name)
        self.assertEqual(self.check()["counts"], {"ok": 3, "warning": 0, "error": 0})

    def test_fenced_examples_and_comments_do_not_create_links(self):
        self.write("README.md", "```md\n[x](absent.md)\n```\n<!-- [x](absent.md) -->\n[web](https://example.invalid) [anchor](#here)")
        self.assertEqual(self.check()["results"], [])

    def test_historical_missing_document_still_fails(self):
        self.write("docs/releases/old.md", "[guide](missing.md)")
        self.assertEqual(self.check("docs/releases/old.md")["counts"]["error"], 1)

    def test_historical_evidence_is_reported_not_claimed_available(self):
        self.write("docs/releases/old.md", "[receipt](../../app/build/original.json)")
        result = self.check("docs/releases/old.md")
        self.assertEqual(result["counts"], {"ok": 0, "warning": 1, "error": 0})
        self.assertEqual(result["results"][0]["kind"], "historical-unavailable-evidence")
        self.assertEqual(self.check("docs/releases/old.md", strict_history=True)["counts"]["error"], 1)

    def test_escape_outside_root_fails(self):
        self.write("README.md", "[escape](../outside.md)")
        self.assertEqual(self.check()["results"][0]["kind"], "target-outside-root")

    def test_current_absolute_evidence_fails(self):
        self.write("README.md", "[receipt](F:/private/receipt.json)")
        self.assertEqual(self.check()["results"][0]["kind"], "local-absolute-evidence")

    def test_missing_source_fails_instead_of_silently_shrinking_scope(self):
        self.assertEqual(self.check()["results"][0]["kind"], "source-missing")

    def test_new_unlisted_page_is_current_and_cannot_mask_a_missing_image(self):
        self.write("docs/new-guide.md", "![image](missing.png)")
        result = self.check("docs/new-guide.md")
        self.assertEqual(result["counts"]["error"], 1)
        self.assertEqual(result["results"][0]["classification"], "current")

    def install_pinned_upstream_originals(self):
        for path, pinned in gate.UPSTREAM_SOURCE_RELATIVE.items():
            data = (gate.ROOT / path).read_bytes()
            self.assertEqual(hashlib.sha256(data).hexdigest(), pinned["sha256"])
            target = self.root / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            if "dsh-web-mobile" in path:
                # This local license is actually distributed; only the five
                # documented upstream targets are absent from the DSHA copy.
                license_file = (gate.ROOT / path).parent / "LICENSE"
                (target.parent / "LICENSE").write_bytes(license_file.read_bytes())
        return list(gate.UPSTREAM_SOURCE_RELATIVE)

    def test_upstream_registry_is_exactly_two_sources_and_eight_fixed_targets(self):
        paths = self.install_pinned_upstream_originals()
        self.assertEqual(len(paths), 2)
        self.assertEqual(sum(len(row["targets"]) for row in gate.UPSTREAM_SOURCE_RELATIVE.values()), 8)
        for path, pinned in gate.UPSTREAM_SOURCE_RELATIVE.items():
            commit = "10e58ce0c4ed424c7fbd9c91d48db06c6061b4e4" if "dsh-web-mobile" in path else "6e2689f55295fa444be8ac8592c527c2c5ef3253"
            for target, url in pinned["targets"].items():
                self.assertIn("/" + commit + "/", url)
                self.assertTrue(url.endswith("/" + target))

    def test_pinned_upstream_references_are_warnings_with_real_source_identity(self):
        result = gate.inspect(self.root, self.install_pinned_upstream_originals())
        self.assertEqual(result["counts"]["error"], 0)
        rows = [row for row in result["results"] if row["kind"] == "upstream-source-relative"]
        self.assertEqual(len(rows), 8)
        for row in rows:
            self.assertEqual(row["status"], "warning")
            self.assertFalse(row["localAssetAvailable"])
            self.assertEqual(row["sourceSha256"], gate.UPSTREAM_SOURCE_RELATIVE[row["source"]]["sha256"])
            self.assertEqual(row["upstreamUrl"], gate.UPSTREAM_SOURCE_RELATIVE[row["source"]]["targets"][row["target"]])

    def test_strict_history_rejects_all_eight_upstream_relative_targets(self):
        result = gate.inspect(self.root, self.install_pinned_upstream_originals(), strict_history=True)
        self.assertEqual(result["counts"]["error"], 8)
        self.assertEqual(result["counts"]["warning"], 0)
        failures = [row for row in result["results"] if row["status"] == "error"]
        self.assertTrue(all(row["kind"] == "upstream-source-relative" for row in failures))

    def test_changed_source_bytes_cannot_reuse_upstream_exception(self):
        path = self.install_pinned_upstream_originals()[0]
        source = self.root / path
        source.write_bytes(source.read_bytes() + b"\n<!-- changed -->\n")
        result = self.check(path)
        self.assertEqual(result["counts"]["error"], 5)
        self.assertEqual(result["counts"]["warning"], 0)

    def test_same_bytes_at_an_unregistered_source_path_still_fail(self):
        original = self.install_pinned_upstream_originals()[0]
        source = self.root / "docs/copied-upstream.md"
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_bytes((self.root / original).read_bytes())
        (source.parent / "LICENSE").write_bytes((self.root / original).with_name("LICENSE").read_bytes())
        result = self.check("docs/copied-upstream.md")
        self.assertEqual(result["counts"]["error"], 5)
        self.assertEqual(result["counts"]["warning"], 0)

    def test_unregistered_target_and_wrong_digest_have_no_upstream_mapping(self):
        path = next(iter(gate.UPSTREAM_SOURCE_RELATIVE))
        pinned = gate.UPSTREAM_SOURCE_RELATIVE[path]
        self.assertIsNone(gate.upstream_source_reference(path, pinned["sha256"], "private-notes.md"))
        self.assertIsNone(gate.upstream_source_reference(path, "0" * 64, "AGENTS.md"))
        self.assertIsNone(gate.upstream_source_reference(path + ".copy", pinned["sha256"], "AGENTS.md"))

    def test_available_local_file_is_checked_as_local_instead_of_an_upstream_warning(self):
        paths = self.install_pinned_upstream_originals()
        path = paths[0]
        target = next(iter(gate.UPSTREAM_SOURCE_RELATIVE[path]["targets"]))
        local = (self.root / path).parent / target
        local.parent.mkdir(parents=True, exist_ok=True)
        local.write_bytes(b"synthetic existing local target")
        result = gate.inspect(self.root, paths)
        available = [row for row in result["results"] if row["source"] == path and row["target"] == target]
        self.assertEqual([(row["status"], row["kind"]) for row in available], [("ok", "available")])
        self.assertEqual(sum(row["kind"] == "upstream-source-relative" for row in result["results"]), 7)


if __name__ == "__main__":
    unittest.main()
