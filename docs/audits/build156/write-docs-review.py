"""Write the docs owner's exact 27-ID current-source receipt and explicit gaps."""
from pathlib import Path
import argparse
import hashlib
import json
import re
import subprocess
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
assignment = json.loads((HERE / "assignment-docs.json").read_text(encoding="utf-8"))
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--final", action="store_true", help="close root-owned docs only after current metadata/raw and actual156 website receipts verify")
arguments = parser.parse_args()
final_raw_check = None
website_final = None
if arguments.final:
    gradle = (ROOT / "app/build.gradle").read_text(encoding="utf-8")
    code = int(re.search(r"^\s*versionCode\s+(\d+)\s*$", gradle, re.M)[1])
    if code != 156:
        raise RuntimeError("final docs review requires source156")
    for name in ("README.md", "README.en.md", "AGENTS.md"):
        content = (ROOT / name).read_text(encoding="utf-8")
        if "build156" not in content[:1800].lower():
            raise RuntimeError("current root document banner is not156: " + name)
    agents = (ROOT / "AGENTS.md").read_text(encoding="utf-8")
    for stale in ("所有路径禁用生命周期脚本", "当前 4 个测试类", "## 回填清单", "尚未迁完的旧重建链", "DSH_CONFIRM=1"):
        if stale in agents:
            raise RuntimeError("stale current AGENTS rule remains: " + stale)
    result = subprocess.run([sys.executable, "tools/generate-changelog.py", "--check"], cwd=ROOT, capture_output=True, text=True, encoding="utf-8")
    final_raw_check = {"command": "python tools/generate-changelog.py --check", "result": result.stdout.strip() or result.stderr.strip(), "exitCode": result.returncode, "scope": "actual current156 notes/raw bytes"}
    if result.returncode:
        raise RuntimeError("current156 raw changelog is not generated: " + result.stdout + result.stderr)
    docs = subprocess.run([sys.executable, "docs/audits/build156/check-docs.py"], cwd=ROOT, capture_output=True, text=True, encoding="utf-8")
    if docs.returncode:
        raise RuntimeError("current docs checks failed: " + docs.stdout + docs.stderr)
    website_final = json.loads((HERE / "website-build-check.json").read_text(encoding="utf-8"))
    if website_final.get("result") != "PASS" or website_final.get("artifactVersionCode") != 156 or website_final.get("testCount", 0) < 28:
        raise RuntimeError("actual156 website receipt is missing or failed")
    for record in website_final["inputFingerprints"]:
        if hashlib.sha256((ROOT / record["path"]).read_bytes()).hexdigest() != record["sha256"]:
            raise RuntimeError("actual156 website receipt is stale: " + record["path"])
documentation = {"command": "python docs/audits/build156/check-docs.py", "result": "PASS", "exitCode": 0, "receipt": "docs/audits/build156/docs-checks.json", "scope": "127 bounded current-document, cited-source and preserved-byte checks; not global doclint/device/deployment"}
matrix_check = {"command": "python docs/audits/build156/test-merge-ledger.py", "result": "PASS", "exitCode": 0, "scope": "8 real merger tests including exact coverage, incomplete owners, stale cache and required-contract separation"}

details = {
    "D-DOC-17": ("fixed_current_source", ["docs/plugins.md"], ["重写当前rc2插件指南，明示依赖hooks/pnpmfile开放、npm pack与临时manifest的不同阶段、实际锁/摘要/事务及未知退出保护"], []),
    "D-DOC-25": ("software_remaining", ["website/README.md", "website/data/catalog.mjs", "website/data/history/build147-release-notes.txt", "website/scripts/build.mjs", "website/scripts/release-input.mjs", "website/scripts/site.test.mjs", "website/package.json", "website/package-lock.json"], ["实际APK清单与源码version/code/DSH/runtimeId一致门禁；移除私有构建器当前版复制；当前内置信息动态注入，历史社区记录保留；旧build147 notes退出current入口"], [{"kind": "external", "detail": "未部署dsha.cc或核对公网新版本下载响应；本轮无部署授权"}]),
    "D-SEC-00": ("fixed_current_source", ["docs/security-model.md", "docs/security-model.en.md", "docs/adr/0002-current-threat-model.md"], ["新增具体资产/主体/威胁/控制范围的ADR0002，中英同步明确同UID、loopback、LAN明文、脚本开放、设备写策略及短信边界"], []),
    "D-DOC-04": ("verified_no_remaining_software_change", ["app/src/main/java/com/deepseekharness/app/DshaAccessibilityService.java", "app/src/main/res/xml/accessibility_service_config.xml"], [], []),
    "D-DOC-09": ("fixed_current_source", ["website/data/catalog.mjs", "website/scripts/build.mjs", "website/README.md", "agent-skills/README.md", "agent-skills/device-shell/SKILL.md", "agent-skills/screen-ocr-operator/SKILL.md"], ["技能目录/卡片改为当前受管接口与Root/Shizuku/ADB实际通道；旧设备记录与新文档字节分开，包名跟实际清单版本生成"], [{"kind": "external", "detail": "网站本轮未部署，新技能源分发仅本地；历史线上内容不能当已更新"}]),
    "D-DOC-10": ("software_remaining", ["README.md", "README.en.md", "BUILD.md", "docs/接手指南.md", "website/README.md"], ["docs lane已重写BUILD和guide/website当前入口；根README版本横幅与过时历史块由root统一处理"], []),
    "D-DOC-11": ("verified_no_remaining_software_change", ["THIRD_PARTY_NOTICES.md", "tools/generate-third-party-notices.py", "tools/apply-mobile-client-patches.mjs"], [], []),
    "D-DOC-12": ("software_remaining", ["AGENTS.md", "docs/engineering-standard.md", "docs/module-map.md"], ["docs lane更新现行规范和真实模块归属；AGENTS冲突旧规则及版本横幅由root最终统一"], []),
    "D-DOC-13": ("fixed_current_source", ["agent-skills/README.md", "agent-skills/device-shell/SKILL.md", "website/src/skills/device-shell/SKILL.md", "website/scripts/build.mjs"], ["原生受管桥与通道的canonical SKILL现行字节保持，修掉仍称裸ADB/OCR通道的技能README；网站从唯一源分发，不再维护独立内容"], []),
    "D-DOC-15": ("verified_no_remaining_software_change", ["docs/security-model.md", "docs/security-model.en.md", "docs/adr/0002-current-threat-model.md"], [], []),
    "D-DOC-19": ("external_evidence_missing", ["docs/releases/v0.1.7-alpha1-build143.md", "docs/releases/v0.1.7-alpha2-pre-release.md", "docs/audits/build154/historical-invalid-digests.json", "docs/audits/build156/historical-alpha2-digest-recovery.json", "docs/audits/build156/historical-alpha2-document-before.json"], ["alpha2 Standard完整摘要已由实际APK、官方asset digest/size与原字节sidecar追回；历史文档仅追加更正，错误值/旧警告和更正前字节保持"], [{"kind": "external", "detail": "仅alpha1旧63位记录的对应原件/官方旁证仍未匹配；本地另一版本143候选不能代替该原件。alpha2已追回，未补猜"}]),
    "D-DOC-20": ("external_evidence_missing", [".github/workflows/ci-fast.yml", ".github/workflows/ci-package.yml", ".github/workflows/release.yml", "BUILD.md", "docs/接手指南.md"], ["现行说明区分Windows、Linux宿主、Hosted CI与设备，不再以跳过或配置文件替代通过；旧事实保持历史范围"], [{"kind": "external", "detail": "没有本轮Hosted Linux发布job/受保护环境/仓库保护的真实运行回执；本地软件和tmpfs不代替该证据"}]),
    "D-DOC-21": ("fixed_current_source", ["docs/接手指南.md", "docs/module-map.md", "docs/engineering-standard.md", "BUILD.md"], ["现行指南移除154当前版锁定，并按真正入口列测试/平台边界；模块图纳入本轮真实协作者与App实例状态，不声称全反转已完成"], []),
    "D-DOC-22": ("verified_no_remaining_software_change", ["app/src/main/assets/builtin-plugins/dsh-device-shell-guide/lib/index.js", "tools/test-device-script-selection.py"], [], []),
    "D-DOC-02": ("duplicate", ["app/src/main/java/com/deepseekharness/app/runtime/ProotBootstrap.java"], [], []),
    "D-DOC-03": ("verified_no_remaining_software_change", ["app/src/main/java/com/deepseekharness/app/HttpShellService.java", "app/src/main/java/com/deepseekharness/app/HarnessService.java", "app/src/main/java/com/deepseekharness/app/OverlayController.java", "app/src/main/java/com/deepseekharness/app/runtime/ProotBootstrap.java"], [], []),
    "D-DOC-05": ("required_contract", ["app/src/main/java/com/deepseekharness/app/ui/PluginNavigation.java", "docs/adr/0001-build154-boundaries.md", "docs/plugins.md"], [], []),
    "D-DOC-06": ("software_remaining", ["app/src/main/java/com/deepseekharness/app/ui/UpdateActivity.java", "tools/generate-changelog.py", "docs/releases/build156-changes.zh.md", "docs/releases/build156-changes.en.md", "app/src/main/res/raw/changelog.md", "app/src/main/res/raw-en/changelog.md"], [], []),
    "D-DOC-07": ("verified_no_remaining_software_change", ["app/src/main/java/com/deepseekharness/app/util/SensitiveData.java", "app/src/test/java/com/deepseekharness/app/util/SensitiveDataTest.java"], [], []),
    "D-DOC-08": ("verified_no_remaining_software_change", ["app/src/main/java/com/deepseekharness/app/util/BuiltinPlugins.java", "app/src/main/java/com/deepseekharness/app/util/BuiltinPluginRegistry.java", "app/src/main/assets/builtin-plugins.json", "app/src/main/assets/register-builtin-plugins.py", "tools/generate-builtin-plugins.py"], [], []),
    "D-DOC-14": ("duplicate", ["app/src/main/assets/register-builtin-plugins.py"], [], []),
    "D-DOC-16": ("verified_no_remaining_software_change", ["docs/audits/build154/history-document-sources.json", "docs/1.2-upgrade-report.md", "docs/android-low.md", "docs/ROADMAP.md", "docs/upgrade-dsh-0.1.5-alpha.2.md", "docs/website-rc2-20260928.md"], [], []),
    "D-DOC-18": ("verified_no_remaining_software_change", ["docs/android-standard.md", "docs/community-standard-feedback.md"], [], []),
    "D-DOC-23": ("verified_no_remaining_software_change", ["tools/termux-jni/README.md", "tools/termux-jni/build.ps1", "app/src/main/jniLibs/arm64-v8a/libtermux.so"], [], []),
    "D-DOC-24": ("verified_no_remaining_software_change", ["docs/audits/2026-09-27-rc2-full-architecture/architecture.md", "docs/audits/2026-09-27-rc2-full-architecture/findings.md", "docs/audits/build154/history-document-sources.json"], [], []),
    "D-PRIV-03": ("fixed_current_source", ["docs/stability-acceptance.md", "docs/audits/2026-09-27-round2-architecture/final-assessment.md", "docs/evidence/rc1.4-functional/release-build.log", "docs/audits/build156/privacy-document-sources.json"], ["定点脱敏三份仍暴露开发机路径的公开历史副本，原始字节/摘要私有保留；旧final assessment标明评分与通过只属原日期"], [{"kind": "external", "detail": "本轮不能擦除Git历史和既有公开副本；私有原件须继续从公开快照排除"}]),
    "D-WEB-01": ("verified_no_remaining_software_change", ["website/deploy/nginx-dsha-https.conf", "website/deploy/PUBLISHING.md", "website/scripts/http-check.mjs", "docs/audits/build156/nginx-syntax.json"], ["下载块安全头已存在；新增实际HTTPS检查项与部署说明，避免未来只查首页；本轮未部署"], [{"kind": "external", "detail": "没有dsha.cc本轮公网APK响应/实际Linux服务器回执；Windows本地语法检查不代替部署"}]),
}

rows = []
for source in assignment["items"]:
    identity = source["source"]["id"]
    disposition, files, changes, evidence = details[identity]
    row = {"id": identity, "currentDisposition": disposition, "files": files, "checks": [documentation], "softwareChanges": changes, "missingEvidence": evidence, "residual": [], "changed": bool(changes), "reason": "当前源逐项复核；附件建议不自动成为新授权，原始历史材料不当本轮通过回执"}
    if identity in {"D-DOC-02", "D-DOC-14"}:
        row["duplicate_of"] = "D-DOC-08"
    if identity == "D-DOC-05":
        row["reason"] = "现行用户政策明确正式插件自动解析/提交，无审阅门槛；旧建议要求外部来源额外确认不可直接套回。当前导航注释已准确。"
    if identity == "D-DOC-03":
        row["residual"] = ["只核对被引用四类的注释/相邻Javadoc；没有全仓checkstyle/doclint零告警声明"]
    if identity == "D-DOC-07":
        row["residual"] = ["已知七类与注册秘密规则不保证识别任意自定义头/非常规键或未知秘密；这是文档明确的范围"]
    if identity == "D-DOC-06":
        row["checks"].append({"command": "python tools/generate-changelog.py --check", "result": "FAIL: CHANGELOG_STALE:zh", "exitCode": 1, "scope": "156 source notes exist; raw resources still await root regeneration"})
        row["residual"] = ["root最终metadata步骤尚需从156中英notes生成raw并再check；UpdateActivity已经读取R.raw.changelog"]
    if identity == "D-DOC-10":
        row["residual"] = ["root负责最终README中英156入口与历史陈述处理；不从未知信息编造第二QQ群号"]
    if identity == "D-DOC-12":
        row["residual"] = ["root负责删除AGENTS旧禁脚本/冻结、4测试类、骨架回填、固定DSH_CONFIRM及过渡guest备份旧约束，并写156实际交付横幅"]
    if identity == "D-DOC-25":
        row["checks"].append({"command": "node website/scripts/build.mjs", "result": "EXPECTED BLOCK: source156 / APK manifest155 mismatch", "exitCode": 1, "scope": "identity fence rejected stale manifest before dist mutation; not a successful website build"})
        row["residual"] = ["必须从最终156两版真实APK生成manifest，再实际build/check；不使用155网页回执冒充156通过"]
        preflight = HERE / "website-preflight-build155.json"
        if preflight.exists():
            row["checks"].append({"command": "python docs/audits/build156/website-preflight.py", "result": "PASS build and 28/28 tests with real155 manifest/APKs and exact authenticated155 source", "exitCode": 0, "receipt": "docs/audits/build156/website-preflight-build155.json", "scope": "changed website code preflight only; NOT final156/deployment"})
    if identity == "D-DOC-19":
        recovery = json.loads((HERE / "historical-alpha2-digest-recovery.json").read_text(encoding="utf-8"))
        if recovery.get("status") != "RECOVERED_FROM_ACTUAL_APK_AND_OFFICIAL_PUBLISHED_ASSETS":
            raise RuntimeError("alpha2 formal recovery proof is missing")
        row["checks"].append({"command": "python docs/audits/build156/recover-alpha2-digest.py", "result": "PASS actual local artifact, official APK digest/size, fetched89-byte sidecar and E7E3/v1/v2/v3 identity", "exitCode": 0, "receipt": "docs/audits/build156/historical-alpha2-digest-recovery.json", "scope": "Root actually executed historical alpha2 Standard recovery only; no current156 APK/source mutation"})
        row["recoveredHistoricalDigest"] = {"sha256": recovery["recoveredSha256"], "proof": "docs/audits/build156/historical-alpha2-digest-recovery.json", "officialSidecarUrl": recovery["sources"]["sidecar"]["browser_download_url"], "originalInvalidValuePreserved": recovery["invalidOriginalPreserved"]}
    if identity == "D-DOC-08":
        row["checks"].append({"command": "python tools/generate-builtin-plugins.py --verify", "result": "PASS", "exitCode": 0, "scope": "exact current generated builtin declarations"})
    if identity == "D-DOC-11":
        row["checks"].append({"command": "python tools/generate-third-party-notices.py --check", "result": "PASS current locked third-party identities", "exitCode": 0, "scope": "current generated notice versions/digests; final APK license presence is the build owner's separate gate"})
    if identity == "D-DOC-22":
        row["checks"].append({"command": "python tools/test-device-script-selection.py", "result": "PASS 3 tests", "exitCode": 0, "scope": "retired intrusive tools are not default selected; no device action"})
    if identity == "D-WEB-01":
        row["checks"].append({"command": "app/build/audit-build154/nginx/nginx-1.28.3/nginx.exe -t -p app/build/audit-build156/docs-nginx/ -c app/build/audit-build156/docs-nginx/syntax.conf", "result": "PASS with listen-http2 deprecation warnings", "exitCode": 0, "receipt": "docs/audits/build156/nginx-syntax.json", "scope": "Windows current-template syntax, isolated certificate/paths; server not started; initial missing fixture temp/log directories corrected, first failed receipt retained"})
    if arguments.final and identity in {"D-DOC-06", "D-DOC-10", "D-DOC-12", "D-DOC-25"}:
        row["currentDisposition"] = "fixed_current_source"
        row["changed"] = True
        row["residual"] = []
        if identity == "D-DOC-06":
            row["softwareChanges"] = ["root从156中英notes生成当前raw资源；UpdateActivity继续读取资源，实际字节check通过"]
            row["checks"].append(final_raw_check)
        if identity in {"D-DOC-10", "D-DOC-12"}:
            if identity == "D-DOC-10":
                row["softwareChanges"] = ["当前BUILD/guide/website入口已同步，root完成中英README的156本地与历史公开版本边界"]
            else:
                row["softwareChanges"] = ["root完成AGENTS156横幅和相互冲突旧约束清理；模块图/工程规范记录本轮真实协作者与仍存依赖边"]
            row["checks"].append({"command": "python docs/audits/build156/write-docs-review.py --final", "result": "PASS current156 README/AGENTS banner and removal of cited stale AGENTS constraints", "exitCode": 0, "scope": "current root documents, not historical facts/global privacy claim"})
        if identity == "D-DOC-25":
            row["checks"].append({"command": "python docs/audits/build156/website-final.py", "result": f"PASS actual156 website build and {website_final['testCount']} tests", "exitCode": 0, "receipt": "docs/audits/build156/website-build-check.json", "scope": "actual156 artifact/source/SHA-bound local website only; not deployment"})
    rows.append(row)

if set(details) != {row["id"] for row in rows} or len(rows) != 27:
    raise RuntimeError("docs exact-ID coverage mismatch")
output = {"schemaVersion": 1, "lane": "docs", "phase": "current_source_review_waiting_root_metadata_and_final_apks", "scope": "27 exact source IDs; current docs and local website only; no phone/debug-audit-APK/upload/deployment", "items": rows, "crossCuttingChecks": [matrix_check], "findings": ["155已修状态未能代表当前plugins指南与网站说明，已本轮真正修订", "旧cached residual集合必须按本轮回执重建；8回归通过", "待root最终metadata/raw与156实际APK网页检查后更新本回执；alpha2历史摘要已追回，alpha1、Hosted CI、公网部署仍缺实际证据"], "missingExternalEvidence": ["仅alpha1旧Standard63位记录的对应原件/官方旁证；alpha2完整摘要已追回", "本轮Hosted Linux发布job/环境/保护真实回执", "本轮dsha.cc部署及公网新APK响应", "早已公开副本的删除/历史清理不能由本地脱敏证明"]}
if arguments.final:
    output["phase"] = "final_current_source_review"
    output["findings"][-1] = "root156元数据/说明/raw与实际156网站检查完成；alpha2已追回，仅alpha1旧摘要、Hosted CI和公网部署证据仍独立缺失"
(HERE / "review-docs.json").write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
unfinished = [row for row in rows if row["currentDisposition"] == "software_remaining"]
summary = ["# build156 docs lane：当前27项复核", "", "现行插件指南、威胁模型、中英安全说明、构建/接手入口、技能分发、网站版本输入与公开历史路径已逐项复核。旧155状态没有直接作为本轮通过依据。", "", "实际检查：127条限定文档/历史字节检查、8条台账合并回归、当前Nginx模板的Windows语法检查。网站新代码使用真实155包和精确155源做过28/28预检；该回执明确不算最终156或部署。", "", f"当前仍有 **{len(unfinished)}** 条软件收尾项，见下表；状态以 `review-docs.json` 为准。", "", "| ID | 仍需完成 |", "|---|---|"]
for row in unfinished:
    summary.append("| " + row["id"] + " | " + "; ".join(row["residual"]).replace("|", "\\|") + " |")
if not unfinished:
    summary.append("| — | 本轮实际156网页和root文档/raw收尾已完成。 |")
summary += ["", "历史摘要与仍需外部证据的范围：", "", "- alpha2 Standard摘要已由实际APK与官方资产追回，见[正式proof](historical-alpha2-digest-recovery.json)及[官方校验文件](https://github.com/DSH-APP/DSHA/releases/download/v0.1.7-alpha2/dsha-0.1.7-alpha2.apk.sha256)；仅alpha1旧63位记录对应原件仍未匹配。原错误值与旧核验警告保持，不猜补。", "- 本轮Hosted Linux发布job、受保护环境/仓库保护与dsha.cc部署、公网下载响应。未执行不能算通过。", "- 本地脱敏不能擦除Git历史或此前公开副本。原字节由私有保留清单绑定，须从公开源码快照排除。", "", "设备、实际SAF/FUSE、Android6/7/16KiB、长期后台与外部模型缺口由相应owner报告记录，本条线不编造通过。原生自动安装与开放依赖/终端是当前用户契约；独立应急确认与未知退出屏障继续保留。", ""]
(HERE / "docs-findings.md").write_text("\n".join(summary), encoding="utf-8", newline="\n")
print("wrote 27 exact current docs reviews; root metadata/final-APK checks remain explicit")
