#!/usr/bin/env python3
"""Generate exact file-slot and image gallery-menu recipes from locked npm originals."""
import argparse
import hashlib
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
from source_text import write_text, matches_text

VERSION = '0.2.2-rc.1'
MODULES = {
    'file-deliverables-patch.json': ('@deepseek-ai/dsh-client-ui-deliverables/lib/client.js',
        '0aa3fc762aa1debf106a67dccfbb7a640f40de0c774a2b091a8ddc6730257da5'),
    # 0.2.2-rc.1: images use the upstream context menu (long press = contextmenu in
    # Android WebView). DSHA adds one "save to gallery" item to the upstream image
    # menu instead of competing pointer handlers on the attachment button.
    'file-image-actions-patch.json': ('@deepseek-ai/dsh-client-ui-file-actions/lib/client.js',
        '437f70ba26ff1a11efc0aeb547e3a799948f55e8cabef69839dbc481e96ed40d'),
}


def generate(module_root, lock_path=None):
    lock = json.loads((lock_path or ROOT / 'tools/dsh-runtime/package-lock.json').read_text(encoding='utf8'))
    results = {}
    for asset, (module, digest) in MODULES.items():
        target = module_root / module
        data = target.read_bytes()
        if hashlib.sha256(data).hexdigest() != digest:
            raise ValueError('FILE_ACTIONS_OFFICIAL_SOURCE_CHANGED: ' + module)
        package_name = module.removesuffix('/lib/client.js')
        package = json.loads((target.parent.parent / 'package.json').read_text(encoding='utf8'))
        locked = lock['packages']['node_modules/' + package_name]
        if package['name'] != package_name or package['version'] != VERSION or locked['version'] != VERSION:
            raise ValueError('FILE_ACTIONS_PACKAGE_VERSION_CHANGED')
        source = data.decode('utf8')
        patches = []

        def replace(before, after):
            if source.count(before) != 1:
                raise ValueError('FILE_ACTIONS_ANCHOR_CHANGED: ' + before[:100])
            patches.append(dict(before=before, after=after))

        if asset == 'file-deliverables-patch.json':
            # Official PresentedFile has only path/description. Do not invent a
            # directory kind, infer from display strings, or decode action URLs.
            for slot in ['deliverables.file.actions', 'deliverables.review.file.actions']:
                anchor = 'renderSlot("' + slot + '", {\n'
                replace(anchor, anchor + '\t\t\t\t\t\t\t\t/* DSHA_FILE_DELIVERABLES_RC2_V1: exact owner coordinates. */\n'
                        + '\t\t\t\t\t\t\t\tsessionId,\n\t\t\t\t\t\t\t\tabsolutePath: file.path,\n')
            before = 'host !== null && host !== "error" && !host.available && (0, react_jsx_runtime.jsx)("span", {'
            replace(before, 'host !== null && host !== "error" && !host.available && typeof window.DSHA?.downloadArtifact !== "function" && (0, react_jsx_runtime.jsx)("span", {')
            before = 'host === "error" && (0, react_jsx_runtime.jsxs)("div", {'
            replace(before, 'host === "error" && typeof window.DSHA?.downloadArtifact !== "function" && (0, react_jsx_runtime.jsxs)("div", {')
        else:
            before = '''\t\t\t\t\treturn [
\t\t\t\t\t\t{
\t\t\t\t\t\t\tid: "copy",
\t\t\t\t\t\t\ticon: { key: "file-actions.copy" },'''
            after = '''\t\t\t\t\treturn [
\t\t\t\t\t\t// DSHA_IMAGE_GALLERY_MENU_V1: Android only. The native bridge saves the
\t\t\t\t\t\t// original same-origin Blob to the system gallery; browsers keep upstream rows.
\t\t\t\t\t\t...typeof window.DSHA?.saveImage === "function" && typeof window.DSHAFileTransfer?.imageMenu === "function" && typeof source.url === "string" && source.url.startsWith("blob:" + location.origin + "/") ? [{
\t\t\t\t\t\t\tid: "dsha.saveToGallery",
\t\t\t\t\t\t\ticon: { key: "file-actions.save" },
\t\t\t\t\t\t\tfailureLabel: t("menu.saveFailed"),
\t\t\t\t\t\t\tlabel: document.documentElement.lang?.startsWith("zh") ? "保存到相册" : "Save to gallery",
\t\t\t\t\t\t\trun: () => {
\t\t\t\t\t\t\t\tif (!window.DSHAFileTransfer?.imageMenu(source.url, source.name)) throw new Error("Gallery save unavailable");
\t\t\t\t\t\t\t}
\t\t\t\t\t\t}] : [],
\t\t\t\t\t\t{
\t\t\t\t\t\t\tid: "copy",
\t\t\t\t\t\t\ticon: { key: "file-actions.copy" },'''
            replace(before, after)
        results[asset] = json.dumps(dict(version=1, dshVersion=VERSION, module=module,
            sourceSha256=digest, packageIntegrity=locked['integrity'], patches=patches), ensure_ascii=False, indent=2) + '\n'
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--module-root', required=True, type=Path, help='Unmodified locked npm node_modules directory')
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--lock', type=Path)
    options = parser.parse_args()
    for asset, expected in generate(options.module_root, options.lock).items():
        destination = ROOT / 'app/src/main/assets' / asset
        if options.check:
            if not matches_text(destination, expected):
                raise ValueError('FILE_ACTIONS_RECIPE_STALE: ' + asset)
        else:
            write_text(destination, expected, encoding='utf8')
        print('PASS locked rc5 file actions recipe: ' + asset)


if __name__ == '__main__':
    main()
