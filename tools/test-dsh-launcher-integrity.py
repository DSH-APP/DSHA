#!/usr/bin/env python3
"""Real Node24 ESM launcher-anchor checks in disposable POSIX fixtures; no Android or network.

Run with DSHA_NODE24=/path/to/node24 python3 -B tools/test-dsh-launcher-integrity.py -v.
The fixture imports one minimal boot module to isolate Node's ESM path contract. It does
not claim to boot the full DSH application or exercise Android plugin capabilities.
"""
import contextlib
import argparse
import base64
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1]
ASSETS = Path(os.environ.get("DSHA_TEST_ASSETS", ROOT / "app/src/main/assets"))
BOOT = "@deepseek-ai/dsh-app-boot"
ENTRY = """#!/usr/bin/env node
import { marker } from '@deepseek-ai/dsh-app-boot';
console.log(JSON.stringify({ marker, url: import.meta.url, args: process.argv.slice(2) }));
"""


def put(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8", newline="\n")


@unittest.skipIf(os.name == "nt", "Run the POSIX symlink fixture under Linux/WSL; no Android required")
class DshLauncherIntegrity(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.node = os.environ.get("DSHA_NODE24") or shutil.which("node")
        if not cls.node:
            raise RuntimeError("NODE24_REQUIRED")
        version = subprocess.run([cls.node, "--version"], capture_output=True, text=True, timeout=10)
        if version.returncode or not version.stdout.startswith("v24."):
            raise RuntimeError("NODE24_REQUIRED: " + version.stdout + version.stderr)
        cls.java_temp = tempfile.TemporaryDirectory(prefix="dsha-entrypoint-java-")
        cls.addClassCleanup(cls.java_temp.cleanup)
        java_root = Path(cls.java_temp.name)
        java = os.environ.get("DSHA_JAVA17") or shutil.which("java")
        if not java:
            raise RuntimeError("JDK17_REQUIRED")
        java = Path(java).resolve()
        compiler = java.with_name("javac")
        version = subprocess.run([str(java), "-version"], capture_output=True, text=True, timeout=10)
        if version.returncode or 'version "17.' not in version.stdout + version.stderr:
            raise RuntimeError("JDK17_REQUIRED: " + version.stdout + version.stderr)
        source = ROOT / "app/src/main/java/com/deepseekharness/app/util"
        files = [source / (name + ".java") for name in ("DshEntrypoint", "WebLaunchCommand", "WebProcSel", "ShellQuote")]
        before = {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in files}
        helper = java_root / "EntryDump.java"
        put(helper, '''import com.deepseekharness.app.util.*;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
public class EntryDump {
  public static void main(String[] args) {
    for (String value : new String[] { DshEntrypoint.NODE, DshEntrypoint.SCRIPT,
      DshEntrypoint.exec(), DshEntrypoint.wrapper(),
      WebLaunchCommand.build("ask", true, "en", "web", 0, 42),
      WebLaunchCommand.build("ask", true, "en", "dsha-startup-safe-fixture", 0, 43) })
      System.out.println(Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
  }
}
''')
        result = subprocess.run([str(compiler), "-encoding", "UTF-8", "-d", str(java_root),
                                 *map(str, files), str(helper)], capture_output=True, text=True, timeout=45)
        if result.returncode:
            raise RuntimeError("ACTUAL_JAVA_ENTRYPOINT_COMPILE_FAILED: " + result.stderr)
        result = subprocess.run([str(java), "-cp", str(java_root), "EntryDump"], capture_output=True, text=True, timeout=15)
        if result.returncode:
            raise RuntimeError("ACTUAL_JAVA_ENTRYPOINT_DUMP_FAILED: " + result.stderr)
        values = [base64.b64decode(line).decode("utf-8") for line in result.stdout.splitlines()]
        if len(values) != 6 or before != {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in files}:
            raise RuntimeError("ACTUAL_JAVA_ENTRYPOINT_SOURCE_CHANGED")
        cls.java_values = dict(zip(("node", "script", "exec", "wrapper", "web", "safe"), values))

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="dsha-launcher-integrity-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.package = self.root / "usr/local/lib/node_modules/@deepseek-ai/dsh"
        self.bundled = self.package / "node_modules"
        self.bin = self.package / "lib/bin.js"
        self.command = self.root / "usr/local/bin/dsh"
        self.home = self.root / "root/.dsh"
        put(self.package / "package.json", json.dumps({"name": "@deepseek-ai/dsh", "type": "module"}))
        put(self.bin, ENTRY)
        self.boot = self.bundled / BOOT
        put(self.boot / "package.json", json.dumps({"name": BOOT, "type": "module", "exports": "./index.js"}))
        put(self.boot / "index.js", "export const marker = 'fixture-boot-from-package';\n")
        self.command.parent.mkdir(parents=True)
        self.command.symlink_to(os.path.relpath(self.bin, self.command.parent))
        (self.command.parent / "node").symlink_to(Path(self.node).resolve())
        put(self.home / "startup-observer.cjs", "// Private import-only preload; Android observer not exercised.\n")
        registry = json.loads((ASSETS / "builtin-plugins.json").read_text(encoding="utf-8"))
        self.managed = []
        for row in registry["plugins"]:
            directory = self.root / row["guestDirectory"].lstrip("/")
            put(directory / "package.json", json.dumps({"name": row["name"], "type": "module"}))
            # This is a resolution probe, never the actual plugin implementation.
            put(directory / "launcher-anchor-probe.mjs", ENTRY)
            self.managed.append(directory)
        with patch.dict(os.environ, {"DSHA_TEST_ROOT": str(self.root), "DSH_HOME": "/root/.dsh"}):
            spec = importlib.util.spec_from_file_location("launcher_fixture_register", ASSETS / "register-builtin-plugins.py")
            self.registrar = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(self.registrar)

    def node_run(self, entry=None, *args, node_path=None, node_flags=()):
        # Explicit private HOME/DSH_HOME; no inherited import hooks or credentials.
        env = {"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "HOME": str(self.root / "root"),
               "DSH_HOME": str(self.home), "LANG": "C.UTF-8"}
        if node_path is not None:
            env["NODE_PATH"] = str(node_path)
        return subprocess.run([self.node, *node_flags, str(entry or self.command), *args], env=env,
                              cwd=self.root, capture_output=True, text=True, timeout=15)

    def boot_ok(self, entry=None, args=(), node_flags=()):
        result = self.node_run(entry, *args, node_flags=node_flags)
        self.assertEqual(0, result.returncode, result.stderr)
        value = json.loads(result.stdout)
        self.assertEqual("fixture-boot-from-package", value["marker"])
        self.assertEqual(list(args), value["args"])
        return value

    def test_relative_symlink_keeps_package_esm_anchor_and_literal_arguments(self):
        value = self.boot_ok(args=("--profile", "web", "literal ' quoted; $(not-run)"))
        self.assertEqual(str(self.bin), unquote(urlsplit(value["url"]).path))
        self.assertTrue(self.command.is_symlink())

    def test_canonical_main_keeps_anchor_with_preserve_symlinks_main(self):
        value = self.boot_ok(self.bin, ("--profile", "web"), ("--preserve-symlinks-main",))
        self.assertEqual(str(self.bin), unquote(urlsplit(value["url"]).path))

    def test_global_symlink_is_not_sufficient_when_preserve_symlinks_main_is_set(self):
        result = self.node_run(node_flags=("--preserve-symlinks-main",))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Cannot find package '" + BOOT + "'", result.stderr)

    def guest_script(self, value):
        # Only guest filesystem prefixes move into the disposable fixture. The
        # production Java output, flags, shell quoting and argv stay unchanged.
        return value.replace("/usr/local", str(self.root / "usr/local")).replace("/root", str(self.root / "root"))

    def shell_run(self, shell):
        env = {"PATH": "/usr/bin:/bin", "HOME": str(self.root / "root"), "LANG": "C.UTF-8",
               "NODE_OPTIONS": "--preserve-symlinks-main"}
        return subprocess.run(["/bin/bash", "--noprofile", "--norc", "-c", self.guest_script(shell)],
                              env=env, cwd=self.root, capture_output=True, text=True, timeout=15)

    def test_actual_java_main_and_safe_shell_bypass_copied_global_command(self):
        self.assertEqual("/usr/local/bin/node", self.java_values["node"])
        self.assertEqual("/usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js", self.java_values["script"])
        self.command.unlink()
        self.command.write_bytes(self.bin.read_bytes())
        original = self.command.read_bytes()
        for label, expected in (("web", ["web"]), ("safe", ["--profile", "dsha-startup-safe-fixture"])):
            with self.subTest(mode=label):
                result = self.shell_run(self.java_values[label])
                self.assertEqual(0, result.returncode, result.stderr)
                value = json.loads(result.stdout)
                self.assertEqual(str(self.bin), unquote(urlsplit(value["url"]).path))
                self.assertEqual(expected, value["args"][:len(expected)])
                self.assertEqual(["--no-open", "--host", "127.0.0.1", "--port", "0"], value["args"][len(expected):])
                self.assertEqual(original, self.command.read_bytes())

    def test_actual_java_wrapper_bypasses_missing_dangling_and_copied_global_command(self):
        wrapper = self.root / "root/dsh-bin/dsh"
        put(wrapper, self.guest_script(self.java_values["wrapper"]))
        for state in ("missing", "dangling", "copied"):
            with self.subTest(state=state):
                if self.command.is_symlink() or self.command.exists():
                    self.command.unlink()
                if state == "dangling":
                    self.command.symlink_to("missing-bin.js")
                elif state == "copied":
                    self.command.write_bytes(self.bin.read_bytes())
                original = self.command.read_bytes() if state == "copied" else None
                result = subprocess.run(["/bin/sh", str(wrapper), "--profile", "literal ' quoted; $(not-run)"],
                                        env={"PATH": "/usr/bin:/bin", "NODE_OPTIONS": "--preserve-symlinks-main"},
                                        cwd=self.root, capture_output=True, text=True, timeout=15)
                self.assertEqual(0, result.returncode, result.stderr)
                value = json.loads(result.stdout)
                self.assertEqual(["--profile", "literal ' quoted; $(not-run)"], value["args"])
                self.assertEqual(str(self.bin), unquote(urlsplit(value["url"]).path))
                if state == "copied":
                    self.assertEqual(original, self.command.read_bytes())
                else:
                    self.assertFalse(self.command.exists())
                    self.assertEqual(state == "dangling", self.command.is_symlink())

    def test_actual_java_canonical_main_still_reports_missing_required_boot(self):
        shutil.rmtree(self.boot)
        for label in ("web", "safe"):
            with self.subTest(mode=label):
                result = self.shell_run(self.java_values[label])
                self.assertNotEqual(0, result.returncode)
                self.assertIn("Cannot find package '" + BOOT + "'", result.stderr)

    def test_copied_js_bin_reproduces_boot_package_error_even_with_node_path(self):
        self.command.unlink()
        self.command.write_bytes(self.bin.read_bytes())
        result = self.node_run(node_path=self.bundled)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("ERR_MODULE_NOT_FOUND", result.stderr)
        self.assertIn("Cannot find package '" + BOOT + "'", result.stderr)
        self.assertIn(str(self.command), result.stderr)

    def test_missing_and_dangling_command_are_explicit_failures(self):
        self.command.unlink()
        result = self.node_run()
        self.assertNotEqual(0, result.returncode)
        self.command.symlink_to("missing-bin.js")
        result = self.node_run()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.command.exists())
        self.assertTrue(self.command.is_symlink())

    def test_real_registrar_links_all_eight_wrappers_and_cache_reuses_them(self):
        self.assertEqual(8, len(self.managed))
        self.assertGreater(self.registrar.ensure_runtime_modules(), 0)
        with contextlib.redirect_stdout(io.StringIO()) as captured:
            self.assertEqual(0, self.registrar.ensure_runtime_modules())
        self.assertIn("RUNTIME_LINKS_CACHED", captured.getvalue())
        for directory in self.managed:
            self.assertEqual(self.bundled, (directory / "node_modules").resolve())
            self.boot_ok(directory / "launcher-anchor-probe.mjs")
        self.boot_ok()

    def test_deleted_wrapper_links_invalidate_cache_and_are_repaired(self):
        self.registrar.ensure_runtime_modules()
        for directory in self.managed:
            (directory / "node_modules").unlink()
        self.assertEqual(8, self.registrar.ensure_runtime_modules())
        for directory in self.managed:
            self.boot_ok(directory / "launcher-anchor-probe.mjs")

    def test_deleted_shared_boot_link_is_repaired_without_touching_package(self):
        self.registrar.ensure_runtime_modules()
        link = self.home / "node_modules" / BOOT
        self.assertEqual(self.boot, link.resolve())
        original = (self.boot / "index.js").read_bytes()
        link.unlink()
        self.assertEqual(1, self.registrar.ensure_runtime_modules())
        self.assertEqual(self.boot, link.resolve())
        self.assertEqual(original, (self.boot / "index.js").read_bytes())
        self.boot_ok()

    def test_known_cache_does_not_turn_copied_command_into_valid_package_anchor(self):
        self.registrar.ensure_runtime_modules()
        self.command.unlink()
        self.command.write_bytes(self.bin.read_bytes())
        original = self.command.read_bytes()
        with contextlib.redirect_stdout(io.StringIO()) as captured:
            self.registrar.ensure_runtime_modules()
        self.assertIn("RUNTIME_LINKS_CACHED", captured.getvalue())
        self.assertEqual(original, self.command.read_bytes())
        result = self.node_run()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Cannot find package '" + BOOT + "'", result.stderr)
        # Shared/profile repair cannot substitute for validating the launcher's anchor.
        self.boot_ok(self.bin)

    def test_missing_required_boot_cannot_be_hidden_by_empty_or_safe_profile(self):
        shutil.rmtree(self.boot)
        for profile in ("web", "dsha-startup-safe-fixture"):
            with self.subTest(profile=profile):
                put(self.home / "profiles" / profile / "package.json", json.dumps({"name": "private-test", "dsh": {"profile": {"bundles": []}}}))
                result = self.node_run(None, "--profile", profile)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("Cannot find package '" + BOOT + "'", result.stderr)


def actual_builtin_check(argv):
    parser = argparse.ArgumentParser(description="Import exact signed backend entrypoints in a private home; no tools/bridge are invoked")
    parser.add_argument("--runtime", required=True, type=Path)
    parser.add_argument("--node", required=True)
    parser.add_argument("--report", required=True, type=Path)
    args = parser.parse_args(argv)
    runtime = args.runtime.resolve(strict=True)
    node_version = subprocess.run([args.node, "--version"], capture_output=True, text=True, timeout=10)
    if node_version.returncode or not node_version.stdout.startswith("v24."):
        raise RuntimeError("NODE24_REQUIRED")
    registry_path = ASSETS / "builtin-plugins.json"
    registry = json.loads(registry_path.read_text(encoding="utf-8"))
    deployment_path = ASSETS / "managed-runtime-inputs.json"
    deployment = json.loads(deployment_path.read_text(encoding="utf-8"))
    rows = []
    args.report.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="dsha-actual-builtin-import-") as temporary:
        work = Path(temporary)
        for row in registry["plugins"]:
            target = work / row["name"]
            prefix = row["guestDirectory"].lstrip("/") + "/"
            sources = {}
            for install in deployment["installs"]:
                if not install["target"].startswith(prefix):
                    continue
                relative = Path(install["target"][len(prefix):])
                if relative.is_absolute() or ".." in relative.parts:
                    raise RuntimeError("BUILTIN_FIXTURE_TARGET_OUTSIDE")
                source = ASSETS / install["asset"]
                destination = target / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, destination)
                sources[relative.as_posix()] = source
            if "package.json" not in sources or row["entrypoint"] not in sources:
                raise RuntimeError("BUILTIN_FIXTURE_SIGNED_INSTALL_MISSING: " + row["name"])
            (target / "node_modules").symlink_to(runtime / "node_modules", target_is_directory=True)
            entry = target / row["entrypoint"]
            command = [args.node, "--input-type=module", "--eval",
                       "await import(process.argv[1]); console.log('DSHA_BUILTIN_MODULE_IMPORTED');", entry.as_uri()]
            env = {"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "HOME": str(work / "home"),
                   "DSH_HOME": str(work / "home/.dsh"), "NARB_DISABLE_NATIVE_CACHE": "1", "LANG": "C.UTF-8"}
            if os.name == "nt":
                env["SystemRoot"] = os.environ["SystemRoot"]
            try:
                result = subprocess.run(command, cwd=work, env=env, capture_output=True, text=True, timeout=35)
                code, stdout, stderr = result.returncode, result.stdout, result.stderr
                status = "IMPORTED" if code == 0 and stdout.splitlines().count("DSHA_BUILTIN_MODULE_IMPORTED") == 1 else "IMPORT_FAILED"
            except subprocess.TimeoutExpired as error:
                code, stdout, stderr = None, str(error.stdout or ""), str(error.stderr or "")
                status = "TIMEOUT_UNKNOWN"
            log = args.report.parent / (row["name"] + ".log")
            log.write_text(stdout + stderr, encoding="utf-8")
            rows.append({"name": row["name"], "entrypoint": row["entrypoint"], "status": status,
                         "exitCode": code, "sourcePackageSha256": hashlib.sha256(sources["package.json"].read_bytes()).hexdigest(),
                         "sourceEntrySha256": hashlib.sha256(sources[row["entrypoint"]].read_bytes()).hexdigest(),
                         "copiedSignedFiles": {relative: hashlib.sha256(path.read_bytes()).hexdigest() for relative, path in sources.items()},
                         "log": str(log), "logSha256": hashlib.sha256(log.read_bytes()).hexdigest(),
                         "stdout": stdout, "stderr": stderr})
    report = {"schema": 1, "scope": "Actual signed backend module imports only, exact source assets copied to private temporary trees, dependencies linked to supplied real managed Host runtime. No stub dependencies, fallback installs, tools or bridge calls. This does not prove plugin activation or Android capabilities.",
              "nodeVersion": node_version.stdout.strip(), "runtime": str(runtime),
              "registrySha256": hashlib.sha256(registry_path.read_bytes()).hexdigest(),
              "signedInstallTableSha256": hashlib.sha256(deployment_path.read_bytes()).hexdigest(), "rows": rows,
              "status": "PASS_MODULE_IMPORTS_ONLY" if all(row["status"] == "IMPORTED" for row in rows) else "FAILED_OR_UNKNOWN_MODULE_IMPORTS"}
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": report["status"], "rows": [{"name": row["name"], "status": row["status"], "exitCode": row["exitCode"]} for row in rows], "report": str(args.report)}))
    return 0 if report["status"] == "PASS_MODULE_IMPORTS_ONLY" else 1


if __name__ == "__main__":
    import sys
    if sys.argv[1:2] == ["--actual-builtin-check"]:
        raise SystemExit(actual_builtin_check(sys.argv[2:]))
    unittest.main()
