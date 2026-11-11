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
                    # 测试包节点需要测试证书（SUNSET_TEST_*）；正式签名 Secrets 绝不进入，
                    # 且与正式签名节点同策略，不写 Gradle 共享缓存。
                    self.assertIn("SUNSET_TEST_KEYSTORE_B64", trigger)
                    self.assertIn("SUNSET_TEST_STORE_PASSWORD", trigger)
                    self.assertNotRegex(source, r"secrets\.DSHA_")
                    self.assertIn("gradle-cache: 'false'", source)
                    self.assertNotIn("gradle-cache: 'true'", source)
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
            # 正式发布在同 run 附带 .debug / .dev 共存测试包（可 none），dry_run 时只出报告。
            self.assertEqual(set(self.jobs), {"bind", "contracts", "java-format", "host-regression",
                                              "inputs", "unit", "lint", "build-signed",
                                              "test-packages", "publish", "dry-run-summary"})
            gates = {"bind", "contracts", "java-format", "host-regression", "inputs", "unit", "lint"}
            self.assertEqual(needs(self.jobs["build-signed"]), gates)
            self.assertEqual(needs(self.jobs["test-packages"]), gates)
            self.assertEqual(needs(self.jobs["publish"]), {"bind", "build-signed", "test-packages"})
            self.assertEqual(needs(self.jobs["dry-run-summary"]), {"bind", "build-signed", "test-packages"})
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
        for name, block in self.jobs.items():
            with self.subTest(job=name):
                if name == "build-signed":
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
        self.assertIn("verify-unit-results.py --mode full --require-fixtures", self.jobs["build-signed"])
        self.assertIn("sunset_test: false", self.jobs["lint"])

    def test_key_cleanup_always_runs_and_no_key_is_uploaded(self):
        block = self.jobs["build-signed"]
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

    def test_test_packages_job_reuses_the_shared_node(self):
        block = self.jobs["test-packages"]
        self.assertIn("uses: ./.github/workflows/_node-test-packages.yml", block)
        self.assertIn("inputs.test_packages != 'none'", block)
        self.assertIn("ref: ${{ inputs.expected_sha }}", block)
        self.assertIn("require_configured_key: true", block)
        self.assertIn("artifact_prefix: release-test-package", block)
        self.assertIn("apk_name_prefix: dsha-a-su", block)
        self.assertIn("retention_days: 1", block)
        self.assertIn("secrets.SUNSET_TEST_KEYSTORE_B64", block)
        self.assertNotIn("secrets.DSHA_", block)
        self.assertNotIn("contents: write", block)

    def test_publish_skips_dry_run_and_merges_test_package_evidence(self):
        publish = self.jobs["publish"]
        self.assertIn("!inputs.dry_run", publish)
        self.assertIn("(needs.test-packages.result == 'success' || needs.test-packages.result == 'skipped')", publish)
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
        signed = self.jobs["build-signed"]
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
        self.assertIn("gradle-cache: 'false'", source)
        self.assertIn("require_configured_key", source)
        self.assertIn("SUNSET_TEST_KEYSTORE_B64 is required", source)
        self.assertNotIn("gh release", source)
        self.assertNotIn("contents: write", source)
        self.assertIn("permissions:\n  contents: read", source)
        cleanup = source.split("- name: Remove test private key", 1)[1].split("\n      - ", 1)[0]
        self.assertIn("if: always()", cleanup)
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


if __name__ == "__main__":
    unittest.main()
