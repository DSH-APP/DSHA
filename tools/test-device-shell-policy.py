#!/usr/bin/env python3
"""设备执行器回归：假设备元数据，拒绝路径与混合进程批次不得进入写操作。"""
import importlib.util
from pathlib import Path
import shlex
import types
import unittest

ASSET = Path(__file__).resolve().parents[1] / 'app/src/main/assets/device-shell-policy.py'
spec = importlib.util.spec_from_file_location('device_policy', ASSET)
policy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(policy)
RULES = dict(protected=['/dcim', '/pictures', '/android/data', '/android/obb'],
             aliases=['/sdcard', '/mnt/sdcard', '/storage/self/primary', '/mnt/user/0/primary'],
             storage=r'^/storage/(?:emulated/[0-9]+|[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4})(/.*)?$', temporary='/data/local/tmp')


def result(output='', code=0):
    return types.SimpleNamespace(output=output, exit_code=code)


class PolicyTest(unittest.TestCase):
    def setUp(self):
        self.sent = []
        self.users = 'package:example.app uid:12345\npackage:com.dsh.client uid:12346\n'
        self.systems = 'package:android uid:1000\npackage:example.system uid:12347\n'
        self.ps = 'PID UID NAME\n42 12345 example.app\n43 1000 system_server\n44 12346 com.dsh.client\n'
        self.metadata, self.links = {}, ''

    def shell(self, command):
        self.sent.append(command)
        if command.startswith('if [ -L '):
            path = shlex.split(command)[3]
            return self.metadata.get(path, result('DIR\n' + path + '\n'))
        argv = shlex.split(command)
        if argv[0] == '/system/bin/pm': return result(self.systems if '-s' in argv else self.users)
        if argv[0] == '/system/bin/ps': return result(self.ps)
        if argv[0] == '/system/bin/find': return result(self.links)
        if argv[0] == '/system/bin/readlink': return result(argv[-1])
        return result('executed')

    def plan(self, kind, argv, paths):
        return dict(version=1, kind=kind, argv=argv, operands=paths, paths=RULES)

    def execute(self, plan): return policy.execute(plan, self.shell, lambda text, code: result(text, code))

    def no_mutations(self):
        self.assertFalse(any(c.startswith(('/system/bin/rm ', '/system/bin/cp ', '/system/bin/mv ', '/system/bin/am ', '/system/bin/touch ')) for c in self.sent), self.sent)

    def test_aliases_and_protected_children(self):
        for alias in RULES['aliases']:
            for suffix in ('/DCIM/a', '/Pictures', '/Android/data/a', '/Android', ''):
                self.assertFalse(policy.write_allowed(alias + suffix, RULES))
            self.assertTrue(policy.write_allowed(alias + '/Download/a', RULES))
        for path in ('/', '/system/x', '/dev/block/x', '/storage/emulated/1/Pictures/a', '/storage/ABCD-0123/DCIM/x', '/data/local/tmp', '/sdcard/Download/../Pictures/a'):
            self.assertFalse(policy.write_allowed(path, RULES), path)

    def test_canonical_destination_cannot_escape(self):
        path = '/data/local/tmp/a'
        self.metadata[path] = result('DIR\n/storage/emulated/0/DCIM\n')
        with self.assertRaises(policy.Blocked): self.execute(self.plan('FILE', ['touch', path], [path]))
        self.no_mutations()

    def test_symlink_in_write_chain_is_blocked(self):
        path = '/data/local/tmp/a/file'
        self.metadata['/data/local/tmp/a'] = result('LINK\n')
        with self.assertRaises(policy.Blocked): self.execute(self.plan('FILE', ['rm', path], [path]))
        self.no_mutations()

    def test_copy_checks_effective_target_inside_existing_directory(self):
        path = '/data/local/tmp/a'
        self.metadata[path + '/photo'] = result('LINK\n')
        with self.assertRaises(policy.Blocked): self.execute(self.plan('FILE', ['cp', '/sdcard/DCIM/photo', path], ['/sdcard/DCIM/photo', path]))
        self.no_mutations()

    def test_recursive_delete_with_symlink_is_blocked(self):
        self.links = '/data/local/tmp/a/link\n'
        with self.assertRaises(policy.Blocked): self.execute(self.plan('FILE', ['rm', '-rf', '/data/local/tmp/a'], ['/data/local/tmp/a']))
        self.no_mutations()

    def test_unreadable_metadata_blocks_mutation(self):
        self.metadata['/data/local/tmp/a'] = result('permission denied', 1)
        with self.assertRaises(policy.Blocked): self.execute(self.plan('FILE', ['touch', '/data/local/tmp/a'], ['/data/local/tmp/a']))
        self.no_mutations()

    def test_missing_mkdir_parents_resolve_existing_ancestor(self):
        for path in ('/data/local/tmp/a/b', '/data/local/tmp/a'):
            self.metadata[path] = result('MISSING\n')
        self.execute(self.plan('FILE', ['mkdir', '-p', '/data/local/tmp/a/b'], ['/data/local/tmp/a/b']))
        self.assertEqual('/system/bin/mkdir -p /data/local/tmp/a/b', self.sent[-1])

    def test_copy_readonly_source_allowed_and_execution_paths_normalized(self):
        plan = self.plan('FILE', ['cp', '/sdcard/DCIM/photo', '/sdcard/Download/a'], ['/sdcard/DCIM/photo', '/sdcard/Download/a'])
        self.execute(plan)
        self.assertEqual('/system/bin/cp /storage/emulated/0/DCIM/photo /storage/emulated/0/Download/a', self.sent[-1])

    def test_read_arguments_are_quoted_data(self):
        self.execute(self.plan('READ', ['echo', 'a; rm -rf /'], []))
        self.assertEqual(['/system/bin/echo', 'a; rm -rf /'], shlex.split(self.sent[-1]))

    def test_refresh_groups_before_each_user_app_stop(self):
        plan = self.plan('STOP', ['am', 'force-stop', 'example.app'], ['example.app'])
        plan['apps'] = [dict(name='android', uid=12345, system=False)]
        output = self.execute(plan).output
        self.assertIn('[用户应用]', output)
        self.assertIn('[系统应用]', output)
        self.assertEqual(['/system/bin/pm list packages -U -3', '/system/bin/pm list packages -U -s', '/system/bin/am force-stop example.app'], self.sent)

    def test_pid_is_resolved_to_package(self):
        self.execute(self.plan('STOP', ['kill', '-9', '42'], ['42']))
        self.assertEqual('/system/bin/am force-stop example.app', self.sent[-1])

    def test_mixed_batch_system_and_own_app_block_every_stop(self):
        for values in (['42', '43'], ['42', '44'], ['42', '999']):
            self.sent.clear()
            with self.assertRaises(policy.Blocked): self.execute(self.plan('STOP', ['kill'] + values, values))
            self.no_mutations()

    def test_system_and_unknown_packages_are_blocked(self):
        for name in ('android', 'example.system', 'com.dsh.client', 'unknown.app'):
            with self.assertRaises(policy.Blocked): self.execute(self.plan('STOP', ['am', 'force-stop', name], [name]))
            self.no_mutations()

    def test_shared_system_uid_and_incomplete_inventory_are_blocked(self):
        for users, systems in [(self.users + 'package:example.other uid:1000\n', self.systems), (self.users, ''), ('garbage', self.systems)]:
            self.users, self.systems = users, systems
            with self.assertRaises(policy.Blocked): self.execute(self.plan('STOP', ['am', 'force-stop', 'example.other'], ['example.other']))
            self.no_mutations()

    def test_unknown_plan_never_dispatches(self):
        for plan in (None, {}, dict(version=1, kind='DENY'), dict(version=2, kind='READ')):
            with self.assertRaises(policy.Blocked): self.execute(plan)
        self.assertEqual([], self.sent)

    def test_multiple_android_users_and_cloned_apps(self):
        self.users = 'package:example.app uid:12345,99912345\n'
        self.systems = 'package:android uid:1000,99901000\n'
        self.ps = 'PID UID NAME\n42 99912345 example.app\n43 99901000 system_server\n'
        self.execute(self.plan('STOP', ['kill', '42'], ['42']))
        self.assertEqual('/system/bin/am force-stop example.app', self.sent[-1])
        self.sent.clear()
        with self.assertRaises(policy.Blocked): self.execute(self.plan('STOP', ['kill', '42', '43'], ['42', '43']))
        self.no_mutations()
        self.users += 'package:example.shared uid:22222,99901000\n'
        with self.assertRaises(policy.Blocked): self.execute(self.plan('STOP', ['am', 'force-stop', 'example.shared'], ['example.shared']))


if __name__ == '__main__': unittest.main(verbosity=2)
