#!/usr/bin/env python3
"""Compile production Java patch admission for account recipes and actual 2100 upgrade."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import zipfile
import runtime_stable_patches

ROOT = Path(__file__).resolve().parents[1]
NAMES = {'deepseek-account-requests-patch.json', 'deepseek-account-client-diagnostic-patch.json'}
OLD_APK_SHA = 'd62f17f23a7949f0f25285ec7afa44cb35de24a6bc91887ba4600023d18ffee0'
OLD_MODULE_SHA = {
    '@deepseek-ai/dsh-client-ui-settings-account/lib/client.js': '3a542af3f92b87aef984c017ad71116727c9e6c8dae20c7d7ee813008c24eedd',
    '@deepseek-ai/dsh-deepseek-account-platform/lib/index.js': '3b82b41e3e94566e43105a2552c0a4804983be39e1e16ad8b0d0646c76ec0057',
}
JAVA = r'''
import com.deepseekharness.app.util.ManagedPatchChain;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
public final class AccountRequestChainFixture {
  static void require(boolean valid,String reason) {if(!valid)throw new AssertionError(reason);}
  static List<ManagedPatchChain.Recipe> chain(Path folder) throws Exception {
    var result=new ArrayList<ManagedPatchChain.Recipe>();
    for(int i=0;i<Integer.parseInt(Files.readString(folder.resolve("count.txt")));i++) {
      Path recipe=folder.resolve(Integer.toString(i));var patches=new ArrayList<ManagedPatchChain.Patch>();
      for(int j=0;j<Integer.parseInt(Files.readString(recipe.resolve("count.txt")));j++)
        patches.add(new ManagedPatchChain.Patch(Files.readString(recipe.resolve(j+"-before.txt")),Files.readString(recipe.resolve(j+"-after.txt"))));
      result.add(new ManagedPatchChain.Recipe(Files.readString(recipe.resolve("id.txt")),patches,Files.readAllLines(recipe.resolve("markers.txt"))));
    }
    return result;
  }
  public static void main(String[] args) throws Exception {
    Path root=Path.of(args[0]);
    for(String id:Files.readAllLines(root.resolve("modules.txt"))) {
      Path module=root.resolve(id);String expected=Files.readString(module.resolve("expected.js"));
      var chain=chain(module.resolve("chain"));
      for(String input:List.of("canonical.js","expected.js","old-2100.js")) {
        String value=ManagedPatchChain.apply(Files.readString(module.resolve(input)),chain,()->{throw new AssertionError(input+" unexpectedly used canonical restore");});
        require(value.equals(expected),input+" differs");
      }
      String fromArchive=ManagedPatchChain.apply("incomplete previous managed module",chain,()->expected);
      require(fromArchive.equals(expected),"new managed canonical rebuild differs");
      for(String canonical:Arrays.asList(null,"incomplete canonical")) {
        try { ManagedPatchChain.apply("incomplete old module",chain,()->canonical);throw new AssertionError("bad canonical accepted"); }
        catch(IOException error) { require(error.getMessage().contains(canonical==null?"PATCH_CANONICAL_MISSING":"PATCH_CHAIN_MISMATCH"),"wrong guarded error"); }
      }
      if(Files.exists(module.resolve("apk.js"))) {
        String exact=Files.readString(module.resolve("apk.js"));require(exact.equals(expected),"exact new APK archive differs");
        require(ManagedPatchChain.apply(exact,chain,()->{throw new AssertionError("exact APK restore");}).equals(expected),"exact APK repeat differs");
      }
      System.out.println("PASS_ACCOUNT_CHAIN fresh/repeat/actual-2100-upgrade/canonical/failclosed "+Files.readString(module.resolve("name.txt")));
    }
  }
}
'''


def sha(data):
    return hashlib.sha256(data).hexdigest()


def archive(apk):
    with zipfile.ZipFile(apk) as package:
        return tarfile.open(fileobj=io.BytesIO(package.read('assets/dsh-runtime.bin')), mode='r:*')


def module_bytes(archive_file, module):
    matches = [entry for entry in archive_file.getmembers() if entry.isfile() and entry.name.endswith('/node_modules/' + module)]
    assert len(matches) == 1, 'APK_MODULE_UNIQUENESS: ' + module
    return archive_file.extractfile(matches[0]).read()


def write_chain(folder, chain):
    folder.mkdir()
    (folder / 'count.txt').write_bytes(str(len(chain)).encode())
    for i, (name, patches, markers) in enumerate(chain):
        target = folder / str(i)
        target.mkdir()
        (target / 'id.txt').write_bytes(name.encode())
        (target / 'count.txt').write_bytes(str(len(patches)).encode())
        (target / 'markers.txt').write_bytes('\n'.join(markers).encode())
        for j, (before, after) in enumerate(patches):
            (target / (str(j) + '-before.txt')).write_bytes(before.encode())
            (target / (str(j) + '-after.txt')).write_bytes(after.encode())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', type=Path)
    parser.add_argument('--old-apk', type=Path, help='read-only exact retained 2100 standard APK; otherwise use hash-pinned prior-chain modules')
    parser.add_argument('--apk', type=Path, help='read-only exact new APK for final archive validation')
    args = parser.parse_args()
    if __debug__ is False:
        raise RuntimeError('TEST_ASSERTIONS_REQUIRE_UNOPTIMIZED_PYTHON')
    if args.old_apk:
        assert sha(args.old_apk.read_bytes()) == OLD_APK_SHA, 'OLD_2100_APK_IDENTITY'
    assets = ROOT / 'app/src/main/assets'
    chains, _ = runtime_stable_patches.load_chains(assets, '0.2.1-rc.2')
    registered = {name for rows in chains.values() for name, _, _ in rows}
    assert NAMES <= registered, 'ACCOUNT_RECIPES_MUST_BE_REGISTERED'
    pointer = json.loads((ROOT / 'app/build/test-runtimes/current.json').read_text(encoding='utf8'))
    raw = Path(pointer['raw']) / 'node_modules'
    owned = Path(tempfile.mkdtemp(prefix='account-java-chain-', dir=ROOT / 'app/build'))
    old = archive(args.old_apk) if args.old_apk else None
    new = archive(args.apk) if args.apk else None
    names, records = [], []
    try:
        for module, chain in chains.items():
            if not any(name in NAMES for name, _, _ in chain):
                continue
            directory = owned / str(len(names))
            names.append(directory.name)
            directory.mkdir()
            canonical = (raw / module).read_bytes()
            for name, _, _ in chain:
                if name in NAMES:
                    recipe = json.loads((assets / name).read_text(encoding='utf8'))
                    assert sha(canonical) == recipe['sourceSha256'], 'UNKNOWN_OFFICIAL_SOURCE'
            expected = runtime_stable_patches.apply(canonical, chain)
            prior_chain = [row for row in chain if row[0] not in NAMES]
            old_bytes = module_bytes(old, module) if old else runtime_stable_patches.apply(canonical, prior_chain)
            assert sha(old_bytes) == OLD_MODULE_SHA[module], 'PINNED_2100_MODULE_IDENTITY'
            assert old_bytes == runtime_stable_patches.apply(canonical, prior_chain), 'ACTUAL_2100_NOT_PRIOR_CANONICAL: ' + module
            for filename, data in [('canonical.js', canonical), ('old-2100.js', old_bytes), ('expected.js', expected), ('name.txt', module.encode())]:
                (directory / filename).write_bytes(data)
            write_chain(directory / 'chain', chain)
            if new:
                (directory / 'apk.js').write_bytes(module_bytes(new, module))
            records.append(dict(module=module, sourceSha256=sha(canonical), old2100Sha256=sha(old_bytes), currentSha256=sha(expected), recipes=[name for name, _, _ in chain]))
    finally:
        if old:
            old.close()
        if new:
            new.close()
    assert len(names) == 2
    (owned / 'modules.txt').write_bytes('\n'.join(names).encode())
    (owned / 'AccountRequestChainFixture.java').write_bytes(JAVA.encode())
    jdk = args.jdk or (Path(os.environ['JAVA_HOME']) if os.environ.get('JAVA_HOME') else None)
    suffix = '.exe' if os.name == 'nt' else ''
    javac = str(jdk / 'bin' / ('javac' + suffix)) if jdk else shutil.which('javac')
    java = str(jdk / 'bin' / ('java' + suffix)) if jdk else shutil.which('java')
    assert javac and java, 'JDK17_REQUIRED'
    production = ROOT / 'app/src/main/java/com/deepseekharness/app/util'
    classes = owned / 'classes'
    classes.mkdir()
    command = [javac, '-encoding', 'UTF-8', '--release', '17', '-d', str(classes), str(production / 'ManagedPatchChain.java'), str(production / 'ExactTextPatch.java'), str(ROOT / 'tools/fixtures/documents-parent/com/deepseekharness/app/util/UiText.java'), str(owned / 'AccountRequestChainFixture.java')]
    compiled = subprocess.run(command, capture_output=True, timeout=60)
    (owned / 'compile.log').write_bytes(compiled.stdout + compiled.stderr)
    assert compiled.returncode == 0, 'JAVA_COMPILE_FAILED: ' + str(owned)
    ran = subprocess.run([java, '-cp', str(classes), 'AccountRequestChainFixture', str(owned)], capture_output=True, timeout=60)
    (owned / 'java.log').write_bytes(ran.stdout + ran.stderr)
    result = dict(status='PASS' if ran.returncode == 0 else 'FAIL', exitCode=ran.returncode, modules=records,
                  old2100ApkSha256=OLD_APK_SHA if args.old_apk else None,
                  historicalInput='actual retained 2100 APK' if args.old_apk else 'reconstructed prior chain matched to module hashes from exact 2100 APK',
                  apkSha256=sha(args.apk.read_bytes()) if args.apk else None,
                  productionAlgorithmSha256={name: sha((production / name).read_bytes()) for name in ['ManagedPatchChain.java', 'ExactTextPatch.java']},
                  deviceValidation=False, output=str(owned))
    (owned / 'result.json').write_bytes((json.dumps(result, indent=2) + '\n').encode())
    print(json.dumps(result))
    if ran.returncode:
        print((ran.stdout + ran.stderr).decode('utf8', errors='replace'))
        raise SystemExit(ran.returncode)


if __name__ == '__main__':
    main()
