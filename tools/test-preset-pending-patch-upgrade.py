#!/usr/bin/env python3
"""Pinned preset VIEW_V1 upgrades through the real production Java patch chain.

The old recipe is a byte-frozen source fixture, not an extracted device module.
Only UiText's Android translation is stubbed. No APK/device/profile is touched.
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

OLD_SHA256 = '997570271fb7b48c188ad6e93a6965710ff99487f2076e37de11bfef8210fce9'
OLD_MARKERS = ['DSHA_PRESET_PENDING_V2', 'DSHA_PRESET_POST_WRITE_READ_V1', 'DSHA_PRESET_PENDING_VIEW_V1']
CURRENT_MARKERS = ['DSHA_PRESET_PENDING_V2', 'DSHA_PRESET_POST_WRITE_READ_V1', 'DSHA_PRESET_PENDING_VIEW_V2']
BOUNDARY = '\n\t\t\t\t\tstate.error === null ? null : (0, react_jsx_runtime.jsx)("p", {'

JAVA = r'''
import com.deepseekharness.app.util.ManagedPatchChain;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class PresetPendingUpgradeFixture {
  private static void require(boolean value, String reason) {
    if (!value) throw new AssertionError(reason);
  }
  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    var patches = new ArrayList<ManagedPatchChain.Patch>();
    for (int index = 0; index < Integer.parseInt(Files.readString(root.resolve("count.txt"))); index++)
      patches.add(new ManagedPatchChain.Patch(Files.readString(root.resolve("before-" + index + ".txt")),
          Files.readString(root.resolve("after-" + index + ".txt"))));
    String canonical = Files.readString(root.resolve("canonical.js"));
    String old = Files.readString(root.resolve("old-v1.js"));
    String expected = Files.readString(root.resolve("current-v2.js"));
    var recipe = new ManagedPatchChain.Recipe("preset", patches,
        Files.readAllLines(root.resolve("current-markers.txt")));
    var staleMarkers = new ManagedPatchChain.Recipe("preset", patches,
        Files.readAllLines(root.resolve("old-markers.txt")));
    String skipped = ManagedPatchChain.apply(old, List.of(staleMarkers), () -> {
      throw new AssertionError("stale markers negative must reproduce the early skip");
    });
    require(skipped.equals(old) && !skipped.equals(expected), "old VIEW_V1 skip negative");
    String fresh = ManagedPatchChain.apply(canonical, List.of(recipe), () -> {
      throw new AssertionError("fresh canonical must not need restoring");
    });
    require(fresh.equals(expected), "fresh canonical differs from complete current V2");
    AtomicInteger restores = new AtomicInteger();
    String upgraded = ManagedPatchChain.apply(old, List.of(recipe), () -> {
      restores.incrementAndGet(); return canonical;
    });
    Files.writeString(root.resolve("actual-upgrade.js"), upgraded);
    System.out.println("canonicalRestores=" + restores.get() + " expectedBytes=" + upgraded.equals(expected)
        + " oldV1Retained=" + upgraded.contains("DSHA_PRESET_PENDING_VIEW_V1"));
    require(upgraded.equals(expected), "old V1 must become exact canonical V2 without the old extra paragraph");
    require(restores.get() == 1, "old V1 requires one complete canonical restoration");
    require(!upgraded.contains("DSHA_PRESET_PENDING_VIEW_V1"), "old standalone pending paragraph survived");
    require(ManagedPatchChain.apply(upgraded, List.of(recipe), () -> {
      throw new AssertionError("current V2 must not restore on reapplication");
    }).equals(expected), "current V2 must remain idempotent");
    System.out.println("PASS Java: fresh / actual V1 upgrade / V2 idempotence / stale-marker negative");
  }
}
'''


def sha(data):
    return hashlib.sha256(data).hexdigest()


def apply_recipe(data, recipe):
    text = data.decode('utf-8')
    for patch in recipe['patches']:
        assert text.count(patch['before']) == 1, 'historical/canonical anchor must be unique'
        text = text.replace(patch['before'], patch['after'])
    return text.encode('utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--historical', type=Path, help='exact old VIEW_V1 fixture (SHA pinned)')
    parser.add_argument('--jdk', type=Path)
    parser.add_argument('--anchor-negative', action='store_true', help='restore only the faulty short VIEW_V2 anchor; must fail')
    args = parser.parse_args()
    if sys.flags.optimize:
        raise RuntimeError('TEST_ASSERTIONS_REQUIRE_UNOPTIMIZED_PYTHON')
    root = args.source_root.resolve()
    assets = root / 'app/src/main/assets'
    historical_bytes = (args.historical or root / 'tools/fixtures/agent-preset-pending-view-v1-patch.json').read_bytes()
    assert sha(historical_bytes) == OLD_SHA256, 'historical VIEW_V1 fixture identity'
    old = json.loads(historical_bytes)
    current_bytes = (assets / 'agent-preset-pending-patch.json').read_bytes()
    current = json.loads(current_bytes)
    selected = json.loads((root / 'app/build/test-runtimes/current.json').read_text(encoding='utf-8'))
    canonical = (Path(selected['raw']) / 'node_modules' / current['module']).read_bytes()
    lock = json.loads((root / 'tools/dsh-runtime/package-lock.json').read_text(encoding='utf-8'))
    package = 'node_modules/' + current['module'].split('/lib/')[0]
    assert old['module'] == current['module']
    assert old['sourceSha256'] == current['sourceSha256'] == sha(canonical)
    assert old['packageIntegrity'] == current['packageIntegrity'] == lock['packages'][package]['integrity']
    business = lambda recipe: [patch for patch in recipe['patches'] if 'DSHA_PRESET_PENDING_VIEW_' not in patch['after']]
    assert business(old) == business(current), 'layout fix must not change save/read/sync patches'
    views = [patch for patch in current['patches'] if 'DSHA_PRESET_PENDING_VIEW_V2' in patch['after']]
    assert len(views) == 1
    view = views[0]
    assert view['before'].endswith(BOUNDARY) and view['after'].endswith(BOUNDARY), 'V1 extra paragraph must invalidate the whole contiguous anchor'
    if args.anchor_negative:
        view['before'] = view['before'][:-len(BOUNDARY)]
        view['after'] = view['after'][:-len(BOUNDARY)]
    previous = apply_recipe(canonical, old)
    expected = apply_recipe(canonical, current)
    assert previous != expected
    registry = json.loads((assets / 'runtime-patches.json').read_text(encoding='utf-8'))
    rows = [row for row in registry['active'] if row['asset'] == 'agent-preset-pending-patch.json']
    assert len(rows) == 1 and rows[0]['markers'] == CURRENT_MARKERS, 'registry must require the current VIEW_V2 marker'
    sys.path.insert(0, str(root / 'tools'))
    import runtime_stable_patches
    chains, _ = runtime_stable_patches.load_chains(assets, current['dshVersion'])
    chain = chains[current['module']]
    assert len(chain) == 1 and chain[0][0] == 'agent-preset-pending-patch.json'
    assert chain[0][2] == CURRENT_MARKERS
    assert runtime_stable_patches.apply(canonical, chain) == expected
    assert runtime_stable_patches.apply(expected, chain) == expected
    try:
        runtime_stable_patches.apply(previous, chain)
    except ValueError as failure:
        assert 'STABLE_PATCH_ANCHOR' in str(failure)
    else:
        raise AssertionError('archive patcher silently accepted the old extra paragraph')
    build = root / 'app/build'
    output = Path(tempfile.mkdtemp(prefix='preset-pending-upgrade-', dir=build))
    for name, data in [('canonical.js', canonical), ('old-v1.js', previous), ('current-v2.js', expected)]:
        (output / name).write_bytes(data)
    (output / 'count.txt').write_bytes(str(len(current['patches'])).encode('utf-8'))
    for index, patch in enumerate(current['patches']):
        for side in ['before', 'after']:
            (output / (side + '-' + str(index) + '.txt')).write_bytes(patch[side].encode('utf-8'))
    for name, markers in [('current', CURRENT_MARKERS), ('old', OLD_MARKERS)]:
        (output / (name + '-markers.txt')).write_bytes(('\n'.join(markers) + '\n').encode('utf-8'))
    fixture = output / 'PresetPendingUpgradeFixture.java'
    fixture.write_bytes(JAVA.encode('utf-8'))
    classes = output / 'classes'
    classes.mkdir()
    jdk = args.jdk or (Path(os.environ['JAVA_HOME']) if os.environ.get('JAVA_HOME') else None)
    suffix = '.exe' if os.name == 'nt' else ''
    javac = str(jdk / 'bin' / ('javac' + suffix)) if jdk else shutil.which('javac')
    java = str(jdk / 'bin' / ('java' + suffix)) if jdk else shutil.which('java')
    if not javac or not java:
        raise RuntimeError('JDK17_REQUIRED')
    production = root / 'app/src/main/java/com/deepseekharness/app/util'
    stub = root / 'tools/fixtures/documents-parent/com/deepseekharness/app/util/UiText.java'
    compiled = subprocess.run([javac, '-encoding', 'UTF-8', '--release', '17', '-d', str(classes),
                               str(production / 'ManagedPatchChain.java'), str(production / 'ExactTextPatch.java'),
                               str(stub), str(fixture)], capture_output=True, timeout=60)
    (output / 'compile.log').write_bytes(compiled.stdout + compiled.stderr)
    assert compiled.returncode == 0, 'Java compile failed: ' + str(output)
    ran = subprocess.run([java, '-cp', str(classes), 'PresetPendingUpgradeFixture', str(output)], capture_output=True, timeout=30)
    (output / 'java.log').write_bytes(ran.stdout + ran.stderr)
    result = dict(status='PASS' if ran.returncode == 0 else 'FAIL', negative=args.anchor_negative,
                  historicalRecipeSha256=OLD_SHA256, currentRecipeSha256=sha(current_bytes),
                  canonicalSha256=sha(canonical), oldV1ModuleSha256=sha(previous), currentV2ModuleSha256=sha(expected),
                  productionAlgorithmSha256={name: sha((production / name).read_bytes()) for name in ['ManagedPatchChain.java', 'ExactTextPatch.java']},
                  registrySha256=sha((assets / 'runtime-patches.json').read_bytes()), javaCases=4, archiveCases=3,
                  deviceValidation=False, exitCode=ran.returncode, output=str(output))
    (output / 'result.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result))
    if ran.returncode:
        print((ran.stdout + ran.stderr).decode('utf-8', errors='replace'), file=sys.stderr)
        raise SystemExit(ran.returncode)


if __name__ == '__main__':
    main()
