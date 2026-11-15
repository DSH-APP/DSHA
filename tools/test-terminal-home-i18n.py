#!/usr/bin/env python3
"""Exercise the new native terminal and launch-page language catalog on the host."""
import base64
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

from ui_language_catalog import LANGUAGES, RESOURCE_FOLDERS, format_signature, load_catalog

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/main/java/com/deepseekharness/app'
RES = ROOT / 'app/src/main/res'


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'tools' / filename)
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


PREPARE = module('prepare_ui_languages', 'prepare-ui-languages.py')
SOURCE_CHECK = module('check_ui_i18n', 'check-ui-i18n.py')

# Scan the complete terminal chain, including future terminal collaborators.
# ExtraKeysDialog's radio helper forwards its two literal arguments to UiText.choose.
LITERAL_WRAPPERS = {'ui/ExtraKeysDialog.java': {'radio'}}
# The close glyph is invariant; TerminalTabBar gives it a localized accessibility label.
INVARIANT_LITERALS = {'ui/TerminalTabBar.java': {'×'}}
REQUIRED_SOURCES = {
    'ui/TerminalActivity.java', 'ui/LaunchFragment.java', 'ui/ExtraKeysDialog.java',
    'ui/PtyTerminalFragment.java', 'ui/TerminalFragment.java', 'ui/TerminalFloatingMenu.java',
    'ui/TerminalTabBar.java', 'ui/TerminalActionsDialog.java', 'ui/TerminalOutputDialog.java',
    'util/TerminalExtraKeys.java', 'util/TerminalSession.java', 'util/TerminalTabs.java',
    'core/TerminalSessionOwner.java', 'core/SimpleTerminalBackend.java',
    'runtime/TerminalProcessCloser.java', 'TerminalRuntimeService.java', 'PtySession.java',
}


def terminal_sources():
    paths = set(SOURCE.rglob('*Terminal*.java'))
    paths.update(SOURCE / name for name in (
        'PtySession.java', 'ui/ExtraKeysDialog.java', 'ui/LaunchFragment.java'))
    return sorted(paths)


def literal_expression(values):
    """Read complete constant strings and conditional branches, not nested call arguments."""
    if not values:
        return []
    if values[0] == '(':
        _, end = SOURCE_CHECK.call_arguments(values, 0)
        if end == len(values) - 1:
            return literal_expression(values[1:-1])
    if all(value.startswith('"') if index % 2 == 0 else value == '+'
           for index, value in enumerate(values)) and len(values) % 2:
        return [''.join(json.loads(value) for value in values[::2])]
    depth, question, nested = 0, None, 0
    for index, value in enumerate(values):
        if value in ('(', '[', '{'):
            depth += 1
        elif value in (')', ']', '}'):
            depth -= 1
        elif depth == 0 and value == '?':
            if question is None:
                question = index
            else:
                nested += 1
        elif depth == 0 and value == ':' and question is not None:
            if nested:
                nested -= 1
            else:
                return (literal_expression(values[question + 1:index])
                        + literal_expression(values[index + 1:]))
    return []


def catalog_literals(source, wrappers=()):
    values = [value for value, _ in SOURCE_CHECK.java_tokens(source)]
    result = set()
    for index, value in enumerate(values):
        opening = None
        if (value == 'UiText' and values[index + 1:index + 2] == ['.']
                and values[index + 2:index + 3] in (['choose'], ['text'], ['format'])
                and values[index + 3:index + 4] == ['(']):
            opening = index + 3
        elif value in wrappers and values[index + 1:index + 2] == ['(']:
            opening = index + 1
        if opening is not None:
            arguments, _ = SOURCE_CHECK.call_arguments(values, opening)
            if arguments:
                result.update(literal_expression(arguments[0]))
    return result


class TerminalHomeLanguageTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.messages, cls.catalogs = load_catalog(ROOT)
        cls.by_source = {item['zh']: item for item in cls.messages}
        cls.source_literals = {}
        for path in terminal_sources():
            name = path.relative_to(SOURCE).as_posix()
            cls.source_literals[name] = catalog_literals(
                path.read_text(encoding='utf8'), LITERAL_WRAPPERS.get(name, ()))
        cls.covered_literals = set().union(*cls.source_literals.values())

    def test_terminal_and_launch_literal_calls_have_complete_catalog_entries(self):
        # Missing files cannot silently reduce coverage; new terminal classes join automatically.
        self.assertEqual(set(), REQUIRED_SOURCES - self.source_literals.keys())
        for name, literals in self.source_literals.items():
            with self.subTest(source=name):
                missing = sorted(literals - self.by_source.keys() - INVARIANT_LITERALS.get(name, set()))
                self.assertEqual([], missing)
        self.assertGreaterEqual(len(self.covered_literals), 250)
        for name in ('ui/TerminalActivity.java', 'ui/LaunchFragment.java'):
            self.assertGreater(len(self.source_literals[name]), 20, name)
        terminal_literals = self.source_literals['ui/TerminalActivity.java']
        self.assertIn('新建终端会话', terminal_literals)
        self.assertNotIn('新建会话', terminal_literals)
        self.assertIn('聚焦输入', terminal_literals)
        self.assertIn('留空恢复默认名称，最多 80 个字符', terminal_literals)
        self.assertTrue({'手机布局', '平板布局'} <= self.source_literals['ui/ExtraKeysDialog.java'])
        self.assertTrue({'终端 %s · 关闭中…', '终端 %s'} <= self.source_literals['ui/TerminalTabBar.java'])

    def test_call_inventory_follows_wrappers_branches_and_constant_concatenation(self):
        source = r'''
        // UiText.text("注释不算")
        String example = "UiText.text(\\\"示例不算\\\")";
        UiText.choose("中文", "English");
        radio("手机布局", "Phone layout");
        UiText.format(tab.isClosing() ? "终端 %s · 关闭中…" : "终端 %s", tab.number);
        UiText.choose("第一段" + "\n第二段", "First\nSecond");
        UiText.format(UiText.choose("数量 %d", "Count %d"), number);
        UiText.text(userContent);
        '''
        self.assertEqual({'中文', '手机布局', '终端 %s · 关闭中…', '终端 %s',
                          '第一段\n第二段', '数量 %d'}, catalog_literals(source, {'radio'}))

    def test_interruption_actions_and_output_scopes_have_distinct_sources(self):
        pty = self.source_literals['ui/PtyTerminalFragment.java']
        self.assertTrue({'关闭提示', '恢复输入', '终端输入中断', '粘贴预览'} <= pty)
        self.assertNotIn('继续输入', pty)
        self.assertEqual('Close notice', self.by_source['关闭提示']['en'])
        self.assertEqual('Resume input', self.by_source['恢复输入']['en'])
        output = self.source_literals['ui/TerminalOutputDialog.java']
        self.assertTrue({'当前可见屏幕', '已保留的历史输出',
                         '输出较长，仅保留最近的部分（最多 64K 字符）。'} <= output)
        actions = self.source_literals['ui/TerminalActionsDialog.java']
        self.assertTrue({'终端操作', '粘贴文本…', '查看终端输出…', '会话管理'} <= actions)
        for tag in LANGUAGES:
            with self.subTest(language=tag):
                data = self.catalogs[tag]
                self.assertNotEqual(data[self.by_source['关闭提示']['id']],
                                    data[self.by_source['恢复输入']['id']])
                self.assertIn('80', data[self.by_source['留空恢复默认名称，最多 80 个字符']['id']])
                self.assertIn('64K', data[self.by_source['输出较长，仅保留最近的部分（最多 64K 字符）。']['id']])

    def test_indexed_close_all_messages_keep_argument_validation(self):
        indexed = [item for item in self.messages if item['id'].startswith('termhome')
                   and re.search(r'%\d+\$[sd]', item['zh'])]
        self.assertGreaterEqual(len(indexed), 5)
        self.assertTrue(all(item.get('androidFormat') and not item.get('uiFormat') for item in indexed))
        # load_catalog validates each numbered argument's identity and type in all ten languages.

    def test_layout_string_resources_exist_in_every_language(self):
        names = set()
        layouts = set(RES.glob('layout*/*terminal*.xml'))
        layouts.update(RES.glob('layout*/fragment_launch.xml'))
        self.assertTrue({'fragment_launch.xml', 'fragment_pty_terminal.xml',
                         'fragment_terminal.xml', 'view_terminal_tabs.xml'}
                        <= {path.name for path in layouts})
        for path in sorted(layouts):
            names.update(re.findall(r'@string/([A-Za-z0-9_]+)', path.read_text(encoding='utf8')))

        def resources(folder):
            found = {}
            for path in folder.glob('*.xml'):
                for item in ET.parse(path).getroot().findall('string'):
                    found[item.get('name')] = item
            return found

        default = resources(RES / 'values')
        self.assertEqual(set(), names - default.keys())
        translated_names = {name for name in names if default[name].get('translatable') != 'false'}
        for tag in LANGUAGES:
            with self.subTest(language=tag):
                localized = resources(RES / RESOURCE_FOLDERS[tag])
                self.assertEqual(set(), translated_names - localized.keys())

    def test_termux_selection_resources_override_library_labels_in_all_languages(self):
        # Termux resolves these library resource names for both inflated and new views.
        names = ('copy_text', 'paste_text', 'text_selection_more')
        expected = {
            'zh': ('复制', '粘贴', '更多…'),
            'zh-Hant': ('複製', '貼上', '更多…'),
            'en': ('Copy', 'Paste', 'More…'),
            'ru': ('Копировать', 'Вставить', 'Ещё…'),
            'ja': ('コピー', '貼り付け', 'その他…'),
            'ko': ('복사', '붙여넣기', '더보기…'),
            'ar': ('نسخ', 'لصق', 'المزيد…'),
            'pt': ('Copiar', 'Colar', 'Mais…'),
            'la': ('Exscribe', 'Insere', 'Plura…'),
            'fr': ('Copier', 'Coller', 'Plus…'),
        }
        self.assertEqual(set(LANGUAGES), expected.keys())
        for tag in LANGUAGES:
            with self.subTest(language=tag):
                found = {name: [] for name in names}
                for path in (RES / RESOURCE_FOLDERS[tag]).glob('*.xml'):
                    for node in ET.parse(path).getroot().findall('string'):
                        if node.get('name') in found:
                            found[node.get('name')].append(node)
                for index, name in enumerate(names):
                    self.assertEqual(1, len(found[name]), name)
                    node = found[name][0]
                    self.assertNotEqual('false', node.get('translatable'), name)
                    value = node.text
                    if value.startswith('"'):
                        value = json.loads(value)
                    self.assertEqual(expected[tag][index], value, name)
                    message = self.by_source[expected['zh'][index]]
                    self.assertEqual(expected[tag][index], self.catalogs[tag][message['id']])

    def test_generated_java_renders_context_and_indexed_arguments_in_all_languages(self):
        expected_new = [
            '新建终端会话', '新增終端工作階段', 'New terminal session', 'Новый сеанс терминала',
            '新しい端末セッション', '새 터미널 세션', 'جلسة طرفية جديدة', 'Nova sessão de terminal',
            'Nova sessio terminalis', 'Nouvelle session de terminal',
        ]
        expected_hint = [
            'PTY / 简易', 'PTY / 簡易', 'PTY / Simple', 'PTY / простой', 'PTY / シンプル',
            'PTY / 간편', 'PTY / بسيط', 'PTY / simples', 'PTY / simplex', 'PTY / simple',
        ]
        expected_home = [
            '本机工作台 · 已就绪', '本機工作台 · 已就緒', 'Local workspace · ready',
            'Локальное рабочее пространство · готово', 'ローカルワークスペース · 準備完了',
            '로컬 작업대 · 준비됨', 'مساحة العمل المحلية · جاهزة', 'Bancada local · pronta',
            'Area operis localis · parata', 'Espace de travail local · prêt',
        ]
        confirm = self.by_source['将结束 %1$d 个会话（PTY %2$d / 简易 %3$d）。\n'
                                 '这不是返回页面：每个进程分别结束并核验，未确认的会话会保留并标记，Web 和其他功能不受影响。']
        jstr = lambda value: json.dumps(value, ensure_ascii=True)
        languages = ', '.join(jstr(tag) for tag in LANGUAGES)
        expected_new_java = ', '.join(jstr(value) for value in expected_new)
        expected_hint_java = ', '.join(jstr(value) for value in expected_hint)
        expected_home_java = ', '.join(jstr(value) for value in expected_home)
        harness = '''package com.deepseekharness.app.util;
public final class TerminalHomeLanguageHarness {
  public static void main(String[] args) throws Exception {
    String[] languages = {%s};
    String[] expectedNew = {%s};
    String[] expectedHint = {%s};
    String[] expectedHome = {%s};
    for (int i = 0; i < languages.length; i++) {
      String language = languages[i];
      UiText.setLanguage(language);
      String terminal = UiText.choose("新建终端会话", "New terminal session");
      String conversation = UiText.choose("新建会话", "New conversation");
      String hint = UiText.text("PTY / 简易");
      String home = UiText.choose("本机工作台 · 已就绪", "Local workspace · ready");
      String confirm = UiText.format(UiText.choose(%s, %s), 11, 22, 33).replace('\\n', ' ');
      if (!terminal.equals(expectedNew[i])) throw new AssertionError(language + ": terminal");
      if (terminal.equals(conversation)) throw new AssertionError(language + ": context collision");
      if (!hint.equals(expectedHint[i])) throw new AssertionError(language + ": hint");
      if (!home.equals(expectedHome[i])) throw new AssertionError(language + ": home");
      if (!confirm.contains("11") || !confirm.contains("PTY 22") || !confirm.contains("33")
          || confirm.matches(".*%%[0-9]+\\\\$[sd].*"))
        throw new AssertionError(language + ": indexed arguments");
      System.out.println("PASS " + language);
    }
    int count = 0;
    for (String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of(args[0]),
        java.nio.charset.StandardCharsets.UTF_8)) {
      String[] fields = line.split("\\t", -1);
      String language = fields[0], id = fields[1];
      String zh = decode(fields[2]), en = decode(fields[3]), expected = decode(fields[4]);
      UiText.setLanguage(language);
      String selected = UiText.choose(zh, en);
      if (!selected.equals(expected) || !UiText.text(zh).equals(expected))
        throw new AssertionError(language + ": lookup " + id);
      if (!fields[5].equals("N")) {
        Object[] values;
        if (fields[5].equals("I")) values = new Object[] {11, 22, 33, 44};
        else {
          values = new Object[fields[6].length()];
          for (int i = 0; i < values.length; i++)
            values[i] = fields[6].charAt(i) == 's' ? "ARG" + i : Integer.valueOf(10 + i);
        }
        String formatted = String.format(java.util.Locale.ROOT, expected, values);
        if (!UiText.format(selected, values).equals(formatted)
            || !UiText.format(zh, values).equals(formatted))
          throw new AssertionError(language + ": format " + id);
      }
      count++;
    }
    System.out.println("PASS catalog " + count);
  }
  private static String decode(String value) {
    return new String(java.util.Base64.getDecoder().decode(value), java.nio.charset.StandardCharsets.UTF_8);
  }
}
''' % (languages, expected_new_java, expected_hint_java, expected_home_java,
       jstr(confirm['zh']), jstr(confirm['en']))
        with tempfile.TemporaryDirectory(prefix='dsha-terminal-i18n-') as temp:
            tmp = Path(temp)
            generated = tmp / 'generated'
            PREPARE.generate(ROOT, generated)
            package = tmp / 'com/deepseekharness/app/util'
            package.mkdir(parents=True)
            (package / 'SystemLanguage.java').write_text(
                'package com.deepseekharness.app.util; final class SystemLanguage {'
                ' static String tag() { return "zh"; } }\n', encoding='utf8')
            (package / 'TerminalHomeLanguageHarness.java').write_text(harness, encoding='utf8')
            extra = [item for item in self.messages if item['id'].startswith('termhome')
                     or item['zh'] in self.covered_literals | {'复制', '粘贴', '更多…'}]
            self.assertGreaterEqual(len(extra), 250)
            rows = []
            encode = lambda value: base64.b64encode(value.encode('utf8')).decode('ascii')
            for tag in LANGUAGES:
                for item in extra:
                    kind = 'I' if item.get('androidFormat') else 'S' if item.get('uiFormat') else 'N'
                    signature = ''.join(format_signature(item['zh'])) if kind == 'S' else ''
                    rows.append('\t'.join((tag, item['id'], encode(item['zh']), encode(item['en']),
                                           encode(self.catalogs[tag][item['id']]), kind, signature)))
            input_path = tmp / 'catalog.tsv'
            input_path.write_text('\n'.join(rows) + '\n', encoding='utf8')
            classes = tmp / 'classes'
            classes.mkdir()
            java_files = sorted((generated / 'com/deepseekharness/app/util').glob('*.java'))
            java_files += [SOURCE / 'util/UiText.java', SOURCE / 'util/UiLanguagePreference.java',
                           package / 'SystemLanguage.java', package / 'TerminalHomeLanguageHarness.java']
            subprocess.run(['javac', '-encoding', 'UTF-8', '--release', '17', '-d', str(classes),
                            *map(str, java_files)], check=True, capture_output=True, text=True,
                           encoding='utf8', errors='replace', timeout=120)
            result = subprocess.run(['java', '-Dfile.encoding=UTF-8', '-cp', str(classes),
                                     'com.deepseekharness.app.util.TerminalHomeLanguageHarness',
                                     str(input_path)],
                                    check=True, capture_output=True, text=True, encoding='utf8',
                                    errors='replace', timeout=30)
        self.assertEqual(['PASS ' + tag for tag in LANGUAGES] +
                         ['PASS catalog ' + str(len(extra) * len(LANGUAGES))],
                         result.stdout.splitlines())


if __name__ == '__main__':
    unittest.main()
