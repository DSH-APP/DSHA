#!/usr/bin/env python3
"""Verify file actions recipes reproduce, reject source drift, and apply idempotently."""
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
from runtime_stable_patches import apply
spec = importlib.util.spec_from_file_location('file_client_generator', Path(__file__).with_name('prepare-client-patches.py'))
generator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(generator)


def runtime():
    if os.environ.get('DSHA_TEST_RUNTIME'):
        return Path(os.environ['DSHA_TEST_RUNTIME']) / 'node_modules'
    return Path(json.loads((ROOT / 'app/build/test-runtimes/current.json').read_text(encoding='utf8'))['raw']) / 'node_modules'


class FileClientRecipesTest(unittest.TestCase):
    def test_exact_recipes_and_idempotent_application(self):
        generated = generator.generate(runtime())
        for asset, text in generated.items():
            with self.subTest(asset=asset):
                self.assertEqual(text, (ROOT / 'app/src/main/assets' / asset).read_text(encoding='utf8'))
                recipe = json.loads(text)
                patches = [(row['before'], row['after']) for row in recipe['patches']]
                raw = (runtime() / recipe['module']).read_bytes()
                chain = [(asset, patches, [])]
                patched = apply(raw, chain)
                self.assertNotEqual(patched, raw)
                self.assertEqual(apply(patched, chain), patched)
                with self.assertRaises(ValueError):
                    apply(raw.replace(patches[0][0].encode(), b'changed anchor'), chain)

    def test_source_drift_rejected_before_generation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            module = next(iter(generator.MODULES.values()))[0]
            target = root / module
            target.parent.mkdir(parents=True)
            target.write_bytes((runtime() / module).read_bytes() + b'\n')
            with self.assertRaisesRegex(ValueError, 'OFFICIAL_SOURCE_CHANGED'):
                generator.generate(root)

    def test_file_body_and_normal_browser_fallback_remain(self):
        text = generator.generate(runtime())['file-deliverables-patch.json']
        recipe = json.loads(text)
        raw = (runtime() / recipe['module']).read_bytes()
        patched = apply(raw, [('files', [(p['before'], p['after']) for p in recipe['patches']], [])]).decode()
        self.assertIn('openFile(file.path);', patched)
        self.assertIn('!host.available && typeof window.DSHA?.downloadArtifact !== "function"', patched)
        self.assertIn('absolutePath: file.path', patched)
        self.assertNotIn('isDirectory:', patched)

    def test_image_gallery_item_joins_upstream_image_menu(self):
        text = generator.generate(runtime())['file-image-actions-patch.json']
        recipe = json.loads(text)
        self.assertEqual(recipe['module'], '@deepseek-ai/dsh-client-ui-file-actions/lib/client.js')
        raw = (runtime() / recipe['module']).read_bytes()
        patched = apply(raw, [('image', [(p['before'], p['after']) for p in recipe['patches']], [])]).decode()
        image_menu = patched[patched.index('id: "file-actions.image"'):patched.index('"file actions: image menu"')]
        # One extra item, only with the Android bridge; upstream copy/download/save-as stay.
        self.assertEqual(image_menu.count('DSHA_IMAGE_GALLERY_MENU_V1'), 1)
        self.assertLess(image_menu.index('id: "dsha.saveToGallery"'), image_menu.index('id: "copy"'))
        self.assertIn('typeof window.DSHA?.saveImage === "function"', image_menu)
        for row in ('id: "copy"', 'id: "download"', 'id: "saveAs"'):
            self.assertIn(row, image_menu)
        self.assertNotIn('dsha.saveToGallery', patched[:patched.index('id: "file-actions.image"')])


if __name__ == '__main__':
    unittest.main()
