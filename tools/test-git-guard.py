"""proroot 下 git SIGSEGV 的 fail-loud 保护：模拟 rc=139、stderr 签名与正常 rc=0。"""
import contextlib
import importlib.util
import io
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

TOOLS = Path(__file__).resolve().parent


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, TOOLS / file)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


guard = load("git_guard", "git_guard.py")


def simulated(body):
    """用当前解释器冒充 git：run_git 的 argv 原样传给 `python -c`。"""
    return ["-c", body]


class SignatureTest(unittest.TestCase):
    def test_returncode_139_is_crash(self):
        self.assertTrue(guard.is_proroot_crash(139, b""))
        self.assertTrue(guard.is_proroot_crash(-11, ""))

    def test_stderr_marker_is_crash_even_with_rc_zero(self):
        self.assertTrue(guard.is_proroot_crash(0, b"[proroot] SIGSEGV pc=0x1 lr=0x2\n"))

    def test_normal_exit_and_ordinary_failure_are_not_crash(self):
        self.assertFalse(guard.is_proroot_crash(0, b""))
        self.assertFalse(guard.is_proroot_crash(0, b"fatal: bad revision\n"))
        self.assertFalse(guard.is_proroot_crash(1, b"fatal: bad revision\n"))


class RunGitTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.calls = Path(self.temp.name) / "calls"
        original = guard.GIT
        guard.GIT = sys.executable
        self.addCleanup(setattr, guard, "GIT", original)

    def crash_body(self):
        return (
            "open(%r, 'a').write('x')\n"
            "import sys\n"
            "sys.stderr.write('[proroot] SIGSEGV pc=0x1 lr=0x2\\n')\n"
            "sys.exit(139)\n" % str(self.calls)
        )

    def test_crash_raises_clear_error_without_retry(self):
        with self.assertRaises(guard.GitSigsegvError) as caught:
            guard.run_git(simulated(self.crash_body()))
        error = caught.exception
        message = str(error)
        self.assertEqual(error.returncode, 139)
        self.assertIn("GIT_PROROOT_SIGSEGV", message)
        self.assertIn("stdout", message)
        self.assertIn("--name-status", message)
        self.assertIn("--dirstat", message)
        self.assertIn("| head", message)
        self.assertIn("[proroot] SIGSEGV", message)
        self.assertEqual(self.calls.read_text(), "x", "崩溃只允许执行一次，不自动重试")

    def test_success_returns_output(self):
        completed = guard.run_git(simulated("import sys; sys.stdout.write('name-status\\n')"))
        self.assertEqual(completed.returncode, 0)
        self.assertEqual(completed.stdout, b"name-status\n")
        self.assertEqual(
            guard.git_output(simulated("import sys; sys.stdout.write('name-status\\n')"), text=True),
            "name-status\n")

    def test_ordinary_failure_raises_called_process_error(self):
        body = "import sys; sys.stderr.write('fatal: bad revision\\n'); sys.exit(2)"
        with self.assertRaises(subprocess.CalledProcessError) as caught:
            guard.run_git(simulated(body))
        self.assertEqual(caught.exception.returncode, 2)
        self.assertNotIsInstance(caught.exception, guard.GitSigsegvError)

    def test_check_false_returns_ordinary_failure(self):
        completed = guard.run_git(simulated("import sys; sys.exit(3)"), check=False)
        self.assertEqual(completed.returncode, 3)


class StreamCapture:
    """CLI 用 sys.stdout.buffer / print 两种写入方式；统一收进 BytesIO。"""

    def __init__(self):
        self.buffer = io.BytesIO()

    def write(self, data):
        encoded = data.encode("utf-8") if isinstance(data, str) else data
        self.buffer.write(encoded)
        return len(encoded)

    def flush(self):
        pass


class CommandLineTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.calls = Path(self.temp.name) / "calls"
        original = guard.GIT
        guard.GIT = sys.executable
        self.addCleanup(setattr, guard, "GIT", original)

    def crash_body(self):
        return (
            "open(%r, 'a').write('x')\n"
            "import sys\n"
            "sys.stderr.write('[proroot] SIGSEGV pc=0x1 lr=0x2\\n')\n"
            "sys.exit(139)\n" % str(self.calls)
        )

    def cli(self, *args):
        out, err = StreamCapture(), StreamCapture()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            # redirect_* 替换了 stdout 对象本身；main 通过 .buffer 写字节，StreamCapture 提供 buffer。
            code = guard.main(list(args))
        return code, out.buffer.getvalue(), err.buffer.getvalue()

    def test_cli_crash_is_empty_stdout_and_clear_failure(self):
        code, out, err = self.cli(*simulated(self.crash_body()))
        self.assertEqual(code, 139)
        self.assertEqual(out, b"")
        self.assertIn(b"GIT_PROROOT_SIGSEGV", err)
        self.assertIn(b"--name-status", err)
        self.assertEqual(self.calls.read_text(), "x", "CLI 也不能自动重试")

    def test_cli_success_passes_output_through(self):
        code, out, err = self.cli(*simulated("import sys; sys.stdout.write('name-status\\n')"))
        self.assertEqual(code, 0)
        self.assertEqual(out, b"name-status\n")
        self.assertEqual(err, b"")

    def test_cli_ordinary_failure_keeps_rc_and_stderr(self):
        code, out, err = self.cli(
            *simulated("import sys; sys.stderr.write('fatal: bad revision\\n'); sys.exit(2)"))
        self.assertEqual(code, 2)
        self.assertEqual(out, b"")
        self.assertIn(b"fatal: bad revision", err)

    def test_cli_requires_arguments(self):
        code, out, err = self.cli()
        self.assertEqual(code, 2)
        self.assertEqual(out, b"")
        self.assertIn(b"git_guard.py", err)


class RealGitTest(unittest.TestCase):
    def test_cli_process_exit_code_through_real_git(self):
        completed = subprocess.run(
            [sys.executable, "-B", str(TOOLS / "git_guard.py"), "--", "--version"],
            capture_output=True)
        self.assertEqual(completed.returncode, 0)
        self.assertTrue(completed.stdout.startswith(b"git version"), completed.stdout)


if __name__ == "__main__":
    unittest.main()
