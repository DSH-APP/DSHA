"""Current source ownership checks; behavioral proofs live in the corresponding JVM tests."""
from pathlib import Path
import json
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/deepseekharness/app"


def source(path):
    return (MAIN / path).read_text(encoding="utf-8")


def without_comments(text):
    return re.sub(r"/\*.*?\*/|//[^\n]*", "", text, flags=re.S)


def mutable_static_fields(text):
    # Methods and nested DTO types are excluded by the field terminator. Only immutable
    # scalar/String/Pattern constants may be shared by these application collaborators.
    fields = re.finditer(
        r"(?m)^\s*(?:(?:public|protected|private)\s+)?static\s+"
        r"(?P<modifiers>(?:(?:final|volatile)\s+)*)"
        r"(?P<type>[\w.]+(?:<[^;\n{}]+>)?(?:\[\])?)\s+"
        r"(?P<name>\w+)\s*(?:=|,|;)", without_comments(text))
    immutable = {"boolean", "byte", "short", "int", "long", "float", "double", "char",
                 "String", "Pattern"}
    return [field.group("name") for field in fields
            if "final" not in field.group("modifiers").split()
            or field.group("type").rsplit(".", 1)[-1] not in immutable]


class CoreBoundariesTest(unittest.TestCase):
    def test_harness_facade_does_not_execute_split_concerns(self):
        facade = source("core/HarnessController.java")
        for execution in ("DshAuthSession.exchange", "LanProxyService.",
                          "NativeConfigurationReset.reset", "BackupManager.recoverInterrupted",
                          "PluginActivationHooks.", "lifecycle.beginStart", "lifecycle.beginStop"):
            self.assertNotIn(execution, facade)
        self.assertIn("new WebLifecycleController(this, session)", facade)
        self.assertIn("new HostConfigService(this.context, config)", facade)
        self.assertLessEqual(len(facade.splitlines()), 300)

    def test_one_application_owns_the_runtime_ports_and_web_state(self):
        application = source("DshaApp.java")
        self.assertIn("RuntimeHostPorts.Owner", application)
        self.assertIn("new com.deepseekharness.app.runtime.RuntimeHostPorts()", application)
        self.assertIn("new com.deepseekharness.app.core.HarnessSessionState()", application)
        ports = source("runtime/RuntimeHostPorts.java")
        self.assertNotIn("static final RuntimeHostPorts SHARED", ports)
        self.assertNotIn("RuntimeHostPorts shared()", ports)
        self.assertNotIn("static volatile Provider", ports)
        for path in MAIN.rglob("*.java"):
            text = path.read_text(encoding="utf-8")
            self.assertNotIn("RuntimeHostPorts.shared()", text, str(path.relative_to(ROOT)))

    def test_runtime_and_backup_have_no_direct_ui_dependency(self):
        for package in ("runtime", "backup"):
            for path in (MAIN / package).rglob("*.java"):
                text = path.read_text(encoding="utf-8")
                text = re.sub(r"/\*.*?\*/|//[^\n]*", "", text, flags=re.S)
                self.assertNotRegex(text, r"\bcom\.deepseekharness\.app\.ui\.",
                                    str(path.relative_to(ROOT)))

    def test_existing_backup_host_coordination_edges_can_only_decrease(self):
        baseline = json.loads((ROOT / "tools/architecture-backup-core-baseline.json").read_text(encoding="utf-8"))
        self.assertEqual(1, baseline["schema"])
        permitted = {(edge["file"], edge["type"]) for edge in baseline["edges"]}
        actual = set()
        for path in (MAIN / "backup").rglob("*.java"):
            text = re.sub(r"/\*.*?\*/|//[^\n]*", "", path.read_text(encoding="utf-8"), flags=re.S)
            for dependency in re.findall(r"\bcom\.deepseekharness\.app\.core\.[A-Z]\w*", text):
                actual.add((path.relative_to(ROOT).as_posix(), dependency))
        self.assertFalse(actual - permitted, "New backup/core edge requires an explicit host port: " + repr(sorted(actual - permitted)))

    def test_ui_has_no_access_to_package_private_web_collaborators(self):
        collaborators = ("WebLifecycleController", "WebProcessSession", "LanAuthBridge",
                         "HostConfigService", "StartupEnvironmentActions")
        for name in collaborators:
            self.assertIn("final class " + name, source("core/" + name + ".java"))
            self.assertNotIn("public final class " + name, source("core/" + name + ".java"))
        for path in (MAIN / "ui").rglob("*.java"):
            text = path.read_text(encoding="utf-8")
            for name in collaborators:
                self.assertNotIn("core." + name, text, str(path.relative_to(ROOT)))

    def test_service_reference_is_application_owned_and_public_static_activity_is_absent(self):
        service = source("HarnessService.java")
        self.assertNotRegex(service, r"static\s+volatile\s+HarnessService")
        self.assertIn("harnessService().attach(this)", service)
        self.assertIn("harnessService().release(this)", service)
        for path in MAIN.rglob("*.java"):
            self.assertNotRegex(path.read_text(encoding="utf-8"),
                                r"public\s+static\s+volatile\s+\w*Activity\b", str(path))

    def test_stop_algorithms_keep_typed_facts_and_render_only_through_host_display_ports(self):
        for path in ("runtime/WebProcessManager.java", "runtime/WebRecordedStop.java",
                     "util/WebStopDiagnostic.java", "util/WebStopException.java"):
            self.assertNotIn("UiText", source(path), path)
        self.assertIn("List<WebStopDiagnostic>", source("core/WebStopCoordinator.java"))
        self.assertIn("stopGuestFacts()", source("core/WebLifecycleController.java"))
        self.assertIn("forceStopResult(forcedCandidate)", source("core/WebLifecycleController.java"))
        self.assertIn("WebStopText.render(diagnostic)", source("DshaApp.java"))

    def test_virtual_screen_owner_has_one_lazy_factory_and_no_mutable_static_state(self):
        manager = source("vscreen/VirtualScreenManager.java")
        application = source("DshaApp.java")
        compact = re.sub(r"\s+", "", application)
        self.assertRegex(compact, r"virtualScreenOwner\.get\(\(\)->new"
                         r"(?:com\.deepseekharness\.app\.vscreen\.)?VirtualScreenManager\(this\)\)")
        self.assertRegex(manager, r"public\s+VirtualScreenManager\s*\(\s*Context\s+\w+\s*\)")
        locator = re.search(r"public\s+static\s+VirtualScreenManager\s+from\s*"
                            r"\(\s*Context\s+\w+\s*\)\s*\{([^{}]*)\}", manager)
        self.assertIsNotNone(locator, "The locator must delegate to the context's Application owner")
        self.assertRegex(locator.group(1), r"return\s+(?:com\.deepseekharness\.app\.)?DshaApp\.from\(\w+\)"
                         r"\.virtualScreenManager\(\)\s*;")
        self.assertIn('throw new IllegalStateException("DSHA_APPLICATION_SCOPE_UNAVAILABLE")', application)
        for name in ("VirtualScreenManager", "VirtualScreenPreviews", "VirtualScreenForeground",
                     "VirtualScreenOverlayController", "VirtualScreenAccessibility", "VirtualScreenInput"):
            text = source("vscreen/" + name + ".java")
            self.assertEqual([], mutable_static_fields(text), name)
            self.assertNotIn("DshaApp.current()", text, name)
        for path in MAIN.rglob("*.java"):
            constructors = re.findall(r"\bnew\s+(?:[\w$.]+\.)?VirtualScreenManager\s*\(",
                                      without_comments(path.read_text(encoding="utf-8")))
            self.assertEqual(1 if path.name == "DshaApp.java" else 0, len(constructors), str(path))

    def test_portable_settings_listener_and_writer_belong_to_one_application(self):
        settings = source("data/PortableSettings.java")
        application = source("DshaApp.java")
        compact = re.sub(r"\s+", "", application)
        self.assertRegex(application, r"(?:com\.deepseekharness\.app\.data\.)?PortableSettings\.Owner")
        self.assertRegex(compact, r"portableSettingsOwner\.get\("
                         r"(?:com\.deepseekharness\.app\.data\.)?PortableSettings::new\)")
        self.assertEqual([], mutable_static_fields(settings))
        self.assertIn("application instanceof Owner", settings)
        self.assertIn('throw new IllegalStateException("PORTABLE_SETTINGS_OWNER_UNAVAILABLE")', settings)
        self.assertIn('throw new IllegalStateException("PORTABLE_SETTINGS_UNAVAILABLE")', settings)
        self.assertNotIn("DshaApp.current()", settings)
        for path in MAIN.rglob("*.java"):
            text = without_comments(path.read_text(encoding="utf-8"))
            constructors = re.findall(r"\bnew\s+(?:[\w$.]+\.)?PortableSettings\s*\(", text)
            references = re.findall(r"\bPortableSettings\s*::\s*new\b", text)
            self.assertEqual(0, len(constructors), str(path))
            self.assertEqual(1 if path.name == "DshaApp.java" else 0, len(references), str(path))


if __name__ == "__main__":
    unittest.main()
