"""Pinned JDK 17 Java formatting for explicit files; history and upstream bytes stay out."""
import argparse
import difflib
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
LOCK_PATH = ROOT / "tools/java-format.lock.json"
ADOPTED_PATH = ROOT / "tools/java-format-files.json"
CACHE = ROOT / "app/build/java-format"
HELPER = ROOT / "tools/java-format/JavaTokenFingerprint.java"
EXPORTS = [
    "--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
]


class FormatError(Exception):
    pass


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def checked_path(name, root=ROOT, protected=()):
    """Validate spelling as well as resolution, including every symlinked ancestor."""
    root = root.resolve()
    candidate = Path(name)
    if not candidate.is_absolute():
        candidate = root / candidate
    if ".." in candidate.parts:
        raise FormatError("FORMAT_PATH_TRAVERSAL: " + str(name))
    try:
        relative = candidate.relative_to(root)
        resolved = candidate.resolve(strict=True)
        resolved.relative_to(root)
    except (ValueError, OSError) as error:
        raise FormatError("FORMAT_PATH_OUTSIDE_OR_MISSING: " + str(name)) from error
    for parent in [candidate, *candidate.parents]:
        if parent == root:
            break
        if parent.is_symlink() or (hasattr(parent, "is_junction") and parent.is_junction()):
            raise FormatError("FORMAT_LINK_REFUSED: " + str(name))
    if resolved != candidate.absolute():
        raise FormatError("FORMAT_REDIRECTED_PATH_REFUSED: " + str(name))
    spelling = relative.as_posix()
    parts = relative.parts
    fixture = spelling.startswith("app/build/java-format/fixtures/")
    own_app = (
        len(parts) >= 8 and parts[:2] == ("app", "src")
        and parts[3:7] == ("java", "com", "deepseekharness", "app")
    )
    own_tool = spelling.startswith("tools/java-format/")
    forbidden = {"history", "upstream", "vendor", "third_party", "third-party", "generated"}
    if (not (own_app or own_tool or fixture) or forbidden.intersection(parts)
            or spelling.casefold() in {item.casefold() for item in protected}
            or candidate.suffix != ".java" or not candidate.is_file()):
        raise FormatError("FORMAT_UNOWNED_OR_PROTECTED: " + str(name))
    return candidate, spelling


def changed_files(root=ROOT):
    """Tracked staged/unstaged changes and new unignored files, never a source-tree scan."""
    tracked = subprocess.check_output(
        ["git", "diff", "--name-only", "-z", "--diff-filter=ACMRT", "HEAD", "--", "*.java"], cwd=root
    )
    untracked = subprocess.check_output(
        ["git", "ls-files", "--others", "--exclude-standard", "-z", "--", "*.java"], cwd=root
    )
    return sorted(set(item for item in (tracked + untracked).decode("utf-8").split("\0") if item))


def select_files(names, lock):
    if names == ["changed"]:
        names = changed_files()
    elif names == ["adopted"]:
        declaration = json.loads(ADOPTED_PATH.read_text(encoding="utf-8"))
        if declaration.get("schema") != 1 or not isinstance(declaration.get("files"), list):
            raise FormatError("FORMAT_ADOPTION_SCHEMA")
        names = declaration["files"]
    elif "changed" in names or "adopted" in names:
        raise FormatError("FORMAT_SELECTOR_CANNOT_BE_MIXED")
    if len(names) > 256:
        raise FormatError("FORMAT_TOO_MANY_FILES: choose an explicit package batch, at most 256")
    result = []
    seen = set()
    for name in names:
        if not isinstance(name, str):
            raise FormatError("FORMAT_FILE_MUST_BE_STRING")
        path, relative = checked_path(
            name, protected=lock.get("protectedFiles", []) + lock.get("generatedFiles", []))
        if relative in seen:
            raise FormatError("FORMAT_DUPLICATE_FILE: " + relative)
        result.append((path, relative))
        seen.add(relative)
    return result


def java_command(explicit, lock):
    selected = explicit or os.environ.get("DSHA_JAVA17")
    if not selected and os.environ.get("JAVA_HOME"):
        selected = str(Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java"))
    selected = selected or shutil.which("java")
    if not selected:
        raise FormatError("FORMAT_JDK17_MISSING: set JAVA_HOME, DSHA_JAVA17, or --java")
    executable = Path(selected).resolve(strict=True)
    version = subprocess.run([str(executable), "-version"], capture_output=True, timeout=30)
    text = (version.stdout + version.stderr).decode("utf-8", errors="replace")
    match = re.search(r'version "(\d+)', text)
    if version.returncode or not match or int(match[1]) != lock["javaMajor"]:
        raise FormatError("FORMAT_REQUIRES_JDK17: " + text.strip())
    compiler = executable.with_name("javac.exe" if os.name == "nt" else "javac")
    if not compiler.is_file():
        raise FormatError("FORMAT_JDK_COMPILER_MISSING")
    return executable, compiler, text.strip()


def artifact(lock, offline):
    CACHE.mkdir(parents=True, exist_ok=True)
    jar = CACHE / lock["artifact"]
    if jar.exists():
        data = jar.read_bytes()
        if len(data) != lock["size"] or sha256(data) != lock["sha256"]:
            raise FormatError("FORMAT_CACHE_DIGEST_MISMATCH: " + str(jar))
        return jar
    if offline:
        raise FormatError("FORMAT_OFFLINE_CACHE_MISSING: " + str(jar))
    with urllib.request.urlopen(lock["mavenUrl"], timeout=45) as response:
        data = response.read(lock["size"] + 1)
    if (len(data) != lock["size"] or sha256(data) != lock["sha256"]
            or hashlib.sha1(data).hexdigest() != lock["publishedSha1"]):
        raise FormatError("FORMAT_DOWNLOAD_DIGEST_MISMATCH")
    atomic_write(jar, data)
    return jar


def atomic_write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    mode = path.stat().st_mode if path.exists() else None
    descriptor, temporary = tempfile.mkstemp(prefix=".format-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as output:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        if mode is not None:
            os.chmod(temporary, mode)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def token_helper(java, javac):
    # Cache the compile by exact helper bytes. It has no Android/Gradle dependency.
    destination = CACHE / ("tokens-" + sha256(HELPER.read_bytes())[:24])
    destination.mkdir(parents=True, exist_ok=True)
    # Recompile rather than trusting an executable helper left in a mutable build cache.
    result = subprocess.run(
        [str(javac), *EXPORTS, "-encoding", "UTF-8", "-d", str(destination), str(HELPER)],
        capture_output=True, timeout=60
    )
    if result.returncode:
        raise FormatError("FORMAT_TOKEN_HELPER_COMPILE: " + result.stderr.decode("utf-8", errors="replace"))
    return [str(java), "-Dfile.encoding=UTF-8", *EXPORTS, "-cp", str(destination), "dsha.tools.JavaTokenFingerprint"]


def verify_tokens(command, before, after):
    if b"\0" in before or b"\0" in after:
        raise FormatError("FORMAT_NUL_SOURCE")
    result = subprocess.run(command, input=before + b"\0" + after, capture_output=True, timeout=60)
    lines = result.stdout.decode("ascii", errors="replace").splitlines()
    if (result.returncode or len(lines) != 2 or lines[0] != lines[1]
            or not re.fullmatch("[0-9a-f]{64}", lines[0])):
        raise FormatError("FORMAT_TOKEN_CHANGE: exact Java tokens must remain identical")
    return lines[0]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true", help="fail with exit 1 if any selected file needs formatting")
    mode.add_argument("--write", action="store_true", help="write only the explicitly selected files after token validation")
    parser.add_argument("--files", nargs="+", required=True, metavar="PATH|changed|adopted")
    parser.add_argument("--java", help="JDK 17 java executable (otherwise DSHA_JAVA17/JAVA_HOME/PATH)")
    parser.add_argument("--offline", action="store_true", help="require the verified app/build cache; never download")
    parser.add_argument("--report", help="write SHA/token/diff evidence beneath app/build/java-format")
    args = parser.parse_args(argv)
    lock = json.loads(LOCK_PATH.read_text(encoding="utf-8"))
    if lock.get("schema") != 1 or lock.get("tool") != "google-java-format":
        raise FormatError("FORMAT_LOCK_SCHEMA")
    selected = select_files(args.files, lock)
    report = None
    if args.report:
        report = Path(args.report)
        if not report.is_absolute():
            report = ROOT / report
        try:
            report.resolve().relative_to(CACHE.resolve())
        except ValueError as error:
            raise FormatError("FORMAT_REPORT_OUTSIDE_CACHE") from error
    if not selected:
        print("PASS no changed Java files")
        return 0
    java, javac, version = java_command(args.java, lock)
    jar = artifact(lock, args.offline)
    helper = token_helper(java, javac)
    entries = []
    prepared = []
    # Validate the whole batch before writing any source. No formatter -i is used.
    for path, relative in selected:
        before = path.read_bytes()
        before.decode("utf-8", errors="strict")
        output = subprocess.run(
            [str(java), "-Dfile.encoding=UTF-8", "-jar", str(jar), *lock["flags"], "--assume-filename", relative, "-"],
            input=before, capture_output=True, timeout=60
        )
        if output.returncode:
            raise FormatError("FORMAT_PARSE_FAILED: " + relative + "\n" + output.stderr.decode("utf-8", errors="replace"))
        after = output.stdout.replace(b"\r\n", b"\n")
        token_sha = verify_tokens(helper, before, after)
        changed = before != after
        entries.append({
            "file": relative, "beforeSha256": sha256(before), "formattedSha256": sha256(after),
            "javaTokenSha256": token_sha, "needsFormatting": changed,
            "diff": "".join(difflib.unified_diff(
                before.decode("utf-8").splitlines(keepends=True), after.decode("utf-8").splitlines(keepends=True),
                fromfile=relative + " (before)", tofile=relative + " (formatted)"
            ))
        })
        prepared.append((path, before, after, changed))
    if args.write:
        for path, before, after, changed in prepared:
            if path.read_bytes() != before:
                raise FormatError("FORMAT_CONCURRENT_SOURCE_CHANGE: " + str(path))
            if changed:
                atomic_write(path, after)
    if report:
        atomic_write(report, (json.dumps({
            "schema": 1, "tool": lock["tool"], "version": lock["version"],
            "artifactSha256": lock["sha256"], "javaVersion": version,
            "mode": "write" if args.write else "check", "files": entries
        }, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
    count = sum(entry["needsFormatting"] for entry in entries)
    for entry in entries:
        label = "WROTE " if args.write and entry["needsFormatting"] else "NEEDS_FORMAT " if entry["needsFormatting"] else "PASS "
        print(label + entry["file"])
    print(f"google-java-format {lock['version']}: {len(entries)} files, {count} changed; exact Java tokens preserved")
    return 1 if args.check and count else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (FormatError, OSError, ValueError, subprocess.SubprocessError) as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(2)
