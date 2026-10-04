"""Bounded current-document assertions and immutable historical-source checks."""
from pathlib import Path
import hashlib
import json
import re

ROOT = Path(__file__).resolve().parents[3]
checks = []

def require(condition, name):
    if not condition:
        raise RuntimeError(name)
    checks.append(name)

def text(relative):
    return (ROOT / relative).read_text(encoding="utf-8-sig")

def digest(relative):
    return hashlib.sha256((ROOT / relative).read_bytes()).hexdigest()

dependency = text("app/src/main/assets/plugin-dependencies.py")
require("dangerously-allow-all-builds" in dependency and "ignore-scripts=false" in dependency, "current dependency policy allows lifecycle scripts")
for name in ("docs/plugins.md", "docs/security-model.md", "docs/security-model.en.md", "BUILD.md"):
    content = text(name)
    require("不执行包的安装脚本" not in content and "不会自动执行 prepare / build / install" not in content, name + ": no obsolete no-install-script claim")
require("0.2.0-rc.2" in text("docs/plugins.md") and "plugin-transactions" in text("docs/plugins.md"), "plugin guide names current runtime and transaction entry")
require("Termux 通道" not in text("agent-skills/README.md") and "Root、Shizuku 或 ADB" in text("agent-skills/README.md"), "canonical skill README uses actual channels")
zh = re.findall(r"^## (.+)$", text("docs/security-model.md"), re.M)
en = re.findall(r"^## (.+)$", text("docs/security-model.en.md"), re.M)
expected_zh = ["资产与主体", "信任边界", "终端与插件", "设备与浏览器能力", "凭据、网络与日志", "备份与恢复", "应急与进程事务", "交付与验证限度"]
expected_en = ["Assets and actors", "Trust boundaries", "Terminals and plugins", "Device and browser capabilities", "Credentials, network and logs", "Backups and restore", "Recovery and process transactions", "Delivery and evidence limits"]
require(zh == expected_zh and en == expected_en, "Chinese/English security sections have the same mapped topology")
require("ADR 0002" in text("docs/security-model.md") and "ADR 0002" in text("docs/security-model.en.md"), "both security models link the current threat model")
require("\n|" in text("docs/adr/0002-current-threat-model.md"), "threat model documents individual controls and limits")
for name in ("app/src/main/java/com/deepseekharness/app/HttpShellService.java", "app/src/main/java/com/deepseekharness/app/HarnessService.java", "app/src/main/java/com/deepseekharness/app/OverlayController.java", "app/src/main/java/com/deepseekharness/app/runtime/ProotBootstrap.java"):
    require(not re.search(r"\*/\s*/\*\*", text(name)), name + ": no adjacent Javadoc blocks in cited class (not global doclint)")
accessibility = text("app/src/main/java/com/deepseekharness/app/DshaAccessibilityService.java")
require("清单不限定设置应用" in accessibility and "不是完全不落盘" in accessibility, "accessibility comment states actual event and screenshot boundary")
require("android:packageNames=" not in text("app/src/main/res/xml/accessibility_service_config.xml"), "accessibility XML does not restrict package delivery")
require("自动提交启用" in text("app/src/main/java/com/deepseekharness/app/ui/PluginNavigation.java"), "navigation comment matches current automatic plugin path")
require("任意自定义头" in text("app/src/main/java/com/deepseekharness/app/util/SensitiveData.java"), "SensitiveData comment discloses unknown-secret limitation")
require("openRawResource(R.raw.changelog)" in text("app/src/main/java/com/deepseekharness/app/ui/UpdateActivity.java"), "UpdateActivity reads generated raw changelog")
for name in ("app/src/main/java/com/deepseekharness/app/runtime/ProotBootstrap.java", "app/src/main/java/com/deepseekharness/app/util/BuiltinPlugins.java", "app/src/main/assets/register-builtin-plugins.py"):
    require(not re.search(r"四个|1\.2-alpha", text(name)), name + ": no obsolete fixed plugin count/version comment")

for manifest in ("docs/audits/build154/history-document-sources.json", "docs/audits/build156/source-docs-retained.json", "docs/audits/build156/privacy-document-sources.json"):
    document = json.loads(text(manifest))
    records = document if isinstance(document, list) else document["items"]
    for record in records:
        require(digest(record["retainedSource"]) == record["sha256"] and (ROOT / record["retainedSource"]).stat().st_size == record["bytes"], "original bytes preserved: " + record["retainedSource"])
    checks.append(manifest + ": exact retained-source manifest checked")

for name in ("docs/1.2-upgrade-report.md", "docs/android-low.md", "docs/android-standard.md", "docs/ROADMAP.md", "docs/upgrade-dsh-0.1.5-alpha.2.md", "docs/website-rc2-20260928.md", "docs/community-standard-feedback.md"):
    require("历史" in text(name)[:800], name + ": historical scope disclosed")
for name, before in (("architecture.md", "architecture-before-fixes.md"), ("findings.md", "findings-before-fixes.md")):
    folder = "docs/audits/2026-09-27-rc2-full-architecture/"
    require(digest(folder + name) != digest(folder + before) and "历史审计入口" in text(folder + name), name + ": no false current clone")

invalid = json.loads(text("docs/audits/build154/historical-invalid-digests.json"))["items"]
for record in invalid:
    value = record["invalidRecordedValue"]
    content = text(record["path"])
    require(len(value) == 63 and value in content and "无效" in content, record["path"] + ": invalid historical digest preserved and disclosed")

jni_doc = text("tools/termux-jni/README.md").split("## 历史构建记录")[0]
require(digest("app/src/main/jniLibs/arm64-v8a/libtermux.so") in jni_doc, "current JNI README digest matches actual library")
require(all(value in jni_doc for value in ("API 23", "max-page-size=16384", "common-page-size=4096")), "current JNI README parameters match current entry")

build = text("BUILD.md")
require("assembleStandardDebug" not in build and "assembleLowDebug" not in build, "current BUILD guide does not generate extra debug APKs")
require("原生审阅、依赖冻结" not in build, "current BUILD gate does not revive old plugin policy")
for name in ("docs/接手指南.md", "BUILD.md", "docs/plugins.md"):
    references = set(re.findall(r"tools/[\w./-]+", text(name)))
    for reference in references:
        require((ROOT / reference.rstrip(".")).exists(), name + ": current tool path exists: " + reference)

public = ["docs/1.2-upgrade-report.md", "docs/build131-device-followup.md", "docs/adb-flow-audit.md", "docs/stability-acceptance.md", "docs/audits/2026-09-27-round2-architecture/final-assessment.md", "docs/evidence/rc1.4-functional/release-build.log"]
for name in public:
    content = text(name)
    require(not re.search(r"fmuoeujvonizjj4d|192\.168\.2\.11|C:[/\\]Users[/\\]18768|F:[/\\]DSHA", content), name + ": cited developer identifiers removed from public copy")

nginx = text("website/deploy/nginx-dsha-https.conf")
download = nginx[nginx.index("location ~* \\.(?:apk|tar\\.gz)$"):]
download = download[:download.index("try_files $uri =404;")]
for header in ("Strict-Transport-Security", "X-Content-Type-Options", "Referrer-Policy", "Content-Security-Policy"):
    require("add_header " + header in download, "download location retains " + header)
require("add_header Content-Disposition attachment" in download, "download location supplies attachment header")
require(not (ROOT / "website/data/current-release-notes.txt").exists(), "obsolete current build147 release notes are retired")
require((ROOT / "website/data/history/build147-release-notes.txt").exists(), "historical build147 release notes remain available")

report = {"schemaVersion": 1, "scope": "current documents and cited historical/public copies only; not global doclint, legal proof, device or deployment", "result": "PASS", "checkCount": len(checks), "checks": checks}
output = ROOT / "docs/audits/build156/docs-checks.json"
output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
print(f"PASS {len(checks)} bounded current-document and historical-byte checks")
