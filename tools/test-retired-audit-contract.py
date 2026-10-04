#!/usr/bin/env python3
"""Historical source-byte and current source-set/manifest structure checks only; no device execution."""
import hashlib
import json
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
DEBUG = ROOT / 'app/src/debug/java/com/deepseekharness/app'


class RetiredAuditContractTest(unittest.TestCase):
    def test_exact_retired_originals_are_preserved_outside_active_source_sets(self):
        tables = ['docs/audits/build154/retired-audit-sources.json',
                  'docs/audits/build154/engineering-retired-sources.json',
                  'docs/audits/build156/retired-test-control-sources.json']
        manifest = ET.parse(ROOT / 'app/src/debug/AndroidManifest.xml').getroot()
        names = {node.attrib['{http://schemas.android.com/apk/res/android}name']
                 for node in manifest.findall('instrumentation')}
        checked = 0
        for table in tables:
            for row in json.loads((ROOT / table).read_text(encoding='utf-8')):
                original = row['path']
                if not (original.startswith('app/src/debug/') or original.startswith('app/src/androidTest/')
                        or original in ('tools/control-functional-audit.py', 'tools/device-backup-audit.init.gradle')):
                    continue
                retained = ROOT / row['retainedSource']
                self.assertEqual(row['sha256'], hashlib.sha256(retained.read_bytes()).hexdigest(), original)
                if original.endswith('Rc13Instrumentation.java'):
                    stub = (ROOT / original).read_text(encoding='utf-8')
                    self.assertIn('"RETIRED"', stub)
                    self.assertIn('finish(Activity.RESULT_CANCELED', stub)
                    self.assertNotIn('PackageInfo', stub)
                else:
                    self.assertFalse((ROOT / original).exists(), original)
                    if '/java/' in original:
                        class_name = original.split('/java/')[1].removesuffix('.java').replace('/', '.')
                        self.assertNotIn(class_name, names)
                checked += 1
        self.assertGreaterEqual(checked, 11)

    def test_active_device_flow_has_no_legacy_readonly_or_confirmation_surrogate(self):
        source = (ROOT / 'app/src/main/assets/adb-shell.py').read_text(encoding='utf-8')
        for marker in ('READONLY_CMDS', 'is_readonly_cmd', 'def request_confirm(', 'class ConfirmationError'):
            self.assertNotIn(marker, source)
        self.assertFalse((ROOT / 'app/src/main/assets/selftest.py').exists())
        for file in DEBUG.rglob('*.java'):
            self.assertNotIn('getDeclaredField("sessions")', file.read_text(encoding='utf-8'), str(file))

    def test_browser_variants_share_real_scenarios_and_keep_single_and_multi_selection(self):
        debug = (DEBUG / 'ui/WebRegressionAudit.java').read_text(encoding='utf-8')
        android = (ROOT / 'app/src/androidTest/java/com/deepseekharness/app/ui/WebInstrumentation.java').read_text(encoding='utf-8')
        shared = (DEBUG / 'ui/WebBrowserAudit.java').read_text(encoding='utf-8')
        for source in (debug, android):
            self.assertIn('extends WebBrowserAudit', source)
            self.assertNotIn('class Fixture', source)
        self.assertIn('return true;', debug)
        self.assertIn('return false;', android)
        for marker in ('controller.isWebStoppedForMaintenance()', 'uploaded.length() == 1',
                       'uploaded.length() == 2', 'verifyUploadSessionBound(uri)',
                       'Activity.RESULT_OK', 'Activity.RESULT_CANCELED', 'new WebBrowserFixture('):
            self.assertIn(marker, shared)
        self.assertEqual(1, shared.count('private void testDownloads()'))

    def test_pdf_module_and_language_restore_read_current_contracts(self):
        pdf = (DEBUG / 'ui/BrowserCompatibilityAudit.java').read_text(encoding='utf-8')
        self.assertIn('asset("pdf-compat-patch.json")', pdf)
        self.assertIn('.getString("module")', pdf)
        self.assertNotIn('data-dsha-agent-preset=header', pdf)
        self.assertLess(pdf.index('if (headerAudit)'), pdf.index('HarnessController c ='))
        self.assertIn('finish(Activity.RESULT_CANCELED, result)', pdf)
        for name in ('LogPanelAudit', 'PluginSortAudit', 'MultiTerminalLanguageAudit', 'LayoutAuditInstrumentation'):
            source = (DEBUG / ('ui/' + name + '.java')).read_text(encoding='utf-8')
            self.assertIn('getUiLanguagePreference()', source)
            self.assertNotIn('= store.getUiLanguage()', source)


if __name__ == '__main__':
    unittest.main(verbosity=2)
