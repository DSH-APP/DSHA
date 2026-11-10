#!/usr/bin/env python3
"""proroot 下 git 大 diff SIGSEGV 的 fail-loud 封装。

build330（proroot v1.2.8）本机实测：`git show --stat`、`git diff --stat` / `--numstat`
对某些提交或 ≥8 个路径的组合会确定性崩溃 —— exit 139、stdout 0 字节，stderr 只有
`[proroot] SIGSEGV pc=… lr=…`。历史命令常接 `| head` 或 `;`，最终 rc 常被吞成 0，
审查 / 合入会静默拿到空结果。本模块只做一件事：调用 git、识别该签名、立即抛错，
不自动重试、不降级、不返回部分输出；错误信息给出可执行的安全替代命令。

Python 用法：

    from git_guard import git_output
    names = git_output(["show", "--name-status", "--oneline", commit], cwd=ROOT)

shell 用法（需要统计类输出时用它包一层，崩溃立即报错）：

    python3 tools/git_guard.py -- show --stat --oneline <commit>

GitHub CI 在 Linux runner 上不存在该崩溃；这里只保护本机 proroot 环境，不改变
CI 的 git 语义。
"""
import os
import shlex
import subprocess
import sys

GIT = "git"
SIGSEGV_RETURNCODES = (139, -11)  # shell 的 128+SIGSEGV / subprocess 直接返回 -SIGSEGV
SIGSEGV_MARKER = "[proroot] SIGSEGV"
SAFE_COMMANDS = (
    "git --no-pager show --name-status --oneline <commit>",
    "git --no-pager diff --name-status <a> <b>",
    "git --no-pager diff --dirstat=files <a> <b>",
)


class GitSigsegvError(RuntimeError):
    """git 在 proroot 下 SIGSEGV：本次输出不可用，必须失败而不是继续。"""

    def __init__(self, args, returncode, stderr, cwd=None):
        self.git_args = [str(item) for item in args]
        self.returncode = returncode
        self.stderr = stderr if isinstance(stderr, str) else (stderr or b"").decode("utf-8", "replace")
        self.cwd = str(cwd) if cwd is not None else os.getcwd()
        super().__init__(self.describe())

    def describe(self):
        command = "git " + " ".join(shlex.quote(item) for item in self.git_args)
        first_stderr = self.stderr.strip().splitlines()[0] if self.stderr.strip() else "(空)"
        lines = [
            "GIT_PROROOT_SIGSEGV: git 在 proroot 下崩溃（rc=%s），本次 stdout 为空或已丢弃，结果不可用。"
            % self.returncode,
            "  命令: " + command,
            "  工作目录: " + self.cwd,
            "  stderr: " + first_stderr,
            "不自动重试、不静默回退。请改用安全命令并检查 rc / stderr：",
        ]
        lines.extend("    " + item for item in SAFE_COMMANDS)
        lines.append("  大范围改动按 ≤7 个路径分片执行；仍失败时重启环境或改用 proot 后重试。")
        lines.append("  不要把 git 输出接给 | head 或 ;，管道会吞掉本次失败。")
        return "\n".join(lines)


def is_proroot_crash(returncode, stderr):
    """rc=139/-11 或 stderr 带 `[proroot] SIGSEGV` 都视为该崩溃签名。"""
    text = stderr if isinstance(stderr, str) else (stderr or b"").decode("utf-8", "replace")
    return returncode in SIGSEGV_RETURNCODES or SIGSEGV_MARKER in text


def run_git(args, cwd=None, text=False, check=True):
    """等价于 subprocess.run(["git", *args], capture_output=True)；崩溃时明确失败。"""
    command = [GIT, *args]
    completed = subprocess.run(
        command, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=text)
    if is_proroot_crash(completed.returncode, completed.stderr):
        raise GitSigsegvError(args, completed.returncode, completed.stderr, cwd)
    if check and completed.returncode != 0:
        raise subprocess.CalledProcessError(
            completed.returncode, command, output=completed.stdout, stderr=completed.stderr)
    return completed


def git_output(args, cwd=None, text=False):
    """等价于 subprocess.check_output；proroot 崩溃时抛出可读的 GitSigsegvError。"""
    return run_git(args, cwd=cwd, text=text).stdout


def _write(stream, data):
    if not data:
        return
    try:
        stream.write(data)
        stream.flush()
    except BrokenPipeError:
        # 下游 | head 提前关闭；崩溃已经由退出码和 stderr 表达，这里不再刷屏。
        try:
            os.dup2(os.open(os.devnull, os.O_WRONLY), stream.fileno())
        except OSError:
            pass


def main(argv=None):
    args = list(sys.argv[1:] if argv is None else argv)
    if args[:1] == ["--"]:
        args = args[1:]
    if not args:
        print("用法: git_guard.py [--] <git 参数...>", file=sys.stderr)
        return 2
    try:
        completed = run_git(args)
    except GitSigsegvError as error:
        print(error, file=sys.stderr)
        return 139
    except subprocess.CalledProcessError as error:
        _write(sys.stdout.buffer, error.output or b"")
        _write(sys.stderr.buffer, error.stderr or b"")
        return error.returncode
    _write(sys.stdout.buffer, completed.stdout)
    _write(sys.stderr.buffer, completed.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
