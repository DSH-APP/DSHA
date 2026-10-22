#!/usr/bin/env python3
"""Preserved actual session V3 -> V4 upgrade through the production patch chain.

V2 coverage is a synthetic marker-only negative control, not an old V2 release.
Only private host fixtures are written; no live session/profile/device is read.
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
HISTORY = ROOT / 'tools/fixtures/session-interaction-v3'
MODULE = '@deepseek-ai/dsh-client-ui-workspace/lib/client.js'
CANONICAL_SHA = '52765aacb6c7f96327da5d8709d8cbb30dd2957d4744a42da04c18e07fe264e5'
HISTORICAL_MANAGED_SHA = '3cf9260587e730f4624dc2777e78e10fe9032522eb226df3a0197bcf3d2a3232'
HISTORICAL_SHAS = {
    'session-interaction-patch.json': '37f53270df43f737ec7c1d38d55cbf7e30e0cd8f21418ca8831b1582da590b37',
    'web-integration/session-interaction.js': 'df67935e0ab81e23b494119cb7b4c97e2263cd6d01a6cf9069ca6004e6e3a790',
}
CURRENT_MARKERS = ['DSHA_SESSION_INTERACTION_V4', 'DSHA_SESSION_SINGLE_ACTIVATE_V4', 'dsha-session-open']
SYNTHETIC_V2_MARKERS = ['DSHA_SESSION_INTERACTION_V2', 'dsha-session-open']

JAVA = r'''
import com.deepseekharness.app.util.ManagedPatchChain;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class SessionPatchUpgradeFixture {
  private static void require(boolean okay, String why) {
    if (!okay) throw new AssertionError(why);
  }

  private static Map<String, String> tree(Path root) throws Exception {
    var result = new TreeMap<String, String>();
    try (var paths = Files.walk(root)) {
      for (Path path : paths.filter(Files::isRegularFile).toList())
        result.put(root.relativize(path).toString(), HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
    }
    return result;
  }

  private static void mustFailWithoutPublication(Path module, String old,
      ManagedPatchChain.Recipe recipe, ManagedPatchChain.Canonical canonical,
      String expectedError) throws Exception {
    Files.writeString(module, old);
    try {
      String next = ManagedPatchChain.apply(Files.readString(module), List.of(recipe), canonical);
      // This mirrors an all-or-nothing caller: publication follows a successful
      // return only. A failure must never return old/partial source as success.
      Files.writeString(module, next);
      throw new AssertionError("missing/bad canonical was accepted: " + module);
    } catch (IOException failure) {
      require(failure.getMessage().contains(expectedError), "wrong canonical failure: " + failure);
    }
    require(Files.readString(module).equals(old), "failure published a partial module");
  }

  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    var parts = new ArrayList<ManagedPatchChain.Patch>();
    int count = Integer.parseInt(Files.readString(root.resolve("count.txt")));
    for (int index = 0; index < count; index++)
      parts.add(new ManagedPatchChain.Patch(Files.readString(root.resolve("before-" + index + ".txt")),
          Files.readString(root.resolve("after-" + index + ".txt"))));
    String canonical = Files.readString(root.resolve("canonical.js"));
    String actualV3 = Files.readString(root.resolve("actual-v3.js"));
    String syntheticV2 = Files.readString(root.resolve("synthetic-v2-marker-only.js"));
    String partialV4 = Files.readString(root.resolve("synthetic-partial-v4-marker.js"));
    String expected = Files.readString(root.resolve("current-v4.js"));
    var current = new ManagedPatchChain.Recipe("session-interaction", parts,
        Files.readAllLines(root.resolve("current-markers.txt")));
    var weakV2 = new ManagedPatchChain.Recipe("synthetic-weak-v2-marker-control", parts,
        Files.readAllLines(root.resolve("synthetic-v2-markers.txt")));
    var businessBefore = tree(root.resolve("business"));
    AtomicInteger reads = new AtomicInteger();

    String skipped = ManagedPatchChain.apply(syntheticV2, List.of(weakV2), () -> {
      reads.incrementAndGet(); return canonical;
    });
    require(skipped.equals(syntheticV2) && !skipped.equals(expected) && reads.get() == 0,
        "synthetic weak-marker negative control must expose an old skip");

    for (String old : List.of(actualV3, syntheticV2, partialV4)) {
      reads.set(0);
      String upgraded = ManagedPatchChain.apply(old, List.of(current), () -> {
        reads.incrementAndGet(); return canonical;
      });
      require(upgraded.equals(expected), "upgrade must replay the complete V4 from canonical");
      require(reads.get() == 1, "old/partial marker must not skip the one canonical repair");
    }

    String repeated = ManagedPatchChain.apply(expected, List.of(current), () -> {
      throw new AssertionError("complete V4 must be idempotent without a canonical read");
    });
    require(repeated.equals(expected), "second application changed V4");

    mustFailWithoutPublication(root.resolve("missing-v3.js"), actualV3, current,
        () -> null, "PATCH_CANONICAL_MISSING");
    mustFailWithoutPublication(root.resolve("missing-synthetic-v2.js"), syntheticV2, current,
        () -> null, "PATCH_CANONICAL_MISSING");
    mustFailWithoutPublication(root.resolve("bad-canonical.js"), actualV3, current,
        () -> "not the locked upstream module", "PATCH_CHAIN_MISMATCH");
    mustFailWithoutPublication(root.resolve("unreadable-canonical.js"), actualV3, current,
        () -> { throw new IOException("FIXTURE_CANONICAL_UNREADABLE"); }, "FIXTURE_CANONICAL_UNREADABLE");
    require(tree(root.resolve("business")).equals(businessBefore),
        "current-session/business sentinel bytes or member set changed");
    require(Files.readString(root.resolve("actual-v3.js")).equals(actualV3)
        && Files.readString(root.resolve("canonical.js")).equals(canonical),
        "read-only source inputs changed");
    System.out.println("PASS Java: 10 cases; actual V3 canonical replay, synthetic marker controls, V4 idempotence, fail-closed publication and unchanged private business sentinels");
  }
}
'''


def sha(data):
    return hashlib.sha256(data).hexdigest()


def apply_recipe(data, recipe, assets):
    text = data.decode('utf-8')
    for patch in recipe['patches']:
        before, after = patch['before'], patch['after']
        if patch.get('prependAsset'):
            after = (assets / patch['prependAsset']).read_text(encoding='utf-8') + '\n' + after
        if text.count(before) != 1:
            raise AssertionError('historical/canonical anchor is not unique')
        text = text.replace(before, after)
    return text.encode('utf-8')


def reject_old(data, chain):
    try:
        runtime_stable_patches.apply(data, chain)
    except ValueError as failure:
        assert 'STABLE_PATCH_ANCHOR' in str(failure)
    else:
        raise AssertionError('archive preparer accepted old/partial source as current V4')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--module', type=Path, help='exact locked original rc.2 workspace lib/client.js')
    parser.add_argument('--jdk', type=Path, help='JDK17 home; defaults to JAVA_HOME/PATH')
    parser.add_argument('--output', type=Path, help='parent for a new private numbered evidence directory')
    args = parser.parse_args()
    if sys.flags.optimize:
        raise RuntimeError('TEST_ASSERTIONS_REQUIRE_UNOPTIMIZED_PYTHON')
    module = args.module
    if module is None:
        selected = json.loads((ROOT / 'app/build/test-runtimes/current.json').read_text(encoding='utf-8'))
        module = Path(selected['raw']) / 'node_modules' / MODULE
    provenance = json.loads((HISTORY / 'provenance.json').read_text(encoding='utf-8'))
    assert provenance['kind'] == 'actual-preserved-recipe-and-helper'
    assert provenance['module'] == MODULE and provenance['canonicalSha256'] == CANONICAL_SHA
    assert provenance['historicalManagedModuleSha256'] == HISTORICAL_MANAGED_SHA
    assert provenance['files'] == HISTORICAL_SHAS and provenance['actualV2FixtureAvailable'] is False
    for name, expected_sha in HISTORICAL_SHAS.items():
        assert sha((HISTORY / name).read_bytes()) == expected_sha, 'historical V3 fixture identity: ' + name
    original_input = module.read_bytes()
    assert sha(original_input) == CANONICAL_SHA, 'unknown canonical upstream bytes'
    historical = json.loads((HISTORY / 'session-interaction-patch.json').read_text(encoding='utf-8'))
    current = json.loads((ASSETS / 'session-interaction-patch.json').read_text(encoding='utf-8'))
    assert historical['dshVersion'] == current['dshVersion'] == '0.2.1-rc.2'
    actual_v3 = apply_recipe(original_input, historical, HISTORY)
    assert sha(actual_v3) == HISTORICAL_MANAGED_SHA, 'derived V3 must match the independently retained managed baseline'
    expected = apply_recipe(original_input, current, ASSETS)
    assert actual_v3 != expected and b'DSHA_SESSION_INTERACTION_V3' in actual_v3
    assert b'DSHA_SESSION_SINGLE_ACTIVATE_V4' not in actual_v3
    synthetic_v2 = actual_v3.replace(b'DSHA_SESSION_INTERACTION_V3', b'DSHA_SESSION_INTERACTION_V2')
    partial_v4 = actual_v3.replace(b'DSHA_SESSION_INTERACTION_V3', b'DSHA_SESSION_INTERACTION_V4')
    assert all(marker.encode() in expected for marker in CURRENT_MARKERS)
    assert all(marker.encode() in synthetic_v2 for marker in SYNTHETIC_V2_MARKERS)
    for old in [actual_v3, synthetic_v2, partial_v4]:
        assert not all(marker.encode() in old for marker in CURRENT_MARKERS)

    chains, _ = runtime_stable_patches.load_chains(ASSETS, current['dshVersion'])
    chain = chains[MODULE]
    assert len(chain) == 1 and chain[0][0] == 'session-interaction-patch.json'
    assert chain[0][2] == CURRENT_MARKERS, 'legacy markers must not skip the V4 helper/behavior upgrade'
    assert runtime_stable_patches.apply(original_input, chain) == expected
    assert runtime_stable_patches.apply(expected, chain) == expected
    for old in [actual_v3, synthetic_v2, partial_v4]:
        reject_old(old, chain)
    weak = [(chain[0][0], chain[0][1], SYNTHETIC_V2_MARKERS)]
    assert runtime_stable_patches.apply(synthetic_v2, weak) == synthetic_v2 != expected

    build = args.output or ROOT / 'app/build'
    build.mkdir(parents=True, exist_ok=True)
    output = Path(tempfile.mkdtemp(prefix='session-patch-upgrade-', dir=build))
    for name, data in [('canonical.js', original_input), ('actual-v3.js', actual_v3),
                       ('synthetic-v2-marker-only.js', synthetic_v2), ('synthetic-partial-v4-marker.js', partial_v4),
                       ('current-v4.js', expected)]:
        (output / name).write_bytes(data)
    (output / 'count.txt').write_text(str(len(chain[0][1])), encoding='utf-8')
    for index, (before, after) in enumerate(chain[0][1]):
        for side, text in [('before', before), ('after', after)]:
            (output / (side + '-' + str(index) + '.txt')).write_bytes(text.encode('utf-8'))
    for name, markers in [('current', CURRENT_MARKERS), ('synthetic-v2', SYNTHETIC_V2_MARKERS)]:
        (output / (name + '-markers.txt')).write_bytes(('\n'.join(markers) + '\n').encode('utf-8'))
    business = output / 'business'
    for relative, content in {
        'sessions/current.json': '{"id":"fixture-current","messages":["unchanged"]}\n',
        'sessions/records.jsonl': '{"seq":1,"type":"fixture-business-record"}\n',
        'profile/package.json': '{"name":"fixture-profile","dependencies":{"fixture-plugin":"1.0.0"}}\n',
        'workspace/manual.txt': 'User-created fixture data must remain byte-identical.\n',
    }.items():
        target = business / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content.encode('utf-8'))
    before_business = {str(p.relative_to(business)): sha(p.read_bytes()) for p in business.rglob('*') if p.is_file()}
    fixture = output / 'SessionPatchUpgradeFixture.java'
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
    ui_text = ROOT / 'tools/fixtures/documents-parent/com/deepseekharness/app/util/UiText.java'
    compiled = subprocess.run([javac, '-encoding', 'UTF-8', '--release', '17', '-d', str(classes),
                               str(source / 'ManagedPatchChain.java'), str(source / 'ExactTextPatch.java'),
                               str(ui_text), str(fixture)], capture_output=True, timeout=60)
    (output / 'compile.log').write_bytes(compiled.stdout + compiled.stderr)
    assert compiled.returncode == 0, 'compile failed: ' + str(output / 'compile.log')
    ran = subprocess.run([java, '-cp', str(classes), 'SessionPatchUpgradeFixture', str(output)],
                         capture_output=True, timeout=30)
    (output / 'java.log').write_bytes(ran.stdout + ran.stderr)
    assert ran.returncode == 0, 'Java regression failed: ' + str(output / 'java.log')
    assert before_business == {str(p.relative_to(business)): sha(p.read_bytes()) for p in business.rglob('*') if p.is_file()}
    assert module.read_bytes() == original_input, 'canonical source was modified'
    result = dict(status='PASS', historicalKind=provenance['kind'], historicalV3=HISTORICAL_SHAS,
                  actualHistoricalV2Tested=False, syntheticV2MarkerNegativeControl=True,
                  canonicalSha256=sha(original_input), actualV3ModuleSha256=sha(actual_v3), currentV4ModuleSha256=sha(expected),
                  recipeSha256=sha((ASSETS / 'session-interaction-patch.json').read_bytes()),
                  helperSha256=sha((ASSETS / 'web-integration/session-interaction.js').read_bytes()),
                  registrySha256=sha((ASSETS / 'runtime-patches.json').read_bytes()),
                  javaCases=10, archiveCases=6, privateBusinessSentinelFiles=len(before_business),
                  liveSessionDataReadOrChanged=False, deviceValidation=False, output=str(output))
    (output / 'result.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
