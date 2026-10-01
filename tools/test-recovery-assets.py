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
import recovery_runtime_overlay as browser_overlay

CURRENT_DSH = json.loads((Path(__file__).parents[1] / 'tools/dsh-runtime/package.json').read_text(encoding='utf8'))['dependencies']['@deepseek-ai/dsh']

spec=importlib.util.spec_from_file_location('recovery_assets',Path(__file__).with_name('prepare-recovery-assets.py'))
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)


class RecoveryAssetTests(unittest.TestCase):
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
            (assets/'web-integration/language.js').write_text('function installDshaLanguageBridge(){}',encoding='utf8')
            spec=dict(dshVersion=CURRENT_DSH,module=browser_overlay.TARGETS['client.pdf.js'].removeprefix(browser_overlay.PREFIX),
                      resourceModule=browser_overlay.TARGETS['client-resources.js'].removeprefix(browser_overlay.PREFIX),
                      asset='web-integration/es-compat.js',mainBefore='window.__ModuleLoader__.load({',
                      workerBefore='new Blob([_dsh_pdf_worker_default,',
                      resourceBefore='return hostname;',resourceAfter='return fixedHostname;')
            (assets/'pdf-compat-patch.json').write_text(json.dumps(spec),encoding='utf8')
            language=dict(dshVersion=CURRENT_DSH,module=browser_overlay.TARGETS['client-locale.js'].removeprefix(browser_overlay.PREFIX),
                          patches=[dict(before='provide locale;',after='provide locale; effect language;',prependAsset='web-integration/language.js'),
                                   dict(before='resolve active;',after='resolve active from native;'),
                                   dict(before='choose locale;',after='choose locale and notify native;')])
            (assets/'language-patch.json').write_text(json.dumps(language),encoding='utf8')
            archive=root/'runtime.tar.gz'
            def build_archive(pdf):
                self.archive(archive,[(browser_overlay.TARGETS['index.html'],
                    '<html><head><meta charset="utf-8" /><script src="app.js"></script></head></html>',False),
                    (browser_overlay.TARGETS['client.pdf.js'],pdf,False),
                     (browser_overlay.TARGETS['client-resources.js'],'return hostname;',False),
                     (browser_overlay.TARGETS['client-locale.js'],'provide locale; resolve active; choose locale;',False)])
            build_archive('window.__ModuleLoader__.load({\nnew Blob([_dsh_pdf_worker_default,')
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
            (assets/'pdf-compat-patch.json').write_text(json.dumps(spec),encoding='utf8')
            with self.assertRaisesRegex(ValueError,'RECOVERY_PDF_RECIPE_PATH'):
                browser_overlay.build(archive,assets,CURRENT_DSH)
            spec['dshVersion']=CURRENT_DSH;(assets/'pdf-compat-patch.json').write_text(json.dumps(spec),encoding='utf8')
            language['patches'][1]['before']='missing locale anchor'
            (assets/'language-patch.json').write_text(json.dumps(language),encoding='utf8')
            with self.assertRaisesRegex(ValueError,'RECOVERY_OVERLAY_ANCHOR'):
                browser_overlay.build(archive,assets,CURRENT_DSH)


if __name__=='__main__':unittest.main()
