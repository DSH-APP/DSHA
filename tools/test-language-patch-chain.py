#!/usr/bin/env python3
"""Prove the DSHA web language overlay builds the current signed locale form.

Uses production Java patch algorithms, the verified raw fixture and the current
signed archive. Since DSH 0.2.2 the recipe declares no predecessor: the V1 form
(former tools/fixtures/language-v1-patch.json, now deleted) only ever shipped in the 0.2.1-rc.3
archive, and a DSH version change replaces the whole managed tree from the new
archive, so no 0.2.2 module can carry it. Stacked or unknown managed sources
fail closed. No APK, device or archive is modified.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
from runtime_stable_patches import load_chains, apply
from test_runtime_fixture import runtime

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'

JAVA = r'''
import com.deepseekharness.app.util.ManagedPatchChain;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
public final class LanguagePatchChainFixture {
  static void require(boolean valid,String reason){if(!valid)throw new AssertionError(reason);}
  public static void main(String[] args)throws Exception{
    Path root=Path.of(args[0]);
    var parts=new ArrayList<ManagedPatchChain.Patch>();
    for(int i=0;i<Integer.parseInt(Files.readString(root.resolve("new-count")));i++)
      parts.add(new ManagedPatchChain.Patch(Files.readString(root.resolve("new"+i+"-before")),Files.readString(root.resolve("new"+i+"-after"))));
    var recipe=new ManagedPatchChain.Recipe("language-patch.json",parts,List.of());
    require(recipe.previousPatches().isEmpty(),"0.2.2 declares no predecessor");
    var chain=List.of(recipe);String expected=Files.readString(root.resolve("current.js"));
    String canonical=Files.readString(root.resolve("canonical.js"));
    for(String name:List.of("canonical.js","archive.js","current.js","partial.js")){
      String actual=ManagedPatchChain.apply(Files.readString(root.resolve(name)),chain,()->{throw new AssertionError("unexpected restore "+name);});
      require(actual.equals(expected),"direct differs "+name);
    }
    var reads=new AtomicInteger();
    String repaired=ManagedPatchChain.apply(expected+expected,chain,()->{reads.incrementAndGet();return canonical;});
    require(repaired.equals(expected)&&reads.get()==1,"stacked helper must rebuild from the canonical module once");
    for(String source:List.of(expected+expected,canonical+canonical,"unknown managed source")){
      try{ManagedPatchChain.apply(source,chain,()->null);throw new AssertionError("missing canonical accepted");}
      catch(IOException error){require(error.getMessage().equals("PATCH_CANONICAL_MISSING"),"wrong missing canonical failure");}
      try{ManagedPatchChain.apply(source,chain,()->"invalid canonical");throw new AssertionError("invalid canonical accepted");}
      catch(IOException error){require(error.getMessage().equals("PATCH_CHAIN_MISMATCH"),"wrong chain failure");}
    }
    System.out.println("PASS real Java fresh npm/current archive/idempotence/partial completion/stacked canonical repair/duplicate and unknown fail-closed");
  }
}
'''


def sha(data):
    return hashlib.sha256(data).hexdigest()


def archived_module(archive, module):
    target = 'usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/' + module
    found = []
    with tarfile.open(archive, 'r|gz') as tree:
        for member in tree:
            if member.name.removeprefix('./') == target:
                assert member.isfile() and member.size < 8 * 1024 * 1024, 'ARCHIVE_LOCALE_MEMBER'
                found.append(tree.extractfile(member).read())
    assert len(found) == 1, 'ARCHIVE_LOCALE_UNIQUENESS'
    return found[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', type=Path, default=ASSETS / 'dsh-runtime.bin')
    parser.add_argument('--skip-archive', action='store_true',
                        help='before the signed archive is rebuilt: use the computed current form; reported as archiveChecked=false')
    parser.add_argument('--jdk', type=Path)
    args = parser.parse_args()
    canonical_root = runtime('raw')
    recipe = json.loads((ASSETS / 'language-patch.json').read_text(encoding='utf8'))
    assert 'previousPatches' not in recipe, 'PREDECESSOR_NOT_APPLICABLE_TO_CURRENT_GENERATION'
    canonical = (canonical_root / 'node_modules' / recipe['module']).read_bytes()
    assert sha(canonical) == recipe['sourceSha256'], 'LOCKED_CURRENT_LOCALE_SOURCE'
    chains, _ = load_chains(ASSETS, recipe['dshVersion'])
    chain = chains[recipe['module']]
    assert [row[0] for row in chain] == ['language-patch.json'], 'LOCALE_CHAIN_OWNER'
    expected = apply(canonical, chain)
    if args.skip_archive:
        archive_sha, archived = None, expected
    else:
        archive_sha = sha(args.archive.read_bytes())
        current_proof = json.loads((ASSETS / 'dsh-runtime.inputs.json').read_text(encoding='utf8'))
        assert archive_sha == current_proof['archive_sha256'], 'CURRENT_SIGNED_ARCHIVE_IDENTITY'
        archived = archived_module(args.archive, recipe['module'])
    # Current signed archives carry the complete current locale form only.
    assert archived == expected, 'CURRENT_ARCHIVE_COMPLETE_LOCALE_FORM'
    assert apply(archived, chain) == expected
    assert apply(expected, chain) == expected
    text = expected.decode('utf8')
    assert text.count('function installDshaLanguageBridge') == 1 and text.count('/* DSHA_UI_LANGUAGES_V3:') == 1
    # One recipe part reverted: exact patches complete it without a canonical read.
    part = recipe['patches'][1]
    partial = text.replace(part['after'], part['before'])
    assert apply(partial.encode('utf8'), chain) == expected
    for stacked in (expected + expected, canonical + canonical):
        try:
            apply(stacked, chain)
        except ValueError as error:
            assert 'STABLE_PATCH_ANCHOR' in str(error)
        else:
            raise AssertionError('stacked locale module accepted directly')
    output = Path(tempfile.mkdtemp(prefix='language-patch-chain-', dir=ROOT / 'app/build'))
    for name, data in [('canonical.js', canonical), ('archive.js', archived), ('current.js', expected), ('partial.js', partial.encode('utf8'))]:
        (output / name).write_bytes(data)
    current_parts = [(part['before'], (ASSETS / part['prependAsset']).read_text(encoding='utf8') + '\n' + part['after']
                      if 'prependAsset' in part else part['after']) for part in recipe['patches']]
    (output / 'new-count').write_bytes(str(len(current_parts)).encode())
    for index, (before, after) in enumerate(current_parts):
        (output / ('new' + str(index) + '-before')).write_bytes(before.encode('utf8'))
        (output / ('new' + str(index) + '-after')).write_bytes(after.encode('utf8'))
    fixture = output / 'LanguagePatchChainFixture.java'
    fixture.write_bytes(JAVA.encode('utf8'))
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
                               str(source / 'ManagedPatchChain.java'), str(source / 'ExactTextPatch.java'), str(ui_text), str(fixture)],
                              capture_output=True, timeout=60)
    (output / 'compile.log').write_bytes(compiled.stdout + compiled.stderr)
    assert compiled.returncode == 0, str(output / 'compile.log')
    ran = subprocess.run([java, '-cp', str(classes), 'LanguagePatchChainFixture', str(output)], capture_output=True, timeout=30)
    (output / 'java.log').write_bytes(ran.stdout + ran.stderr)
    assert ran.returncode == 0, str(output / 'java.log')
    report = dict(status='PASS', archiveChecked=not args.skip_archive, archiveSha256=archive_sha, sourceSha256=sha(canonical),
                  predecessor='not applicable: V1 form only shipped in the 0.2.1-rc.3 archive',
                  archivedModuleSha256=sha(archived), currentModuleSha256=sha(expected),
                  currentModuleBytes=len(expected), output=str(output), deviceValidation=False)
    (output / 'result.json').write_text(json.dumps(report, indent=2), encoding='utf8')
    print(json.dumps(report))


if __name__ == '__main__':
    main()
