#!/usr/bin/env python3
"""Validate multi-language catalogue inputs and deterministic Java/resource generation."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from ui_language_catalog import LANGUAGES, RESOURCE_FOLDERS, load_catalog, android_format_signature

HERE = Path(__file__).resolve().parent


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


PREPARE = module('prepare_languages', 'prepare-ui-languages.py')
LOCALIZE = module('localize_languages', 'localize-android-ui.py')
WEB = module('prepare_web_languages', 'prepare-web-languages.py')


class LanguageCatalogTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.messages = [
            {'id': 'm1', 'zh': '下一步', 'en': 'Next'},
            {'id': 'm2', 'zh': '第%s项：%d%%', 'en': 'Item %s: %d%%', 'uiFormat': True},
        ]
        self.write('tools/i18n/messages.json', self.messages)
        self.write('tools/i18n/android-resources.json', {
            'schema':1,
            'standardEdition':{tag:tag+' edition' for tag in LANGUAGES},
            'pluginCount':{tag:{q:'%d plugins' for q in ('zero','one','two','few','many','other')} for tag in LANGUAGES},
        })
        for tag in LANGUAGES:
            if tag not in ('zh', 'en'):
                self.write('tools/i18n/translations/' + tag + '.json',
                           {item['id']: tag + ':' + item['en'] for item in self.messages})

    def write(self, path, value):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(value, ensure_ascii=False), encoding='utf-8')

    def test_missing_language_or_translation_fails_before_generation(self):
        path = self.root / 'tools/i18n/translations/ar.json'
        path.unlink()
        with self.assertRaises(FileNotFoundError):
            load_catalog(self.root)
        self.write('tools/i18n/translations/ar.json', {'m1': 'التالي'})
        with self.assertRaisesRegex(ValueError, 'UI_CATALOG_IDS: ar'):
            load_catalog(self.root)

    def test_format_arguments_cannot_be_dropped_reordered_or_retyped(self):
        for bad in ('Item %s', 'Item %d: %s%%', 'Item %s: %s%%', 'Item %s: %f%%'):
            self.write('tools/i18n/translations/ar.json', {'m1': 'التالي', 'm2': bad})
            with self.assertRaisesRegex(ValueError, 'UI_FORMAT_'):
                load_catalog(self.root)

    def test_android_indexed_arguments_can_reorder_but_not_change_identity(self):
        self.assertEqual(android_format_signature('%1$s / %2$d'), android_format_signature('%2$d: %1$s'))
        self.assertNotEqual(android_format_signature('%1$s / %2$d'), android_format_signature('%1$d: %2$s'))
        with self.assertRaisesRegex(ValueError, 'UI_ANDROID_FORMAT_'):
            android_format_signature('%0$s')

    def test_android_plural_resources_require_every_language_quantity_and_count_argument(self):
        data=LOCALIZE.android_resources(self.root)
        data['pluginCount']['ar'].pop('two')
        self.write('tools/i18n/android-resources.json',data)
        with self.assertRaisesRegex(ValueError,'UI_ANDROID_PLURAL_QUANTITIES: ar'):
            LOCALIZE.android_resources(self.root)
        data['pluginCount']['ar']['two']='%s plugins'
        self.write('tools/i18n/android-resources.json',data)
        with self.assertRaisesRegex(ValueError,'UI_ANDROID_PLURAL_FORMAT: ar'):
            LOCALIZE.android_resources(self.root)

    def test_supplemental_messages_merge_without_changing_main_catalog(self):
        self.write('tools/i18n/messages-extra.json', [{'id': 'x1971', 'zh': '运行', 'en': 'Run'}])
        for tag in LANGUAGES:
            if tag not in ('zh', 'en'):
                self.write('tools/i18n/translations-extra/' + tag + '.json', {'x1971': tag + ':Run'})
        messages, catalogs = load_catalog(self.root)
        self.assertEqual(3, len(messages))
        self.assertEqual('ar:Run', catalogs['ar']['x1971'])
        self.assertEqual(2, len(json.loads((self.root / 'tools/i18n/messages.json').read_text(encoding='utf-8'))))

    def test_catalogs_reject_duplicate_source_and_unknown_ids(self):
        self.write('tools/i18n/translations/ar.json', {'m1': 'التالي', 'm2': 'Item %s: %d%%', 'unknown': 'x'})
        with self.assertRaisesRegex(ValueError, 'UI_CATALOG_IDS'):
            load_catalog(self.root)
        self.write('tools/i18n/messages-extra.json', [{'id': 'x1971', 'zh': '下一步', 'en': 'Next'}])
        with self.assertRaisesRegex(ValueError, 'UI_DUPLICATE_MESSAGE_SOURCE'):
            load_catalog(self.root)

    def test_generation_partitions_language_classes_and_generates_script_resources(self):
        output = self.root / 'generated'
        PREPARE.generate(self.root, output)
        package = output / 'com/deepseekharness/app/util'
        self.assertEqual(11, len(list(package.glob('*.java'))))
        registry = (package / 'UiMessages.java').read_text(encoding='utf-8')
        self.assertIn('case "zh-Hant": return UiMessages_zh_Hant.VALUES;', registry)
        values = self.root / 'app/src/main/res/values'
        values.mkdir(parents=True)
        (values / 'ui_strings.xml').write_text('<resources><string name="ui_m1">下一步</string></resources>', encoding='utf-8')
        (values / 'strings.xml').write_text('<resources><string name="named">下一步</string></resources>', encoding='utf-8')
        messages, catalogs = load_catalog(self.root)
        LOCALIZE.resources(messages, catalogs, self.root, migrate=False)
        LOCALIZE.resources(messages, catalogs, self.root, migrate=False, check=True)
        for tag in LANGUAGES:
            self.assertTrue((self.root / 'app/src/main/res' / RESOURCE_FOLDERS[tag] / 'ui_strings.xml').is_file())
            self.assertTrue((self.root / 'app/src/standard/res' / RESOURCE_FOLDERS[tag] / 'edition.xml').is_file())
        (self.root / 'app/src/main/res/values-ar/ui_strings.xml').write_text('<resources/>', encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'UI_RESOURCE_STALE'):
            LOCALIZE.resources(messages, catalogs, self.root, migrate=False, check=True)


class WebLanguageCatalogTest(unittest.TestCase):
    def test_static_extraction_accepts_only_dictionary_literals_not_bundle_execution(self):
        from web_language_catalog import dictionaries
        source = '''const NS="fixture"; const base={"message":"Keep {name}"};
const zh={...base,description:["First", "Second"].join("\\n")};
const en={...base,description:"First\\nSecond"};
ctx.locale.register(NS,{zh,en});'''
        self.assertEqual([('fixture', {'zh': {'message': 'Keep {name}', 'description': 'First\nSecond'},
                                      'en': {'message': 'Keep {name}', 'description': 'First\nSecond'}})],
                         dictionaries(source))
        with self.assertRaises((KeyError, ValueError)):
            dictionaries('ctx.locale.register("fixture", dangerous());')

    def test_extraction_reads_upstream_language_columns_js_escapes_and_static_references(self):
        # 0.2.2 起上游注册 zh/en/ja/es/fr/de/ru/pt；JS 字符串转义、成员引用、
        # 同名变量（取最近的前置定义）和静态 (locale, dict) 循环都必须精确读取。
        from web_language_catalog import dictionaries
        source = '''const tokens={zh:{goal:"目标"},en:{goal:"goal"}};
const zh={"a":"中","t":tokens.zh.goal}; const en={"a":"A\\xA0x","t":tokens.en.goal}; const fr={"a":'l\\'a'};
ctx.locale.register("one",{zh,en,fr});
const zh$1={b:"乙"}; const en$1={b:"B"};
ctx.locale.register("two",{zh:zh$1,en:en$1,ja:{b:"\\u30d3"}});
const dictionaries=[["zh",{c:"丙"}],["en",{c:"C"}],["de",{}]];
for (const [locale, dict] of dictionaries) disposers.push(ctx.locale.register("three", locale, dict));'''
        rows = dict(dictionaries(source))
        self.assertEqual({'a': 'A\u00a0x', 't': 'goal'}, rows['one']['en'])
        self.assertEqual({'a': 'l\'a'}, rows['one']['fr'])
        self.assertEqual({'b': '\u30d3'}, rows['two']['ja'])
        self.assertEqual({'zh', 'en', 'de'}, set(rows['three']))
        with self.assertRaisesRegex(ValueError, 'WEB_REGISTER_EXTRA_KEYS'):
            dictionaries('const zh={a:"1"}; const en={a:"1"}; ctx.locale.register("x",{zh,en,ja:{b:"2"}});')

    def test_dsha_never_registers_an_upstream_language_and_only_experimental_text_may_fall_back(self):
        catalog = json.loads((WEB.ROOT / 'tools/i18n/web-messages.json').read_text(encoding='utf8'))
        tags = WEB.language_tags(catalog)
        self.assertEqual(len(tags), len(set(tags)))
        self.assertEqual(['zh', 'en', 'ja', 'es', 'fr', 'de', 'ru', 'pt', 'zh-Hant', 'ko', 'ar', 'la'], tags)
        for namespace in catalog['namespaces'].values():
            self.assertFalse(set(namespace['upstreamLocales']) & {'zh-hant', 'zh-Hant', 'ko', 'ar', 'la'})
        translations = WEB.read_translations(catalog)
        broken = dict(translations['ko'])
        stable = next(row['id'] for row in catalog['messages'] if not WEB.experimental(catalog, row))
        broken[stable] = None
        source = WEB.ROOT / 'tools/i18n/web-translations/ko.json'
        original = source.read_bytes()
        try:
            source.write_text(json.dumps(broken, ensure_ascii=False), encoding='utf8')
            with self.assertRaisesRegex(ValueError, 'WEB_TRANSLATION_FALLBACK_NOT_EXPERIMENTAL'):
                WEB.read_translations(catalog)
        finally:
            source.write_bytes(original)
        bridge = (WEB.ROOT / 'tools/i18n/web-language-bridge.js').read_text(encoding='utf8')
        for upstream in ('ja', 'es', 'fr', 'de', 'ru', 'pt'):
            self.assertNotIn("['" + upstream + "',", bridge)

    def test_all_namespaced_keys_are_complete_and_generated_assets_are_exact(self):
        from source_text import matches_text
        catalog = json.loads((WEB.ROOT / 'tools/i18n/web-messages.json').read_text(encoding='utf8'))
        ids = {row['id'] for row in catalog['messages']}
        self.assertEqual(len(ids), len(catalog['messages']))
        self.assertTrue(len(ids) > 2500)
        translations = WEB.read_translations(catalog)
        generated = WEB.outputs(catalog, translations)
        for name, content in generated.items():
            self.assertTrue(matches_text(WEB.ASSETS / name, content), name)
        for tag, data in translations.items():
            self.assertEqual(ids, set(data), tag)
        spec = json.loads(generated['language-patch.json'])
        # 0.2.2：V1 前代只存在于 0.2.1-rc.3 归档，新 locale 模块上不可能出现，不再声明。
        self.assertNotIn('previousPatches', spec)
        self.assertEqual(3, len(spec['patches']))
        # The independent rescue runtime keeps its own pinned recipe; upgrading the
        # production runtime must not silently add or replace that rescue input.
        # Only an explicit rescue lock at the same DSH version yields the same recipe.
        recovery = json.loads((WEB.ROOT / 'tools/recovery-runtime/lock.json').read_text(encoding='utf8'))
        rescue_name = 'recovery-recipes/' + recovery['dshVersion'] + '/language-patch.json'
        expected = {'web-integration/language.js', 'language-patch.json'}
        if recovery['dshVersion'] == WEB.VERSION:
            expected.add(rescue_name)
            self.assertEqual(generated['language-patch.json'], generated[rescue_name])
        self.assertEqual(expected, set(generated))
        rescue = json.loads((WEB.ASSETS / rescue_name).read_text(encoding='utf8'))
        self.assertEqual(recovery['dshVersion'], rescue['dshVersion'])
        # 历史应急 recipe（0.2.1-rc.5 及更早）保留原签名前代；同版本 recipe 与正式一致。
        if recovery['dshVersion'] != WEB.VERSION:
            self.assertEqual(3, len(rescue['previousPatches']))
        self.assertEqual(3, len(rescue['patches']))
        self.assertEqual('web-integration/language.js', rescue['patches'][0]['prependAsset'])


if __name__ == '__main__':
    unittest.main()
