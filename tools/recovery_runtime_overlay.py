"""Build fixed recovery browser overlays from the independently pinned archive.

The signed APK carries final bytes separately from the pinned archive. Android
checks both digests before replacing a candidate file, so a changed upstream
anchor cannot silently produce a partially patched recovery capsule.
"""

import json
import re
import tarfile
from pathlib import Path


PREFIX = 'usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/'
TARGETS = {
    'index.html': PREFIX + '@deepseek-ai/dsh-web-frontend/dist/index.html',
    'client.pdf.js': PREFIX + '@deepseek-ai/dsh-client-ui-sidebar-documentpreview/lib/client.pdf.js',
    'client-resources.js': PREFIX + '@deepseek-ai/dsh-client-resources/lib/client.js',
    'client-locale.js': PREFIX + '@deepseek-ai/dsh-client-locale/lib/client.js',
}
MAX_SOURCE_BYTES = {'index.html': 1024 * 1024, 'client.pdf.js': 12 * 1024 * 1024,
                    'client-resources.js': 1024 * 1024, 'client-locale.js': 8 * 1024 * 1024}
INPUT_ASSETS = ('web-integration/es-compat.js', 'web-integration/startup.js', 'web-integration/language.js')


def recipe_path(dsh_version, name):
    # A formal DSH upgrade must never silently select a recipe for the pinned rescue ABI.
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+(?:-[a-zA-Z0-9.-]+)?', dsh_version):
        raise ValueError('RECOVERY_RECIPE_VERSION')
    return 'recovery-recipes/' + dsh_version + '/' + name


def input_assets(dsh_version):
    return INPUT_ASSETS + tuple(recipe_path(dsh_version, name)
                                for name in ('pdf-compat-patch.json', 'language-patch.json'))
CAPSULE_DEPENDENCIES = {
    **{'destructive-policy/' + name:
       PREFIX + 'dsha-destructive-policy/' + name
       for name in ('package.json', 'index.js', 'destructive.js', 'workdir.js')},
    'recovery-native-consent.cjs': 'run/dsha-launcher/native-consent.cjs',
}


def exact(source, before, after):
    if not before or not after or before == after:
        raise ValueError('RECOVERY_OVERLAY_ANCHOR')
    # Current archives already carry active signed patch chains. Accept only the
    # complete exact replacement, with no extra old anchor alongside it.
    if source.count(after) == 1 and source.count(before) == after.count(before):
        return source
    if source.count(before) != 1 or after in source:
        raise ValueError('RECOVERY_OVERLAY_ANCHOR')
    return source.replace(before, after, 1)


def html_bootstrap(source, javascript):
    if '<!-- DSHA_BROWSER_COMPAT_BEGIN -->' in source or '<!-- DSHA_BROWSER_COMPAT_END -->' in source:
        raise ValueError('RECOVERY_HTML_ALREADY_PATCHED')
    if source.count('<head>') != 1:
        raise ValueError('RECOVERY_HTML_HEAD')
    block = '<!-- DSHA_BROWSER_COMPAT_BEGIN -->\n<script>' \
        + re.sub(r'</script', r'<\\/script', javascript, flags=re.IGNORECASE) \
        + '</script>\n<!-- DSHA_BROWSER_COMPAT_END -->'
    position = source.index('<head>') + len('<head>')
    charset = '<meta charset="utf-8" />'
    meta = source.find(charset, position)
    first_script = source.find('<script', position)
    if meta >= 0 and (first_script < 0 or meta < first_script):
        position = meta + len(charset)
    return source[:position] + '\n' + block + source[position:]


def source_files(archive: Path):
    found = {}
    with tarfile.open(archive, 'r:gz') as tree:
        for item in tree:
            name = item.name.removeprefix('./')
            if name not in TARGETS.values():
                continue
            key = next(label for label, target in TARGETS.items() if target == name)
            if key in found or not item.isfile() or item.size < 1 or item.size > MAX_SOURCE_BYTES[key]:
                raise ValueError('RECOVERY_OVERLAY_SOURCE: ' + key)
            found[key] = tree.extractfile(item).read()
    if set(found) != set(TARGETS):
        raise ValueError('RECOVERY_OVERLAY_SOURCE_MISSING')
    return found


LANGUAGE_MARKER = re.compile(r'/\* DSHA_UI_LANGUAGES_V[0-9]+:')


def language_overlay(locale, language, assets):
    """Replace only a complete signed predecessor, and reject stacked helpers."""
    previous = language.get('previousPatches', [])
    if previous and not LANGUAGE_MARKER.search(locale):
        if len(previous) != 3 or any(not isinstance(row.get('before'), str)
                                     or not isinstance(row.get('after'), str) for row in previous):
            raise ValueError('RECOVERY_LANGUAGE_PREVIOUS_RECIPE')
        if all(locale.count(row['after']) == 1 for row in previous):
            for row in reversed(previous):
                locale = locale.replace(row['after'], row['before'], 1)
        elif 'function installDshaLanguageBridge' in locale:
            raise ValueError('RECOVERY_LANGUAGE_PARTIAL_PREVIOUS_RECIPE')
    for patch in language['patches']:
        before, after = patch.get('before'), patch.get('after')
        if not isinstance(before, str) or not isinstance(after, str):
            raise ValueError('RECOVERY_LANGUAGE_RECIPE_ANCHOR')
        if 'prependAsset' in patch:
            after = (assets / patch['prependAsset']).read_text(encoding='utf8') + '\n' + after
        locale = exact(locale, before, after)
    # 无论是否声明前代形态，结果都必须恰好一个桥接函数与一个版本标记（V2/V3…）。
    if locale.count('function installDshaLanguageBridge') != 1 or len(LANGUAGE_MARKER.findall(locale)) != 1:
        raise ValueError('RECOVERY_LANGUAGE_DUPLICATE_HELPER')
    return locale


def build(archive: Path, assets: Path, dsh_version: str):
    raw = source_files(archive)
    spec = json.loads((assets / recipe_path(dsh_version, 'pdf-compat-patch.json')).read_text(encoding='utf8'))
    if spec.get('dshVersion') != dsh_version \
            or spec.get('module') != TARGETS['client.pdf.js'].removeprefix(PREFIX) \
            or spec.get('resourceModule') != TARGETS['client-resources.js'].removeprefix(PREFIX) \
            or spec.get('asset') != 'web-integration/es-compat.js':
        raise ValueError('RECOVERY_PDF_RECIPE_PATH')
    compatibility = (assets / spec['asset']).read_text(encoding='utf8').strip().replace('\n', ' ')
    html_script = (assets / spec['asset']).read_text(encoding='utf8') + '\n' \
        + (assets / 'web-integration/startup.js').read_text(encoding='utf8')
    html = html_bootstrap(raw['index.html'].decode('utf8'), html_script)
    if not (html.index('DSHA_BROWSER_COMPAT_BEGIN') < html.index('<script')
            < html.index('DSHA_BROWSER_COMPAT_END')
            < html.index('<script', html.index('DSHA_BROWSER_COMPAT_END'))):
        raise ValueError('RECOVERY_HTML_NOT_EARLY')
    pdf = raw['client.pdf.js'].decode('utf8')
    pdf = exact(pdf, spec['mainBefore'], '/* DSHA_PDF_COMPAT_V1 */ ' + compatibility + ' ' + spec['mainBefore'])
    pdf = exact(pdf, spec['workerBefore'], 'new Blob([' + json.dumps(compatibility + '\n', ensure_ascii=False)
                + ', _dsh_pdf_worker_default,')
    resources = exact(raw['client-resources.js'].decode('utf8'), spec['resourceBefore'], spec['resourceAfter'])
    language = json.loads((assets / recipe_path(dsh_version, 'language-patch.json')).read_text(encoding='utf8'))
    if language.get('dshVersion') != dsh_version \
            or language.get('module') != TARGETS['client-locale.js'].removeprefix(PREFIX) \
            or not isinstance(language.get('patches'), list) or len(language['patches']) != 3 \
            or language['patches'][0].get('prependAsset') != 'web-integration/language.js' \
            or any('prependAsset' in patch for patch in language['patches'][1:]):
        raise ValueError('RECOVERY_LANGUAGE_RECIPE_PATH')
    # The independently pinned recovery archive can carry the old signed two-
    # language overlay. Restore that complete known form before installing its
    # successor. Never stack two helpers/effects or borrow the formal runtime.
    locale = language_overlay(raw['client-locale.js'].decode('utf8'), language, assets)
    return raw, {'index.html': html.encode('utf8'), 'client.pdf.js': pdf.encode('utf8'),
                 'client-resources.js': resources.encode('utf8'), 'client-locale.js': locale.encode('utf8')}
