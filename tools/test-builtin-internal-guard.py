#!/usr/bin/env python3
"""APK 独占的内置插件必须不可停用 —— 删除确认守卫是硬约束，不是开关。

为什么单独一条：`register-builtin-plugins.py` 原本一律尊重
`node_modules/<name>.disabled` 标记，连随包提供的插件也不例外。而 AI 在容器里有
全盘写权限，一个空文件就能把"rm 必须先确认"整个关掉 —— 那等于没有约束。
原生侧（WebLifecycleController / StartupRepairs / PluginRepository）早就不允许停用
这类插件，guest 侧必须同口径。

用假 root 跑真脚本（脚本自带 DSHA_TEST_ROOT 支持），不碰真机、不碰真 profile。
"""
import importlib.util
import json
import os
import pathlib
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'
SCRIPT = ASSETS / 'register-builtin-plugins.py'

INTERNAL = 'dsh-destructive-guard'
USER_VISIBLE = 'dsh-task-notifier'


def load_module(root):
    os.environ['DSHA_TEST_ROOT'] = str(root)
    os.environ['DSH_HOME'] = str(root / 'root/.dsh')
    spec = importlib.util.spec_from_file_location('register_under_test_' + root.name, SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class InternalGuard(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = pathlib.Path(self.temporary.name)
        self.saved = {key: os.environ.get(key) for key in ('DSHA_TEST_ROOT', 'DSH_HOME')}
        self.addCleanup(self.restore_environment)
        self.module = load_module(self.root)
        # 与受管运行时链接无关，这里只测启停口径，避免依赖真实安装树。
        self.module.ensure_runtime_modules = lambda *args, **kwargs: 0
        self.make_entities()
        self.write_profile()
        self.assertIn(INTERNAL, self.module.INTERNAL_BUILTINS, 'INTERNAL_BUILTINS 没读到新插件')
        self.assertNotIn(USER_VISIBLE, self.module.INTERNAL_BUILTINS, '用户可见插件不能算 APK 独占')

    def restore_environment(self):
        for key, value in self.saved.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value

    def make_entities(self):
        registry = json.loads((ASSETS / 'builtin-plugins.json').read_text(encoding='utf8'))
        for row in registry['plugins'] + registry['officialBundles']:
            directory = row.get('guestDirectory')
            if not directory:
                continue
            target = pathlib.Path(self.module.local(directory))
            target.mkdir(parents=True, exist_ok=True)
            (target / 'package.json').write_text(json.dumps({'name': row['name']}), encoding='utf8')

    def write_profile(self):
        registry = json.loads((ASSETS / 'builtin-plugins.json').read_text(encoding='utf8'))
        self.bundles = [row['name'] for row in registry['officialBundles']] + [row['name'] for row in registry['plugins']]
        pathlib.Path(self.module.local(self.module.PROFILE)).mkdir(parents=True, exist_ok=True)
        pathlib.Path(self.module.local(self.module.NODE_MODULES)).mkdir(parents=True, exist_ok=True)
        self.save_profile(self.bundles)

    def save_profile(self, bundles):
        document = {
            'name': 'dsh-profile-web',
            'private': True,
            'dependencies': {name: 'link:' + name for name in bundles},
            'dsh': {'profile': {'bundles': list(bundles), 'patchReload': 'startup'}},
        }
        pathlib.Path(self.module.local(self.module.MANIFEST)).write_text(json.dumps(document, indent=2), encoding='utf8')

    def profile_bundles(self):
        document = json.loads(pathlib.Path(self.module.local(self.module.MANIFEST)).read_text(encoding='utf8'))
        return list(document['dsh']['profile']['bundles'])

    def marker(self, name):
        return pathlib.Path(self.module.local(self.module.marker_path(name)))

    def test_internal_guard_survives_a_disable_marker(self):
        self.marker(INTERNAL).write_text('', encoding='utf8')
        self.assertEqual(0, self.module.register())
        self.assertIn(INTERNAL, self.profile_bundles(), '守卫被一个空标记关掉了')
        self.assertFalse(self.marker(INTERNAL).exists(), '标记应当被清掉，让状态收敛')

    def test_internal_guard_is_restored_after_being_removed_from_bundles(self):
        self.save_profile([name for name in self.bundles if name != INTERNAL])
        self.assertEqual(0, self.module.register())
        self.assertIn(INTERNAL, self.profile_bundles())

    def test_user_visible_builtin_still_honours_its_marker(self):
        self.marker(USER_VISIBLE).write_text('', encoding='utf8')
        self.assertEqual(0, self.module.register())
        self.assertNotIn(USER_VISIBLE, self.profile_bundles(), '用户主动禁用的插件必须继续被尊重')
        self.assertTrue(self.marker(USER_VISIBLE).exists())

    def test_disable_command_refuses_the_internal_guard(self):
        self.assertEqual(1, self.module.disable_plugin(INTERNAL))
        self.assertIn(INTERNAL, self.profile_bundles())
        self.assertFalse(self.marker(INTERNAL).exists())

    def test_disable_command_still_disables_a_user_visible_builtin(self):
        self.assertEqual(0, self.module.disable_plugin(USER_VISIBLE))
        self.assertNotIn(USER_VISIBLE, self.profile_bundles())
        self.assertTrue(self.marker(USER_VISIBLE).exists())


if __name__ == '__main__':
    unittest.main()
