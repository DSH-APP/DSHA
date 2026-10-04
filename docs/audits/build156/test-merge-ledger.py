"""Audit-local regression for coverage, policy and stale-cache classification."""
from pathlib import Path
import importlib.util
import json
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
spec = importlib.util.spec_from_file_location("audit_merge156", HERE / "merge-ledger.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class LedgerTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="dsha-ledger156-")
        self.repository = Path(self.directory.name).resolve()
        self.audit = self.repository / "docs/audits/build156"
        self.audit.mkdir(parents=True)
        self.base = json.loads((ROOT / "docs/audits/build155/execution-ledger-final.json").read_text(encoding="utf-8"))
        self.assignments = {}
        for lane in module.LANES:
            self.assignments[lane] = json.loads((HERE / f"assignment-{lane}.json").read_text(encoding="utf-8"))
            self.write(f"docs/audits/build156/assignment-{lane}.json", self.assignments[lane])
        self.write("docs/audits/build155/execution-ledger-final.json", self.base)

    def tearDown(self):
        # TemporaryDirectory owns this explicitly created private test root.
        self.directory.cleanup()

    def write(self, relative, document):
        target = self.repository / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(document), encoding="utf-8")

    def report(self, lane, override=None):
        items = []
        for assignment in self.assignments[lane]["items"]:
            identity = assignment["source"]["id"]
            row = {"id": identity, "currentDisposition": "verified_no_remaining_software_change", "files": [], "checks": [], "softwareChanges": [], "missingEvidence": [], "residual": [], "changed": False}
            if override and identity == override["id"]:
                row.update(override)
            items.append(row)
        self.write(f"docs/audits/build156/review-{lane}.json", {"items": items})
        return items

    def test_no_fresh_review_does_not_reuse_old_remaining_cache(self):
        result = module.merge(self.repository, False)
        self.assertEqual(result["sourceCoverage"], 236)
        self.assertEqual(result["reviewCoverage"], 0)
        self.assertEqual(len(result["reviewPendingIds"]), 236)
        self.assertEqual(result["remainingSoftwareIds"], [])
        self.assertEqual(result["ignoredBaselineResidualActionIds"], self.base["residualActionIds"])

    def test_final_requires_all_owners(self):
        with self.assertRaisesRegex(ValueError, "fresh owner reports missing"):
            module.merge(self.repository, True)

    def test_duplicate_assignment_is_rejected(self):
        document = self.assignments["docs"]
        document["items"].append(document["items"][0])
        self.write("docs/audits/build156/assignment-docs.json", document)
        with self.assertRaisesRegex(ValueError, "duplicate audit ID"):
            module.merge(self.repository, False)

    def test_owner_report_omission_is_rejected(self):
        items = self.report("docs")[:-1]
        self.write("docs/audits/build156/review-docs.json", {"items": items})
        with self.assertRaisesRegex(ValueError, "missing="):
            module.merge(self.repository, False)

    def test_invalid_disposition_cannot_become_an_accepted_bug(self):
        identity = self.assignments["docs"]["items"][0]["source"]["id"]
        self.report("docs", {"id": identity, "currentDisposition": "all_done"})
        with self.assertRaisesRegex(ValueError, "noncanonical"):
            module.merge(self.repository, False)

    def test_fixed_source_and_missing_hardware_are_separate(self):
        identity = self.assignments["docs"]["items"][0]["source"]["id"]
        self.report("docs", {"id": identity, "currentDisposition": "fixed_current_source", "softwareChanges": ["actual source fix"], "missingEvidence": [{"kind": "device", "detail": "not operated"}], "changed": True})
        result = module.merge(self.repository, False)
        self.assertEqual(result["remainingSoftwareIds"], [])
        self.assertEqual(result["deviceEvidenceIds"], [identity])
        self.assertEqual(result["reviewCoverage"], len(self.assignments["docs"]["items"]))

    def test_required_contract_does_not_count_as_unfixed_software(self):
        identity = self.assignments["docs"]["items"][0]["source"]["id"]
        self.report("docs", {"id": identity, "currentDisposition": "required_contract", "residual": ["old archive readers remain required"]})
        result = module.merge(self.repository, False)
        self.assertEqual(result["requiredContractIds"], [identity])
        self.assertEqual(result["remainingSoftwareIds"], [])

    def test_complete_reports_have_236_current_ids(self):
        for lane in module.LANES:
            self.report(lane)
        result = module.merge(self.repository, True)
        self.assertEqual(result["reviewCoverage"], 236)
        self.assertEqual(result["reviewPendingIds"], [])
        self.assertEqual(result["phase"], "final_owner_review")


if __name__ == "__main__":
    unittest.main()
