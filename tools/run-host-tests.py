#!/usr/bin/env python3
"""Execute explicit current host tests; collect every failure and never run device/history implicitly."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "tools/host-tests.manifest.json"
STAGES = ("fast", "runtime", "package", "windows", "linux")
REQUIRES = {"node24", "python39", "powershell", "raw", "managed", "browser", "browser_react", "jdk17", "pnpm", "current_archive", "tar", "guide_fixture", "native_host", "generated_recovery", "mobile_upstream", "git_bash", "toolchains", "debug_classes", "linux_node", "linux_publish_packages", "semver_reference"}
TOKENS = {"apks", "fixture", "posixFixture", "modules", "managedModules", "managedStorageModule", "raw", "managed", "rawSemver", "rawConversationModule", "reactReference", "pnpmCli", "runtimeArchive", "recoveryAssets", "mobileUpstream", "guideFixture", "toolchainRoot", "sdk", "gradleCache", "gradleHome", "jdk", "flavor", "linuxPublisher", "linuxHost", "linuxNativeInputs", "wslDistro", "linuxTempdir"}


def load_manifest():
    data = json.loads(MANIFEST.read_text(encoding="utf-8"))
    if data.get("schema") != 1 or not isinstance(data.get("tests"), list):
        raise ValueError("TEST_MANIFEST_SCHEMA")
    paths = [row["path"] for row in data["tests"]]
    present = {p.relative_to(ROOT).as_posix() for p in (ROOT / "tools").glob("test-*") if p.suffix in (".py", ".mjs", ".cjs", ".ps1")}
    present.add("tools/overlay-bridge-test.mjs")
    if len(set(paths)) != len(paths) or set(paths) != present:
        raise ValueError("TEST_MANIFEST_COVERAGE: " + str(sorted(set(paths) ^ present)))
    for row in data["tests"]:
        if row.get("stage") not in STAGES + ("device", "history", "support"):
            raise ValueError("TEST_MANIFEST_STAGE")
        if not row.get("reason") or not isinstance(row.get("args"), list) or not all(isinstance(v, str) for v in row["args"]):
            raise ValueError("TEST_MANIFEST_DESCRIPTION")
        if not isinstance(row.get("requires", []), list) or not set(row.get("requires", [])) <= REQUIRES:
            raise ValueError("TEST_MANIFEST_REQUIREMENTS: " + row["path"])
        if len(row.get("requires", [])) != len(set(row.get("requires", []))):
            raise ValueError("TEST_MANIFEST_DUPLICATE_REQUIREMENT: " + row["path"])
        if row.get("executor") not in (None, "linux"):
            raise ValueError("TEST_MANIFEST_EXECUTOR: " + row["path"])
        if row.get("platform") not in (None, ["windows"], ["linux"]):
            raise ValueError("TEST_MANIFEST_PLATFORM: " + row["path"])
        env = row.get("environment", {})
        if not isinstance(env, dict) or any(not re.fullmatch(r"[A-Z][A-Z0-9_]*", key) or not isinstance(value, str) for key, value in env.items()):
            raise ValueError("TEST_MANIFEST_ENVIRONMENT")
        for value in row["args"] + list(env.values()):
            if value.startswith("{") and (not value.endswith("}") or value[1:-1] not in TOKENS):
                raise ValueError("TEST_MANIFEST_PLACEHOLDER: " + value)
        if type(row.get("timeoutSeconds")) is not int or not 1 <= row["timeoutSeconds"] <= 3600:
            raise ValueError("TEST_MANIFEST_TIMEOUT")
    return data


def executable(value):
    found = shutil.which(value)
    path = Path(found or value).absolute()
    if not path.is_file():
        raise ValueError("HOST_EXECUTABLE_MISSING: " + str(path))
    return str(path)


def required_file(path, description):
    path = Path(path).resolve()
    if not path.is_file():
        raise ValueError(description + ": " + str(path))
    return path


def required_directory(path, description):
    path = Path(path).resolve()
    if not path.is_dir():
        raise ValueError(description + ": " + str(path))
    return path


class Inputs:
    def __init__(self, args):
        self.args = args
        self.cache = {}
        self.original = dict(os.environ)
        self.node = shutil.which(args.node) or args.node
        self.python = sys.executable
        self.native = None
        self.session = None
        self.runtime_inputs = {}
        self.root = Path(args.toolchain_root).resolve() if args.toolchain_root else None
        profile = self.original.get("USERPROFILE") or self.original.get("HOME", "")
        bundled = Path(profile) / ".cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright"
        self.playwright = Path(args.playwright).resolve() if args.playwright else bundled if bundled.is_dir() else None
        default_browser = Path(self.original.get("LOCALAPPDATA", "")) / "ms-playwright" if os.name == "nt" else Path(profile) / ".cache/ms-playwright"
        self.browsers = Path(args.browsers_path).resolve() if args.browsers_path else default_browser
        self.jdk = (Path(args.jdk).resolve() if args.jdk else self.root / "jdk-17" if self.root
                    else Path(self.original["JAVA_HOME"]).resolve() if self.original.get("JAVA_HOME") else None)

    def runtime(self, kind, current_archive=False):
        from test_runtime_fixture import identity
        from runtime_fixture_session import FixtureSession
        selected = self.args.raw if kind == "raw" else self.args.managed
        directory, proof = identity(kind, selected=selected, require_current_archive=current_archive)
        if self.session is None:
            self.session = FixtureSession(self.args.output.resolve(), node_executable=self.node)
        self.session.register(directory, proof)
        key = (kind, str(directory))
        self.runtime_inputs[key] = self.runtime_inputs.get(key, False) or current_archive
        return directory

    def finalize(self):
        if self.session is None:
            return []
        from test_runtime_fixture import identity
        failures = []
        for (kind, directory), archive_required in self.runtime_inputs.items():
            try:
                identity(kind, selected=directory, require_current_archive=archive_required)
            except Exception as error:
                failures.append({"kind": kind, "directory": directory, "status": "FAILED", "error": str(error)})
        return failures + self.session.finalize()

    def values(self, fixture, row):
        req = set(row.get("requires", []))
        if row.get("platform")==["windows"] and os.name!="nt":raise ValueError("HOST_WINDOWS_PLATFORM_REQUIRED")
        if row.get("platform")==["linux"] and sys.platform!="linux":
            if not (row.get("executor")=="linux" and os.name=="nt" and self.args.linux_host):
                raise ValueError("HOST_LINUX_PLATFORM_REQUIRED")
        values = {"fixture": str(fixture), "posixFixture": "/" + fixture.as_posix().split(":", 1)[1].lstrip("/") if os.name == "nt" else str(fixture), "flavor": self.args.flavor,
                  "runtimeArchive": str(ROOT / "app/src/main/assets/dsh-runtime.bin"), "recoveryAssets": str(ROOT / "app/build/generated/recoveryAssets"),
                  "mobileUpstream": str(Path(self.args.mobile_upstream).resolve())}
        if "node24" in req:
            self.node=executable(self.node)
            if self.native is None:
                self.native = json.loads(subprocess.check_output([self.node, "-p", "JSON.stringify({major:+process.versions.node.split('.')[0],os:process.platform,cpu:process.arch})"], text=True, timeout=10))
            if self.native["major"] < 24:
                raise ValueError("HOST_NODE24_REQUIRED")
        if "python39" in req and sys.version_info < (3, 9):
            raise ValueError("HOST_PYTHON39_REQUIRED")
        if "raw" in req or "native_host" in req or "current_archive" in req:
            raw = self.runtime("raw", "current_archive" in req)
            values.update(raw=str(raw), modules=str(raw / "node_modules"), rawSemver=str(raw / "node_modules/semver"), rawConversationModule=str(raw / "node_modules/@deepseek-ai/dsh-client-ui-conversation/lib/client.js"))
        if "managed" in req:
            managed = self.runtime("managed", "current_archive" in req)
            values.update(managed=str(managed), managedModules=str(managed / "node_modules"), managedStorageModule=str(managed / "node_modules/@deepseek-ai/dsh-storage-json/lib/index.js"))
        if "native_host" in req:
            proof = json.loads((Path(values["raw"]) / "dsha-test-runtime.json").read_text(encoding="utf-8"))
            if self.native is None:
                self.native = json.loads(subprocess.check_output([self.node, "-p", "JSON.stringify({major:+process.versions.node.split('.')[0],os:process.platform,cpu:process.arch})"], text=True, timeout=10))
            if proof.get("platform") != {"os": self.native["os"], "cpu": self.native["cpu"]}:
                raise ValueError("HOST_NATIVE_FIXTURE_PLATFORM_MISMATCH")
        if "{rawSemver}" in row["args"]:
            import importlib.util
            spec=importlib.util.spec_from_file_location("semver_reference",ROOT/"tools/prepare-semver-reference.py")
            module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
            values["rawSemver"]=str(module.prepare(check=True))
        if "pnpm" in req:
            entry = Path(self.args.pnpm_cli).resolve() if self.args.pnpm_cli else None
            if entry is None:
                raise ValueError("HOST_PNPM_CLI_REQUIRED: pass --pnpm-cli for the verified bundled pnpm")
            required_file(entry, "HOST_PNPM_CLI_MISSING")
            lock = json.loads((ROOT / "tools/runtime-tools.lock.json").read_text(encoding="utf-8"))["pnpm"]["version"]
            actual = subprocess.check_output([self.node, str(entry), "--version"], text=True, timeout=20).strip()
            if actual != lock:
                raise ValueError("HOST_PNPM_VERSION_MISMATCH")
            values["pnpmCli"] = str(entry)
        if "browser" in req:
            if self.playwright is None:
                raise ValueError("HOST_PLAYWRIGHT_REQUIRED: pass --playwright")
            required_file(self.playwright / "package.json", "HOST_PLAYWRIGHT_MISSING")
            required_directory(self.browsers, "HOST_BROWSER_CACHE_REQUIRED")
        if "browser_react" in req:
            import importlib.util
            spec=importlib.util.spec_from_file_location("browser_react_reference",ROOT/"tools/prepare-browser-react-reference.py")
            module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
            values["reactReference"]=str(module.prepare(check=True))
        if "jdk17" in req or "toolchains" in req or "debug_classes" in req:
            if self.jdk is None:
                raise ValueError("HOST_JDK_REQUIRED: pass --jdk or --toolchain-root")
            for name in ("java", "javac"):
                required_file(self.jdk / "bin" / (name + ".exe" if os.name == "nt" else name), "HOST_JDK_MISSING")
            if "jdk17" not in self.cache:
                settings = subprocess.check_output(
                    [str(self.jdk / "bin" / ("java.exe" if os.name == "nt" else "java")),
                     "-XshowSettings:properties", "-version"], stderr=subprocess.STDOUT,
                    text=True, encoding="utf-8", errors="replace", timeout=10)
                version = re.search(r"java\.specification\.version\s*=\s*([0-9]+)", settings)
                if not version or version.group(1) != "17":
                    raise ValueError("HOST_JDK17_REQUIRED")
                self.cache["jdk17"] = str(self.jdk)
            values["jdk"] = str(self.jdk)
        if "toolchains" in req or "debug_classes" in req:
            if self.root is None:
                raise ValueError("HOST_TOOLCHAIN_ROOT_REQUIRED")
            values.update(toolchainRoot=str(self.root), sdk=str(self.root / "android-sdk"), gradleHome=str(self.root / "gradle-user-home"), gradleCache=str(self.root / "gradle-user-home/caches"))
            required_directory(values["sdk"], "HOST_SDK_MISSING")
            required_directory(values["gradleCache"], "HOST_GRADLE_CACHE_MISSING")
        if "debug_classes" in req:
            cap = self.args.flavor[0].upper() + self.args.flavor[1:]
            required_directory(ROOT / f"app/build/intermediates/javac/{self.args.flavor}Debug/compile{cap}DebugJavaWithJavac/classes", "HOST_CURRENT_DEBUG_CLASSES_REQUIRED")
        if "generated_recovery" in req:
            required_file(Path(values["recoveryAssets"]) / "recovery-runtime.json", "HOST_GENERATED_RECOVERY_MISSING")
        if "mobile_upstream" in req:
            required_directory(values["mobileUpstream"], "HOST_FIXED_MOBILE_UPSTREAM_REQUIRED")
        if "git_bash" in req:
            required_file("C:/Program Files/Git/bin/bash.exe", "HOST_GIT_BASH_REQUIRED")
        if "tar" in req and not shutil.which("tar"):
            raise ValueError("HOST_TAR_REQUIRED")
        if "linux_node" in req or "linux_publish_packages" in req:
            if not self.args.linux_host:raise ValueError("HOST_LINUX_INPUT_REQUIRED: pass --linux-host")
            host=required_directory(self.args.linux_host,"HOST_LINUX_INPUT_MISSING")
            required_file(host/"node","HOST_LINUX_NODE_MISSING")
            values.update(linuxHost=str(host), linuxNativeInputs=str(Path(self.args.linux_native_inputs).resolve()),
                          wslDistro=self.args.wsl_distro, linuxTempdir=self.args.linux_tempdir or "/dev/shm")
            values["linuxPublisher"]=str(required_file(host/"node_modules/dsha-runtime-fs/index.js","HOST_LINUX_PUBLISHER_MISSING"))
            provenance=json.loads(required_file(host/"native-provenance.json","HOST_LINUX_NATIVE_PROOF_MISSING").read_text(encoding="utf-8"))
            expected={"@deepseek-ai/node-addon-system","@deepseek-ai/node-addon-system-linux-x64","koffi","@koromix/koffi-linux-x64"}
            if not isinstance(provenance,list) or {package.get("name") for package in provenance}!=expected:raise ValueError("HOST_LINUX_NATIVE_PROOF_SCOPE")
            lock=json.loads((ROOT/"tools/dsh-runtime/package-lock.json").read_text(encoding="utf-8"))["packages"]
            for package in provenance:
                pinned=lock.get("node_modules/"+package["name"])
                if not pinned or package.get("integrity")!=pinned.get("integrity") or package.get("version")!=pinned.get("version"):raise ValueError("HOST_LINUX_NATIVE_LOCK_MISMATCH")
        if "guide_fixture" in req:
            guide = fixture / "guide"
            shutil.copytree(ROOT / "app/src/main/assets/builtin-plugins/dsh-device-shell-guide", guide)
            # The private copy uses the verified raw dependencies; it never mutates raw's member set.
            subprocess.run([self.node, "-e", "require('node:fs').symlinkSync(process.argv[1],process.argv[2],process.platform==='win32'?'junction':'dir')", str(Path(values["raw"]) / "node_modules"), str(fixture / "node_modules")], check=True, timeout=10, stdout=subprocess.DEVNULL)
            values["guideFixture"] = str(guide / "lib/index.js")
        return values

    def environment(self, fixture, row, values):
        env = {key: value for key, value in self.original.items() if not re.search(r"API_KEY|TOKEN|SECRET|PASSWORD|COOKIE|AUTHORIZATION", key, re.I)
               and not key.startswith(("DSHA_", "DSH_", "NPM_CONFIG_", "npm_config_")) and key not in ("PYTHONOPTIMIZE", "PYTHONPATH", "PYTHONSTARTUP", "NODE_OPTIONS", "NODE_PATH", "PNPM_HOME")}
        home = fixture / "home"; home.mkdir()
        npmrc = fixture / "empty.npmrc"; npmrc.write_text("", encoding="utf-8")
        env.update(HOME=str(home), USERPROFILE=str(home), PYTHONUTF8="1", PYTHONIOENCODING="utf-8", DSHA_PYTHON=self.python, DSHA_TEST_NODE=self.node, NARB_DISABLE_NATIVE_CACHE="1", NPM_CONFIG_USERCONFIG=str(npmrc))
        prefixes = [str(Path(self.node).parent), str(Path(self.python).parent)]
        if self.jdk: prefixes.append(str(self.jdk / "bin"))
        env["PATH"] = os.pathsep.join(prefixes + [env.get("PATH", "")])
        if "raw" in values: env["DSHA_TEST_RUNTIME"] = values["raw"]
        if "managed" in values: env["DSHA_TEST_MANAGED_RUNTIME"] = values["managed"]
        if row.get("executor")=="linux":
            env["LD_LIBRARY_PATH"]=str(Path(self.args.linux_host).resolve())
        if "browser" in row.get("requires", []):
            env.update(DSHA_PLAYWRIGHT=str(self.playwright), PLAYWRIGHT_BROWSERS_PATH=str(self.browsers))
            if self.args.browser: env.update(DSHA_BROWSER=self.args.browser, DSHA_CHROME=self.args.browser)
        for key, value in row.get("environment", {}).items(): env[key] = resolve(value, values)
        if self.session is not None:
            env.update(self.session.environment())
        return env


def resolve(value, values):
    if value.startswith("{") and value.endswith("}"):
        key = value[1:-1]
        if key not in values: raise ValueError("HOST_INPUT_REQUIRED: " + key)
        return values[key]
    return value


def run_one(inputs, row, output):
    started = time.monotonic(); log = output / (Path(row["path"]).name + ".log")
    item = {"path": row["path"], "status": "FAILED", "log": str(log.relative_to(ROOT)) if log.is_relative_to(ROOT) else str(log)}
    try:
        with tempfile.TemporaryDirectory(prefix="host-test-", dir=output) as temporary, log.open("wb") as stream:
            fixture = Path(temporary).resolve(); values = inputs.values(fixture, row)
            if inputs.session is not None:
                item["fixtureBindings"] = inputs.session.bindings()
            args = []
            for value in row["args"]:
                if value == "{apks}":
                    if not inputs.args.apks: raise ValueError("PACKAGE_TEST_NEEDS_APKS")
                    args.extend(str(Path(file).resolve()) for file in inputs.args.apks)
                else: args.append(resolve(value, values))
            path = ROOT / row["path"]
            if row.get("executor") == "linux":
                if not inputs.args.linux_host: raise ValueError("HOST_LINUX_INPUT_REQUIRED: pass --linux-host")
                host = Path(inputs.args.linux_host).resolve(); required_file(host / "node", "HOST_LINUX_NODE_MISSING")
                publisher = host / "node_modules/dsha-runtime-fs/index.js"; required_file(publisher, "HOST_LINUX_PUBLISHER_MISSING")
                if hashlib.sha256(publisher.read_bytes()).digest() != hashlib.sha256((ROOT / "app/src/main/assets/runtime-fs/index.js").read_bytes()).digest(): raise ValueError("HOST_LINUX_PUBLISHER_STALE")
                def lx(p):
                    p=Path(p).resolve();return "/mnt/host/"+p.drive[0].lower()+p.as_posix().split(":",1)[1] if os.name=="nt" else str(p)
                linux_environment = ["LD_LIBRARY_PATH="+lx(host)]
                linux_tmp = getattr(inputs.args, "linux_tempdir", None)
                if linux_tmp:
                    from pathlib import PurePosixPath
                    if not PurePosixPath(linux_tmp).is_absolute() or ".." in PurePosixPath(linux_tmp).parts:
                        raise ValueError("HOST_LINUX_TMPDIR_INVALID")
                    linux_environment.append("TMPDIR="+linux_tmp)
                command = ["wsl.exe", "-d", inputs.args.wsl_distro, "--", "env", *linux_environment, lx(host/"node"), lx(path), lx(publisher)] if os.name=="nt" else ["env",*linux_environment,str(host/"node"),str(path),str(publisher)]
                env = inputs.environment(fixture, row, {})
            else:
                command = [inputs.python,"-B"] if path.suffix==".py" else [inputs.node] if path.suffix in (".mjs",".cjs") else [executable(inputs.args.powershell),"-NoProfile","-File"]
                command += [str(path)] + args;env=inputs.environment(fixture,row,values)
            item["command"] = command
            result = subprocess.run(command, cwd=ROOT, env=env, stdout=stream, stderr=subprocess.STDOUT, timeout=row["timeoutSeconds"], check=False)
            item.update(exitCode=result.returncode,status="PASS" if result.returncode==0 else "FAILED")
    except Exception as error:
        item.update(error=str(error),errorType=type(error).__name__,status="TIMEOUT" if isinstance(error,subprocess.TimeoutExpired) else "FAILED")
        with log.open("ab") as stream: stream.write(("\nRUNNER_ERROR: "+str(error)+"\n").encode("utf-8"))
    item["seconds"] = round(time.monotonic()-started,3)
    if log.is_file(): item["logSha256"] = hashlib.sha256(log.read_bytes()).hexdigest()
    return item


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage",choices=STAGES+("all","device"),default="fast")
    parser.add_argument("--check-manifest",action="store_true");parser.add_argument("--list",action="store_true");parser.add_argument("--only",action="append",default=[])
    parser.add_argument("--node",default=shutil.which("node") or "node");parser.add_argument("--powershell",default=shutil.which("pwsh") or "pwsh")
    parser.add_argument("--raw");parser.add_argument("--managed");parser.add_argument("--pnpm-cli");parser.add_argument("--toolchain-root",default=os.environ.get("DSHA_TOOLCHAINS"));parser.add_argument("--jdk")
    parser.add_argument("--playwright");parser.add_argument("--browsers-path");parser.add_argument("--browser");parser.add_argument("--mobile-upstream",default=str(ROOT/"app/build/mobile-upstream-a094/lib"))
    parser.add_argument("--linux-host");parser.add_argument("--wsl-distro",default="docker-desktop");parser.add_argument("--linux-tempdir",help="Explicit writable Linux fixture directory; no WSL repair or remount");parser.add_argument("--flavor",choices=("standard","low"),default="standard")
    parser.add_argument("--linux-native-inputs",default=str(ROOT/"app/build/audit-build156/linux-native"),help="Prepared NDK/Ubuntu sysroot receipt for the explicit WSL native handshake fixture")
    parser.add_argument("--apks",nargs=2);parser.add_argument("--output",type=Path,default=ROOT/"app/build/host-tests")
    args=parser.parse_args()
    if sys.flags.optimize:parser.error("禁止 -O/PYTHONOPTIMIZE，测试断言必须执行")
    data=load_manifest()
    if args.check_manifest or args.list:
        print(json.dumps(data if args.list else {"status":"PASS_MANIFEST","tests":len(data["tests"])},ensure_ascii=False,indent=2));return 0
    if args.stage=="device":parser.error("设备动作不属于宿主回归；本轮禁止操作手机")
    stages=set(STAGES) if args.stage=="all" else {args.stage}
    if args.stage=="all" and os.name!="nt":stages.discard("windows")
    if args.stage=="all" and not args.apks:stages.discard("package")
    selected=[row for row in data["tests"] if row["stage"] in stages and (not args.only or Path(row["path"]).name in args.only)]
    if not selected:parser.error("没有匹配测试")
    known={Path(row["path"]).name for row in selected}
    if set(args.only)-known:parser.error("未匹配已选当前阶段测试: "+str(sorted(set(args.only)-known)))
    output=args.output.resolve();output.mkdir(parents=True,exist_ok=True)
    report={"schema":2,"requestedStage":args.stage,"selectedStages":sorted(stages),"status":"RUNNING","tests":[],"excluded":[{"path":row["path"],"stage":row["stage"],"reason":row["reason"]} for row in data["tests"] if row not in selected]}
    inputs=Inputs(args)
    try:
        for row in selected:
            item=run_one(inputs,row,output);report["tests"].append(item);print(json.dumps(item,ensure_ascii=False),flush=True)
            (output/"manifest.json").write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n",encoding="utf-8",newline="\n")
    finally:
        report["runtimeFixtureVerification"] = inputs.finalize()
        report["failures"]=[row["path"] for row in report["tests"] if row["status"]!="PASS"]
        if any(row["status"] != "PASS_FINAL_COMPLETE_BYTE_RECHECK" for row in report["runtimeFixtureVerification"]):
            report["failures"].append("runtime-fixture-final-verification")
            for row in report["tests"]:
                if row["status"] == "PASS" and row.get("fixtureBindings"):
                    row.update(status="FAILED_FIXTURE_INVALIDATED", childExitStatus="PASS")
        report["status"]="FAILED" if report["failures"] else "PASS_FOR_SELECTED_STAGES"
        (output/"manifest.json").write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n",encoding="utf-8",newline="\n")
    return 0 if report["status"]=="PASS_FOR_SELECTED_STAGES" else 1


if __name__=="__main__":sys.exit(main())
