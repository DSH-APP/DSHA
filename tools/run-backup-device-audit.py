#!/usr/bin/env python3
"""在独立非调试验收安装执行；只允许固定测试包，不覆盖用户 DSHA。"""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.dsh.client.rc21audit"
CERTIFICATE = "e7e3a31a75946f2669194c972b3dd0c9aea3fc7c50a8b885d2dee710b22a53f5"

def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()

def main():
    global PACKAGE
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", choices=["com.dsh.client.rc21audit","com.dsh.client.stabilityaudit"], default=PACKAGE)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--sdk", type=Path, default=Path(os.environ.get("ANDROID_HOME", "F:/DSHA/_toolchains/android-sdk")))
    parser.add_argument("--java", type=Path, default=Path(os.environ.get("JAVA_HOME", "F:/DSHA/_toolchains/jdk-17")) / "bin/java.exe")
    parser.add_argument("--mode", choices=["core", "runtime", "workflow", "ui", "style", "reset", "reexport", "io", "issue67", "network", "credential", "plugins", "plugin_workflow", "retained", "bridge", "plugin_recovery", "attachments"], default="core")
    parser.add_argument("--language", choices=["zh", "en"], default="zh")
    parser.add_argument("--proroot", choices=["true", "false"], default="false")
    args = parser.parse_args()
    if os.environ.get('DSHA_ENABLE_LEGACY_AUDIT') != '1':
        parser.error('用户已停用审计 APK 流程；请用同签名正式 Release 覆盖安装，只做非破坏性验收。')
    PACKAGE=args.package
    apk = args.apk.resolve(strict=True)
    report_dir = ROOT / "app/build/backup-device-validation" / str(uuid.uuid4())
    report_dir.mkdir(parents=True)
    tools = args.sdk / "build-tools/36.0.0"
    adb = [str(args.sdk / "platform-tools/adb.exe"), "-s", args.serial]
    def output(command):
        return subprocess.check_output([str(v) for v in command], encoding="utf-8", errors="replace", stderr=subprocess.STDOUT)
    report = {"status": "INCOMPLETE", "mode": args.mode, "package": PACKAGE, "proroot": args.proroot, "apk": str(apk), "sha256": digest(apk), "createdAt": datetime.datetime.now(datetime.timezone.utc).isoformat(), "userInstallationModified": False}
    try:
        badging = output([tools / "aapt.exe", "dump", "badging", apk])
        match = re.search(r"^package: name='([^']+)'", badging, re.MULTILINE)
        if not match or match[1] != PACKAGE or "application-debuggable" in badging:
            raise RuntimeError("Only the isolated, non-debuggable deviceAudit APK is allowed")
        certificate = output([args.java, "-jar", tools / "lib/apksigner.jar", "verify", "--print-certs", apk])
        if CERTIFICATE not in certificate.lower():
            raise RuntimeError("Historical signing certificate mismatch")
        report["certificateSha256"] = CERTIFICATE
        report["device"] = output(adb + ["shell", "getprop", "ro.product.model"]).strip()
        report["api"] = output(adb + ["shell", "getprop", "ro.build.version.sdk"]).strip()
        report["pageSize"] = output(adb + ["shell", "getconf", "PAGESIZE"]).strip()
        report["install"] = output(adb + ["install", "-r", str(apk)]).strip()
        if "Success" not in report["install"]:
            raise RuntimeError(report["install"])
        runner = {"core": "backup.BackupDeviceAudit", "runtime": "core.RuntimeDeviceAudit", "workflow": "backup.BackupWorkflowDeviceAudit", "ui": "ui.NativeDataUiAudit", "style": "ui.LayoutAuditInstrumentation", "reset": "core.ConfigurationResetDeviceAudit", "reexport": "backup.ReexportDeviceAudit", "io": "backup.DocumentIoDeviceAudit", "issue67": "core.Issue67DeviceAudit", "network": "NetworkDeviceAudit", "credential": "data.CredentialDeviceAudit", "plugins": "PluginScriptsDeviceAudit", "plugin_workflow": "backup.PluginWorkflowDeviceAudit", "retained": "backup.RetainedWorkflowDeviceAudit", "bridge": "LocalBridgeDeviceAudit", "plugin_recovery": "backup.PluginRecoveryDeviceAudit", "attachments": "core.AttachmentDeviceAudit"}[args.mode]
        command = adb + ["shell", "am", "instrument", "-w", "-e", "mode", args.mode, "-e", "language", args.language, "-e", "proroot", args.proroot, PACKAGE + "/com.deepseekharness.app." + runner]
        with (report_dir / "instrumentation.log").open("w", encoding="utf-8") as log:
            process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, encoding="utf-8", errors="replace")
            lines = []
            for line in process.stdout:
                log.write(line); log.flush(); lines.append(line)
                if line.startswith("INSTRUMENTATION_STATUS: "):
                    print(line.rstrip(), flush=True)
            report["adbExit"] = process.wait()
        text = "".join(lines)
        if args.mode not in ("runtime", "style"):
            record = re.search(r"^INSTRUMENTATION_RESULT: report=(.*)$", text, re.MULTILINE)
            if not record:
                raise RuntimeError("No machine-readable device result; inspect instrumentation.log")
            report["deviceResult"] = json.loads(record[1])
            report["status"] = report["deviceResult"]["status"]
            screenshots = report["deviceResult"].get("screenshots")
            if screenshots:
                if not re.fullmatch(r"/storage/emulated/0/Android/data/" + re.escape(PACKAGE) + r"/files/device-ui-[a-f0-9-]{36}", screenshots):
                    raise RuntimeError("Screenshot directory is outside the isolated test application")
                report["screenshotsPulled"] = output(adb + ["pull", screenshots, str(report_dir / "screenshots")]).strip()
        else:
            report["status"] = "PASS" if "INSTRUMENTATION_RESULT: result=PASS" in text and "INSTRUMENTATION_RESULT: failure=" not in text else "FAIL"
            if args.mode == "style":
                record = re.search(r"^INSTRUMENTATION_RESULT: artifactDirectory=(.*)$", text, re.MULTILINE)
                if record:
                    path = record[1].strip()
                    if not re.fullmatch(r"/storage/emulated/0/Android/data/" + re.escape(PACKAGE) + r"/files/device-style-[a-f0-9-]{36}", path):
                        raise RuntimeError("Style artifact directory is outside the isolated installation")
                    report["artifactsPulled"] = output(adb + ["pull", path, str(report_dir / "style")]).strip()
                report["language"] = args.language
        if report["adbExit"] != 0:
            report["status"] = "FAIL"
    except Exception as error:
        report["error"] = str(error)
        if isinstance(error, subprocess.CalledProcessError):
            report["commandOutput"] = error.output
        report["status"] = "FAIL"
    (report_dir / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"status": report["status"], "report": str(report_dir / "result.json")}, ensure_ascii=False), flush=True)
    return 0 if report["status"] == "PASS" else 1

if __name__ == "__main__":
    raise SystemExit(main())
