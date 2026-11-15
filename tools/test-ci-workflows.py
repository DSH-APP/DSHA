"""Static contracts for the node-based GitHub Actions graph; text-only, no YAML dependency."""
from pathlib import Path
import json
import re
import subprocess
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
WORKFLOWS = ROOT / ".github/workflows"
ACTIONS = ROOT / ".github/actions"


def text(name):
    return (WORKFLOWS / name).read_text(encoding="utf-8")


def jobs(source):
    """Top-level job blocks keyed by id (two-space indentation under `jobs:`)."""
    body = source.split("\njobs:\n", 1)[1]
    result, current = {}, None
    for line in body.splitlines(keepends=True):
        match = re.match(r"^  ([A-Za-z0-9_-]+):\s*$", line)
        if match:
            current = match.group(1)
            result[current] = ""
        elif current is not None:
            if line.strip() and not line.startswith("  "):
                break
            result[current] += line
    return result


def needs(block):
    match = re.search(r"^    needs: (.+)$", block, re.M)
    if not match:
        return set()
    value = match.group(1).strip()
    return {item.strip() for item in value.strip("[]").split(",")}


class WorkflowGraph(unittest.TestCase):
    def test_private_repository_workflows_are_gone(self):
        names = {path.name for path in WORKFLOWS.glob("*.yml")}
        for retired in ("sunset-3090-q.yml", "sunset-release.yml", "terminal-regression.yml"):
            self.assertNotIn(retired, names)
        for path in WORKFLOWS.glob("*.yml"):
            self.assertNotIn("DSHA-Sunset-mod-3090-Q", path.read_text(encoding="utf-8"), path.name)

    def test_every_local_reference_exists_and_external_actions_are_pinned(self):
        files = [*WORKFLOWS.glob("*.yml"), *ACTIONS.glob("*/action.yml")]
        for path in files:
            for selected in re.findall(r"uses:\s*([^\s#]+)", path.read_text(encoding="utf-8")):
                with self.subTest(file=path.name, uses=selected):
                    if selected.startswith("./"):
                        self.assertTrue((ROOT / selected[2:]).exists())
                    else:
                        self.assertRegex(selected, r"^[\w.-]+/[\w.-]+@[0-9a-f]{40}$")

    def test_reusable_nodes_are_call_only_and_read_only(self):
        for path in WORKFLOWS.glob("_node-*.yml"):
            source = path.read_text(encoding="utf-8")
            with self.subTest(node=path.name):
                trigger = source.split("\non:\n", 1)[1].split("\npermissions:", 1)[0]
                self.assertRegex(trigger, r"^  workflow_call:")
                self.assertEqual(re.findall(r"(?m)^\s+contents:\s*(\w+)", source), ["read"])
                self.assertNotIn("concurrency:", source)
                self.assertIn("persist-credentials: false", source)
                if path.name == "_node-test-packages.yml":
                    # 测试包节点需要测试证书（SUNSET_TEST_*）；正式签名 Secrets 绝不进入。
                    # 只有不持证书的 unsigned_only 编译模式可以写 Gradle 共享缓存。
                    self.assertIn("SUNSET_TEST_KEYSTORE_B64", trigger)
                    self.assertIn("SUNSET_TEST_STORE_PASSWORD", trigger)
                    self.assertNotRegex(source, r"secrets\.DSHA_")
                    self.assertIn("gradle-cache: ${{ (inputs.unsigned_only && 'true') || 'false' }}", source)
                    self.assertNotIn("gradle-cache: 'true'", source)
                    self.assertNotIn("gradle-cache: 'false'", source)
                else:
                    self.assertNotIn("secrets.", source)

    def test_standard_scope_only(self):
        for path in WORKFLOWS.glob("*.yml"):
            source = path.read_text(encoding="utf-8")
            with self.subTest(file=path.name):
                self.assertNotRegex(source, r":app:(?:test|lint|assemble)Low")
                self.assertNotIn("tools/remote", source)
                self.assertNotIn("dsha-connect", source)
        self.assertIn(":app:testStandardDebugUnitTest", text("_node-unit-tests.yml"))
        self.assertIn("--stage package --flavors standard", text("ci-package.yml"))
        result = subprocess.run([sys.executable, str(ROOT / "tools/verify-stability.py"),
                                 "--stage", "software", "--flavors", "both"],
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 2)
        self.assertIn("invalid choice", result.stderr)

    def test_ci_package_keeps_run_path_for_receipt_gate(self):
        gate = (ROOT / "tools/verify-ci-package.py").read_text(encoding="utf-8")
        self.assertIn("'.github/workflows/ci-package.yml'", gate)
        self.assertTrue((WORKFLOWS / "ci-package.yml").is_file())


class ReleaseGraph(unittest.TestCase):
    """Mod 身份正式发布；ReleaseUpstreamGraph 用同一组门禁检查上游身份工作流。"""
    workflow = "release-production.yml"
    identity = "mod"

    def setUp(self):
        self.source = text(self.workflow)
        self.jobs = jobs(self.source)
        self.expected = json.loads((ROOT / f"ci/release-identity.{self.identity}.json").read_text(encoding="utf-8"))

    def test_repository_guard_skips_in_the_other_repository(self):
        bind = self.jobs["bind"]
        self.assertIn("github.event_name == 'workflow_dispatch'", bind)
        self.assertIn(f"github.repository == '{self.expected['repository']}'", bind)
        self.assertIn(f"github.ref == '{self.expected['ref']}'", bind)
        self.assertEqual("github.event.repository.private" in bind, self.expected["privateRepository"])
        other = json.loads((ROOT / f"ci/release-identity.{'upstream' if self.identity == 'mod' else 'mod'}.json")
                           .read_text(encoding="utf-8"))
        self.assertNotIn(other["repository"], bind)
        # 其余节点都直接或间接依赖 bind：bind 被跳过时整条发布链跳过。
        for name, block in self.jobs.items():
            if name != "bind":
                self.assertTrue(needs(block), name)

    def test_build_and_verification_use_only_the_selected_identity(self):
        if self.workflow == "release-production.yml":
            # 构建与签名拆成两个节点：assemble 在无 Secrets 的 build-unsigned 里，
            # 身份 / 证书 / 签名方案由 sign-verify 调用的签名工具强制核对。
            self.assertIn(f":app:assembleStandardRelease -Pdsha.identity={self.identity} ",
                          self.jobs["build-unsigned"])
            self.assertIn(f"--identity {self.identity}", self.jobs["sign-verify"])
            self.assertIn("tools/sign-release-apks.py", self.jobs["sign-verify"])
        else:
            signed = self.jobs["build-signed"]
            self.assertIn(f":app:assembleStandardRelease -Pdsha.identity={self.identity} ", signed)
            self.assertIn(f"open('ci/release-identity.{self.identity}.json'", signed)
            self.assertIn("${KEY_ALIAS:-" + self.expected["keyAlias"] + "}", signed)
            self.assertIn(f"application-label:'{self.expected['appName']}'", signed)
            self.assertIn("match.group(1) != identity['certificateSha256']", signed)
            self.assertIn("if 'application-debuggable' in badging:", signed)
            self.assertIn("version_name.endswith(('-test', '-SunsetTest', '-island'))", signed)
        tag = self.expected["tagPrefix"]
        self.assertIn(f"re.fullmatch(r'{tag}[0-9]+-[0-9]{{8}}-[0-9]{{6}}', tag)", self.jobs["bind"])
        self.assertIn(f"output.write('apk_name={self.expected['apkPrefix']}-'", self.jobs["bind"])
        other = "upstream" if self.identity == "mod" else "mod"
        self.assertNotIn(f"-Pdsha.identity={other}", self.source)
        self.assertNotIn(f"release-identity.{other}.json", self.source)

    def test_node_set_and_gates_before_signing(self):
        if self.workflow == "release-production.yml":
            # 正式发布：先无 Secrets 构建（与门禁并行），全部门禁通过后再签名；
            # 同 run 附带 .debug / .dev 共存测试包（可 none），dry_run 时只出报告。
            self.assertEqual(set(self.jobs), {"bind", "contracts", "java-format", "host-regression",
                                              "inputs", "unit", "lint", "build-unsigned",
                                              "sign-verify", "test-packages-unsigned",
                                              "test-packages", "publish", "dry-run-summary"})
            gates = {"bind", "contracts", "java-format", "host-regression", "inputs", "unit", "lint"}
            self.assertEqual(needs(self.jobs["build-unsigned"]), {"bind", "inputs"})
            self.assertEqual(needs(self.jobs["test-packages-unsigned"]), {"bind", "inputs"})
            self.assertEqual(needs(self.jobs["sign-verify"]), gates | {"build-unsigned"})
            self.assertEqual(needs(self.jobs["test-packages"]), gates | {"test-packages-unsigned"})
            self.assertEqual(needs(self.jobs["publish"]), {"bind", "sign-verify", "test-packages"})
            self.assertEqual(needs(self.jobs["dry-run-summary"]), {"bind", "sign-verify", "test-packages"})
            for name in ("contracts", "java-format", "host-regression", "inputs"):
                self.assertEqual(needs(self.jobs[name]), {"bind"}, name)
        else:
            self.assertEqual(set(self.jobs), {"bind", "contracts", "java-format", "host-regression",
                                              "inputs", "unit", "lint", "build-signed",
                                              "test-packages", "publish"})
            gates = {"bind", "contracts", "java-format", "host-regression", "inputs", "unit", "lint"}
            self.assertEqual(needs(self.jobs["build-signed"]), gates)
            self.assertEqual(needs(self.jobs["test-packages"]), gates)
            self.assertEqual(needs(self.jobs["publish"]), {"bind", "build-signed", "test-packages"})
            official_tests = self.jobs["test-packages"]
            self.assertIn("uses: ./.github/workflows/_node-test-packages.yml", official_tests)
            self.assertIn("release_identity: upstream", official_tests)
            self.assertIn("require_configured_key: true", official_tests)
            self.assertIn("require_pinned_cert: true", official_tests)
            self.assertIn("artifact_prefix: release-test-package", official_tests)
            self.assertIn("apk_name_prefix: dsha", official_tests)
            self.assertIn("test_packages:", self.source)
            self.assertIn("pattern: release-test-package-evidence-*", self.jobs["publish"])
            self.assertIn("--apk-prefix dsha", self.jobs["publish"])
            self.assertIn("test-package-assets.txt", self.jobs["publish"])
            for name in ("contracts", "java-format", "host-regression", "inputs"):
                self.assertEqual(needs(self.jobs[name]), {"bind"}, name)

    def test_every_node_checks_out_the_exact_requested_commit(self):
        for name, block in self.jobs.items():
            if name == "publish":
                continue
            with self.subTest(job=name):
                self.assertIn("${{ inputs.expected_sha }}", block)

    def test_secrets_and_write_permission_are_isolated(self):
        signer = "sign-verify" if self.workflow == "release-production.yml" else "build-signed"
        for name, block in self.jobs.items():
            with self.subTest(job=name):
                if name == signer:
                    self.assertIn("secrets.DSHA_KEYSTORE_B64", block)
                    self.assertNotIn("contents: write", block)
                    self.assertIn("gradle-cache: 'false'", block)
                elif name == "test-packages":
                    # 正式 Release 的测试包只拿独立测试证书；正式签名 Secrets 不进入该节点。
                    self.assertIn("secrets.SUNSET_TEST_KEYSTORE_B64", block)
                    self.assertIn("secrets.SUNSET_TEST_STORE_PASSWORD", block)
                    self.assertNotIn("secrets.DSHA_", block)
                    self.assertNotIn("contents: write", block)
                elif name == "publish":
                    self.assertIn("contents: write", block)
                    self.assertNotIn("secrets.", block)
                    self.assertNotIn("actions/checkout@", block)
                    self.assertIn("sha256sum -c SHA256SUMS", block)
                else:
                    self.assertNotIn("secrets.", block)
                    self.assertNotIn("contents: write", block)
        self.assertEqual(re.findall(r"(?m)^\s+contents:\s*(\w+)", self.source), ["read", "write"])

    def test_managed_apk_asset_gate_runs_between_identity_and_build_evidence(self):
        if self.workflow == "release-production.yml":
            unsigned = self.jobs["build-unsigned"]
            # 受管资产门禁只需要本次构建的生成资产目录，所以在无 Secrets 的未签名阶段运行，
            # 且必须在交接给签名节点之前完成；签名后由载荷逐成员比对证明内容未被改动。
            self.assertLess(unsigned.index("tools/sign-release-apks.py --check-only"),
                            unsigned.index("tools/verify-apk-managed-assets.py"))
            self.assertLess(unsigned.index("tools/verify-apk-managed-assets.py"),
                            unsigned.index("Hand unsigned APK to the signing node"))
            signed = self.jobs["sign-verify"]
            self.assertNotIn("verify-apk-managed-assets.py", signed)
            self.assertIn("tools/sign-release-apks.py --apk", signed)
            self.assertLess(signed.index("Sign unsigned production APK and verify identity"),
                            signed.index("Package commit-bound build evidence"))
        else:
            signed = self.jobs["build-signed"]
            gate = signed.index("tools/verify-apk-managed-assets.py")
            self.assertLess(
                signed.index("Verify production APK identity and signing certificate"), gate)
            self.assertLess(gate, signed.index("Package commit-bound build evidence"))

    def test_full_unit_tests_require_fixtures_with_one_fork(self):
        unit = self.jobs["unit"]
        self.assertIn("mode: full", unit)
        self.assertIn("require_fixtures: true", unit)
        self.assertIn("forks: 1", unit)
        signer = self.jobs["sign-verify" if self.workflow == "release-production.yml" else "build-signed"]
        self.assertIn("verify-unit-results.py --mode full --require-fixtures", signer)
        self.assertIn("sunset_test: false", self.jobs["lint"])

    def test_key_cleanup_always_runs_and_no_key_is_uploaded(self):
        block = self.jobs["sign-verify" if self.workflow == "release-production.yml" else "build-signed"]
        cleanup = block.split("- name: Remove release private key", 1)[1].split("\n      - ", 1)[0]
        self.assertIn("if: always()", cleanup)
        self.assertIn("key.unlink(missing_ok=True)", cleanup)
        self.assertIn("::add-mask::", block)
        upload = block.split("- name: Hand verified release bundle to publish", 1)[1]
        self.assertIn("retention-days: 1", upload)
        self.assertNotRegex(upload, r"(?i)\.(?:jks|keystore|p12|pfx)\b|RUNNER_TEMP")

    def test_existing_tag_is_refused_before_building_and_before_publishing(self):
        self.assertIn("gh release view", self.jobs["bind"])
        self.assertIn("gh release view", self.jobs["publish"])


class ModReleaseIdentity(unittest.TestCase):
    def test_mod_release_keeps_public_main_guard(self):
        bind = jobs(text("release-production.yml"))["bind"]
        self.assertIn("github.repository == 'SunsetRNE/DSHA-Sunset-mod'", bind)
        self.assertIn("github.ref == 'refs/heads/main'", bind)
        self.assertIn("if identity['packageName'] != 'io.github.sunsetrne.dsha':", bind)
        self.assertIn("selected = resolver.check()['mod']", bind)


class ProductionTestPackageAttachments(unittest.TestCase):
    """正式 Release 同 run 附带共存测试包（.debug / .dev）与 dry_run；upstream 工作流不涉及。"""

    def setUp(self):
        self.source = text("release-production.yml")
        self.jobs = jobs(self.source)

    def test_inputs_choose_test_packages_and_dry_run(self):
        self.assertIn("      test_packages:\n", self.source)
        self.assertIn("options: [debug+dev, debug, none]", self.source)
        self.assertIn("default: debug+dev", self.source)
        self.assertIn("      dry_run:\n", self.source)
        self.assertIn("type: boolean", self.source)
        self.assertRegex(self.source, r"(?m)^        default: false$")

    def test_test_packages_jobs_split_compile_and_sign(self):
        unsigned = self.jobs["test-packages-unsigned"]
        self.assertIn("uses: ./.github/workflows/_node-test-packages.yml", unsigned)
        self.assertIn("needs: [bind, inputs]", unsigned)
        self.assertIn("unsigned_only: true", unsigned)
        self.assertIn("fixture: false", unsigned)
        self.assertIn("artifact_prefix: release-test-unsigned", unsigned)
        self.assertIn("require_configured_key: false", unsigned)
        self.assertNotIn("secrets.", unsigned)
        self.assertNotIn("contents: write", unsigned)
        signed = self.jobs["test-packages"]
        self.assertIn("uses: ./.github/workflows/_node-test-packages.yml", signed)
        self.assertIn("test-packages-unsigned", signed)
        self.assertIn("require_configured_key: true", signed)
        self.assertIn("artifact_prefix: release-test-package", signed)
        self.assertIn("unsigned_artifact_prefix: release-test-unsigned", signed)
        self.assertIn("fixture: true", signed)
        self.assertIn("secrets.SUNSET_TEST_KEYSTORE_B64", signed)
        self.assertNotIn("secrets.DSHA_", signed)
        self.assertNotIn("contents: write", signed)

    def test_publish_skips_dry_run_and_merges_test_package_evidence(self):
        publish = self.jobs["publish"]
        self.assertIn("!inputs.dry_run", publish)
        # 只有 none 时的 skipped 可以放行；测试包节点失败导致的 skipped 必须挡住发布。
        self.assertIn("needs.test-packages.result == 'success'", publish)
        self.assertIn("(needs.test-packages.result == 'skipped' && inputs.test_packages == 'none')", publish)
        self.assertIn("pattern: release-test-package-evidence-*", publish)
        self.assertIn("release-test-packages.py merge", publish)
        self.assertIn('--requested "$TEST_PACKAGES"', publish)
        self.assertIn("test-package-assets.txt", publish)
        self.assertIn("Re-verify merged release bundle checksums", publish)
        self.assertNotIn("actions/checkout@", publish)

    def test_dry_run_summary_merges_and_prints_without_write_permission(self):
        summary = self.jobs["dry-run-summary"]
        self.assertIn("!cancelled()", summary)
        self.assertIn("inputs.dry_run", summary)
        self.assertIn("release-test-packages.py merge", summary)
        self.assertIn("GITHUB_STEP_SUMMARY", summary)
        self.assertIn("gh release create", summary)
        self.assertNotIn("contents: write", summary)
        self.assertNotIn("secrets.", summary)

    def test_test_package_evidence_tool_is_commit_bound_before_execution(self):
        signed = self.jobs["sign-verify"]
        self.assertIn("testPackageEvidenceToolSha256", signed)
        self.assertIn("out/release-test-packages.py", signed)
        publish = self.jobs["publish"]
        self.assertIn("TEST_PACKAGE_TOOL_TAMPERED", publish)
        self.assertLess(publish.index("TEST_PACKAGE_TOOL_TAMPERED"),
                        publish.index("Re-verify merged release bundle checksums"))


class SourceAndServices(unittest.TestCase):
    def test_source_checks_run_contracts_format_unit_and_host_as_nodes(self):
        graph = jobs(text("ci-fast.yml"))
        self.assertEqual(set(graph), {"contracts", "java-format", "inputs", "unit-source", "host-fast"})
        self.assertEqual(needs(graph["unit-source"]), {"inputs"})
        self.assertEqual(needs(graph["host-fast"]), {"inputs"})
        self.assertIn("mode: source-only", graph["unit-source"])
        self.assertIn("stage: fast", graph["host-fast"])

    def test_only_test_package_and_release_sign(self):
        for path in WORKFLOWS.glob("*.yml"):
            source = path.read_text(encoding="utf-8")
            if path.name in ("release-production.yml", "release-official.yml",
                             "test-package.yml", "test-package-variants.yml", "_node-test-packages.yml"):
                continue
            with self.subTest(file=path.name):
                self.assertNotIn("apksigner", source)
                self.assertNotIn("secrets.", source)
                self.assertNotIn("contents: write", source)

    def test_test_package_variants_reuses_the_shared_signing_node(self):
        source = text("test-package-variants.yml")
        graph = jobs(source)
        self.assertEqual(set(graph), {"contracts", "inputs", "patch-chains", "unit", "lint",
                                      "package-tests", "package-prod-unsigned", "publish"})
        package_tests = graph["package-tests"]
        self.assertIn("uses: ./.github/workflows/_node-test-packages.yml", package_tests)
        self.assertIn("variants: ${{ inputs.targets == 'all' && 'test+debug+dev' || inputs.targets }}", package_tests)
        self.assertIn("require_configured_key: ${{ inputs.publish }}", package_tests)
        self.assertIn("require_pinned_cert: ${{ inputs.publish }}", package_tests)
        self.assertIn("SUNSET_TEST_KEYSTORE_B64: ${{ secrets.SUNSET_TEST_KEYSTORE_B64 }}", package_tests)
        self.assertNotIn('"$apksigner" sign', source)
        self.assertNotIn("DSHA_KEYSTORE", source)
        self.assertIn("permissions:\n  contents: read", source)
        publish = graph["publish"]
        self.assertIn("needs: [package-tests]", publish)
        self.assertIn("inputs.publish", publish)
        self.assertIn("contents: write", publish)
        self.assertNotIn("secrets.", publish)
        self.assertNotIn('"$apksigner" sign', publish)
        self.assertIn("gh run download", publish)
        self.assertIn("gh release create", publish)
        self.assertIn("sha256sum", publish)
        prod = graph["package-prod-unsigned"]
        self.assertIn("-Pdsha.unsignedPackage=true", prod)
        self.assertNotIn('"$apksigner" sign', prod)
        self.assertNotIn("SUNSET_TEST_KEYSTORE_B64", prod)
        self.assertIn("UNSIGNED_PACKAGE_MUST_NOT_VERIFY", prod)

    def test_shared_test_package_node_signs_only_with_the_test_certificate(self):
        source = text("_node-test-packages.yml")
        self.assertIn("-Pdsha.sunsetTest=true -Pdsha.unsignedPackage=true", source)
        self.assertIn("-Pdsha.debugPackage=true -Pdsha.unsignedPackage=true", source)
        self.assertIn("-Pdsha.devPackage=true -Pdsha.unsignedPackage=true", source)
        self.assertIn("SUNSET_TEST_KEYSTORE_B64", source)
        self.assertNotIn("DSHA_KEYSTORE", source)
        self.assertIn('test "$cert" != "$production"', source)
        self.assertIn('test "$cert" != "$upstream"', source)
        self.assertIn("application-debuggable", source)
        # 固定测试签名：签名证书必须与 ci/test-signing.json 的指纹一致；发布模式下缺钉直接失败。
        self.assertIn("tools/test-signing.py --check-pem out/test-cert.pem --require-pinned", source)
        self.assertIn("REQUIRE_PINNED_CERT", source)

    def test_test_signing_pin_is_distinct_and_matches_the_public_certificate(self):
        pin = json.loads((ROOT / "ci/test-signing.json").read_text(encoding="utf-8"))
        self.assertEqual(pin["schema"], 1)
        self.assertEqual(pin["keyAlias"], "sunset-test")
        self.assertRegex(pin["certificateSha256"], r"[a-f0-9]{64}")
        for name in ("mod", "upstream"):
            identity = json.loads((ROOT / f"ci/release-identity.{name}.json").read_text(encoding="utf-8"))
            self.assertNotEqual(pin["certificateSha256"], identity["certificateSha256"])
        result = subprocess.run([sys.executable, str(ROOT / "tools/test-signing.py"),
                                 "--check-pem", str(ROOT / "docs/test-signing/test-cert.pem"),
                                 "--require-pinned"], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        rejected = subprocess.run(
            [sys.executable, str(ROOT / "tools/test-signing.py"), "--check-pem",
             str(ROOT / "LICENSE"), "--require-pinned"], capture_output=True, text=True)
        self.assertNotEqual(rejected.returncode, 0)
        self.assertIn("TEST_", rejected.stderr)

    def test_shared_test_package_node_keeps_single_signing_path(self):
        source = text("_node-test-packages.yml")
        self.assertEqual(source.count('"$apksigner" sign'), 1)
        # 只有无证书的 unsigned_only 编译模式允许写共享缓存；签名模式仍是 false。
        self.assertIn("gradle-cache: ${{ (inputs.unsigned_only && 'true') || 'false' }}", source)
        self.assertNotIn("gradle-cache: 'true'", source)
        self.assertIn("if: ${{ !inputs.unsigned_only }}", source)
        self.assertIn("unsigned_artifact_prefix", source)
        self.assertIn("SIGNED_APK_PAYLOAD_CHANGED", source)
        self.assertIn("payload entries unchanged by signing", source)
        self.assertIn("require_configured_key", source)
        self.assertIn("SUNSET_TEST_KEYSTORE_B64 is required", source)
        self.assertNotIn("gh release", source)
        self.assertNotIn("contents: write", source)
        self.assertIn("permissions:\n  contents: read", source)
        cleanup = source.split("- name: Remove test private key", 1)[1].split("\n      - ", 1)[0]
        self.assertIn("always()", cleanup)
        self.assertIn("key.unlink(missing_ok=True)", cleanup)
        for step in source.split("\n      - "):
            if "actions/upload-artifact@" in step:
                self.assertNotRegex(step, r"(?i)\.(?:jks|keystore|p12|pfx)\b|RUNNER_TEMP")

    def test_test_package_identity_is_never_the_production_certificate(self):
        source = text("test-package.yml")
        self.assertIn("-Pdsha.sunsetTest=true -Pdsha.unsignedPackage=true", source)
        self.assertIn('"-Pdsha.identity=$RELEASE_IDENTITY"', source)
        self.assertIn("options: [mod, upstream]", source)
        self.assertIn("default: mod", source)
        self.assertIn('test "$package" = "$expected"', source)
        self.assertIn('--field packageName)$(python3 tools/release-identity.py --identity "$RELEASE_IDENTITY" --field testSuffix)', source)
        self.assertIn('test "$cert" != "$production"', source)
        self.assertIn('test "$cert" != "$upstream"', source)
        self.assertNotIn("gh release", source)
        cleanup = source.split("- name: Remove test private key", 1)[1].split("\n      - ", 1)[0]
        self.assertIn("if: always()", cleanup)
        for step in source.split("\n      - "):
            if "actions/upload-artifact@" in step:
                self.assertNotRegex(step, r"(?i)\.(?:jks|keystore|p12|pfx)\b|RUNNER_TEMP")

    def test_test_package_verifies_managed_apk_assets_and_keeps_the_receipt(self):
        source = text("test-package.yml")
        self.assertIn('tools/verify-apk-managed-assets.py "$apk" > out/apk-managed-assets.txt',
                      source)
        self.assertIn("out/apk-managed-assets.txt", source.split("Upload public package evidence", 1)[1])

    def test_handoff_is_short_lived_and_uncompressed(self):
        source = text("_node-inputs.yml")
        self.assertIn("tools/ci-handoff.py pack", source)
        self.assertIn("retention-days: 1", source)
        self.assertIn("compression-level: 0", source)
        restore = (ACTIONS / "restore-handoff/action.yml").read_text(encoding="utf-8")
        self.assertIn("tools/ci-handoff.py unpack", restore)
        self.assertIn("verify-runtime-descriptor-source.py", restore)

    def test_scheduled_inputs_health_only_reads(self):
        source = text("inputs-health.yml")
        self.assertIn("schedule:", source)
        self.assertIn("tools/ci-assets.py", source)
        self.assertNotIn("upload-artifact", source)


class ProductionSplitRelease(unittest.TestCase):
    """build-unsigned / sign-verify 拆分：凭据、缓存与证据边界。"""

    def setUp(self):
        self.source = text("release-production.yml")
        self.jobs = jobs(self.source)

    def test_unsigned_builders_hold_no_secrets_and_use_the_gradle_cache(self):
        self.assertIn("gradle-cache: 'true'", self.jobs["build-unsigned"])
        for name in ("build-unsigned", "test-packages-unsigned"):
            block = self.jobs[name]
            with self.subTest(job=name):
                self.assertNotIn("secrets.", block)
                self.assertNotIn("contents: write", block)
                self.assertNotIn('"$apksigner" sign', block)

    def test_signing_nodes_are_the_only_secret_holders_and_do_not_cache(self):
        # 正式签名节点就地禁止共享缓存；测试包是复用节点调用，缓存策略由 _node-test-packages.yml
        # 的 unsigned_only 条件保证（见 WorkflowGraph 的断言）。
        self.assertIn("gradle-cache: 'false'", self.jobs["sign-verify"])
        self.assertNotIn("contents: write", self.jobs["sign-verify"])
        self.assertIn("secrets.DSHA_KEYSTORE_B64", self.jobs["sign-verify"])
        self.assertNotIn("secrets.DSHA_", self.jobs["test-packages"])
        self.assertIn("secrets.SUNSET_TEST_KEYSTORE_B64", self.jobs["test-packages"])
        self.assertNotIn("contents: write", self.jobs["test-packages"])

    def test_sign_verify_consumes_a_commit_bound_unsigned_artifact(self):
        block = self.jobs["sign-verify"]
        self.assertIn("name: release-unsigned-apk", block)
        self.assertIn("UNSIGNED_HANDOFF_NOT_THIS_RUN", block)
        self.assertIn("unsigned-receipt.json", block)
        self.assertIn("tools/sign-release-apks.py --apk", block)
        self.assertLess(block.index("Verify unsigned handoff binding"),
                        block.index("Sign unsigned production APK and verify identity"))

    def test_unsigned_stage_verifies_manifest_and_assets_before_signing(self):
        block = self.jobs["build-unsigned"]
        self.assertIn("--check-only", block)
        self.assertIn("verify-apk-managed-assets.py", block)
        self.assertLess(block.index("--check-only"), block.index("verify-apk-managed-assets.py"))
        self.assertLess(block.index("verify-apk-managed-assets.py"),
                        block.index("Hand unsigned APK to the signing node"))

    def test_evidence_zip_no_longer_duplicates_the_apk(self):
        block = self.jobs["sign-verify"]
        files = [line for line in block.splitlines() if line.strip().startswith("files = [")]
        self.assertEqual(len(files), 1)
        self.assertNotIn("APK_NAME", files[0])
        self.assertIn("apk-signing.json", files[0])
        self.assertIn("names = [os.environ['APK_NAME'], 'dsha-a-su-build-evidence.zip', 'build-info.json']",
                      block)
        self.assertIn("hashlib.sha256((Path('out') / name).read_bytes()).hexdigest()", block)

    def test_signing_tool_pins_certificate_schemes_and_payload(self):
        tool = (ROOT / "tools/sign-release-apks.py").read_text(encoding="utf-8")
        self.assertIn("PUBLISH_CERTIFICATE_MISMATCH", tool)
        self.assertIn("SIGNED_CERTIFICATE_MISMATCH", tool)
        self.assertIn("require_schemes", tool)
        self.assertIn("SIGNED_APK_PAYLOAD_CHANGED", tool)
        self.assertIn("--v1-signing-enabled", tool)
        self.assertIn("--v2-signing-enabled", tool)
        self.assertIn("--v3-signing-enabled", tool)
        self.assertIn("UNSIGNED_APK_IS_SIGNED", tool)
        self.assertIn("ONLY_UNSIGNED_INPUT", tool)
        self.assertIn("RELEASE_SIGNING_ENV_MISSING", tool)
        self.assertIn("VERIFY_MIN_SDK", tool)


class AndroidSdkAction(unittest.TestCase):
    def test_preinstalled_packages_skip_download_and_install_is_bounded(self):
        source = (ACTIONS / "android-sdk/action.yml").read_text(encoding="utf-8")
        self.assertIn("platforms/$platform/android.jar", source)
        self.assertIn("build-tools/$buildtools/aapt2", source)
        self.assertIn("ndk/$ndk/source.properties", source)
        self.assertIn("Pkg.Revision", source)
        self.assertIn("timeout 600", source)
        self.assertIn('"$attempt" -ge 2', source)
        self.assertIn("sdkmanager 失败或超时", source)
        self.assertIn("已预装", source)


class InputsNpmCache(unittest.TestCase):
    def test_runtime_rebuild_reuses_lock_keyed_npm_cache(self):
        source = text("_node-inputs.yml")
        self.assertIn("actions/cache@55cc8345863c7cc4c66a329aec7e433d2d1c52a9", source)
        self.assertIn("dsha-dsh-runtime-npm-${{ runner.os }}-${{ hashFiles('tools/dsh-runtime/package-lock.json') }}",
                      source)
        self.assertIn('prepare-dsh-runtime.py --cache "$RUNNER_TEMP/dsha-npm-cache"', source)
        self.assertIn("retention-days: 1", source)
        self.assertIn("compression-level: 0", source)


if __name__ == "__main__":
    unittest.main()
