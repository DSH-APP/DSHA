#!/usr/bin/env python3
"""Device runners reject implicit adb and device selection without contacting a device."""
import contextlib
import importlib.util
import io
from pathlib import Path
import runpy
import subprocess
import sys
import unittest
from unittest.mock import patch
from device_script_privacy import redact


TOOLS = Path(__file__).resolve().parent
SCRIPTS = {
    "measure-stability-device.py": [],
    "device-policy-device-audit.py": ["--output", "result.json", "--fixture-id", "00000000-0000-0000-0000-000000000000"],
    "control-adb-flow-audit.py": ["status"],
}


class DeviceSelectionTest(unittest.TestCase):
    def test_missing_device_or_adb_never_starts_process(self):
        for filename, extra in SCRIPTS.items():
            for supplied in (["--adb", "fake-adb"], ["--serial", "fake-serial"]):
                with self.subTest(script=filename, supplied=supplied):
                    argv = [filename] + extra + supplied
                    with patch.object(sys, "argv", argv), \
                         patch.object(subprocess, "run", side_effect=AssertionError("device contacted")) as run, \
                         patch.object(subprocess, "Popen", side_effect=AssertionError("device contacted")) as popen, \
                         patch.object(subprocess, "check_output", side_effect=AssertionError("device contacted")) as check, \
                         contextlib.redirect_stderr(io.StringIO()):
                        with self.assertRaises(SystemExit) as exit_result:
                            runpy.run_path(str(TOOLS / filename), run_name="__main__")
                    self.assertEqual(2, exit_result.exception.code)
                    run.assert_not_called()
                    popen.assert_not_called()
                    check.assert_not_called()

    def test_control_prefix_always_selects_a_device(self):
        source = TOOLS / "control-adb-flow-audit.py"
        spec = importlib.util.spec_from_file_location("device_control", source)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        self.assertEqual(["fake-adb", "-s", "fake-serial"], module.adb_prefix("fake-adb", "fake-serial"))
        for adb, serial in (("", "fake-serial"), ("fake-adb", "")):
            with self.assertRaises(ValueError):
                module.adb_prefix(adb, serial)

    def test_report_redaction_covers_nested_values(self):
        value = {"command": ["fake-adb", "-s", "fake-serial"], "message": "fake-serial via fake-adb"}
        safe = redact(value, ("fake-serial", "fake-adb"))
        self.assertNotIn("fake-serial", repr(safe))
        self.assertNotIn("fake-adb", repr(safe))
        self.assertEqual("[local selection] via [local selection]", safe["message"])


if __name__ == "__main__":
    unittest.main()
