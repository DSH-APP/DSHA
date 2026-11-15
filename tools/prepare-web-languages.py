#!/usr/bin/env python3
"""Generate DSHA-owned web UI dictionaries and exact signed locale recipes.

Since DSH 0.2.2 upstream ships zh/en/ja/es/fr/de/ru/pt itself; DSHA only adds
zh-Hant/ko/ar/la and never re-registers an upstream language (LocaleRuntime
throws on a duplicate namespace locale).

No upstream installation, translation service, or user document is read here.
Extraction accepts static literals from the lock-bound raw npm fixture and the
current signed production patch chain, excluding this recipe's own dictionaries.
"""
import argparse
from collections import Counter
import hashlib
import importlib.util
import json
from pathlib import Path
import re
from source_text import write_text, matches_text
from web_language_catalog import DSHA_LANGUAGES, SOURCE_LANGUAGES, extract
from runtime_stable_patches import load_chains, apply
from test_runtime_fixture import runtime as verified_runtime

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'
MODULE = '@deepseek-ai/dsh-client-locale/lib/client.js'
SOURCE_SHA256 = '769747aa79323075eba9545ff9982770c0163e0bba3cabcc49ff56581c0f3613'
# Upstream addLanguage order in dsh-client-locale apply(); used only to order the allow-list.
UPSTREAM_LANGUAGES = ('ja', 'es', 'fr', 'de', 'ru', 'pt')
# 实验包（forge/liveshare/stats 等）文案允许 null：显式沿用语言回退链（zh-Hant→zh，其余→en）。
EXPERIMENTAL_MODULE_PREFIX = '@deepseek-ai/dsh-experimental-'


def locked_version():
    """The DSH version comes from the lock, never from a hardcoded generator constant."""
    package = json.loads((ROOT / 'tools/dsh-runtime/package.json').read_text(encoding='utf8'))
    lock = json.loads((ROOT / 'tools/dsh-runtime/package-lock.json').read_text(encoding='utf8'))
    version = package['dependencies']['@deepseek-ai/dsh']
    if lock['packages']['node_modules/@deepseek-ai/dsh']['version'] != version:
        raise ValueError('WEB_LANGUAGE_LOCK_VERSION')
    return version


VERSION = locked_version()


def official_catalog(runtime):
    runtime = Path(runtime)
    lock = (ROOT / 'tools/dsh-runtime/package-lock.json').read_bytes()
    if (runtime / 'package-lock.json').read_bytes() != lock:
        raise ValueError('WEB_LANGUAGE_LOCK_CHANGED')
    package = json.loads((runtime / 'node_modules/@deepseek-ai/dsh/package.json').read_text(encoding='utf8'))
    if package['name'] != '@deepseek-ai/dsh' or package['version'] != VERSION:
        raise ValueError('WEB_LANGUAGE_PACKAGE_GENERATION')
    spec = importlib.util.spec_from_file_location('web_language_runtime_builder', ROOT / 'tools/build-dsh-runtime.py')
    builder = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(builder)
    chains, _ = load_chains(ASSETS, VERSION)
    def transform(relative, data):
        prepared = builder.patched_content(Path(relative), data)
        chain = [row for row in chains.get(relative, []) if row[0] != 'language-patch.json']
        return apply(prepared, chain)
    return extract(runtime / 'node_modules', transform, VERSION)


def read_translations(catalog):
    messages = catalog['messages']
    expected = {row['id'] for row in messages}
    result = {}
    for tag in DSHA_LANGUAGES:
        data = json.loads((ROOT / 'tools/i18n/web-translations' / (tag + '.json')).read_text(encoding='utf8'))
        if set(data) != expected:
            raise ValueError('WEB_TRANSLATION_IDS: ' + tag)
        for row in messages:
            value = data[row['id']]
            if value is None:
                if not experimental(catalog, row):
                    raise ValueError('WEB_TRANSLATION_FALLBACK_NOT_EXPERIMENTAL: ' + tag + ':' + row['id'])
                continue
            format_separator = row['id'] == 'chat:number.groupSeparator' and value in (' ', '\u00a0', '\u202f', ',', '.')
            if not isinstance(value, str) or '\0' in value or (not value.strip() and row['en'].strip() and not format_separator):
                raise ValueError('WEB_TRANSLATION_TEXT: ' + tag + ':' + row['id'])
            if Counter(re.findall(r'\{\w+\}', value)) != Counter(re.findall(r'\{\w+\}', row['en'])):
                raise ValueError('WEB_TRANSLATION_ARGUMENTS: ' + tag + ':' + row['id'])
        result[tag] = data
    return result


def experimental(catalog, row):
    return catalog['namespaces'][row['namespace']]['module'].startswith(EXPERIMENTAL_MODULE_PREFIX)


def language_tags(catalog):
    upstream = set()
    for namespace in catalog['namespaces'].values():
        upstream.update(namespace['upstreamLocales'])
    if not upstream <= set(UPSTREAM_LANGUAGES):
        raise ValueError('WEB_LANGUAGE_UPSTREAM_UNKNOWN: ' + ','.join(sorted(upstream - set(UPSTREAM_LANGUAGES))))
    return list(SOURCE_LANGUAGES) + [tag for tag in UPSTREAM_LANGUAGES if tag in upstream] + list(DSHA_LANGUAGES)


def validate_source(catalog):
    if catalog.get('schema') != 2 or catalog.get('dshVersion') != VERSION \
            or catalog.get('inputs', {}).get(MODULE) != SOURCE_SHA256:
        raise ValueError('WEB_LANGUAGE_SOURCE_GENERATION')
    ids = set()
    namespaces = catalog.get('namespaces')
    if not isinstance(namespaces, dict) or any(not isinstance(value.get('module'), str)
                                               or not isinstance(value.get('upstreamLocales'), list)
                                               or set(value['upstreamLocales']) & {tag.lower() for tag in DSHA_LANGUAGES}
                                               for value in namespaces.values()):
        raise ValueError('WEB_LANGUAGE_SOURCE_NAMESPACES')
    for row in catalog['messages']:
        if row['namespace'] not in namespaces:
            raise ValueError('WEB_LANGUAGE_SOURCE_NAMESPACES')
        if row['id'] != row['namespace'] + ':' + row['key'] or row['id'] in ids \
                or not isinstance(row['zh'], str) or not isinstance(row['en'], str):
            raise ValueError('WEB_LANGUAGE_SOURCE_IDS')
        ids.add(row['id'])
    return catalog


def outputs(catalog, translations):
    groups = {}
    for row in catalog['messages']:
        values = [translations[tag][row['id']] for tag in DSHA_LANGUAGES]
        if any(value is not None for value in values):
            groups.setdefault(row['namespace'], []).append([row['key'], values])
    packed = json.dumps(list(groups.items()), ensure_ascii=False, separators=(',', ':'))
    template = (ROOT / 'tools/i18n/web-language-bridge.js').read_text(encoding='utf8')
    if template.count('/* DSHA_WEB_CATALOG */') != 1:
        raise ValueError('WEB_LANGUAGE_TEMPLATE_ANCHOR')
    helper = template.replace('/* DSHA_WEB_CATALOG */', packed)
    if len(helper.encode('utf8')) > 8 * 1024 * 1024:
        raise ValueError('WEB_LANGUAGE_HELPER_LIMIT')
    tags = json.dumps(language_tags(catalog))
    lock = json.loads((ROOT / 'tools/dsh-runtime/package-lock.json').read_text(encoding='utf8'))
    recipe = dict(dshVersion=VERSION, module=MODULE, patches=[
        dict(before='ctx.provide("locale", locale);',
             after='ctx.provide("locale", locale);\n            ctx.effect(() => installDshaLanguageBridge(locale), "DSHA application language");',
             prependAsset='web-integration/language.js'),
        dict(before='resolveActive() {\n\t\t\t\tif (this.preference === void 0) return this.provisional;',
             after='resolveActive() {\n                if (typeof window !== "undefined") { const dshaLanguage = typeof window.__dshaEffectiveLanguage === "function" ? window.__dshaEffectiveLanguage() : window.__DSHA_LANGUAGE__; if (' + tags + '.includes(dshaLanguage) && this.catalog.has(localeKey(dshaLanguage))) return dshaLanguage; }\n\t\t\t\tif (this.preference === void 0) return this.provisional;'),
        dict(before='this.preference = match.id;\n\t\t\t\tif (this.snapshot.active !== match.id)',
             after='this.preference = match.id;\n                if (typeof window !== "undefined") { if (typeof window.__dshaSelectWebLanguage === "function") window.__dshaSelectWebLanguage(match.id); else if (window.__DSHA_LANGUAGE__ && window.__DSHA_LANGUAGE__ !== match.id) { window.__DSHA_LANGUAGE__ = match.id; window.dispatchEvent(new CustomEvent("dsha-language-selected", { detail: match.id })); } }\n\t\t\t\tif (this.snapshot.active !== match.id)')
    ], sourceSha256=SOURCE_SHA256,
        packageIntegrity=lock['packages']['node_modules/@deepseek-ai/dsh-client-locale']['integrity'])
    # 0.2.2 起不再声明 previousPatches：V1 前代形态（原 tools/fixtures/language-v1-patch.json，已删除）
    # 只存在于 0.2.1-rc.3 归档，绑定的是旧 locale 源码；DSH 版本变化时受管树整体
    # 由新归档替换，该前代在 0.2.2 模块上不可能出现，继续声明只是伪造的证明。
    text = json.dumps(recipe, ensure_ascii=False, indent=2) + '\n'
    generated = {'web-integration/language.js': helper, 'language-patch.json': text}
    # 独立应急 runtime 由 tools/recovery-runtime/lock.json 单独固定。只有救援
    # 版本与当前正式 runtime 一致时才生成同内容应急 recipe；正式升级不得
    # 静默新增或替换救援输入。
    recovery_version = json.loads(
        (ROOT / 'tools/recovery-runtime/lock.json').read_text(encoding='utf8')).get('dshVersion')
    if recovery_version == VERSION:
        generated['recovery-recipes/' + VERSION + '/language-patch.json'] = text
    return generated


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path)
    parser.add_argument('--extract', action='store_true', help='write the source catalog only; never translates it')
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--catalog-only', action='store_true', help='offline build check of fixed catalog and generated outputs; no upstream fixture required')
    args = parser.parse_args()
    source = ROOT / 'tools/i18n/web-messages.json'
    if args.catalog_only:
        if not args.check or args.extract:
            parser.error('--catalog-only requires --check and cannot extract')
        catalog = validate_source(json.loads(source.read_text(encoding='utf8')))
    else:
        runtime = args.runtime or Path(json.loads((ROOT / 'app/build/test-runtimes/current.json').read_text(encoding='utf8'))['raw'])
        runtime = verified_runtime('raw', runtime)
        actual = hashlib.sha256((runtime / 'node_modules' / MODULE).read_bytes()).hexdigest()
        if actual != SOURCE_SHA256:
            raise ValueError('WEB_LANGUAGE_OFFICIAL_SOURCE_CHANGED')
        catalog = validate_source(official_catalog(runtime))
    encoded = json.dumps(catalog, ensure_ascii=False, indent=2) + '\n'
    if args.extract:
        write_text(source, encoded)
        print('Extracted ' + str(len(catalog['messages'])) + ' locked UI messages')
        return
    if not matches_text(source, encoded):
        raise ValueError('WEB_LANGUAGE_SOURCE_CATALOG_STALE')
    generated = outputs(catalog, read_translations(catalog))
    for name, content in generated.items():
        target = ASSETS / name
        if args.check:
            if not matches_text(target, content):
                raise ValueError('WEB_LANGUAGE_GENERATED_ASSET_STALE: ' + name)
        else:
            write_text(target, content)
    print('PASS ' + str(len(catalog['messages'])) + ' DSH UI messages; DSHA languages ' + ','.join(DSHA_LANGUAGES)
          + ' on top of upstream ' + ','.join(language_tags(catalog)[:-len(DSHA_LANGUAGES)]) + '; ' + str(len(generated['web-integration/language.js'].encode('utf8'))) + ' helper bytes')


if __name__ == '__main__':
    main()
