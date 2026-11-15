#!/usr/bin/env python3
"""验证应急归档的路径边界及覆盖层证明，全部故障在宿主临时夹具中执行。"""
import importlib.util
import copy
import io
import tarfile
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path
import json
import re
import recovery_runtime_overlay as browser_overlay

CURRENT_DSH = json.loads((Path(__file__).parents[1] / 'tools/recovery-runtime/lock.json').read_text(encoding='utf8'))['dshVersion']

spec=importlib.util.spec_from_file_location('recovery_assets',Path(__file__).with_name('prepare-recovery-assets.py'))
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)


class RecoveryAssetTests(unittest.TestCase):
    def test_language_overlay_replaces_the_complete_pinned_predecessor_and_rejects_partial_forms(self):
        assets = module.ROOT / 'app/src/main/assets'
        recipe = json.loads((assets / 'recovery-recipes/0.2.1-rc.3/language-patch.json').read_text(encoding='utf8'))
        previous = recipe['previousPatches']
        canonical = '\n'.join(row['before'] for row in previous)
        old = canonical
        for row in previous:
            old = browser_overlay.exact(old, row['before'], row['after'])
        expected = browser_overlay.language_overlay(canonical, recipe, assets)
        self.assertEqual(expected, browser_overlay.language_overlay(old, recipe, assets))
        self.assertEqual(expected, browser_overlay.language_overlay(expected, recipe, assets))
        partial = old.replace(previous[1]['after'], previous[1]['before'])
        with self.assertRaisesRegex(ValueError, 'RECOVERY_LANGUAGE_PARTIAL_PREVIOUS_RECIPE'):
            browser_overlay.language_overlay(partial, recipe, assets)
        with self.assertRaisesRegex(ValueError, 'RECOVERY_OVERLAY_ANCHOR|RECOVERY_LANGUAGE_DUPLICATE_HELPER'):
            browser_overlay.language_overlay(old + '\n' + expected, recipe, assets)

    def test_signed_capsule_dependencies_are_complete_and_bound_to_files(self):
        assets = module.ROOT / 'app/src/main/assets'
        proof = {}
        rows = module.capsule_dependencies(assets, proof, [])
        self.assertEqual(len(rows), 5)
        for row in rows:
            self.assertEqual(row['sha256'], module.digest(assets / row['asset']))
            self.assertEqual(proof[row['path']], {key: row[key] for key in ('path', 'sha256', 'bytes')})
        self.assertEqual(rows[-1]['path'], 'run/dsha-launcher/native-consent.cjs')

    def test_capsule_dependency_collision_does_not_partially_publish(self):
        assets = module.ROOT / 'app/src/main/assets'
        target = next(iter(module.CAPSULE_DEPENDENCIES.values()))
        for proof, links in [({target: {'path': target}}, []), ({}, [{'path': target}])]:
            original = copy.deepcopy(proof)
            with self.assertRaisesRegex(ValueError, 'RECOVERY_CAPSULE_DEPENDENCY_COLLISION'):
                module.capsule_dependencies(assets, proof, links)
            self.assertEqual(proof, original)

    def test_exact_overlay_accepts_complete_preapplied_patch_but_rejects_extra_old_anchor(self):
        self.assertEqual(browser_overlay.exact('before + extra', 'before', 'before patched'), 'before patched + extra')
        self.assertEqual(browser_overlay.exact('before patched + extra', 'before', 'before patched'), 'before patched + extra')
        with self.assertRaisesRegex(ValueError, 'RECOVERY_OVERLAY_ANCHOR'):
            browser_overlay.exact('before patched + before', 'before', 'before patched')

    def test_android_overlay_inputs_match_the_generator_names(self):
        # build193 起描述使用 recovery-recipes/<版本>/ 键，Android 曾仍读顶层 recipe 名；两边必须同源。
        java = (module.ROOT / 'app/src/main/java/com/deepseekharness/app/util/RecoveryOverlayInputs.java').read_text(encoding='utf8')

        def literals(field):
            block = re.search(r'\b' + field + r'\s*=\s*\{(.*?)\};', java, re.S)
            self.assertIsNotNone(block, field)
            return tuple(re.findall(r'"([^"]+)"', block.group(1)))

        fixed, recipes = literals('FIXED'), literals('RECIPES')
        self.assertEqual(fixed, browser_overlay.INPUT_ASSETS)
        self.assertEqual(browser_overlay.input_assets(CURRENT_DSH),
                         fixed + tuple(browser_overlay.recipe_path(CURRENT_DSH, name) for name in recipes))
        runtime = (module.ROOT / 'app/src/main/java/com/deepseekharness/app/recovery/RecoveryRuntime.java').read_text(encoding='utf8')
        self.assertIn('RecoveryOverlayInputs.forVersion(descriptor.getString("dshVersion"))', runtime)
        self.assertNotIn('OVERLAY_INPUTS', runtime)

    def layout(self, root):
        shared, store, output = root / 'shared', root / 'pinned', root / 'output'
        shared.mkdir(); store.mkdir()
        rows = []
        for logical, source in module.SHARED_ASSETS.items():
            value = ('pinned:' + logical).encode()
            (store / logical).write_bytes(value)
            (shared / source).write_bytes(value)
            rows.append(dict(asset=logical, seed='shared/' + source, sha256=module.hashlib.sha256(value).hexdigest()))
        return dict(schema=1, archives=rows), shared, store, output

    def locate(self, lock, shared, store, output):
        return module.prepare_archive_locations(lock, output, shared, store, shared.parent)

    def archive(self,path,rows):
        with tarfile.open(path,'w:gz') as tar:
            for name,value,link in rows:
                member=tarfile.TarInfo(name)
                if link:
                    member.type=tarfile.SYMTYPE;member.linkname=value;tar.addfile(member)
                else:
                    content=value.encode();member.size=len(content);tar.addfile(member,io.BytesIO(content))

    def test_path_escape(self):
        for name in ['/etc/passwd','../settings.yaml','root/../../data','root\\home','root//home']:
            with self.subTest(name=name),self.assertRaises(ValueError):module.safe_name(name)
        self.assertEqual(module.safe_name('./usr/bin/bash'),'usr/bin/bash')

    def test_overlay_and_link_proof(self):
        with tempfile.TemporaryDirectory() as temp:
            base=Path(temp)/'base.tar.gz';runtime=Path(temp)/'runtime.tar.gz'
            self.archive(base,[('./usr/bin/node','old',False),('./bin','usr/bin',True)])
            self.archive(runtime,[('usr/bin/node','new',False),('usr/local/bin/dsh','../lib/dsh.js',True)])
            files,links=module.describe([base,runtime])
            self.assertEqual(files,[dict(path='usr/bin/node',sha256=module.hashlib.sha256(b'new').hexdigest(),bytes=3)])
            self.assertEqual(links,[dict(path='bin',target='usr/bin'),dict(path='usr/local/bin/dsh',target='usr/local/lib/dsh.js')])

    def test_link_outside_guest_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'bad.tar.gz';self.archive(path,[('root/x','../../outside',True)])
            with self.assertRaises(ValueError):module.describe([path])

    def test_absolute_guest_link_maps_inside(self):
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'guest.tar.gz';self.archive(path,[('root/x','/usr/lib/x',True)])
            self.assertEqual(module.describe([path])[1],[dict(path='root/x',target='usr/lib/x')])

    def test_equal_archives_share_bytes_but_keep_pinned_copy(self):
        with tempfile.TemporaryDirectory() as temp:
            lock, shared, store, output = self.layout(Path(temp))
            before = copy.deepcopy(lock)
            archives, locations = self.locate(lock, shared, store, output)
            self.assertEqual(lock, before)
            self.assertEqual(archives, [store / row['asset'] for row in lock['archives']])
            for row, location in zip(lock['archives'], locations):
                self.assertEqual(location, dict(asset=row['asset'], source=module.SHARED_ASSETS[row['asset']], sha256=row['sha256']))
                self.assertFalse((output / row['asset']).exists())
                self.assertEqual(module.digest(store / row['asset']), row['sha256'])

    def test_changed_formal_archive_keeps_locked_recovery_and_removes_stale_copy_on_return(self):
        with tempfile.TemporaryDirectory() as temp:
            lock, shared, store, output = self.layout(Path(temp))
            logical = 'recovery-dsh-runtime.bin'; source = module.SHARED_ASSETS[logical]
            # 正式版本升级时恢复为独立打包，不让应急版本漂移。
            (shared / source).write_bytes(b'new-formal-version')
            _, locations = self.locate(lock, shared, store, output)
            row = next(row for row in locations if row['asset'] == logical)
            self.assertEqual(row['source'], logical)
            self.assertEqual((output / logical).read_bytes(), (store / logical).read_bytes())
            sentinel = output / 'unrelated.bin'; sentinel.write_bytes(b'keep')
            (shared / source).write_bytes((store / logical).read_bytes())
            _, locations = self.locate(lock, shared, store, output)
            self.assertEqual(next(row for row in locations if row['asset'] == logical)['source'], source)
            self.assertFalse((output / logical).exists())
            self.assertEqual(sentinel.read_bytes(), b'keep')

    def test_missing_formal_archive_uses_pinned_copy(self):
        with tempfile.TemporaryDirectory() as temp:
            lock, shared, store, output = self.layout(Path(temp))
            for path in shared.iterdir(): path.unlink()
            _, locations = self.locate(lock, shared, store, output)
            for row in locations:
                self.assertEqual(row['asset'], row['source'])
                self.assertEqual(module.digest(output / row['source']), row['sha256'])

    def test_first_pin_requires_exact_seed_and_bad_pin_cannot_hide_behind_shared_bytes(self):
        with tempfile.TemporaryDirectory() as temp:
            lock, shared, store, output = self.layout(Path(temp))
            logical = 'recovery-rootfs.bin'; source = module.SHARED_ASSETS[logical]
            (store / logical).unlink()
            self.locate(lock, shared, store, output)
            self.assertEqual((store / logical).read_bytes(), (shared / source).read_bytes())
            (store / logical).write_bytes(b'corrupt')
            with self.assertRaisesRegex(ValueError, 'RECOVERY_ARCHIVE_HASH'):
                self.locate(lock, shared, store, output)
            (store / logical).unlink(); (shared / source).write_bytes(b'new-version')
            with self.assertRaisesRegex(ValueError, 'RECOVERY_PINNED_ARCHIVE_MISSING'):
                self.locate(lock, shared, store, output)

    def test_mapping_names_and_digests_are_bounded(self):
        with tempfile.TemporaryDirectory() as temp:
            lock, shared, store, output = self.layout(Path(temp))
            for asset in ['../victim.bin', '/recovery-rootfs.bin', 'recovery-other.bin', 'offline-rootfs.bin']:
                bad = copy.deepcopy(lock); bad['archives'][0]['asset'] = asset
                with self.subTest(asset=asset), self.assertRaisesRegex(ValueError, 'RECOVERY_LOCK_INVALID'):
                    self.locate(bad, shared, store, output)
            bad = copy.deepcopy(lock); bad['archives'][0]['sha256'] = 'wrong'
            with self.assertRaisesRegex(ValueError, 'RECOVERY_LOCK_HASH'):
                self.locate(bad, shared, store, output)
            with self.assertRaisesRegex(ValueError, 'RECOVERY_OUTPUT_OVERLAP'):
                self.locate(lock, shared, store, shared)

    def test_stale_output_symlink_is_rejected_without_deleting_target(self):
        with tempfile.TemporaryDirectory() as temp:
            lock, shared, store, output = self.layout(Path(temp)); output.mkdir()
            victim = Path(temp) / 'original'; victim.write_bytes(b'preserve')
            try:
                (output / 'recovery-rootfs.bin').symlink_to(victim)
            except OSError:
                self.skipTest('当前宿主不能创建软链接')
            with self.assertRaisesRegex(ValueError, 'RECOVERY_OUTPUT_ALIAS'):
                self.locate(lock, shared, store, output)
            self.assertEqual(victim.read_bytes(), b'preserve')

    def test_reported_output_alias_is_rejected_before_mutation_on_every_host(self):
        with tempfile.TemporaryDirectory() as temp:
            lock, shared, store, output = self.layout(Path(temp)); output.mkdir()
            target = output / 'recovery-rootfs.bin'; target.write_bytes(b'preserve')
            original = Path.is_symlink
            with patch.object(Path, 'is_symlink', lambda path: path == target or original(path)):
                with self.assertRaisesRegex(ValueError, 'RECOVERY_OUTPUT_ALIAS'):
                    self.locate(lock, shared, store, output)
            self.assertEqual(target.read_bytes(), b'preserve')

    def test_browser_overlay_precedes_application_script_and_worker_anchor_is_required(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);assets=root/'assets';(assets/'web-integration').mkdir(parents=True)
            (assets/'web-integration/es-compat.js').write_text('window.__ES_COMPAT__=true;',encoding='utf8')
            (assets/'web-integration/startup.js').write_text('window.__STARTUP__=true;',encoding='utf8')
            (assets/'web-integration/language.js').write_text('/* DSHA_UI_LANGUAGES_V3: fixture */\nfunction installDshaLanguageBridge(){}',encoding='utf8')
            (assets/browser_overlay.recipe_path(CURRENT_DSH, 'pdf-compat-patch.json')).parent.mkdir(parents=True)
            spec=dict(dshVersion=CURRENT_DSH,module=browser_overlay.TARGETS['client.pdf.js'].removeprefix(browser_overlay.PREFIX),
                      resourceModule=browser_overlay.TARGETS['client-resources.js'].removeprefix(browser_overlay.PREFIX),
                      asset='web-integration/es-compat.js',mainBefore='window.__ModuleLoader__.load({',
                      workerBefore='new Blob([_dsh_pdf_worker_default,',
                      resourceBefore='return hostname;',resourceAfter='return fixedHostname;')
            (assets/browser_overlay.recipe_path(CURRENT_DSH, 'pdf-compat-patch.json')).write_text(json.dumps(spec),encoding='utf8')
            language=dict(dshVersion=CURRENT_DSH,module=browser_overlay.TARGETS['client-locale.js'].removeprefix(browser_overlay.PREFIX),
                          patches=[dict(before='provide locale;',after='provide locale; effect language;',prependAsset='web-integration/language.js'),
                                   dict(before='resolve active;',after='resolve active from native;'),
                                   dict(before='choose locale;',after='choose locale and notify native;')])
            (assets/browser_overlay.recipe_path(CURRENT_DSH, 'language-patch.json')).write_text(json.dumps(language),encoding='utf8')
            archive=root/'runtime.tar.gz'
            def build_archive(pdf):
                self.archive(archive,[(browser_overlay.TARGETS['index.html'],
                    '<html><head><meta charset="utf-8" /><script src="app.js"></script></head></html>',False),
                    (browser_overlay.TARGETS['client.pdf.js'],pdf,False),
                     (browser_overlay.TARGETS['client-resources.js'],'return hostname;',False),
                     (browser_overlay.TARGETS['client-locale.js'],'provide locale; resolve active; choose locale;',False)])
            build_archive('window.__ModuleLoader__.load({\nnew Blob([_dsh_pdf_worker_default,')
            (assets/'pdf-compat-patch.json').write_text('{"dshVersion":"formal-new-generation"}', encoding='utf8')
            (assets/'language-patch.json').write_text('{"dshVersion":"formal-new-generation"}', encoding='utf8')
            raw, patched=browser_overlay.build(archive,assets,CURRENT_DSH)
            html=patched['index.html'].decode()
            self.assertLess(html.index('DSHA_BROWSER_COMPAT_BEGIN'),html.index('<script src="app.js"'))
            self.assertIn('window.__ES_COMPAT__=true;',html)
            self.assertIn('window.__STARTUP__=true;',html)
            self.assertIn('new Blob(["window.__ES_COMPAT__=true;\\n", _dsh_pdf_worker_default,',patched['client.pdf.js'].decode())
            self.assertEqual(raw['client-resources.js'],b'return hostname;')
            self.assertIn('installDshaLanguageBridge',patched['client-locale.js'].decode())
            self.assertIn('resolve active from native;',patched['client-locale.js'].decode())
            build_archive('window.__ModuleLoader__.load({\nno worker anchor')
            with self.assertRaisesRegex(ValueError,'RECOVERY_OVERLAY_ANCHOR'):
                browser_overlay.build(archive,assets,CURRENT_DSH)
            spec['dshVersion']='future-version'
            (assets/browser_overlay.recipe_path(CURRENT_DSH, 'pdf-compat-patch.json')).write_text(json.dumps(spec),encoding='utf8')
            with self.assertRaisesRegex(ValueError,'RECOVERY_PDF_RECIPE_PATH'):
                browser_overlay.build(archive,assets,CURRENT_DSH)
            spec['dshVersion']=CURRENT_DSH;(assets/browser_overlay.recipe_path(CURRENT_DSH, 'pdf-compat-patch.json')).write_text(json.dumps(spec),encoding='utf8')
            language['patches'][1]['before']='missing locale anchor'
            (assets/browser_overlay.recipe_path(CURRENT_DSH, 'language-patch.json')).write_text(json.dumps(language),encoding='utf8')
            with self.assertRaisesRegex(ValueError,'RECOVERY_OVERLAY_ANCHOR'):
                browser_overlay.build(archive,assets,CURRENT_DSH)


if __name__=='__main__':unittest.main()
