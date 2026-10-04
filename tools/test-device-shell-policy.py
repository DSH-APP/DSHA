#!/usr/bin/env python3
"""设备执行器回归：假设备元数据，拒绝路径与混合进程批次不得进入写操作。"""
import importlib.util
from pathlib import Path
import posixpath
import shlex
import types
import unittest

ASSET = Path(__file__).resolve().parents[1] / 'app/src/main/assets/device-shell-policy.py'
spec = importlib.util.spec_from_file_location('device_policy', ASSET)
policy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(policy)
RULES = dict(protected=['/dcim', '/pictures', '/android/data', '/android/obb'],
             aliases=['/sdcard', '/mnt/sdcard', '/storage/self/primary', '/mnt/user/0/primary'],
             smsProvider='com.android.providers.telephony',
             smsReadRoots=['/data/data/com.android.providers.telephony', '/data/user/*/com.android.providers.telephony', '/data/user_de/*/com.android.providers.telephony', '/data_mirror/data_ce/*/*/com.android.providers.telephony', '/data_mirror/data_de/*/*/com.android.providers.telephony', '/mnt/expand/*/user/*/com.android.providers.telephony', '/mnt/expand/*/user_de/*/com.android.providers.telephony'],
             storage=r'^/storage/(?:emulated/[0-9]+|[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4})(/.*)?$', temporary='/data/local/tmp')


def result(output='', code=0):
    return types.SimpleNamespace(output=output, exit_code=code)


class PolicyTest(unittest.TestCase):
    def setUp(self):
        self.sent = []
        self.users = 'package:example.app uid:12345\npackage:com.dsh.client uid:12346\n'
        self.systems = 'package:android uid:1000\npackage:example.system uid:12347\n'
        self.ps = 'PID UID NAME\n42 12345 example.app\n43 1000 system_server\n44 12346 com.dsh.client\n'
        self.metadata, self.links, self.realpaths = {}, '', {}

    def shell(self, command):
        self.sent.append(command)
        if command.startswith('if [ -L '):
            path = shlex.split(command)[3]
            return self.metadata.get(path, result('DIR\n' + path + '\n'))
        argv = shlex.split(command)
        if argv[0] == '/system/bin/pm': return result(self.systems if '-s' in argv else self.users)
        if argv[0] == '/system/bin/ps': return result(self.ps)
        if argv[0] == '/system/bin/find': return result(self.links)
        if argv[0] == '/system/bin/readlink': return result(self.realpaths.get(argv[-1], posixpath.normpath('/' + argv[-1].lstrip('/'))))
        return result('executed')

    def plan(self, kind, argv, paths):
        return dict(version=1, kind=kind, argv=argv, operands=paths, paths=RULES,
                    selfPackage="com.dsh.client", selfUid=12346, sourceApk='/data/app/com.dsh.client/base.apk')

    def execute(self, plan): return policy.execute(plan, self.shell, lambda text, code: result(text, code))

    def no_mutations(self):
        self.assertFalse(any(c.startswith(('/system/bin/rm ', '/system/bin/cp ', '/system/bin/mv ', '/system/bin/am ', '/system/bin/touch ', '/system/bin/app_process ')) for c in self.sent), self.sent)

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
        self.assertEqual(['mkdir', '-p', '/data/local/tmp/a/b'], shlex.split(self.sent[-1])[9:])
        self.assertIn('com.deepseekharness.app.DeviceFileCore', self.sent[-1])

    def test_copy_readonly_source_allowed_and_execution_paths_normalized(self):
        plan = self.plan('FILE', ['cp', '/sdcard/DCIM/photo', '/sdcard/Download/a'], ['/sdcard/DCIM/photo', '/sdcard/Download/a'])
        self.execute(plan)
        self.assertEqual(['cp', '/storage/emulated/0/DCIM/photo', '/storage/emulated/0/Download/a'], shlex.split(self.sent[-1])[9:])
        self.assertEqual(1, sum('com.deepseekharness.app.DeviceFileCore' in sent for sent in self.sent))

    def test_file_helper_identity_invalid_never_dispatches(self):
        plan = self.plan('FILE', ['touch', '/data/local/tmp/a'], ['/data/local/tmp/a'])
        for change in ({'sourceApk': '/sdcard/attacker.apk'}, {'sourceApk': '/data/app/../attacker.apk'},
                       {'selfPackage': ''}, {'selfUid': True}, {'selfUid': 2000}, {'argv': ['app_process']}):
            self.sent.clear()
            with self.assertRaises(policy.Blocked): self.execute(dict(plan, **change))
            self.no_mutations()

    def test_file_helper_failure_is_returned_once_without_shell_fallback(self):
        plan = self.plan('FILE', ['touch', '/data/local/tmp/a'], ['/data/local/tmp/a'])
        original = self.shell
        def failed(command):
            if 'com.deepseekharness.app.DeviceFileCore' in command:
                self.sent.append(command)
                return result('PRIVILEGED_PACKAGE_CONTEXT_UNAVAILABLE', 126)
            return original(command)
        reply = policy.execute(plan, failed, result)
        self.assertEqual(126, reply.exit_code)
        self.assertEqual(1, sum('com.deepseekharness.app.DeviceFileCore' in sent for sent in self.sent))
        self.assertFalse(any(sent.startswith('/system/bin/touch ') for sent in self.sent))

    def test_file_helper_unknown_result_is_not_replayed(self):
        plan = self.plan('FILE', ['touch', '/data/local/tmp/a'], ['/data/local/tmp/a'])
        original = self.shell
        def lost(command):
            if 'com.deepseekharness.app.DeviceFileCore' in command:
                self.sent.append(command)
                raise TimeoutError('synthetic dispatch result unknown')
            return original(command)
        with self.assertRaises(TimeoutError): policy.execute(plan, lost, result)
        self.assertEqual(1, sum('com.deepseekharness.app.DeviceFileCore' in sent for sent in self.sent))

    def test_file_helper_quotes_user_paths_as_data(self):
        path = '/data/local/tmp/space ; $name'
        self.execute(self.plan('FILE', ['touch', path], [path]))
        argv = shlex.split(self.sent[-1])
        self.assertEqual(['touch', path], argv[9:])

    def test_read_arguments_are_quoted_data(self):
        self.execute(self.plan('READ', ['echo', 'a; rm -rf /'], []))
        self.assertEqual(['/system/bin/echo', 'a; rm -rf /'], shlex.split(self.sent[-1]))

    def test_root_reads_and_copy_sources_cannot_read_sms_provider(self):
        protected = '/data/user/0/com.android.providers.telephony/databases/mmssms.db'
        for plan in (self.plan('READ', ['cat', protected], []),
                     self.plan('FILE', ['cp', protected, '/sdcard/Download/messages.db'], [protected, '/sdcard/Download/messages.db'])):
            plan['su'] = True
            with self.assertRaises(policy.Blocked): self.execute(plan)
        self.assertEqual([], self.sent)

        multi = self.plan('FILE', ['cp', '/data/local/tmp/ok', protected, '/sdcard/Download/messages.db'],
                          ['/data/local/tmp/ok', protected, '/sdcard/Download/messages.db'])
        multi['su'] = True
        with self.assertRaises(policy.Blocked): self.execute(multi)
        self.assertFalse(any(command.startswith('/system/bin/cp ') for command in self.sent))

    def test_root_cannot_follow_symlinked_copy_source_into_sms_provider(self):
        alias = '/data/local/tmp/innocent.db'
        self.realpaths[alias] = '/data/user_de/10/com.android.providers.telephony/databases/mmssms.db-wal'
        plan = self.plan('FILE', ['cp', alias, '/sdcard/Download/copy.db'], [alias, '/sdcard/Download/copy.db'])
        plan['su'] = True
        with self.assertRaises(policy.Blocked): self.execute(plan)
        self.assertIn('/system/bin/readlink -f -- /data/local/tmp/innocent.db', self.sent)
        self.assertFalse(any(command.startswith('/system/bin/cp ') for command in self.sent))

    def test_root_recursive_traversal_cannot_enter_telephony_tree(self):
        for command, argv, operands in (
                ('find /', ['find', '/'], ['/']),
                ('du', ['du'], []),
                ('du data', ['du', 'data'], []),
                ('du /data', ['du', '/data'], ['/data']),
                ('grep -R sms /data/data', ['grep', '-R', 'sms', '/data/data'], []),
                ('ls -R', ['ls', '-R'], []),
                ('ls -R data', ['ls', '-R', 'data'], []),
                ('ls -R /data/user', ['ls', '-R', '/data/user'], []),
                ('cp -R /data/data /sdcard/Download/data', ['cp', '-R', '/data/data', '/sdcard/Download/data'], ['/data/data', '/sdcard/Download/data'])):
            with self.subTest(command=command):
                self.sent.clear()
                plan = self.plan('FILE' if argv[0] == 'cp' else 'READ', argv, operands)
                plan['su'] = True
                with self.assertRaises(policy.Blocked): self.execute(plan)
                self.assertFalse(any(sent == '/system/bin/find /' or sent.startswith('/system/bin/cp ') for sent in self.sent))
        self.assertFalse(policy.sms_provider_descendant('/data/user/0/com.example.app', RULES))
        self.assertFalse(policy.sms_provider_descendant('/data/data/com.example.app', RULES))

    def test_typed_virtual_screen_requires_ticket_and_exact_managed_source(self):
        source = '/data/app/com.dsh.client/base.apk'
        ticket = '0123456789abcdef0123456789abcdef0123456789abcdef'
        plan = dict(version=1, kind='VIRTUAL_SCREEN', argv=['app_process', '-Djava.class.path=' + source,
            '/system/bin', 'com.deepseekharness.app.vscreen.VirtualScreenCore', '--launch', '--port', '8800', '--package', 'com.dsh.clienu'],
            sourceApk=source, selfPackage="com.dsh.clienu", nativeAuthorization='managed-vscreen-start', nativeTicket=ticket, su=False)
        self.execute(plan)
        self.assertEqual('/system/bin/app_process -Djava.class.path=/data/app/com.dsh.client/base.apk /system/bin com.deepseekharness.app.vscreen.VirtualScreenCore --launch --port 8800 --package com.dsh.clienu', self.sent[-1])
        self.sent.clear()
        for change in (
                {'nativeTicket': ''}, {'nativeAuthorization': 'generic'}, {'su': True},
                {'sourceApk': '/data/app/attacker/base.apk'},
                {'argv': plan['argv'] + ['--token', 'a' * 48]}):
            bad = dict(plan); bad.update(change)
            with self.assertRaises(policy.Blocked): self.execute(bad)
        self.assertEqual([], self.sent)

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

    def test_mirror_and_adopted_sms_paths_cannot_be_read_or_walked(self):
        for path in ('/data_mirror/data_ce/null/0/com.android.providers.telephony/databases/mmssms.db',
                     '/data_mirror/data_de/null/10/com.android.providers.telephony/databases/mmssms.db-wal',
                     '/mnt/expand/fixture/user/0/com.android.providers.telephony/databases/mmssms.db'):
            self.assertTrue(policy.sms_provider_path(path, RULES))
            with self.assertRaises(policy.Blocked): self.execute(dict(self.plan('READ', ['cat', path], []), su=True))
        for path in ('/data_mirror', '/data_mirror/data_ce', '/data_mirror/data_ce/null', '/data_mirror/data_ce/null/0', '/mnt/expand/fixture/user/0'):
            with self.assertRaises(policy.Blocked): self.execute(dict(self.plan('READ', ['find', path], []), su=True))
        self.assertEqual([], self.sent)

    def test_sms_paths_consume_native_root_table_without_python_mirror(self):
        rules = dict(RULES, smsReadRoots=['/vendor/private/*/sms-provider'])
        self.assertTrue(policy.sms_provider_path('/vendor/private/10/sms-provider/database', rules))
        self.assertTrue(policy.sms_provider_descendant('/vendor/private', rules))
        self.assertFalse(policy.sms_provider_path('/vendor/private/10/other', rules))
        with self.assertRaises(policy.Blocked):
            policy.sms_provider_path('/data/local/tmp/file', dict(RULES, smsReadRoots=None))

    def test_multiple_android_users_and_cloned_apps(self):
        self.users = 'package:example.app uid:12345,99912345\npackage:com.dsh.client uid:13333,99913333\n'
        self.systems = 'package:android uid:1000,99901000\n'
        self.ps = 'PID UID NAME\n42 99912345 example.app\n43 99901000 system_server\n'
        self.execute(self.plan('STOP', ['kill', '42'], ['42']))
        self.assertEqual('/system/bin/am force-stop example.app', self.sent[-1])
        self.sent.clear()
        with self.assertRaises(policy.Blocked): self.execute(self.plan('STOP', ['kill', '42', '43'], ['42', '43']))
        self.no_mutations()
        self.users += 'package:example.shared uid:22222,99901000\n'
        with self.assertRaises(policy.Blocked): self.execute(self.plan('STOP', ['am', 'force-stop', 'example.shared'], ['example.shared']))

    def test_actual_clone_identity_protects_its_uid_and_missing_identity_never_stops(self):
        self.users = 'package:example.app uid:12345\npackage:com.dsh.clienu uid:12346,99912346\npackage:shared.app uid:99912346\n'
        for target in ('com.dsh.clienu', 'shared.app'):
            plan=self.plan('STOP',['am','force-stop',target],[target]);plan['selfPackage']='com.dsh.clienu'
            with self.assertRaises(policy.Blocked):self.execute(plan)
            self.no_mutations()
        self.sent.clear();plan=self.plan('STOP',['am','force-stop','example.app'],['example.app']);plan.pop('selfPackage')
        with self.assertRaises(policy.Blocked):self.execute(plan)
        self.assertEqual([],self.sent)


if __name__ == '__main__': unittest.main(verbosity=2)
