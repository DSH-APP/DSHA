#!/usr/bin/env python3
"""Real F10 V1 -> current welcome patch, using the production Java patch chain.

The historical recipe is preserved byte-for-byte from the F10 source delivery
and the independently retained F11 runtime baseline. No Android/device state is
read, no live profile is changed, and no APK or runtime archive is generated.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

import runtime_stable_patches

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'
OLD_RECIPE = ROOT / 'tools/fixtures/welcome-notice-v1-patch.json'
OLD_SHA256 = 'de03bfcd64a00718c5576c25505b14e05af5a38b56ecee2d800dfe6e3c66bde0'
OLD_MARKERS = ['DSHA_WELCOME_NOTICE_RC2_V1', 'DSHA_WELCOME_STORE_RC2_V1']
CURRENT_MARKERS = ['DSHA_WELCOME_NOTICE_RC2_V1', 'DSHA_WELCOME_STORE_RC2_V3']

JAVA = r'''
import com.deepseekharness.app.util.ManagedPatchChain;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class WelcomePatchUpgradeFixture {
  private static void require(boolean okay, String why) {
    if (!okay) throw new AssertionError(why);
  }

  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    var parts = new ArrayList<ManagedPatchChain.Patch>();
    int count = Integer.parseInt(Files.readString(root.resolve("count.txt")));
    for (int index = 0; index < count; index++) {
      parts.add(new ManagedPatchChain.Patch(
          Files.readString(root.resolve("before-" + index + ".txt")),
          Files.readString(root.resolve("after-" + index + ".txt"))));
    }
    String canonical = Files.readString(root.resolve("canonical.js"));
    String old = Files.readString(root.resolve("old-v1.js"));
    String expected = Files.readString(root.resolve("current-v3.js"));
    var current = new ManagedPatchChain.Recipe("welcome", parts,
        Files.readAllLines(root.resolve("current-markers.txt")));
    var legacy = new ManagedPatchChain.Recipe("welcome", parts,
        Files.readAllLines(root.resolve("old-markers.txt")));
    AtomicInteger restores = new AtomicInteger();
    String incorrectlySkipped = ManagedPatchChain.apply(old, List.of(legacy), () -> {
      restores.incrementAndGet();
      return canonical;
    });
    require(incorrectlySkipped.equals(old) && !incorrectlySkipped.equals(expected),
        "negative control: legacy markers must reproduce the old V1 skip");
    require(restores.get() == 0, "old skip bypassed the canonical repair");
    String upgraded = ManagedPatchChain.apply(old, List.of(current), () -> {
      restores.incrementAndGet();
      return canonical;
    });
    require(upgraded.equals(expected), "actual old V1 must become the complete current V3");
    require(restores.get() == 1, "one signed canonical repair is required for old V1");
    String again = ManagedPatchChain.apply(upgraded, List.of(current), () -> {
      throw new AssertionError("current V3 must be idempotent without a canonical read");
    });
    require(again.equals(expected), "current V3 changed on a second application");
    System.out.println("PASS Java: legacy negative control, actual V1 canonical upgrade, V3 idempotence");
  }
}
'''


def sha(data):
    return hashlib.sha256(data).hexdigest()


def apply_recipe(data, recipe):
    text = data.decode('utf-8')
    for patch in recipe['patches']:
        if text.count(patch['before']) != 1:
            raise AssertionError('historical/canonical anchor is not unique')
        text = text.replace(patch['before'], patch['after'])
    return text.encode('utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--module', type=Path, help='locked original rc2 lib/client.js')
    parser.add_argument('--jdk', type=Path, help='JDK 17 home (otherwise JAVA_HOME/PATH)')
    args = parser.parse_args()
    if sys.flags.optimize:
        raise RuntimeError('TEST_ASSERTIONS_REQUIRE_UNOPTIMIZED_PYTHON')
    module = args.module
    if module is None:
        selected = json.loads((ROOT / 'app/build/test-runtimes/current.json').read_text(encoding='utf-8'))
        current = json.loads((ASSETS / 'welcome-notice-patch.json').read_text(encoding='utf-8'))
        module = Path(selected['raw']) / 'node_modules' / current['module']
    historical_bytes = OLD_RECIPE.read_bytes()
    assert sha(historical_bytes) == OLD_SHA256, 'historical F10 fixture identity'
    historical = json.loads(historical_bytes)
    current = json.loads((ASSETS / 'welcome-notice-patch.json').read_text(encoding='utf-8'))
    canonical = module.read_bytes()
    assert historical['module'] == current['module']
    assert historical['sourceSha256'] == current['sourceSha256'] == sha(canonical)
    assert historical['packageIntegrity'] == current['packageIntegrity']
    old = apply_recipe(canonical, historical)
    expected = apply_recipe(canonical, current)
    assert old != expected
    assert all(marker.encode() in old for marker in OLD_MARKERS)
    chains, _ = runtime_stable_patches.load_chains(ASSETS, current['dshVersion'])
    chain = chains[current['module']]
    assert len(chain) == 1 and chain[0][0] == 'welcome-notice-patch.json'
    assert chain[0][2] == CURRENT_MARKERS, 'current registry must not use legacy V1 skip markers'
    assert runtime_stable_patches.apply(canonical, chain) == expected
    assert runtime_stable_patches.apply(expected, chain) == expected
    try:
        runtime_stable_patches.apply(old, chain)
    except ValueError as failure:
        assert 'STABLE_PATCH_ANCHOR' in str(failure)
    else:
        raise AssertionError('archive preparer silently accepted old V1 as current V3')

    build = ROOT / 'app/build'
    build.mkdir(exist_ok=True)
    output = Path(tempfile.mkdtemp(prefix='welcome-patch-upgrade-', dir=build))
    for name, data in [('canonical.js', canonical), ('old-v1.js', old), ('current-v3.js', expected)]:
        (output / name).write_bytes(data)
    (output / 'count.txt').write_text(str(len(current['patches'])), encoding='utf-8')
    for index, patch in enumerate(current['patches']):
        for side in ['before', 'after']:
            (output / (side + '-' + str(index) + '.txt')).write_bytes(patch[side].encode('utf-8'))
    for name, markers in [('current', CURRENT_MARKERS), ('old', OLD_MARKERS)]:
        (output / (name + '-markers.txt')).write_bytes(('\n'.join(markers) + '\n').encode('utf-8'))
    fixture = output / 'WelcomePatchUpgradeFixture.java'
    fixture.write_bytes(JAVA.encode('utf-8'))
    classes = output / 'classes'
    classes.mkdir()
    jdk = args.jdk or (Path(os.environ['JAVA_HOME']) if os.environ.get('JAVA_HOME') else None)
    suffix = '.exe' if os.name == 'nt' else ''
    javac = str(jdk / 'bin' / ('javac' + suffix)) if jdk else shutil.which('javac')
    java = str(jdk / 'bin' / ('java' + suffix)) if jdk else shutil.which('java')
    if not javac or not java:
        raise RuntimeError('JDK17_REQUIRED')
    source = ROOT / 'app/src/main/java/com/deepseekharness/app/util'
    # Only the Android translation label is stubbed; both patch algorithms are
    # the current production Java source, not a reimplementation in the test.
    ui_text = ROOT / 'tools/fixtures/documents-parent/com/deepseekharness/app/util/UiText.java'
    compiled = subprocess.run([javac, '-encoding', 'UTF-8', '--release', '17', '-d', str(classes),
                               str(source / 'ManagedPatchChain.java'), str(source / 'ExactTextPatch.java'),
                               str(ui_text), str(fixture)],
                              capture_output=True, timeout=60)
    (output / 'compile.log').write_bytes(compiled.stdout + compiled.stderr)
    assert compiled.returncode == 0, 'compile failed: ' + str(output / 'compile.log')
    ran = subprocess.run([java, '-cp', str(classes), 'WelcomePatchUpgradeFixture', str(output)],
                         capture_output=True, timeout=30)
    (output / 'java.log').write_bytes(ran.stdout + ran.stderr)
    assert ran.returncode == 0, 'Java regression failed: ' + str(output / 'java.log')
    result = dict(status='PASS', historicalRecipeSha256=OLD_SHA256,
                  canonicalSha256=sha(canonical), oldV1ModuleSha256=sha(old), currentV3ModuleSha256=sha(expected),
                  registrySha256=sha((ASSETS / 'runtime-patches.json').read_bytes()),
                  javaCases=3, archiveCases=3, deviceValidation=False, output=str(output))
    (output / 'result.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
