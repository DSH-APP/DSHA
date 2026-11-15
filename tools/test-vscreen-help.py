#!/usr/bin/env python3
"""Check the live virtual-screen help examples and all generated UI languages.

This is an offline host check. It compiles the actual bridge request parser and
generated UI dictionaries, but does not contact a device or build an APK.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

from ui_language_catalog import LANGUAGES, load_catalog

ROOT = Path(__file__).resolve().parents[1]
HELP_ID = 'x1970977'
JAVA = ROOT / 'app/src/main/java/com/deepseekharness/app'
EXAMPLE = re.compile(r'^(/app/vscreen/\S+)', re.MULTILINE)


PROBE = r'''
import com.deepseekharness.app.util.HttpProtocol;
import com.deepseekharness.app.util.UiText;
import com.deepseekharness.app.util.VscreenBridgeRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class VscreenHelpProbe {
  private static final String CORE = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
  private static void check(boolean condition, String description) {
    if (!condition) throw new AssertionError(description);
  }
  private static void rejected(String path, String query, String code) throws Exception {
    try {
      VscreenBridgeRequest.query(path, query);
      throw new AssertionError("accepted " + path + " " + query);
    } catch (HttpProtocol.Failure e) {
      check(e.getMessage().equals(code), e.getMessage());
    }
  }
  public static void main(String[] args) throws Exception {
    Path directory = Path.of(args[0]);
    String source = Files.readString(directory.resolve("zh.txt"));
    for (String language : args[1].split(",")) {
      UiText.setLanguage(language);
      String actual = UiText.format(source, "probe-protocol");
      String expected = String.format(Locale.ROOT,
          Files.readString(directory.resolve(language + ".txt")), "probe-protocol");
      check(actual.equals(expected), "wrong rendered help for " + language);
    }
    for (String example : Files.readAllLines(directory.resolve("examples.txt"))) {
      String input = example.replace("$CORE", CORE).replace("$GEN", "gen-1")
          .replace("$FRAME", "42").replace("$NODE", "node-1");
      String[] parts = input.split("\\?", 2);
      VscreenBridgeRequest request = VscreenBridgeRequest.query(parts[0],
          parts.length == 1 ? "" : parts[1]);
      if (request.requiresExistingCore() || parts[0].equals("/app/vscreen/close"))
        check(CORE.equals(request.parameters.get("coreInstance")), "missing Core: " + example);
      if (parts[0].equals("/app/vscreen/node"))
        check(!request.parameters.containsKey("frameSeq"), "node example requires a frame");
      if (parts[0].equals("/app/vscreen/launch"))
        check(!request.parameters.containsKey("generation"), "launch example has generation");
    }
    rejected("/app/vscreen/launch", "coreInstance=" + CORE
        + "&package=com.example.app&generation=gen-1", "UNKNOWN_ARGUMENT");
    rejected("/app/vscreen/tap", "coreInstance=" + CORE
        + "&generation=gen-1&x=10&y=20", "INVALID_frameSeq");
    VscreenBridgeRequest.query("/app/vscreen/node", "coreInstance=" + CORE
        + "&generation=gen-1&nodeId=node-1&action=click");
    System.out.println("PASS actual Java help rendering and request examples");
  }
}
'''


class VscreenHelpContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.messages, cls.catalogs = load_catalog(ROOT)
        cls.message = next(item for item in cls.messages if item['id'] == HELP_ID)

    def test_all_languages_share_the_valid_endpoint_examples(self):
        expected = [
            '/app/vscreen/create?orientation=portrait',
            '/app/vscreen/status',
            '/app/vscreen/launch?coreInstance=$CORE&package=com.example.app',
            '/app/vscreen/see?coreInstance=$CORE',
            '/app/vscreen/tap?coreInstance=$CORE&generation=$GEN&frameSeq=$FRAME&x=10&y=20',
            '/app/vscreen/tree?coreInstance=$CORE',
            '/app/vscreen/node?coreInstance=$CORE&generation=$GEN&nodeId=$NODE&action=click',
            '/app/vscreen/editor?coreInstance=$CORE&generation=$GEN',
            '/app/vscreen/close?coreInstance=$CORE',
        ]
        for tag in LANGUAGES:
            with self.subTest(language=tag):
                value = self.catalogs[tag][HELP_ID]
                self.assertEqual(expected, EXAMPLE.findall(value))
                for identity in ('$CORE', '$GEN', '$FRAME', '$NODE', 'observationId',
                                 'anchorId', 'editorId', 'anchored-click'):
                    self.assertIn(identity, value)

    def test_catalog_key_is_the_actual_help_source(self):
        source = (JAVA / 'HttpShellService.java').read_text(encoding='utf-8')
        self.assertIn(json.dumps(self.message['zh'], ensure_ascii=False), source)
        self.assertIn('this endpoint does not accept generation', self.message['en'])
        self.assertIn('Semantic node action; frameSeq is not required', self.message['en'])

    def test_generated_languages_and_examples_use_production_parser(self):
        lock = json.loads((ROOT / 'tools/backup-dependencies.lock.json').read_text(encoding='utf-8'))
        gson_lock = next(item for item in lock['dependencies'] if item['name'] == 'gson')
        cache = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.gradle')))
        jar_dir = (cache / 'caches/modules-2/files-2.1' / gson_lock['group'] /
                   gson_lock['name'] / gson_lock['version'])
        jars = list(jar_dir.glob('**/gson-' + gson_lock['version'] + '.jar'))
        jars = [jar for jar in jars if hashlib.sha256(jar.read_bytes()).hexdigest() == gson_lock['sha256']]
        self.assertTrue(jars, 'The locked Gson jar must be present in the local Gradle cache')
        jdk = Path(os.environ['JAVA_HOME']) / 'bin'
        extension = '.exe' if os.name == 'nt' else ''
        with tempfile.TemporaryDirectory(prefix='dsha-vscreen-help-') as directory:
            work = Path(directory)
            generated = work / 'generated'
            subprocess.run([os.sys.executable, str(ROOT / 'tools/prepare-ui-languages.py'),
                            '--output', str(generated)], check=True, timeout=30)
            for tag in LANGUAGES:
                (work / (tag + '.txt')).write_bytes(self.catalogs[tag][HELP_ID].encode('utf-8'))
            (work / 'examples.txt').write_text('\n'.join(EXAMPLE.findall(self.message['en'])),
                                               encoding='utf-8')
            (work / 'VscreenHelpProbe.java').write_text(PROBE, encoding='utf-8')
            sources = [JAVA / name for name in [
                'util/HttpProtocol.java', 'util/Ids.java', 'util/VirtualScreenRoutes.java',
                'util/VscreenBridgeRequest.java', 'util/UiText.java',
                'util/UiLanguagePreference.java', 'util/SystemLanguage.java',
                'backup/BackupJson.java', 'backup/BackupLimits.java',
            ]]
            sources += list(generated.rglob('*.java')) + [work / 'VscreenHelpProbe.java']
            subprocess.run([str(jdk / ('javac' + extension)), '-encoding', 'UTF-8',
                            '-cp', str(jars[0]), '-d', str(work), *map(str, sources)],
                           check=True, timeout=90)
            subprocess.run([str(jdk / ('java' + extension)), '-Dfile.encoding=UTF-8',
                            '-cp', str(work) + os.pathsep + str(jars[0]), 'VscreenHelpProbe',
                            str(work), ','.join(LANGUAGES)], check=True, timeout=30)


if __name__ == '__main__':
    unittest.main()
