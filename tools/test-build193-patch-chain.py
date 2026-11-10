#!/usr/bin/env python3
"""Verify every current Java patch chain and the exact build212 (rc3) generation boundary.

Old-generation modules are evidence, never a substitute for staging the signed
new runtime archive through ManagedRuntimeTransaction/RuntimeTrial. Marker
idempotence cannot prove that the official upstream module was upgraded."""
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
NAMES = {'plugin-pnpm-argv-patch.json', 'language-patch.json'}
OLD_APK_SHA = '2a69a315eca22f8711a97122a54a2f8beb4d98e624a04d8bb3afa65a420e89d2'
OLD_MODULE_SHA = {
    '@deepseek-ai/dsh-plugin-manager/lib/index.js': '7b287c984c0f9e1835e59f3b5d5bf60a68f23430b627a85df41455136a453874',
}
OLD_ARCHIVE_SHA = 'a02cc5a42727238491ee36d4b0b46461d035dd6f8fca22580eb19d8456fbefc3'
OLD_FRONTEND = '@deepseek-ai/dsh-web-frontend/dist/assets/index-BXV-oxvX.js'

JAVA = r'''
import com.deepseekharness.app.util.ManagedPatchChain;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
public final class Build193PatchChainFixture {
  static void require(boolean valid,String reason) {if(!valid)throw new AssertionError(reason);}
  static List<ManagedPatchChain.Recipe> chain(Path folder) throws Exception {
    var result=new ArrayList<ManagedPatchChain.Recipe>();
    for(int i=0;i<Integer.parseInt(Files.readString(folder.resolve("count.txt")));i++) {
      Path recipe=folder.resolve(Integer.toString(i));var patches=new ArrayList<ManagedPatchChain.Patch>();
      for(int j=0;j<Integer.parseInt(Files.readString(recipe.resolve("count.txt")));j++)
        patches.add(new ManagedPatchChain.Patch(Files.readString(recipe.resolve(j+"-before.txt")),Files.readString(recipe.resolve(j+"-after.txt"))));
      int previousCount=Integer.parseInt(Files.readString(recipe.resolve("previous-count.txt")));
      var previous=new ArrayList<ManagedPatchChain.Patch>();
      for(int j=0;j<previousCount;j++)
        previous.add(new ManagedPatchChain.Patch(Files.readString(recipe.resolve("previous-"+j+"-before.txt")),Files.readString(recipe.resolve("previous-"+j+"-after.txt"))));
      var current=new ManagedPatchChain.Recipe(Files.readString(recipe.resolve("id.txt")),patches,Files.readAllLines(recipe.resolve("markers.txt")),previous);
      require(current.previousPatches().size()==previousCount,"fixture dropped signed predecessor metadata");
      result.add(current);
    }
    return result;
  }
  static void rejectWithoutCanonical(String source,List<ManagedPatchChain.Recipe> chain)throws Exception {
    for(String canonical:Arrays.asList(null,"incomplete canonical")) {
      try { ManagedPatchChain.apply(source,chain,()->canonical);throw new AssertionError("bad canonical accepted"); }
      catch(IOException error) { require(error.getMessage().contains(canonical==null?"PATCH_CANONICAL_MISSING":"PATCH_CHAIN_MISMATCH"),"wrong guarded error"); }
    }
  }
  static void predecessorControls(String old,List<ManagedPatchChain.Recipe> chain,String expected)throws Exception {
    int variants=0;
    for(var recipe:chain)for(var previous:recipe.previousPatches()) {
      require(old.contains(previous.after())&&old.indexOf(previous.after())==old.lastIndexOf(previous.after()),"old192 predecessor must match exactly once");
      String partial=old.replace(previous.after(),previous.before());
      rejectWithoutCanonical(partial,chain);
      var reads=new java.util.concurrent.atomic.AtomicInteger();
      require(ManagedPatchChain.apply(partial,chain,()->{reads.incrementAndGet();return expected;}).equals(expected),"partial predecessor canonical repair differs");
      require(reads.get()==1,"partial predecessor bypassed canonical repair");
      variants++;
    }
    if(variants==0)return;
    rejectWithoutCanonical(old+old,chain);
    // Negative control for this fixture's former bug: dropping predecessor
    // metadata must change admission, rather than silently weakening the gate.
    var omitted=new ArrayList<ManagedPatchChain.Recipe>();
    for(var recipe:chain)omitted.add(new ManagedPatchChain.Recipe(recipe.id(),recipe.patches(),recipe.markers()));
    rejectWithoutCanonical(old,omitted);
    System.out.println("PASS_SIGNED_PREDECESSOR partial-variants="+variants+" duplicate/metadata-omission/failclosed");
  }
  public static void main(String[] args) throws Exception {
    Path root=Path.of(args[0]);
    for(String id:Files.readAllLines(root.resolve("modules.txt"))) {
      Path module=root.resolve(id);String expected=Files.readString(module.resolve("expected.js"));
      var chain=chain(module.resolve("chain"));
      for(String input:List.of("canonical.js","expected.js")) {
        String value=ManagedPatchChain.apply(Files.readString(module.resolve(input)),chain,()->{throw new AssertionError(input+" unexpectedly used canonical restore");});
        require(value.equals(expected),input+" differs");
      }
      // A chain is an overlay preparer, not an upstream generation installer.
      // Compare Java with the independently evaluated old-module chain result;
      // a stale marker-only result requires the separately staged new archive.
      var repairs=new java.util.concurrent.atomic.AtomicInteger();
      String old=Files.readString(module.resolve("old-192.js"));
      String directExpected=Files.exists(module.resolve("old-direct.js"))
          ?Files.readString(module.resolve("old-direct.js")):expected;
      String direct=ManagedPatchChain.apply(old,chain,()->{repairs.incrementAndGet();return expected;});
      require(direct.equals(directExpected),"old192 Java/Python chain semantics differ");
      require(repairs.get()==(Files.exists(module.resolve("old-direct.js"))?0:1),"old192 canonical boundary differs");
      String staged=ManagedPatchChain.apply(expected,chain,()->{throw new AssertionError("staged new archive restore");});
      require(staged.equals(expected),"staged complete current chain differs");
      String fromArchive=ManagedPatchChain.apply("incomplete previous managed module",chain,()->expected);
      require(fromArchive.equals(expected),"new managed canonical rebuild differs");
      rejectWithoutCanonical("incomplete old module",chain);
      if(Files.exists(module.resolve("predecessor-unavailable.txt")))
        System.out.println("PASS_PREDECESSOR_NOT_APPLICABLE "+Files.readString(module.resolve("predecessor-unavailable.txt")));
      else
        predecessorControls(old,chain,expected);
      if(Files.exists(module.resolve("apk.js"))) {
        String exact=Files.readString(module.resolve("apk.js"));require(exact.equals(expected),"exact new APK archive differs");
        require(ManagedPatchChain.apply(exact,chain,()->{throw new AssertionError("exact APK restore");}).equals(expected),"exact APK repeat differs");
      }
      System.out.println("PASS_BUILD213_CHAIN fresh/repeat/build212-boundary/staged-canonical/failclosed "+Files.readString(module.resolve("name.txt")));
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
        previous = getattr(patches, 'previous', ())
        (target / 'previous-count.txt').write_bytes(str(len(previous)).encode())
        for j, (before, after) in enumerate(previous):
            (target / ('previous-' + str(j) + '-before.txt')).write_bytes(before.encode())
            (target / ('previous-' + str(j) + '-after.txt')).write_bytes(after.encode())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', type=Path)
    parser.add_argument('--old-apk', type=Path, default=os.environ.get('DSHA_PREVIOUS_RUNTIME_APK'), help='read-only exact retained published192 Standard APK (required unless --old-archive)')
    parser.add_argument('--old-archive', type=Path, default=os.environ.get('DSHA_PREVIOUS_RUNTIME_ARCHIVE'), help='read-only hash-pinned published192 runtime archive, mutually exclusive with --old-apk')
    parser.add_argument('--apk', type=Path, help='read-only exact new APK for final archive validation')
    args = parser.parse_args()
    if __debug__ is False:
        raise RuntimeError('TEST_ASSERTIONS_REQUIRE_UNOPTIMIZED_PYTHON')
    if bool(args.old_apk) == bool(args.old_archive):
        parser.error('provide exactly one of --old-apk or --old-archive; historical input cannot be synthesized from current upstream sources')
    if args.old_archive:
        assert sha(args.old_archive.read_bytes()) == OLD_ARCHIVE_SHA, 'OLD_212_ARCHIVE_IDENTITY'
    if args.old_apk:
        assert sha(args.old_apk.read_bytes()) == OLD_APK_SHA, 'OLD_212_APK_IDENTITY'
    assets = ROOT / 'app/src/main/assets'
    manifest = json.loads((ROOT / 'tools/dsh-runtime/package.json').read_text(encoding='utf8'))
    version = manifest['dependencies']['@deepseek-ai/dsh']
    assert version == '0.2.2-rc.1', 'S1_REQUIRES_REVIEWED_0_2_2_RC1'
    lock = json.loads((ROOT / 'tools/dsh-runtime/package-lock.json').read_text(encoding='utf8'))
    chains, _ = runtime_stable_patches.load_chains(assets, version)
    registered = {name for rows in chains.values() for name, _, _ in rows}
    assert NAMES <= registered, 'BUILD193_RECIPES_MUST_BE_REGISTERED'
    selected = os.environ.get('DSHA_TEST_RUNTIME')
    if selected is None:
        selected = json.loads((ROOT / 'app/build/test-runtimes/current.json').read_text(encoding='utf8'))['raw']
    raw = Path(selected) / 'node_modules'
    raw_package = json.loads((raw / '@deepseek-ai/dsh/package.json').read_text(encoding='utf8'))
    assert raw_package['name'] == '@deepseek-ai/dsh' and raw_package['version'] == version, 'RAW_RUNTIME_GENERATION'
    assert sha((Path(selected) / 'package-lock.json').read_bytes()) == sha((ROOT / 'tools/dsh-runtime/package-lock.json').read_bytes()), 'RAW_RUNTIME_LOCK_IDENTITY'
    if args.old_apk:
        with zipfile.ZipFile(args.old_apk) as package:
            assert sha(package.read('assets/dsh-runtime.bin')) == OLD_ARCHIVE_SHA, 'OLD_212_ARCHIVE_IDENTITY'
    owned = Path(tempfile.mkdtemp(prefix='build193-java-chain-', dir=ROOT / 'app/build'))
    old = archive(args.old_apk) if args.old_apk else tarfile.open(args.old_archive, 'r:*') if args.old_archive else None
    new = archive(args.apk) if args.apk else None
    builder_spec = importlib.util.spec_from_file_location('build193_archive_builder', ROOT / 'tools/build-dsh-runtime.py')
    builder = importlib.util.module_from_spec(builder_spec)
    builder_spec.loader.exec_module(builder)
    names, records = [], []
    try:
        for module, chain in chains.items():
            directory = owned / str(len(names))
            names.append(directory.name)
            directory.mkdir()
            canonical = (raw / module).read_bytes()
            for name, _, _ in chain:
                recipe = json.loads((assets / name).read_text(encoding='utf8'))
                if 'sourceSha256' in recipe:
                    assert sha(canonical) == recipe['sourceSha256'], 'UNKNOWN_OFFICIAL_SOURCE: ' + name
                if 'packageIntegrity' in recipe:
                    package_name = 'node_modules/' + '/'.join(module.split('/')[:2])
                    assert recipe['packageIntegrity'] == lock['packages'][package_name]['integrity'], 'RECIPE_PACKAGE_INTEGRITY: ' + name
            prepared = builder.patched_content(Path(module), canonical)
            expected = runtime_stable_patches.apply(prepared, chain)
            old_module = OLD_FRONTEND if module.startswith('@deepseek-ai/dsh-web-frontend/dist/assets/index-') else module
            old_bytes = module_bytes(old, old_module)
            if module in OLD_MODULE_SHA:
                assert sha(old_bytes) == OLD_MODULE_SHA[module], 'PINNED_212_MODULE_IDENTITY'
            direct_digest, direct_status = None, 'canonical-rebuild-on-anchor-mismatch'
            try:
                direct = runtime_stable_patches.apply(old_bytes, chain)
            except ValueError as failure:
                assert 'STABLE_PATCH_ANCHOR' in str(failure), 'UNEXPECTED_OLD_CHAIN_ERROR'
            else:
                (directory / 'old-direct.js').write_bytes(direct)
                direct_digest = sha(direct)
                direct_status = 'complete-current-bytes' if direct == expected else 'old-markers-retain-old-upstream-bytes'
            # The signed new archive is always the official generation transition.
            # A successful marker-only overlay is never accepted as that transition.
            # Signed predecessor forms belong to the generation that introduced the
            # recipe; when the pinned old input already contains the replacement
            # form (e.g. the rc3 language receipt), the control is not applicable.
            old_text = old_bytes.decode('utf8')
            predecessor_problem = None
            for name, patches, _ in chain:
                for before, after in getattr(patches, 'previous', ()):
                    if old_text.count(after) != 1:
                        predecessor_problem = name + ': predecessor form absent from pinned old input (superseded generation)'
                        break
                if predecessor_problem:
                    break
            for filename, data in [('canonical.js', prepared), ('old-192.js', old_bytes), ('expected.js', expected), ('name.txt', module.encode())]:
                (directory / filename).write_bytes(data)
            write_chain(directory / 'chain', chain)
            if predecessor_problem:
                (directory / 'predecessor-unavailable.txt').write_bytes(predecessor_problem.encode())
            if new:
                (directory / 'apk.js').write_bytes(module_bytes(new, module))
            records.append(dict(module=module, sourceSha256=sha(canonical), old192Module=old_module, old192Sha256=sha(old_bytes), currentSha256=sha(expected), oldDirectStatus=direct_status, oldDirectSha256=direct_digest, generationUpgrade='stage-signed-new-runtime-with-ManagedRuntimeTransaction-and-RuntimeTrial', recipes=[name for name, _, _ in chain], previousPatchCounts={name: len(getattr(patches, 'previous', ())) for name, patches, _ in chain}, predecessorControl='not-applicable' if predecessor_problem else 'executed'))
    finally:
        if old:
            old.close()
        if new:
            new.close()
    assert len(names) == len(chains) and len(names) >= 30, 'ALL_CURRENT_ACTIVE_MODULES_REQUIRED'
    (owned / 'modules.txt').write_bytes('\n'.join(names).encode())
    (owned / 'Build193PatchChainFixture.java').write_bytes(JAVA.encode())
    jdk = args.jdk or (Path(os.environ['JAVA_HOME']) if os.environ.get('JAVA_HOME') else None)
    suffix = '.exe' if os.name == 'nt' else ''
    javac = str(jdk / 'bin' / ('javac' + suffix)) if jdk else shutil.which('javac')
    java = str(jdk / 'bin' / ('java' + suffix)) if jdk else shutil.which('java')
    assert javac and java, 'JDK17_REQUIRED'
    production = ROOT / 'app/src/main/java/com/deepseekharness/app/util'
    classes = owned / 'classes'
    classes.mkdir()
    command = [javac, '-encoding', 'UTF-8', '--release', '17', '-d', str(classes), str(production / 'ManagedPatchChain.java'), str(production / 'ExactTextPatch.java'), str(ROOT / 'tools/fixtures/documents-parent/com/deepseekharness/app/util/UiText.java'), str(owned / 'Build193PatchChainFixture.java')]
    compiled = subprocess.run(command, capture_output=True, timeout=60)
    (owned / 'compile.log').write_bytes(compiled.stdout + compiled.stderr)
    assert compiled.returncode == 0, 'JAVA_COMPILE_FAILED: ' + str(owned)
    ran = subprocess.run([java, '-cp', str(classes), 'Build193PatchChainFixture', str(owned)], capture_output=True, timeout=60)
    (owned / 'java.log').write_bytes(ran.stdout + ran.stderr)
    result = dict(status='PASS' if ran.returncode == 0 else 'FAIL', exitCode=ran.returncode, modules=records,
                  old192ApkSha256=OLD_APK_SHA if args.old_apk else None,
                  old192ArchiveSha256=OLD_ARCHIVE_SHA if args.old_archive else None,
                  historicalInput='actual build212 APK' if args.old_apk else 'actual build212 archive',
                  currentDshVersion=version, activeModuleCount=len(names),
                  predecessorPartialVariants=sum(sum(record['previousPatchCounts'].values()) for record in records),
                  predecessorNegativeControls=['each incomplete predecessor requires canonical repair', 'duplicate predecessor rejected', 'omitted predecessor metadata rejected'],
                  exactNewApkValidation=args.apk is not None,
                  generationBoundary='old package generation receives no in-place current overlay; signed new archive installation/trial is required',
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
