import ast
import os
from pathlib import Path
import subprocess
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]


class VerificationChecks(unittest.TestCase):
    def test_explicit_check_survives_optimization(self):
        for flag in ("-B", "-O", "-OO"):
            result = subprocess.run(
                [sys.executable, flag, "-c",
                 "from verification_require import require; require(False, 'EXPECTED_REJECTION')"],
                cwd=ROOT / "tools", capture_output=True, text=True,
                env={**os.environ, "PYTHONOPTIMIZE": "2"})
            self.assertNotEqual(0, result.returncode)
            self.assertIn("EXPECTED_REJECTION", result.stderr)

    def test_release_gates_do_not_use_erasable_assertions(self):
        for path in sorted((ROOT / "tools").glob("verify-*.py")):
            tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
            self.assertFalse(any(isinstance(node, ast.Assert) for node in ast.walk(tree)), path.name)


if __name__ == "__main__":
    unittest.main()
