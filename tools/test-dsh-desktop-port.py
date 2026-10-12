#!/usr/bin/env python3
"""验证 dsh-desktop 移植工具的分类规则、契约校验与骨架生成。

这些用例锁定的是**真实移植中踩到的失效模式**，不是构造出来的边界：

  * 值导入 electron / Electron 侧适配器 → 不可移植；但**纯类型导入**编译期擦除，
    早期把它一起判死，会把 21 个本来可移植的文件误判。
  * 运行时访问 `ctx.desktopRuntime` → 需适配，且必须报出具体行号。
  * 被 Electron 外壳引用的纯函数（如日志脱敏）**不该**被判成需适配 —— 只打标记。
  * 契约校验要看**代码**而不是注释：移植时会在注释里写「原版用的是 desktopRuntime」，
    按原文匹配会误报。
  * 生成的骨架必须自己通过校验 —— 首版生成的 TODO 清单没逐行加注释前缀，
    产出的是语法错误的 JS。
"""
import importlib.util
import json
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1] / 'tools'
HAS_NODE = shutil.which('node') is not None


def load_tool():
    spec = importlib.util.spec_from_file_location('dsh_desktop_port', TOOLS / 'dsh-desktop-port.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class ClassifyTest(unittest.TestCase):
    """分类规则。"""

    @classmethod
    def setUpClass(cls):
        cls.tool = load_tool()

    def classify(self, rel, text):
        return self.tool.classify_file(rel, text)

    def test_electron_value_import_is_native_shell(self):
        bucket, reasons, _ = self.classify('src/main.ts', "import { app } from 'electron'\n")
        self.assertEqual(bucket, self.tool.B1)
        self.assertIn('值导入 electron', reasons)

    def test_type_only_adapter_import_stays_portable(self):
        # `import type` 编译期擦除，不构成运行时依赖
        text = "import type {} from './runtime.ts'\nexport function f() { return 1 }\n"
        bucket, reasons, hot = self.classify('src/plain.ts', text)
        self.assertEqual(bucket, self.tool.A1)
        self.assertEqual(hot, [])
        self.assertTrue(any('类型导入' in r for r in reasons))

    def test_value_adapter_import_is_native_shell(self):
        bucket, _, _ = self.classify('src/x.ts', "import { y } from './runtime.ts'\n")
        self.assertEqual(bucket, self.tool.B1)

    def test_desktop_runtime_access_needs_adaptation_with_line_numbers(self):
        text = 'export function apply(ctx) {\n  ctx.desktopRuntime.notifyAttention(n)\n}\n'
        bucket, _, hot = self.classify('src/notifications.ts', text)
        self.assertEqual(bucket, self.tool.A2)
        self.assertEqual(hot, [(2, 'ctx.desktopRuntime.notifyAttention')])

    def test_bare_runtime_method_call_is_detected(self):
        text = 'function f(runtime) {\n  runtime.openTerminal()\n}\n'
        bucket, _, hot = self.classify('src/terminal.ts', text)
        self.assertEqual(bucket, self.tool.A2)
        self.assertEqual(hot, [(2, 'openTerminal')])

    def test_prefix_buckets(self):
        for rel, expected in [
            ('src/windows-acl-runner.ts', self.tool.B2),
            ('src/asar-archive-policy.ts', self.tool.B3),
            ('src/updates.ts', self.tool.B4),
            ('src/native-ui/setup-wizard/App.tsx', self.tool.B1),
            ('src/client/desktop-settings-api.ts', self.tool.CLIENT),
        ]:
            with self.subTest(rel=rel):
                bucket, _, _ = self.classify(rel, 'export const x = 1\n')
                self.assertEqual(bucket, expected)

    def test_source_root_prefix_does_not_defeat_matching(self):
        # 源包自带 src/ 前缀；前缀规则必须作用在去掉它之后的路径上
        bucket, _, _ = self.classify('src/windows-console-host.ts', 'export const x = 1\n')
        self.assertEqual(bucket, self.tool.B2)

    def test_inline_type_specifier_is_not_a_value_import(self):
        # 回归：`import { type A }` 是内联类型说明符，编译期擦除。
        # 早期只认 `import type {...}`，这种写法会被误判成值导入 → 误杀成不可移植。
        for text in ("import { type A } from './runtime.ts'\n",
                     "import { type A, type B } from './runtime.ts'\n"):
            with self.subTest(text=text):
                bucket, _, _ = self.classify('src/a.ts', text)
                self.assertEqual(bucket, self.tool.A1)
        # 混入值成员就不再是纯类型
        for text in ("import { type A, B } from './runtime.ts'\n",
                     "import D, { type A } from './runtime.ts'\n"):
            with self.subTest(text=text):
                bucket, _, _ = self.classify('src/a.ts', text)
                self.assertEqual(bucket, self.tool.B1)

    def test_client_desktop_chrome_is_native_shell(self):
        text = 'export const s = "-webkit-app-region: drag"\n'
        bucket, reasons, _ = self.classify('src/client/advanced-shell.ts', text)
        self.assertEqual(bucket, self.tool.B1)
        self.assertIn('桌面自有窗口外壳', reasons[0])


class AnalyzeTest(unittest.TestCase):
    """整包分析。"""

    @classmethod
    def setUpClass(cls):
        cls.tool = load_tool()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / 'pkg'
        (self.root / 'src' / 'client').mkdir(parents=True)

    def tearDown(self):
        self.temp.cleanup()

    def write(self, rel, text):
        path = self.root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding='utf-8')

    def test_build_configs_are_not_plugin_source(self):
        self.write('src/a.ts', 'export const a = 1\n')
        self.write('vite.native-ui.config.ts', 'export default {}\n')
        self.write('tsdown.config.ts', 'export default {}\n')
        rows, _ = self.tool.analyze(self.root)
        self.assertEqual([r['file'] for r in rows], ['src/a.ts'])

    def test_scripts_and_tests_are_skipped(self):
        self.write('src/a.ts', 'export const a = 1\n')
        self.write('scripts/package-mac.ts', 'export const s = 1\n')
        self.write('tests/x.test.ts', 'export const t = 1\n')
        rows, _ = self.tool.analyze(self.root)
        self.assertEqual([r['file'] for r in rows], ['src/a.ts'])

    def test_pure_helper_used_by_shell_is_flagged_not_demoted(self):
        # 日志脱敏是纯函数：被 Electron 的 logger 引用，但本身不需要适配。
        # 判成 A2 是错的 —— 只应打标记。
        self.write('src/mask-secrets.ts', 'export function mask(s) { return s }\n')
        self.write('src/desktop-logger.ts',
                   "import { mask } from './mask-secrets.ts'\n"
                   "import { app } from 'electron'\n"
                   'export const log = mask\n')
        rows, _ = self.tool.analyze(self.root)
        by_file = {r['file']: r for r in rows}
        self.assertEqual(by_file['src/desktop-logger.ts']['bucket'], self.tool.B1)
        self.assertEqual(by_file['src/mask-secrets.ts']['bucket'], self.tool.A1)
        self.assertTrue(by_file['src/mask-secrets.ts']['shell_coupled'])

    def test_source_root_other_than_src_is_detected(self):
        # 回归：写死 src/lib/dist 时，源码放在 source/ 会让前缀规则全部失效，
        # source/windows-acl-runner.ts 会被误判成「直接可移植」
        (self.root / 'source').mkdir(parents=True)
        (self.root / 'source' / 'windows-acl-runner.ts').write_text('export const x = 1\n', encoding='utf-8')
        (self.root / 'source' / 'asar-policy.ts').write_text('export const y = 1\n', encoding='utf-8')
        (self.root / 'source' / 'plain.ts').write_text('export const z = 1\n', encoding='utf-8')
        self.assertEqual(self.tool.detect_source_root(self.root), 'source')
        rows, _ = self.tool.analyze(self.root)
        buckets = {r['file']: r['bucket'] for r in rows}
        self.assertEqual(buckets['source/windows-acl-runner.ts'], self.tool.B2)
        self.assertEqual(buckets['source/asar-policy.ts'], self.tool.B3)
        self.assertEqual(buckets['source/plain.ts'], self.tool.A1)

    def test_parent_relative_import_marks_coupling(self):
        # 回归：客户端普遍用 `../x.ts` 引用上级目录，只匹配 `./` 会漏掉整类引用
        self.write('src/helper.ts', 'export const h = 1\n')
        self.write('src/shell/desktop-frame.tsx',
                   "import { h } from '../helper.ts'\n"
                   'export const s = "-webkit-app-region: drag"\n')
        rows, _ = self.tool.analyze(self.root)
        by_file = {r['file']: r for r in rows}
        self.assertEqual(by_file['src/shell/desktop-frame.tsx']['bucket'], self.tool.B1)
        self.assertTrue(by_file['src/helper.ts']['shell_coupled'])

    def test_type_only_import_does_not_mark_coupling(self):
        self.write('src/helper.ts', 'export const h = 1\n')
        self.write('src/shell/frame.tsx',
                   "import type { H } from '../helper.ts'\n"
                   'export const s = "-webkit-app-region: drag"\n')
        rows, _ = self.tool.analyze(self.root)
        by_file = {r['file']: r for r in rows}
        self.assertFalse(by_file['src/helper.ts']['shell_coupled'])

    def test_summary_counts_every_file_once(self):
        self.write('src/a.ts', 'export const a = 1\n')
        self.write('src/b.ts', "import { app } from 'electron'\n")
        self.write('src/client/c.tsx', 'export const c = 1\n')
        rows, summary = self.tool.analyze(self.root)
        self.assertEqual(sum(r['lines'] for r in rows),
                         sum(summary[b]['lines'] for b in self.tool.BUCKET_ORDER))
        self.assertEqual(sum(summary[b]['files'] for b in self.tool.BUCKET_ORDER), 3)


class VerifyTest(unittest.TestCase):
    """契约校验。"""

    @classmethod
    def setUpClass(cls):
        cls.tool = load_tool()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.plugin = Path(self.temp.name) / 'dsh-sample'
        (self.plugin / 'lib').mkdir(parents=True)
        self.write('lib/index.js', "export const name = 'dsh-sample'\nexport const inject = []\n"
                                   'export function apply(ctx) {}\n')
        self.write('cordis.patch.yml', "- insert:\n    - id: dsh-sample\n      name: 'dsh-sample'\n")
        self.manifest({
            'name': 'dsh-sample', 'version': '0.1.0', 'type': 'module', 'main': 'lib/index.js',
            'exports': {'.': './lib/index.js', './package.json': './package.json',
                        './cordis.patch.yml': './cordis.patch.yml'},
            'dsh': {'bundle': {'patch': './cordis.patch.yml'}},
            'peerDependencies': {'@deepseek-ai/dsh': '^0.2.0-rc.2'},
        })

    def tearDown(self):
        self.temp.cleanup()

    def write(self, rel, text):
        path = self.plugin / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding='utf-8')

    def manifest(self, doc):
        (self.plugin / 'package.json').write_text(
            json.dumps(doc, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

    def check(self):
        errors, warnings, notes = self.tool.verify(self.plugin, None)
        return errors, warnings, notes

    def test_valid_plugin_passes(self):
        errors, warnings, _ = self.check()
        self.assertEqual(errors, [])
        self.assertEqual(warnings, [])

    def test_invalid_name_and_missing_version(self):
        self.manifest({'name': 'Bad_Name', 'version': '', 'main': 'lib/index.js'})
        errors, _, _ = self.check()
        self.assertTrue(any('包名' in e for e in errors))
        self.assertTrue(any('version' in e for e in errors))

    def test_missing_entry_and_patch(self):
        self.manifest({'name': 'dsh-sample', 'version': '1.0.0', 'type': 'module',
                       'main': 'lib/nope.js', 'dsh': {}})
        errors, _, _ = self.check()
        self.assertTrue(any('入口不存在' in e for e in errors))
        self.assertTrue(any('dsh.bundle.patch' in e for e in errors))

    def test_client_declaration_requires_export_and_wrapper(self):
        self.write('lib/client.js', 'export const apply = () => {}\n')
        self.manifest({
            'name': 'dsh-sample', 'version': '1.0.0', 'type': 'module', 'main': 'lib/index.js',
            'exports': {'.': './lib/index.js', './package.json': './package.json',
                        './cordis.patch.yml': './cordis.patch.yml', './client': './lib/client.js'},
            'dsh': {'bundle': {'patch': './cordis.patch.yml'}, 'client': {'platform': 'desktop'}},
        })
        errors, _, _ = self.check()
        self.assertTrue(any('platform 必须是 "web"' in e for e in errors))

        doc = json.loads((self.plugin / 'package.json').read_text(encoding='utf-8'))
        doc['dsh']['client']['platform'] = 'web'
        self.manifest(doc)
        errors, _, _ = self.check()
        self.assertTrue(any('__ModuleLoader__' in e for e in errors), errors)

        self.write('lib/client.js', "window.__ModuleLoader__.load({ id: 'dsh-sample', factory: () => ({}) });\n")
        errors, _, notes = self.check()
        self.assertEqual(errors, [])
        self.assertTrue(any('注册格式正确' in n for n in notes))

    def test_client_declaration_without_client_export(self):
        self.manifest({
            'name': 'dsh-sample', 'version': '1.0.0', 'type': 'module', 'main': 'lib/index.js',
            'exports': {'.': './lib/index.js'},
            'dsh': {'bundle': {'patch': './cordis.patch.yml'}, 'client': {'platform': 'web'}},
        })
        errors, _, _ = self.check()
        self.assertTrue(any('"./client"' in e for e in errors))

    def test_electron_import_is_rejected(self):
        self.write('lib/index.js', "import { app } from 'electron'\nexport function apply() {}\n")
        errors, _, _ = self.check()
        self.assertTrue(any('electron' in e for e in errors))

    def test_desktop_runtime_in_comments_is_not_a_dependency(self):
        # 回归：移植时会在注释里记录「原版用的是 desktopRuntime」，
        # 那是说明不是依赖；按原文匹配会误报。
        self.write('lib/index.js',
                   "/**\n * 原版用 ctx.desktopRuntime.notifyAttention()，这里换成 3090 桥。\n */\n"
                   "export const name = 'dsh-sample'\n"
                   "// runtime.openTerminal() 同样不适用\n"
                   'export function apply(ctx) {}\n')
        errors, _, _ = self.check()
        self.assertEqual(errors, [], errors)

    def test_string_containing_slashes_does_not_hide_real_reference(self):
        # 回归：早期用 `//[^\n]*` 粗暴剥注释，`"http://x"` 会把同一行后面的内容
        # 整段吃掉，于是真实的 desktopRuntime 引用被静默漏掉
        self.write('lib/index.js',
                   'export function apply(ctx) {\n'
                   '  const u = "http://127.0.0.1"; ctx.desktopRuntime.show()\n'
                   '}\n')
        errors, _, _ = self.check()
        self.assertTrue(any('desktopRuntime' in e for e in errors), errors)

    def test_block_comment_does_not_hide_real_reference(self):
        self.write('lib/index.js',
                   'export function apply(ctx) {\n'
                   '  /* 说明：原版走 desktopRuntime */ const u = "a/*b*/"; ctx.desktopRuntime.show()\n'
                   '}\n')
        errors, _, _ = self.check()
        self.assertTrue(any('desktopRuntime' in e for e in errors), errors)

    def test_desktop_runtime_in_code_is_rejected(self):
        self.write('lib/index.js',
                   'export function apply(ctx) {\n  ctx.desktopRuntime.show()\n}\n')
        errors, _, _ = self.check()
        self.assertTrue(any('desktopRuntime' in e for e in errors))

    def test_nonempty_module_level_inject_warns(self):
        self.write('lib/index.js',
                   "export const inject = ['webServer']\nexport function apply(ctx) {}\n")
        _, warnings, _ = self.check()
        self.assertTrue(any('inject' in w for w in warnings))

    @unittest.skipUnless(HAS_NODE, '需要 node 做语法检查')
    def test_syntax_error_is_rejected(self):
        self.write('lib/index.js', 'export function apply(ctx) {\n- [ ] broken todo\n}\n')
        errors, _, _ = self.check()
        self.assertTrue(any('语法检查失败' in e for e in errors), errors)

    def test_string_exports_does_not_crash(self):
        # 回归：exports 是字符串是合法 npm 写法，早期直接当对象用会抛
        # AttributeError: 'str' object has no attribute 'get'
        self.manifest({'name': 'dsh-sample', 'version': '1.0.0', 'type': 'module',
                       'main': 'lib/index.js', 'exports': './lib/index.js',
                       'dsh': {'bundle': {'patch': './cordis.patch.yml'}}})
        errors, warnings, _ = self.check()
        self.assertEqual(errors, [])
        self.assertTrue(any('字符串形式' in w for w in warnings), warnings)

    def test_missing_exports_warns(self):
        self.manifest({'name': 'dsh-sample', 'version': '1.0.0', 'type': 'module',
                       'main': 'lib/index.js',
                       'dsh': {'bundle': {'patch': './cordis.patch.yml'}}})
        errors, warnings, _ = self.check()
        self.assertEqual(errors, [])
        self.assertTrue(any('未声明 exports' in w for w in warnings), warnings)

    def test_overlong_package_name_is_rejected(self):
        # DSHA 的 valid_name 上限是 214；超了会被原生侧拒绝，工具不该放过
        self.manifest({'name': 'd' + 'a' * 300, 'version': '1.0.0', 'type': 'module',
                       'main': 'lib/index.js', 'exports': {'.': './lib/index.js'},
                       'dsh': {'bundle': {'patch': './cordis.patch.yml'}}})
        errors, _, _ = self.check()
        self.assertTrue(any('214' in e for e in errors), errors)

    def test_missing_compat_range_warns(self):
        doc = json.loads((self.plugin / 'package.json').read_text(encoding='utf-8'))
        doc.pop('peerDependencies')
        self.manifest(doc)
        _, warnings, _ = self.check()
        self.assertTrue(any('兼容范围' in w for w in warnings))


class ScaffoldTest(unittest.TestCase):
    """骨架生成。"""

    @classmethod
    def setUpClass(cls):
        cls.tool = load_tool()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.source = self.root / 'pkg'
        (self.source / 'src').mkdir(parents=True)
        (self.source / 'src' / 'notifications.ts').write_text(
            'export function apply(ctx) {\n  ctx.desktopRuntime.notifyAttention(n)\n}\n', encoding='utf-8')
        (self.source / 'src' / 'jobs-bridge.ts').write_text(
            'export const outcome = (s) => s\n', encoding='utf-8')
        self.rows, self.summary = self.tool.analyze(self.source)

    def tearDown(self):
        self.temp.cleanup()

    def scaffold(self, **overrides):
        args = type('Args', (), {
            'name': 'dsh-sample', 'out': str(self.root / 'out'),
            'module': ['src/notifications.ts', 'src/jobs-bridge.ts'],
            'client': False, 'description': None,
        })()
        for key, value in overrides.items():
            setattr(args, key, value)
        return self.tool.scaffold(args, self.rows, self.summary)

    def test_generated_skeleton_satisfies_contract(self):
        # 往返自洽：生成的骨架必须能通过自己的校验
        out_dir, selected, wants_client = self.scaffold()
        self.assertEqual(len(selected), 2)
        self.assertFalse(wants_client)
        errors, warnings, _ = self.tool.verify(out_dir, None)
        self.assertEqual(errors, [], errors)
        self.assertEqual(warnings, [], warnings)

    @unittest.skipUnless(HAS_NODE, '需要 node 做语法检查')
    def test_generated_entry_is_valid_javascript(self):
        # 回归：首版 TODO 清单没逐行加注释前缀，产出的是语法错误的文件
        out_dir, _, _ = self.scaffold()
        check = subprocess.run(['node', '--check', str(out_dir / 'lib' / 'index.js')],
                               capture_output=True, text=True)
        self.assertEqual(check.returncode, 0, check.stderr)

    def test_todo_list_records_adaptation_points(self):
        out_dir, _, _ = self.scaffold()
        body = (out_dir / 'lib' / 'index.js').read_text(encoding='utf-8')
        self.assertIn('notifyAttention', body)
        self.assertIn('可直接搬运', body)
        for line in body.splitlines():
            stripped = line.strip()
            if stripped.startswith('- [ ]'):
                self.fail('TODO 行必须带注释前缀，否则生成的文件是语法错误')

    def test_unknown_module_is_rejected(self):
        with self.assertRaises(SystemExit):
            self.scaffold(module=['src/does-not-exist.ts'])

    def test_client_half_is_generated_when_requested(self):
        out_dir, _, wants_client = self.scaffold(client=True)
        self.assertTrue(wants_client)
        client = (out_dir / 'lib' / 'client.js').read_text(encoding='utf-8')
        self.assertIn('window.__ModuleLoader__.load(', client)
        manifest = json.loads((out_dir / 'package.json').read_text(encoding='utf-8'))
        self.assertEqual(manifest['dsh']['client']['platform'], 'web')
        self.assertEqual(manifest['exports']['./client'], './lib/client.js')


if __name__ == '__main__':
    unittest.main()
