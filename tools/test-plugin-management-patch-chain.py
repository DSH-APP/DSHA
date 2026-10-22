#!/usr/bin/env python3
"""Real Java admission for plugin-management full chains and worker upgrades.

The retained V1 recipe/module is a source fixture, not a phone-data snapshot.
Only Android UiText is stubbed; production patch algorithms are compiled as-is.
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import zipfile

import runtime_stable_patches

ROOT = Path(__file__).resolve().parents[1]
WORKER = 'plugin-bundle-proof-workers-patch.json'
OLD_RECIPE_SHA = '466c61d0fc0d180cd4828fb981a0851cf1721ced023163cf7e17469e1144b681'
OLD_MODULE_SHA = '9f47fda97820a06f33a29f5b5903412c91e3f5f7873c064f1ab18fe91b8657ca'
PRE_WORKER_SHA = 'c1072ef0368c7d352cdba9535b240c5c5b9691c5fcab0e4a2fc44104a9db0714'
MANAGER = '@deepseek-ai/dsh-plugin-manager/lib/index.js'
NEW_RECIPES = {WORKER, 'plugin-inventory-management-patch.json',
               'plugin-manager-management-probe-patch.json',
               'plugin-inventory-management-host-codec-patch.json',
               'plugin-inventory-management-remote-codec-patch.json',
               'plugin-inventory-management-api-codec-patch.json'}

JAVA = r'''
import com.deepseekharness.app.util.ManagedPatchChain;
import com.deepseekharness.app.util.ExactTextPatch;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class PluginManagementChainFixture {
  static void require(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
  static List<ManagedPatchChain.Recipe> chain(Path root, String name) throws Exception {
    Path folder = root.resolve(name);
    var recipes = new ArrayList<ManagedPatchChain.Recipe>();
    for (int i=0; i<Integer.parseInt(Files.readString(folder.resolve("count.txt"))); i++) {
      Path recipe=folder.resolve(Integer.toString(i));
      var patches=new ArrayList<ManagedPatchChain.Patch>();
      for (int j=0; j<Integer.parseInt(Files.readString(recipe.resolve("count.txt"))); j++)
        patches.add(new ManagedPatchChain.Patch(Files.readString(recipe.resolve(j+"-before.txt")), Files.readString(recipe.resolve(j+"-after.txt"))));
      recipes.add(new ManagedPatchChain.Recipe(Files.readString(recipe.resolve("id.txt")), patches, Files.readAllLines(recipe.resolve("markers.txt"))));
    }
    return recipes;
  }
  static void mustFail(String source, List<ManagedPatchChain.Recipe> chain, String canonical, String expected) throws Exception {
    try { ManagedPatchChain.apply(source, chain, () -> canonical); throw new AssertionError("invalid chain returned success"); }
    catch (IOException error) { require(error.getMessage().contains(expected), "wrong guarded error: "+error); }
  }
  public static void main(String[] args) throws Exception {
    Path root=Path.of(args[0]); boolean broken=args.length>1 && args[1].equals("expect-broken");
    for (String id:Files.readAllLines(root.resolve("modules.txt"))) {
      Path module=root.resolve(id); String name=Files.readString(module.resolve("name.txt"));
      String canonical=Files.readString(module.resolve("canonical.js"));
      String expected=Files.readString(module.resolve("expected.js"));
      var chain=chain(module,"chain");
      String fresh=ManagedPatchChain.apply(canonical,chain,()->{throw new AssertionError("fresh canonical restored: "+name);});
      require(fresh.equals(expected),"fresh output differs: "+name);
      if (broken && name.equals("@deepseek-ai/dsh-plugin-manager/lib/index.js")) {
        mustFail(fresh,chain,expected,"PATCH_CHAIN_MISMATCH");
        mustFail("invalid incomplete target",chain,expected,"PATCH_CHAIN_MISMATCH");
        System.out.println("EXPECTED_BROKEN_REPEAT_AND_ARCHIVE "+name); continue;
      }
      require(ManagedPatchChain.apply(fresh,chain,()->{throw new AssertionError("current chain attempted restore: "+name);}).equals(expected),"current repeat differs: "+name);
      AtomicInteger restores=new AtomicInteger();
      require(ManagedPatchChain.apply("invalid incomplete target",chain,()->{restores.incrementAndGet();return expected;}).equals(expected),"managed canonical rebuild differs: "+name);
      require(restores.get()==1,"managed canonical rebuild count: "+name);
      mustFail("invalid incomplete target",chain,null,"PATCH_CANONICAL_MISSING");
      mustFail("invalid incomplete target",chain,"invalid canonical","PATCH_CHAIN_MISMATCH");
      if (Files.exists(module.resolve("apk.js"))) {
        String apk=Files.readString(module.resolve("apk.js"));
        require(apk.equals(expected),"APK archive differs from expected current module: "+name);
        require(ManagedPatchChain.apply(apk,chain,()->{throw new AssertionError("exact APK unexpectedly restored");}).equals(expected),"exact APK chain differs: "+name);
      }
      if (name.equals("@deepseek-ai/dsh-plugin-manager/lib/index.js")) {
        String pre=Files.readString(module.resolve("pre-worker.js")),old=Files.readString(module.resolve("old-worker.js"));
        var historical=chain(module,"historical-chain");
        require(ManagedPatchChain.apply(canonical,historical,()->{throw new AssertionError("old fresh restored");}).equals(old),"historical V1 module identity");
        mustFail(old,historical,old,"PATCH_CHAIN_MISMATCH");
        restores.set(0);
        require(ManagedPatchChain.apply(pre,chain,()->{restores.incrementAndGet();return expected;}).equals(expected),"pre-worker upgrade differs");
        require(restores.get()==0,"pre-worker unexpectedly needed canonical");
        restores.set(0);
        require(ManagedPatchChain.apply(old,chain,()->{restores.incrementAndGet();return expected;}).equals(expected),"broken V1 upgrade differs");
        require(restores.get()==1,"broken V1 must restore whole current managed canonical once");
        mustFail(old,chain,old,"PATCH_CHAIN_MISMATCH");
        System.out.println("PASS_WORKER_UPGRADE historical-negative/pre-worker/V1/canonical-failclosed");
      }
      System.out.println("PASS_CHAIN fresh/repeat/managed-canonical/failclosed "+name);
    }
  }
}
'''


def sha(data):
    return hashlib.sha256(data).hexdigest()


def write_chain(folder, chain):
    folder.mkdir()
    (folder / 'count.txt').write_bytes(str(len(chain)).encode())
    for i, (name, patches, markers) in enumerate(chain):
        directory = folder / str(i)
        directory.mkdir()
        (directory / 'id.txt').write_bytes(name.encode())
        (directory / 'count.txt').write_bytes(str(len(patches)).encode())
        (directory / 'markers.txt').write_bytes(('\n'.join(markers)).encode())
        for j, (before, after) in enumerate(patches):
            (directory / (str(j) + '-before.txt')).write_bytes(before.encode())
            (directory / (str(j) + '-after.txt')).write_bytes(after.encode())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root', type=Path, default=ROOT)
    parser.add_argument('--jdk', type=Path)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--expect-broken-current', action='store_true')
    parser.add_argument('--apk', type=Path, help='add exact APK managed archive admission (read only)')
    args = parser.parse_args()
    if sys.flags.optimize:
        raise RuntimeError('TEST_ASSERTIONS_REQUIRE_UNOPTIMIZED_PYTHON')
    source = args.source_root.resolve()
    assets = source / 'app/src/main/assets'
    chains, _ = runtime_stable_patches.load_chains(assets, '0.2.1-rc.2')
    selected = json.loads((source / 'app/build/test-runtimes/current.json').read_text(encoding='utf-8'))
    raw = Path(selected['raw']) / 'node_modules'
    historical_bytes = (source / 'tools/fixtures/plugin-bundle-proof-workers-v1-patch.json').read_bytes()
    assert sha(historical_bytes) == OLD_RECIPE_SHA, 'historical actual V1 recipe identity'
    historical = json.loads(historical_bytes)
    output = args.output or source / 'app/build'
    output.mkdir(parents=True, exist_ok=True)
    owned = Path(tempfile.mkdtemp(prefix='plugin-management-java-chain-', dir=output))
    archive = None
    if args.apk:
        with zipfile.ZipFile(args.apk) as apk:
            archive = tarfile.open(fileobj=io.BytesIO(apk.read('assets/dsh-runtime.bin')), mode='r:*')
    names, records = [], []
    for module, chain in chains.items():
        if not any(row[0] in NEW_RECIPES for row in chain):
            continue
        name = str(len(names))
        names.append(name)
        directory = owned / name
        directory.mkdir()
        canonical = (raw / module).read_bytes()
        for recipe_name, _, _ in chain:
            if recipe_name in NEW_RECIPES:
                pinned = json.loads((assets / recipe_name).read_text(encoding='utf-8'))
                assert pinned['sourceSha256'] == sha(canonical), 'unknown canonical upstream bytes: ' + module
        expected = runtime_stable_patches.apply(canonical, chain)
        (directory / 'name.txt').write_bytes(module.encode())
        (directory / 'canonical.js').write_bytes(canonical)
        (directory / 'expected.js').write_bytes(expected)
        write_chain(directory / 'chain', chain)
        if archive:
            members = [item for item in archive.getmembers() if item.isfile() and item.name.endswith('/node_modules/' + module)]
            assert len(members) == 1, 'exact APK module uniqueness: ' + module
            data = archive.extractfile(members[0]).read()
            (directory / 'apk.js').write_bytes(data)
        if module == MANAGER:
            worker_rows = [row for row in chain if row[0] == WORKER]
            required_marker = 'DSHA_PLUGIN_BUNDLE_READ_WORKERS_V1' if args.expect_broken_current else 'DSHA_PLUGIN_BUNDLE_READ_WORKERS_V2'
            assert len(worker_rows) == 1 and worker_rows[0][2] == [required_marker], 'worker registry must identify the exact admitted generation'
            pre_chain = [row for row in chain if row[0] != WORKER]
            pre = runtime_stable_patches.apply(canonical, pre_chain)
            assert sha(pre) == PRE_WORKER_SHA, 'pre-worker actual managed source identity'
            historical_row = (WORKER, [(item['before'], item['after']) for item in historical['patches']], ['DSHA_PLUGIN_BUNDLE_READ_WORKERS_V1'])
            old_chain = pre_chain + [historical_row]
            old = runtime_stable_patches.apply(canonical, old_chain)
            assert sha(old) == OLD_MODULE_SHA, 'retained actual V1 managed module identity'
            (directory / 'pre-worker.js').write_bytes(pre)
            (directory / 'old-worker.js').write_bytes(old)
            write_chain(directory / 'historical-chain', old_chain)
        records.append({'module': module, 'canonicalSha256': sha(canonical), 'currentSha256': sha(expected), 'recipes': [row[0] for row in chain]})
    if archive:
        archive.close()
    assert len(names) == 6
    (owned / 'modules.txt').write_bytes(('\n'.join(names)).encode())
    (owned / 'PluginManagementChainFixture.java').write_bytes(JAVA.encode())
    jdk = args.jdk or (Path(os.environ['JAVA_HOME']) if os.environ.get('JAVA_HOME') else None)
    suffix = '.exe' if os.name == 'nt' else ''
    javac = str(jdk / 'bin' / ('javac' + suffix)) if jdk else shutil.which('javac')
    java = str(jdk / 'bin' / ('java' + suffix)) if jdk else shutil.which('java')
    assert javac and java, 'JDK17_REQUIRED'
    production = source / 'app/src/main/java/com/deepseekharness/app/util'
    classes = owned / 'classes'
    classes.mkdir()
    command = [javac, '-encoding', 'UTF-8', '--release', '17', '-d', str(classes), str(production / 'ManagedPatchChain.java'), str(production / 'ExactTextPatch.java'), str(source / 'tools/fixtures/documents-parent/com/deepseekharness/app/util/UiText.java'), str(owned / 'PluginManagementChainFixture.java')]
    compiled = subprocess.run(command, capture_output=True, timeout=60)
    (owned / 'compile.log').write_bytes(compiled.stdout + compiled.stderr)
    assert compiled.returncode == 0, 'compile failed: ' + str(owned)
    command = [java, '-cp', str(classes), 'PluginManagementChainFixture', str(owned)]
    if args.expect_broken_current:
        command.append('expect-broken')
    ran = subprocess.run(command, capture_output=True, timeout=60)
    (owned / 'java.log').write_bytes(ran.stdout + ran.stderr)
    result = {'status': 'PASS' if ran.returncode == 0 else 'FAIL', 'exitCode': ran.returncode, 'expectedBrokenCurrent': args.expect_broken_current, 'modules': records, 'registrySha256': sha((assets / 'runtime-patches.json').read_bytes()), 'productionAlgorithmSha256': {name: sha((production / name).read_bytes()) for name in ['ManagedPatchChain.java', 'ExactTextPatch.java']}, 'historicalRecipeSha256': OLD_RECIPE_SHA, 'historicalModuleSha256': OLD_MODULE_SHA, 'apk': None if not args.apk else {'path': str(args.apk.resolve()), 'sha256': sha(args.apk.read_bytes())}, 'deviceValidation': False, 'output': str(owned)}
    (owned / 'result.json').write_bytes((json.dumps(result, indent=2) + '\n').encode())
    print(json.dumps(result))
    if ran.returncode:
        print((ran.stdout + ran.stderr).decode('utf-8', errors='replace'), file=sys.stderr)
        raise SystemExit(ran.returncode)


if __name__ == '__main__':
    main()
