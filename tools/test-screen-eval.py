#!/usr/bin/env python3
"""tools/screen-eval 的离线自检：固定样本可复现、漂移与超预算会失败、摘要标明合成证据。"""

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("screen_eval", ROOT / "tools/screen-eval/screen_eval.py")
screen_eval = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(screen_eval)


class ScreenEvalTest(unittest.TestCase):
    def test_committed_fixture_golden_and_budget_pass(self):
        self.assertEqual([], screen_eval.check())

    def test_generator_is_deterministic_and_matches_report_mix(self):
        first, second = screen_eval.generate(), screen_eval.generate()
        self.assertEqual(first, second)
        self.assertEqual(200, len(first))
        self.assertEqual(len(first), len({row["id"] for row in first}))
        for row in first:
            self.assertNotIn("\t", row["label"])
            self.assertNotIn("\n", row["label"])
            left, top, right, bottom = row["bounds"]
            self.assertLess(left, right)
            self.assertLess(top, bottom)

    def test_fixture_drift_is_detected(self):
        original = screen_eval.FIXTURE
        with tempfile.TemporaryDirectory() as tmp:
            changed = Path(tmp) / "fixture.tsv"
            changed.write_text(
                original.read_text(encoding="utf-8").replace("\tc\t", "\tcl\t", 1),
                encoding="utf-8",
            )
            screen_eval.FIXTURE = changed
            try:
                self.assertIn("FIXTURE_DRIFT: 运行 --write 并核对差异", screen_eval.check())
            finally:
                screen_eval.FIXTURE = original

    def test_budget_overrun_is_detected(self):
        original = dict(screen_eval.CHAR_BUDGET)
        screen_eval.CHAR_BUDGET[60] = 10
        try:
            self.assertTrue(
                any(f.startswith("COMPACT_CHAR_BUDGET_EXCEEDED size=60") for f in screen_eval.check())
            )
        finally:
            screen_eval.CHAR_BUDGET.clear()
            screen_eval.CHAR_BUDGET.update(original)

    def test_compact_lines_omit_placeholders_and_split_unambiguously(self):
        rows = [
            {"id": "k000", "role": "T", "label": "收藏", "flags": "", "actions": "", "bounds": (0, 0, 1, 1)},
            {"id": "k001", "role": "B", "label": "发送", "flags": "!", "actions": "c", "bounds": (0, 0, 1, 1)},
        ]
        lines = screen_eval.encode_compact(rows).splitlines()
        self.assertEqual('k000 T "收藏"', lines[1])
        self.assertEqual('k001 B "发送" !c', lines[2])
        token = lines[2].rsplit(" ", 1)[1]
        self.assertEqual("!", "".join(ch for ch in token if ch in "!*%^~"))
        self.assertEqual("c", "".join(ch for ch in token if ch in "clfbot"))

    def test_summary_declares_synthetic_evidence_without_tokens(self):
        result = screen_eval.summary(screen_eval.read_fixture())
        self.assertEqual("synthetic-nodes-offline", result["evidence"])
        self.assertIsNone(result["tokenizerSha256"])
        sizes = {entry["elements"]: entry for entry in result["sizes"]}
        self.assertEqual([30, 60, 120, 200], sorted(sizes))
        for entry in sizes.values():
            self.assertIsNone(entry["compact"]["tokens"])
            self.assertGreater(entry["legacy"]["chars"], entry["compact"]["chars"])
        json.dumps(result, ensure_ascii=False)

    def test_tokenizer_requires_matching_pin(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "tokenizer.json"
            path.write_text("{}", encoding="utf-8")
            with self.assertRaises(SystemExit) as missing:
                screen_eval.load_tokenizer(str(path), None)
            self.assertIn("TOKENIZER_SHA256_MISMATCH", str(missing.exception))
            with self.assertRaises(SystemExit) as wrong:
                screen_eval.load_tokenizer(str(path), "0" * 64)
            self.assertIn("TOKENIZER_SHA256_MISMATCH", str(wrong.exception))


if __name__ == "__main__":
    unittest.main()
