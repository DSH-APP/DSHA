#!/usr/bin/env python3
"""Execute the Java-produced Web shell and managed wrappers in a private Linux mount namespace.

This host regression never opens an Android device or uses a user's DSH home. Run on Windows
after RuntimePackageCommandsAndroidTest has produced published-commands.json. Requires the
DSHA-build172 WSL distribution and an explicitly supplied Linux Node 24 installation.
"""
from __future__ import annotations

import argparse
import base64
from datetime import datetime, timezone
import hashlib
import http.cookiejar
import json
import os
from pathlib import Path
import re
import selectors
import shutil
import signal
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.request

NAME = "build190-fix2-command-env-review"


def sha(file: Path) -> str:
    return hashlib.sha256(file.read_bytes()).hexdigest()


def write(file: Path, text: str, executable: bool = False):
    file.parent.mkdir(parents=True, exist_ok=True)
    file.write_text(text, encoding="utf-8", newline="\n")
    if executable:
        file.chmod(0o755)


def redact(text: str) -> str:
    return re.sub(r"(?i)([?&](?:token|auth|secret)=)[^\s&]+", r"\1<isolated-redacted>", text)


def command(file: Path, argv: list[str], *, env=None, timeout=50):
    result = subprocess.run(argv, env=env, capture_output=True, text=True,
                            encoding="utf-8", errors="replace", timeout=timeout)
    write(file, redact(result.stdout + result.stderr))
    return result


def windows(root: Path, args):
    out = root / "app/build" / NAME
    out.mkdir(parents=True, exist_ok=True)
    source = root / "app/src/main/java/com/deepseekharness/app/util"
    classes = out / "java-classes"
    classes.mkdir(exist_ok=True)
    helper = out / "CommandEnvironmentShell.java"
    write(helper, '''import com.deepseekharness.app.util.WebLaunchCommand;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
public class CommandEnvironmentShell {
  public static void main(String[] a) {
    System.out.print(Base64.getEncoder().encodeToString(WebLaunchCommand.build(
      a[0],true,a[1],a[2],0,42).getBytes(StandardCharsets.UTF_8)));
  }
}
''')
    java = Path(os.environ.get("JAVA_HOME", "D:/develop/JDK")) / "bin"
    compile_result = command(out / "compile-shell.log", [str(java / "javac.exe"),
        "-encoding", "UTF-8", "-d", str(classes), *(str(source / (n + ".java"))
        for n in ["DshEntrypoint", "WebLaunchCommand", "WebProcSel", "ShellQuote"]), str(helper)])
    assert compile_result.returncode == 0, compile_result.stderr
    settings = {
        "normal": ["ask", "zh", "web"],
        "web": ["danger-full-access", "zh", "web"],
        "quoted": ["ask'; touch /root/injected-permission; #",
                   "zh'\n$(touch /root/injected-language)",
                   "web'; touch /root/injected-profile; #"],
    }
    generated = {}
    for label, values in settings.items():
        r = subprocess.run([str(java / "java.exe"), "-cp", str(classes),
            "CommandEnvironmentShell", *values], check=True, capture_output=True, text=True)
        generated[label] = base64.b64decode(r.stdout).decode("utf-8")
    write(out / "java-generated-shells.json", json.dumps({"commands": generated,
          "settings": settings}, indent=2, ensure_ascii=False) + "\n")
    # The existing host runtime was installed for Windows. Supply its exact optional native
    # package version through NODE_PATH, without changing that runtime or any APK input.
    native = out / "host-native/node_modules/node-addon-require-builtin-linux-x64-gnu"
    native_metadata_file = out / "host-native-native-metadata.json"
    version = json.loads((root / "app/build/runtime190-managed-host/node_modules/node-addon-require-builtin/package.json").read_text())["version"]
    if not native_metadata_file.exists():
        metadata = json.loads(urllib.request.urlopen("https://registry.npmjs.org/node-addon-require-builtin-linux-x64-gnu/" + version, timeout=30).read())
        write(native_metadata_file, json.dumps(metadata, indent=2) + "\n")
    metadata = json.loads(native_metadata_file.read_text())
    assert metadata["version"] == version
    archive = out / ("node-addon-require-builtin-linux-x64-gnu-" + version + ".tgz")
    if not archive.exists():
        archive.write_bytes(urllib.request.urlopen(metadata["dist"]["tarball"], timeout=45).read())
    algorithm, digest = metadata["dist"]["integrity"].split("-", 1)
    assert base64.b64encode(hashlib.new(algorithm, archive.read_bytes()).digest()).decode() == digest
    if not (native / "package.json").exists():
        with tarfile.open(archive, "r:gz") as package:
            for member in package:
                if not member.isfile():
                    continue
                parts = Path(member.name).parts
                assert parts[0] == "package" and ".." not in parts
                target = native.joinpath(*parts[1:])
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(package.extractfile(member).read())
    linux_script = "/mnt/" + root.drive[0].lower() + str(root)[2:].replace("\\", "/") + "/tools/" + Path(__file__).name
    node = Path(args.node).resolve()
    linux_node = "/mnt/" + node.drive[0].lower() + str(node)[2:].replace("\\", "/")
    argv = ["wsl.exe", "-d", args.distro, "--exec", "unshare", "--mount",
        "--propagation", "private", "/usr/bin/python3", linux_script,
        "--inside", "--node", linux_node, "--web-timeout", str(args.web_timeout)]
    if args.linux_runtime:
        argv += ["--linux-runtime", args.linux_runtime]
    if args.web_only:
        argv += ["--web-only"]
    run = subprocess.run(argv, capture_output=True)
    # WSL emits a UTF-16 localhost warning before the UTF-8 program's output.
    write(out / "wsl-run.stdout.log", run.stdout.decode("utf-8", errors="replace"))
    write(out / "wsl-run.stderr.log", run.stderr.decode("utf-8", errors="replace"))
    print(run.stdout.decode("utf-8", errors="replace"))
    return run.returncode


def inside(root: Path, args):
    out = root / "app/build" / NAME
    work = Path(tempfile.mkdtemp(prefix="run-", dir=out))
    local = work / "usr/local"
    private_home = work / "root"
    profile_d = work / "etc/profile.d"
    for d in [local / "bin", local / "lib/node_modules", private_home / "dsh-bin", profile_d]:
        d.mkdir(parents=True)
    # Absolute guest paths remain unchanged. Only these three targets are privately mounted.
    for source, target in [(private_home, "/root"), (local, "/usr/local"), (profile_d, "/etc/profile.d")]:
        subprocess.run(["mount", "--bind", str(source), target], check=True)
    assert os.stat("/root").st_ino == private_home.stat().st_ino
    node = Path(args.node).resolve()
    Path("/usr/local/bin/node").symlink_to(node)
    Path("/usr/local/lib/node_modules/npm").symlink_to(node.parent.parent / "lib/node_modules/npm")
    runtime = Path(args.linux_runtime) if args.linux_runtime else root / "app/build/runtime190-managed-host"
    original_runtime = root / "app/build/runtime190-managed-host"
    host_module_names = ["@deepseek-ai/dsh/lib/bin.js", "@deepseek-ai/dsh-plugin-manager/lib/index.js",
                         "@deepseek-ai/dsh-bash-local/lib/index.js", "@deepseek-ai/dsh-subprocess-local/lib/index.js",
                         "@deepseek-ai/cordis/lib/index.js", "@deepseek-ai/cordis-plugin-loader/lib/index.js"]
    host_hashes = {name: sha(runtime / "node_modules" / name) for name in host_module_names}
    if not args.web_only:
        assert host_hashes == {name: sha(original_runtime / "node_modules" / name) for name in host_module_names}, "HOST_COPY_CHANGED"
    dsh = runtime / "node_modules/@deepseek-ai/dsh"
    Path("/usr/local/lib/node_modules/@deepseek-ai").mkdir()
    Path("/usr/local/lib/node_modules/@deepseek-ai/dsh").symlink_to(dsh)
    write(Path("/usr/local/bin/dsh"), '#!/bin/sh\nexec /usr/local/bin/node '
          + str(dsh / "lib/bin.js") + ' "$@"\n', True)
    pnpm = Path("/usr/local/lib/dsha-pnpm")
    pnpm.mkdir()
    archive_path = root / "app/src/main/assets/pnpm-runtime.bin"
    with tarfile.open(archive_path, "r:gz") as archive:
        prefix = "usr/local/lib/dsha-pnpm/"
        for member in archive:
            if not member.isfile() or not member.name.startswith(prefix):
                continue
            relative = Path(member.name[len(prefix):])
            if relative.is_absolute() or ".." in relative.parts:
                raise ValueError("ARCHIVE_PATH")
            target = pnpm / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.extractfile(member).read())
    published_path = root / "app/build/build190-fix2-package-commands/published-commands.json"
    published = json.loads(published_path.read_text())
    for name, text in published["wrappers"].items():
        write(Path("/root/dsh-bin") / name, text, True)
    write(Path("/root/.dsh/startup-observer.cjs"),
          (root / "app/src/main/assets/startup-observer.cjs").read_text())
    write(Path("/etc/profile.d/dsha-runtime-env.sh"),
          (root / "app/src/main/assets/dsha-runtime-env.sh").read_text())
    for name in ["web", "env-review", "env-cli"]:
        directory = Path("/root/.dsh/profiles") / name
        directory.mkdir(parents=True, exist_ok=True)
        # The Web run uses the shipped base and Web bundles, without user data or credentials.
        bundles = ["@deepseek-ai/dsh-base", "@deepseek-ai/dsh-web-app"] if name == "web" else []
        write(directory / "package.json", json.dumps({"name": "dsha-env-" + name,
              "private": True, "dsh": {"profile": {"bundles": bundles}}}))
        write(directory / "cordis.patch.yml", "[]\n")
    shell_data = json.loads((out / "java-generated-shells.json").read_text())
    assert shell_data["commands"]["normal"] == published["webCommand"], "JAVA_RECEIPT_STALE"
    probe = root / "tools/fixtures/build190-command-environment.mjs"
    inherited_path = "/usr/local/bin:/usr/bin:/bin:" + str(work / "custom bin;$literal")
    write(work / "custom bin;$literal/user-env-review-tool", "#!/bin/sh\nprintf '%s\\n' user-tail-command\n", True)
    env = {"PATH": inherited_path, "HOME": "/root", "SHELL": "/bin/bash", "LANG": "C.UTF-8",
           "NODE_OPTIONS": "--import=" + str(probe), "DSHA_REVIEW_OUTPUT": str(work),
           "DSHA_REVIEW_RUNTIME": str(runtime), "DSHA_REVIEW_PATH": inherited_path,
           "DSHA_OBSERVER_INSTALL": str(runtime),
           "NODE_PATH": str(out / "host-native/node_modules") + ":" + str(runtime / "node_modules"),
           "NARB_DISABLE_NATIVE_CACHE": "1", "DSHA_PLUGIN_UNLOCKED": "1",
           "npm_config_prefix": "/usr/local",
           "DSH_HOME": "/root/.dsh", "NPM_CONFIG_UPDATE_NOTIFIER": "false",
           "NPM_CONFIG_USERCONFIG": str(work / ".npmrc"), "NPM_CONFIG_GLOBALCONFIG": str(work / "global.npmrc"),
           "npm_config_store_dir": str(work / "store")}
    write(work / ".npmrc", "registry=https://registry.npmjs.org/\nauto-install-peers=false\npackage-import-method=copy\n")
    write(work / "global.npmrc", "")
    normal = shell_data["commands"]["normal"]
    old_shell = normal.split(" && ", 1)[1]
    results = {}
    cases = [] if args.web_only else [("negative", old_shell), ("positive", normal),
                                     ("quoted", shell_data["commands"]["quoted"])]
    for mode, shell in cases:
        r = command(work / (mode + ".log"), ["/bin/bash", "--noprofile", "--norc", "-c", shell],
                    env={**env, "DSHA_REVIEW_MODE": mode}, timeout=90)
        receipt = json.loads((work / (mode + ".json")).read_text())
        results[mode] = {"exitCode": r.returncode, **receipt}
        assert r.returncode == (127 if mode == "negative" else 0), (mode, r.returncode, r.stderr)
    if not args.web_only:
        quoted = results["quoted"]
        assert quoted["permission"] == shell_data["settings"]["quoted"][0]
        assert quoted["language"] == shell_data["settings"]["quoted"][1]
        assert quoted["profile"] == shell_data["settings"]["quoted"][2]
        assert quoted["argv"][1] == quoted["profile"]
        assert not list(Path("/root").glob("injected-*"))
    sentinel = Path("/root/.dsha-stopped")
    sentinel.touch()
    r = command(work / "stopped.log", ["/bin/bash", "--noprofile", "--norc", "-c", normal],
                env={**env, "DSHA_REVIEW_MODE": "stopped"})
    assert r.returncode == 0 and not (work / "stopped.json").exists()
    results["stopped"] = {"exitCode": r.returncode, "nodeWasNotLaunched": True}
    sentinel.unlink()
    # Real DSH Web boot, with the exact Java command and signed observer; no fixture preload.
    web_env = {**env}
    web_env.pop("NODE_OPTIONS")
    web_shell = shell_data["commands"]["web"]
    process = subprocess.Popen(["/bin/bash", "--noprofile", "--norc", "-c", web_shell],
        env=web_env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, start_new_session=True)
    selector = selectors.DefaultSelector()
    selector.register(process.stdout, selectors.EVENT_READ)
    browser = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    data, ready, url = b"", False, None
    deadline = time.monotonic() + args.web_timeout
    try:
        while time.monotonic() < deadline and process.poll() is None:
            for key, _ in selector.select(0.25):
                chunk = os.read(key.fd, 65536)
                data += chunk
            urls = re.findall(rb"http://127\.0\.0\.1:\d+[^\s\x1b]*", data)
            for candidate in reversed(urls):
                candidate = candidate.decode(errors="replace").rstrip("'\"),")
                try:
                    with browser.open(candidate, timeout=2) as response:
                        if response.status == 200:
                            url, ready = candidate, True
                            break
                except Exception:
                    pass
            if ready:
                break
    finally:
        if process.poll() is None:
            os.killpg(process.pid, signal.SIGTERM)
        try:
            tail, _ = process.communicate(timeout=10)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            tail, _ = process.communicate(timeout=5)
        data += tail
    write(work / "actual-web.log", redact(data.decode("utf-8", errors="replace")))
    results["actualWeb"] = {"http200": ready, "url": redact(url or ""),
                            "exitCodeAfterFixtureStop": process.returncode}
    evidence = {"schema": 1, "recordedAt": datetime.now(timezone.utc).isoformat(),
        "scope": "Linux x86_64 host; private mount namespace; no Android or user profile",
        "hostAdaptation": "Bundled DSH archive JS for separate web-only check" if args.web_only else
            "Windows managed-host JS plus exact-version Linux optional native addon via NODE_PATH; no source or runtime edits",
        "checkKind": "web-only" if args.web_only else "command-regressions-and-web",
        "work": str(work), "nodeVersion": subprocess.check_output([str(node), "--version"], text=True).strip(),
        "hostRuntime": str(runtime), "hostModuleSha256": host_hashes,
        "nodeSha256": sha(node), "nativeAddonMetadataSha256": sha(out / "host-native-native-metadata.json"),
        "pnpmArchiveSha256": sha(archive_path), "publishedCommandsSha256": sha(published_path),
        "sourceSha256": {str(p.relative_to(root)): sha(p) for p in [
            root / "app/src/main/java/com/deepseekharness/app/util/WebLaunchCommand.java",
            root / "app/src/main/java/com/deepseekharness/app/runtime/RuntimeTools.java",
            root / "app/src/main/java/com/deepseekharness/app/runtime/RootfsInstaller.java",
            root / "app/src/main/java/com/deepseekharness/app/runtime/RuntimeLauncher.java"]},
        "results": results}
    write(out / ("web-review.json" if args.web_only else "review.json"), json.dumps(evidence, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps({"work": str(work), "checkKind": evidence["checkKind"],
        "actualWeb": results["actualWeb"]}, ensure_ascii=False))
    assert ready, "REAL_WEB_NOT_READY: inspect actual-web.log; shell regressions retained separately"
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inside", action="store_true")
    parser.add_argument("--node", required=True, help="Linux Node 24 executable (Windows path outside WSL)")
    parser.add_argument("--distro", default="DSHA-build172")
    parser.add_argument("--linux-runtime", help="Optional byte-identical JS runtime copy on WSL's Linux filesystem")
    parser.add_argument("--web-timeout", type=float, default=180, help="Bounded host boot observation in seconds")
    parser.add_argument("--web-only", action="store_true", help="Separate Web observation; never replaces core regression evidence")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    sys.exit(inside(root, args) if args.inside else windows(root, args))
