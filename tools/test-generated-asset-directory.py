#!/usr/bin/env python3
"""Exercise the owned-output boundary used by both APK asset generators."""

from pathlib import Path
import shutil
import io
import os
import tarfile
import subprocess
import sys
import tempfile
import unittest

from generated_asset_directory import BUILD, prune
from asset_deployment import load_manifest


class GeneratedAssetDirectoryTest(unittest.TestCase):
    def test_incremental_run_removes_only_omitted_assets(self):
        parent = BUILD / "tmp"
        parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="dsha-assets-", dir=parent) as temp:
            output = Path(temp) / "standardAssets"
            (output / "nested").mkdir(parents=True)
            (output / "current.js").write_text("current", encoding="utf-8")
            (output / "nested/obsolete.js").write_text("obsolete", encoding="utf-8")
            prune(output, {"current.js"})
            self.assertEqual((output / "current.js").read_text(encoding="utf-8"), "current")
            self.assertFalse((output / "nested/obsolete.js").exists())
            self.assertFalse((output / "nested").exists())

    def test_output_outside_build_is_not_pruned(self):
        with tempfile.TemporaryDirectory(prefix="dsha-not-generated-") as temp:
            output = Path(temp) / "user-files"
            output.mkdir()
            protected = output / "keep.txt"
            protected.write_text("keep", encoding="utf-8")
            with self.assertRaises(ValueError):
                prune(output, set())
            self.assertEqual(protected.read_text(encoding="utf-8"), "keep")

    def test_standard_generator_removes_an_asset_deleted_from_its_source(self):
        parent = BUILD / "tmp"
        parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="dsha-standard-incremental-", dir=parent) as temp:
            source = Path(temp) / "source"
            output = Path(temp) / "standardAssets"
            original = BUILD.parent / "src/main/assets"
            manifest=load_manifest()
            names=set(manifest['packagedFiles'])
            for tree in manifest['packagedTrees']:
                names.update(path.relative_to(original).as_posix() for path in (original/tree).rglob('*')
                             if path.is_file() and '__pycache__' not in path.parts)
            compatibility={'web-integration/es-compat.inputs.json','web-integration/es-compat.js',
                           'web-integration/compat.js','web-integration/startup.js','bridge-token-compat.cjs'}
            for name in names:
                target = source / name
                target.parent.mkdir(parents=True, exist_ok=True)
                # Structural JSON/compatibility contracts use current committed
                # text; executable/binary bodies are tiny fixture placeholders.
                # No offline rootfs/runtime/tool archive is needed by this test.
                if name.endswith('.json') or name in compatibility:shutil.copyfile(original/name,target)
                else:target.write_bytes(b'fixture member\n')
            for name in ['glibc-python.tar.gz','adb-wheels.tar.gz']:
                with tarfile.open(source/name,'w:gz') as archive:
                    info=tarfile.TarInfo('fixture.txt');body=b'owned small archive\n';info.size=len(body)
                    archive.addfile(info,io.BytesIO(body))
            marker = source / "web-integration/removed-on-next-build.js"
            marker.write_text("old asset", encoding="utf-8")

            def generate() -> None:
                run = subprocess.run([sys.executable, "-B", str(BUILD.parent.parent / "tools/prepare-standard-assets.py"),
                                      "--source", str(source), "--output", str(output)],
                                     cwd=BUILD.parent.parent, capture_output=True, text=True, encoding='utf-8',
                                     env=dict(os.environ,PYTHONIOENCODING='utf-8',PYTHONUTF8='1'))
                self.assertEqual(run.returncode, 0, run.stdout + run.stderr)

            generate()
            self.assertTrue((output / 'web-integration' / marker.name).is_file())
            for name in ['glibc-python.bin','adb-wheels.bin']:
                with tarfile.open(output/name,'r:gz') as archive:
                    self.assertEqual(archive.extractfile('fixture.txt').read(),b'owned small archive\n')
            marker.unlink()
            generate()
            self.assertFalse((output / 'web-integration' / marker.name).exists())
            unknown=source/'unregistered-old-helper.py';unknown.write_text('obsolete',encoding='utf-8')
            run=subprocess.run([sys.executable,'-B',str(BUILD.parent.parent/'tools/prepare-standard-assets.py'),
                                '--source',str(source),'--output',str(output)],capture_output=True,text=True,encoding='utf-8',
                                env=dict(os.environ,PYTHONIOENCODING='utf-8',PYTHONUTF8='1'))
            self.assertNotEqual(run.returncode,0);self.assertIn('ASSET_DEPLOYMENT_UNREGISTERED',run.stderr)
            self.assertFalse((output/unknown.name).exists())


if __name__ == "__main__":
    unittest.main()
