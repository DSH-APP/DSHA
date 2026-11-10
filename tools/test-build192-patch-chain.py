#!/usr/bin/env python3
"""Compile production Java patch admission for six build192 recipes and exact build191 upgrade."""
import argparse
import hashlib
import importlib.util
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
NAMES = {
    'deepseek-web-search-headers-patch.json', 'file-upload-capacity-patch.json',
    'file-upload-binary-client-patch.json', 'conversation-large-image-file-patch.json',
    'file-deliverables-patch.json', 'file-image-actions-patch.json',
}
OLD_APK_SHA = 'ba5fd1e19c5043fe000f02a3ffb56f125939a11b87bad0988d1677c73334cb26'
OLD_MODULE_SHA = {
    '@deepseek-ai/dsh-client-file-upload/lib/client.js': '4c09378ab7cb3dbe58253bd1fad8c4a00ebf5a93b462d96623edb7bd4e566305',
    '@deepseek-ai/dsh-client-file-upload/lib/index.js': '5ce7e766739a8fc628fa69fb93927e1cf18efcab59615306f0ce2bfe276e4124',
    '@deepseek-ai/dsh-client-ui-attachment/lib/client.js': 'cdb497656ed0e66f011125df22791f6f579c85710d2d915020ddcfe6c0f58072',
    '@deepseek-ai/dsh-client-ui-conversation/lib/client.js': '89027fd7882b21e933086d6da4f07508891e21a0c83c423240ba198ea37a1976',
    '@deepseek-ai/dsh-client-ui-deliverables/lib/client.js': '0b786f9c89e728ac33926b452536daaea678d9e7741a579126e406a232cb03ed',
    '@deepseek-ai/dsh-web-search-deepseek/lib/index.js': 'fc13dd4eaba3c9298af41a4d0559a1b8bb61614462fe18ba6e76d8c598c332bd',
}
OLD_ARCHIVE_SHA = '63da76fb8d34c14e6f5d1bf102509df6aeca0cfebb6f48c82d1cad8eb6c76f7b'

JAVA = r'''
import com.deepseekharness.app.util.ManagedPatchChain;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
public final class Build192PatchChainFixture {
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
      for(String input:List.of("canonical.js","expected.js","old-191.js")) {
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
      System.out.println("PASS_BUILD192_CHAIN fresh/repeat/build191-upgrade/canonical/failclosed "+Files.readString(module.resolve("name.txt")));
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
    parser.add_argument('--old-apk', type=Path, help='read-only exact retained 191/1640 standard APK; otherwise use hash-pinned prior-chain modules')
    parser.add_argument('--old-archive', type=Path, help='read-only hash-pinned build191 runtime archive, mutually exclusive with --old-apk')
    parser.add_argument('--apk', type=Path, help='read-only exact new APK for final archive validation')
    args = parser.parse_args()
    if __debug__ is False:
        raise RuntimeError('TEST_ASSERTIONS_REQUIRE_UNOPTIMIZED_PYTHON')
    if args.old_apk and args.old_archive:
        parser.error('--old-apk and --old-archive are mutually exclusive')
    if args.old_archive:
        assert sha(args.old_archive.read_bytes()) == OLD_ARCHIVE_SHA, 'OLD_191_ARCHIVE_IDENTITY'
    if args.old_apk:
        assert sha(args.old_apk.read_bytes()) == OLD_APK_SHA, 'OLD_191_APK_IDENTITY'
    assets = ROOT / 'app/src/main/assets'
    chains, _ = runtime_stable_patches.load_chains(assets, '0.2.1-rc.2')
    registered = {name for rows in chains.values() for name, _, _ in rows}
    assert NAMES <= registered, 'BUILD192_RECIPES_MUST_BE_REGISTERED'
    selected = os.environ.get('DSHA_TEST_RUNTIME')
    if selected is None:
        selected = json.loads((ROOT / 'app/build/test-runtimes/current.json').read_text(encoding='utf8'))['raw']
    raw = Path(selected) / 'node_modules'
    owned = Path(tempfile.mkdtemp(prefix='build192-java-chain-', dir=ROOT / 'app/build'))
    old = archive(args.old_apk) if args.old_apk else tarfile.open(args.old_archive, 'r:*') if args.old_archive else None
    new = archive(args.apk) if args.apk else None
    builder_spec = importlib.util.spec_from_file_location('build192_archive_builder', ROOT / 'tools/build-dsh-runtime.py')
    builder = importlib.util.module_from_spec(builder_spec)
    builder_spec.loader.exec_module(builder)
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
            prepared = builder.patched_content(Path(module), canonical)
            expected = runtime_stable_patches.apply(prepared, chain)
            prior_chain = [row for row in chain if row[0] not in NAMES]
            old_bytes = module_bytes(old, module) if old else runtime_stable_patches.apply(prepared, prior_chain)
            assert sha(old_bytes) == OLD_MODULE_SHA[module], 'PINNED_191_MODULE_IDENTITY'
            assert old_bytes == runtime_stable_patches.apply(prepared, prior_chain), 'ACTUAL_191_NOT_PRIOR_CANONICAL: ' + module
            for filename, data in [('canonical.js', prepared), ('old-191.js', old_bytes), ('expected.js', expected), ('name.txt', module.encode())]:
                (directory / filename).write_bytes(data)
            write_chain(directory / 'chain', chain)
            if new:
                (directory / 'apk.js').write_bytes(module_bytes(new, module))
            records.append(dict(module=module, sourceSha256=sha(canonical), old191Sha256=sha(old_bytes), currentSha256=sha(expected), recipes=[name for name, _, _ in chain]))
    finally:
        if old:
            old.close()
        if new:
            new.close()
    assert len(names) == 6
    (owned / 'modules.txt').write_bytes('\n'.join(names).encode())
    (owned / 'Build192PatchChainFixture.java').write_bytes(JAVA.encode())
    jdk = args.jdk or (Path(os.environ['JAVA_HOME']) if os.environ.get('JAVA_HOME') else None)
    suffix = '.exe' if os.name == 'nt' else ''
    javac = str(jdk / 'bin' / ('javac' + suffix)) if jdk else shutil.which('javac')
    java = str(jdk / 'bin' / ('java' + suffix)) if jdk else shutil.which('java')
    assert javac and java, 'JDK17_REQUIRED'
    production = ROOT / 'app/src/main/java/com/deepseekharness/app/util'
    classes = owned / 'classes'
    classes.mkdir()
    command = [javac, '-encoding', 'UTF-8', '--release', '17', '-d', str(classes), str(production / 'ManagedPatchChain.java'), str(production / 'ExactTextPatch.java'), str(ROOT / 'tools/fixtures/documents-parent/com/deepseekharness/app/util/UiText.java'), str(owned / 'Build192PatchChainFixture.java')]
    compiled = subprocess.run(command, capture_output=True, timeout=60)
    (owned / 'compile.log').write_bytes(compiled.stdout + compiled.stderr)
    assert compiled.returncode == 0, 'JAVA_COMPILE_FAILED: ' + str(owned)
    ran = subprocess.run([java, '-cp', str(classes), 'Build192PatchChainFixture', str(owned)], capture_output=True, timeout=60)
    (owned / 'java.log').write_bytes(ran.stdout + ran.stderr)
    result = dict(status='PASS' if ran.returncode == 0 else 'FAIL', exitCode=ran.returncode, modules=records,
                  old191ApkSha256=OLD_APK_SHA if args.old_apk else None,
                  old191ArchiveSha256=OLD_ARCHIVE_SHA if args.old_archive else None,
                  historicalInput='actual retained 191/1640 APK' if args.old_apk else 'actual retained build191 archive' if args.old_archive else 'reconstructed prior chain matched to exact build191 archive module hashes',
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
