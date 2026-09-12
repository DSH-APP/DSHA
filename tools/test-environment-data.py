#!/usr/bin/env python3
"""专属临时根目录验证覆盖升级的数据迁移与拒绝危险归档。"""
import importlib.util
import io
import json
import os
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest import mock

spec = importlib.util.spec_from_file_location('environment_data', Path(__file__).resolve().parents[1] / 'app/src/main/assets/environment-data.py')
engine = importlib.util.module_from_spec(spec)
spec.loader.exec_module(engine)


class MigrationTest(unittest.TestCase):
    def test_host_l2s_mount_placeholders_are_never_traversed(self):
        aliases = ['data/user/0/com.dsh.client/files/linux/ubuntu/.l2s',
                   'data/user/10/com.dsh.client/files/linux/ubuntu/.l2s',
                   'data/data/com.dsh.client/files/linux/ubuntu/.l2s']
        blocked = {self.old / name for name in aliases}
        for name in aliases:
            self.put(name + '/cache', '运行时临时文件')
        self.put('data/project/private.txt', '用户数据必须保留')
        original = Path.iterdir
        def guarded(path):
            if path in blocked:
                raise PermissionError('000 mount placeholder')
            return original(path)
        with mock.patch.object(Path, 'iterdir', guarded):
            engine.snapshot(self.old, self.archive)
        inventory = engine.verify_archive(self.archive)['inventory']
        self.assertIn('data/project/private.txt', inventory)
        self.assertFalse(any('.l2s' in name for name in inventory))
        engine.restore(self.new, self.archive)
        self.assertEqual('用户数据必须保留', (self.new/'data/project/private.txt').read_text(encoding='utf-8'))

    def test_permission_denial_in_personal_directory_still_aborts(self):
        self.put('data/project/private.txt')
        original = Path.iterdir
        def guarded(path):
            if path == self.old/'data/project':
                raise PermissionError('real personal data denied')
            return original(path)
        with mock.patch.object(Path, 'iterdir', guarded), self.assertRaises(PermissionError):
            engine.snapshot(self.old, self.archive)
        self.assertFalse(self.archive.exists())
        self.assertTrue((self.old/'data/project/private.txt').exists())

    def test_runtime_cache_match_requires_exact_app_root_boundary(self):
        self.assertTrue(engine.runtime_cache('data/user/0/com.dsh.client/files/linux/ubuntu/.l2s'))
        for name in ['data/project/.l2s', 'data/user/0/com.dsh.client.other/files/linux/ubuntu/.l2s',
                     'data/user/0/com.dsh.client/files/linux/ubuntu-old/.l2s', 'root/.l2s-notes']:
            self.assertFalse(engine.runtime_cache(name), name)

    @unittest.skipIf(os.name == 'nt', 'guest 绝对软链由 Linux 验证')
    def test_old_host_absolute_l2s_link_is_saved_as_content(self):
        self.put('.l2s/content', '硬链接个人文件')
        (self.old/'root').mkdir()
        (self.old/'root/document').symlink_to('/data/user/10/com.dsh.client/files/linux/ubuntu/.l2s/content')
        engine.snapshot(self.old, self.archive); engine.restore(self.new, self.archive)
        self.assertEqual('硬链接个人文件', (self.new/'root/document').read_text(encoding='utf-8'))
        self.assertFalse((self.new/'root/document').is_symlink())
        self.assertFalse((self.new/'.l2s').exists())

    @unittest.skipIf(os.name == 'nt', 'POSIX 链由 Linux 验证')
    def test_mapped_absolute_and_relative_l2s_links_keep_content(self):
        self.put('.l2s/content', '有效数据')
        (self.old/'root').mkdir()
        (self.old/'root/absolute').symlink_to(self.old/'.l2s/content')
        (self.old/'root/relative').symlink_to('../.l2s/content')
        engine.snapshot(self.old, self.archive); engine.restore(self.new, self.archive)
        for name in ['absolute','relative']:
            self.assertEqual('有效数据',(self.new/'root'/name).read_text(encoding='utf-8'))

    @unittest.skipIf(os.name == 'nt', 'POSIX 链由 Linux 验证')
    def test_l2s_loop_and_external_target_fail_without_publishing_archive(self):
        self.put('root/keep.txt')
        (self.old/'.l2s').mkdir()
        (self.old/'root/linked').symlink_to('/.l2s/first')
        (self.old/'.l2s/first').symlink_to('/.l2s/first')
        with self.assertRaisesRegex(ValueError, '循环'):
            engine.snapshot(self.old, self.archive)
        self.assertFalse(self.archive.exists())
        (self.old/'.l2s/first').unlink()
        self.put('sdcard/outside', '外部数据')
        (self.old/'.l2s/first').symlink_to('/sdcard/outside')
        with self.assertRaisesRegex(ValueError, '外部挂载'):
            engine.snapshot(self.old, self.archive)
        self.assertFalse(self.archive.exists())

    def test_source_file_is_not_read_again_just_to_calculate_hash(self):
        source = self.put('root/project/large.txt', '中文与二进制数据' * 20000)
        original = engine.digest
        def no_second_source_read(path, *args):
            self.assertNotEqual(source, Path(path))
            return original(path, *args)
        with mock.patch.object(engine, 'digest', side_effect=no_second_source_read):
            engine.snapshot(self.old, self.archive)
        self.assertEqual(engine.digest(source), engine.verify_archive(self.archive)['inventory']['root/project/large.txt']['sha256'])

    def test_owned_abandoned_archives_are_not_nested_in_new_snapshot(self):
        self.put('root/.dsha-personal-12345678-1234-1234-1234-123456789abc.tar.gz.part', '临时文件')
        self.put('root/.dsha-environment-data.py', '迁移脚本')
        self.put('root/my-personal.tar.gz', '用户自己的归档')
        engine.snapshot(self.old, self.archive)
        names = engine.verify_archive(self.archive)['inventory']
        self.assertEqual({'root/my-personal.tar.gz'}, set(names))
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.base = Path(self.temp.name)
        self.old, self.new = self.base / 'old', self.base / 'new'
        self.old.mkdir(); self.new.mkdir()
        self.archive = self.base / 'personal.tar.gz'

    def tearDown(self):
        self.temp.cleanup()

    def put(self, name, data='用户文件', root=None):
        path = (root or self.old) / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(data, encoding='utf-8')
        return path

    def test_personal_projects_dotfiles_and_non_system_directories_roundtrip(self):
        names = ['root/.ssh/config', 'root/.bashrc', 'root/项目/.git/config', 'root/项目/文件.txt',
                 'home/user/project/main.py', 'opt/my-app/custom.json', 'projects/two/package.json',
                 'data/project/private.txt', 'mnt/local/project.txt']
        for name in names:
            self.put(name)
        for name in ['usr/bin/node', 'etc/passwd', 'root/.cache/runtime', 'root/.npm/cache',
                     'root/.dsh/sessions/a', 'root/dsha-web-mobile/lib/client.js']:
            self.put(name, '旧环境')
        self.put('usr/bin/node', '新环境', self.new)
        self.put('root/dsha-web-mobile/lib/client.js', '新插件', self.new)
        snapshot = engine.snapshot(self.old, self.archive)
        restored = engine.restore(self.new, self.archive)
        self.assertTrue(restored['verified']); self.assertEqual(snapshot['bytes'], restored['bytes'])
        for name in names:
            self.assertEqual('用户文件', (self.new / name).read_text(encoding='utf-8'))
        self.assertEqual('新环境', (self.new / 'usr/bin/node').read_text(encoding='utf-8'))
        self.assertEqual('新插件', (self.new / 'root/dsha-web-mobile/lib/client.js').read_text(encoding='utf-8'))
        self.assertFalse((self.new / 'root/.cache').exists())
        self.assertTrue((self.old / 'root/.dsh/sessions/a').is_file())

    def test_registered_workspace_under_system_tree_is_preserved(self):
        self.put('root/.dsh/storages/workspace.json', json.dumps({'tables': {'workspaces': {'one': {'path': '/var/my-project'}}}}))
        self.put('var/my-project/main.py')
        self.put('var/lib/dpkg/status', '旧系统数据库')
        engine.snapshot(self.old, self.archive)
        engine.restore(self.new, self.archive)
        self.assertTrue((self.new / 'var/my-project/main.py').is_file())
        self.assertFalse((self.new / 'var/lib/dpkg/status').exists())

    def test_broad_system_workspace_fails_before_environment_changes(self):
        self.put('root/project/file')
        with self.assertRaisesRegex(ValueError, '系统目录'):
            engine.snapshot(self.old, self.archive, '/usr')
        self.assertFalse(self.archive.exists())
        self.assertTrue((self.old / 'root/project/file').is_file())

    def test_archive_inside_home_never_archives_itself(self):
        self.put('root/project/file')
        archive = self.old / 'root/personal.tar.gz'
        engine.snapshot(self.old, archive)
        self.assertNotIn('root/personal.tar.gz', engine.verify_archive(archive)['inventory'])
        engine.restore(self.new, archive)
        self.assertTrue((self.new / 'root/project/file').is_file())

    def test_corrupt_archive_is_rejected_before_new_files_change(self):
        self.put('root/project/file')
        engine.snapshot(self.old, self.archive)
        content = self.archive.read_bytes()
        self.archive.write_bytes(content[:len(content) // 2])
        with self.assertRaises(Exception):
            engine.restore(self.new, self.archive)
        self.assertEqual([], list(self.new.iterdir()))

    @unittest.skipIf(os.name == 'nt', '符号链接由 Linux 测试覆盖')
    def test_links_remain_links_without_copying_external_storage(self):
        self.put('root/project/file')
        (self.old / 'root/phone').symlink_to('/sdcard', target_is_directory=True)
        (self.old / 'root/project/relative').symlink_to('file')
        engine.snapshot(self.old, self.archive)
        engine.restore(self.new, self.archive)
        self.assertEqual('/sdcard', os.readlink(self.new / 'root/phone'))
        self.assertEqual('file', os.readlink(self.new / 'root/project/relative'))

    @unittest.skipIf(os.name == 'nt', '符号链接由 Linux 测试覆盖')
    def test_destination_parent_link_never_writes_outside_new_environment(self):
        self.put('root/project/file')
        engine.snapshot(self.old, self.archive)
        outside = self.base / 'outside'; outside.mkdir()
        (self.new / 'root').symlink_to(outside, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, '恢复父目录是链接'):
            engine.restore(self.new, self.archive)
        self.assertEqual([], list(outside.iterdir()))

    @unittest.skipIf(os.name == 'nt', 'POSIX 权限由 Linux 测试覆盖')
    def test_private_permissions_survive(self):
        secret = self.put('root/.ssh/key')
        secret.chmod(0o600); secret.parent.chmod(0o700)
        engine.snapshot(self.old, self.archive)
        engine.restore(self.new, self.archive)
        self.assertEqual(0o600, (self.new / 'root/.ssh/key').stat().st_mode & 0o777)
        self.assertEqual(0o700, (self.new / 'root/.ssh').stat().st_mode & 0o777)

    def test_no_data_environment_has_valid_empty_snapshot(self):
        engine.snapshot(self.old, self.archive)
        self.assertTrue(engine.restore(self.new, self.archive)['verified'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
