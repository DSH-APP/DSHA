#!/usr/bin/env python3
"""隔离 ADB 回归：假 socket/ADB/mDNS/凭据，不连接设备，不读取用户配置。"""
import contextlib
import errno
import importlib.util
import io
import json
from pathlib import Path
import re
import socket
import subprocess
import sys
import types
import unittest
import urllib.error
import urllib.request
from unittest.mock import patch, Mock

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, ASSETS / file)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


adb = load('adb_test_shell', 'adb-shell.py')
pair = load('adb_test_pair', 'adb-pair.py')


class FlowTest(unittest.TestCase):
    def setUp(self):
        self.stack = contextlib.ExitStack()
        self.addCleanup(self.stack.close)
        # 漏掉任何隔离都会直接失败，而不是偷偷连接、执行进程或读取实际凭据。
        self.stack.enter_context(patch('socket.socket', side_effect=AssertionError('真实 socket 被禁止')))
        self.stack.enter_context(patch('socket.create_connection', side_effect=AssertionError('真实连接被禁止')))
        self.stack.enter_context(patch('subprocess.Popen', side_effect=AssertionError('真实子进程被禁止')))
        self.stack.enter_context(patch.object(adb, 'local_ips', return_value=['192.0.2.1']))
        self.stack.enter_context(patch.object(adb, 'open', side_effect=lambda *a, **k: io.BytesIO(b'synthetic'), create=True))
        self.stack.enter_context(patch.object(adb, 'remember_endpoint'))
        self.stack.enter_context(patch.object(adb, 'load_port_history', return_value=[]))
        self.discovery = self.stack.enter_context(patch.object(adb, 'discover_endpoints', return_value=[]))
        self.stack.enter_context(patch.object(pair, 'adb', adb))
        self.stack.enter_context(patch.object(pair, 'ensure_key'))
        self.stack.enter_context(patch.object(pair, 'check_deps', return_value=True))
        self.stack.enter_context(patch.object(pair, 'open', side_effect=lambda *a, **k: io.StringIO('39000'), create=True))
        self.events = []
        self.connect_error = None
        self.shell_error = None
        self.remote_output = '结果\n'
        self.remote_exit = 0
        self.framed = True
        test = self
        class Device:
            def __init__(self, host, port, default_transport_timeout_s=None):
                test.assertGreater(default_transport_timeout_s, 0)
                self.host, self.port = host, port
                test.events.append(('new', host, port))
            def connect(self, **kwargs):
                test.events.append(('connect', self.host, self.port))
                for name in ('transport_timeout_s', 'read_timeout_s', 'auth_timeout_s'):
                    test.assertGreater(kwargs[name], 0)
                    test.assertLessEqual(kwargs[name], 8)
                if test.connect_error:
                    error = test.connect_error(self.host, self.port)
                    if error: raise error
                return True
            def shell(self, command, **kwargs):
                test.events.append(('shell', self.host, self.port))
                for name in ('transport_timeout_s', 'read_timeout_s', 'timeout_s'):
                    test.assertGreater(kwargs[name], 0)
                if test.shell_error: raise test.shell_error
                marker = re.search(r'__DSHA_EXIT_[0-9a-f]+__=', command).group()
                return test.remote_output + ('\n' + marker + str(test.remote_exit) + '\n' if test.framed else '')
            def close(self): test.events.append(('close', self.host, self.port))
        self.Device = Device
        self.Signer = lambda *a: object()

    def execute(self):
        return adb.connect_with_retry(self.Device, self.Signer, 'input tap 10 10', 39000)

    def test_timeout_after_dispatch_never_replays(self):
        self.shell_error = TimeoutError('回复丢失')
        with self.assertRaises(adb.ExecutionUnknown): self.execute()
        self.assertEqual(1, sum(e[0] == 'shell' for e in self.events))
        self.assertEqual(1, sum(e[0] == 'close' for e in self.events))
        self.discovery.assert_not_called()

    def test_library_type_error_never_reexecutes_without_timeouts(self):
        self.shell_error = TypeError('不兼容库')
        with self.assertRaises(adb.ExecutionUnknown): self.execute()
        self.assertEqual(1, sum(e[0] == 'shell' for e in self.events))

    def test_connect_failure_can_retry_but_closes_failed_device(self):
        self.connect_error = lambda host, port: ConnectionRefusedError() if host == '192.0.2.1' else None
        self.assertEqual(0, self.execute().exit_code)
        self.assertEqual(2, sum(e[0] == 'close' for e in self.events))
        self.assertEqual(1, sum(e[0] == 'shell' for e in self.events))

    def test_budget_stops_connect_retries(self):
        clock = [100.0]
        def failed(host, port):
            clock[0] += 15
            return TimeoutError()
        self.connect_error = failed
        with patch.object(adb.time, 'monotonic', side_effect=lambda: clock[0]):
            with self.assertRaises(adb.ConnectFail): self.execute()
        self.discovery.assert_not_called()
        self.assertFalse(any(e[0] == 'shell' for e in self.events))

    def test_real_remote_exit_is_returned(self):
        self.remote_exit = 17
        result = self.execute()
        self.assertEqual(17, result.exit_code)
        self.assertEqual(self.remote_output, result.output)
        self.assertEqual(1, sum(e[0] == 'shell' for e in self.events))

    def test_missing_exit_marker_is_unknown_not_success(self):
        self.framed = False
        with self.assertRaises(adb.ExecutionUnknown): self.execute()
        self.discovery.assert_not_called()

    def test_exit_framing_handles_exit_exec_quotes_and_bytes(self):
        for command in ["exit 7", "exec false", "printf '%s' 'a b'", "echo x\nexit 3"]:
            wrapped = adb.frame_command(command, 'MARK=')
            self.assertTrue(wrapped.startswith('/system/bin/sh -c '))
            self.assertIn('__dsha_rc=$?', wrapped)
        result = adb.parse_shell_result(b'one\r\nMARK=255\r\n', 'MARK=')
        self.assertEqual(('one', 255), (result.output, result.exit_code))
        for raw in ['MARK=0', '\nMARK=256\n', '\nOTHER=0\n', '\nMARK=0\ntruncated']:
            with self.assertRaises(adb.ExecutionUnknown): adb.parse_shell_result(raw, 'MARK=')

    def test_readonly_classifier_rejects_writing_tools_and_shell_syntax(self):
        for command in ['env input tap 10 10', 'logcat -c', 'date -s 20260101.000000',
                        'dumpsys battery set level 9', 'find / -exec id {} +',
                        'input tap 10 10', 'echo x > /sdcard/f', 'cat $(id)',
                        '/data/local/tmp/id', 'pm uninstall example.app', 'settings put global foo 1']:
            self.assertFalse(adb.is_readonly_cmd(command), command)
        for command in ['id', '/system/bin/getprop ro.product.model', 'pm list packages', 'settings get global foo']:
            self.assertTrue(adb.is_readonly_cmd(command), command)

    def main_fakes(self, command, root=False, disabled=False, blocked=False):
        mods = {'adb_shell_wifi': types.ModuleType('adb_shell_wifi'),
                'adb_shell_wifi.adb_device': types.SimpleNamespace(AdbDeviceTls=self.Device),
                'adb_shell_wifi.auth': types.ModuleType('auth'),
                'adb_shell_wifi.auth.sign_pythonrsa': types.SimpleNamespace(PythonRSASigner=self.Signer)}
        self.stack.enter_context(patch.dict(sys.modules, mods))
        self.stack.enter_context(patch.object(adb.sys, 'argv', ['adb-shell.py', '--host', '192.0.2.1', '--port', '39000'] + command))
        self.stack.enter_context(patch.object(adb.os, 'environ', {}))
        self.native_plan = self.stack.enter_context(patch.object(adb, 'request_native_execution',
            side_effect=adb.policy.Blocked('策略拒绝') if blocked else None,
            return_value={'version': 1, 'kind': 'READ', 'argv': ['id'], 'su': root}))
        self.stack.enter_context(patch.object(adb.os.path, 'isfile', side_effect=lambda p: p in (adb.KEY, adb.KEYPUB)
                or (root and p.endswith('allow-root-shell')) or (disabled and p.endswith('confirm-shell-disabled'))))

    def test_main_uses_native_plan_and_propagates_nonzero_exit(self):
        self.main_fakes(['id'])
        self.remote_exit = 5
        with patch.object(adb, 'request_confirm', return_value=True) as confirm, contextlib.redirect_stdout(io.StringIO()) as out:
            result = adb.main()
        self.assertEqual(5, result)
        self.assertTrue(out.getvalue().endswith('[EXIT=5]\n'))
        confirm.assert_not_called()
        self.native_plan.assert_called_once_with('id', False, True)
        wrapper = (ASSETS / 'adb-setup.sh').read_text(encoding='utf-8').split("cat > /root/dsh-bin/adb-shell <<'EOF'", 1)[1].split('\nEOF', 1)[0]
        self.assertNotIn('dsh-confirm.sh', wrapper)
        self.assertIn('exec python3', wrapper)

    def test_native_denial_does_not_connect(self):
        self.main_fakes(['input tap 10 10'], blocked=True)
        with patch.object(adb, 'request_confirm', side_effect=adb.ConfirmationError('BUSY')), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(126, adb.main())
        self.assertEqual([], self.events)

    def test_root_authorization_required_even_with_confirmation_disabled(self):
        self.main_fakes(['--su', 'id'], disabled=True, blocked=True)
        with contextlib.redirect_stdout(io.StringIO()): self.assertEqual(126, adb.main())
        self.assertEqual([], self.events)

    def test_root_still_requires_native_plan_when_authorized(self):
        self.main_fakes(['--su', 'id'], root=True, disabled=True)
        with patch.object(adb, 'request_confirm', return_value=True) as confirm, contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, adb.main())
        confirm.assert_not_called()
        self.native_plan.assert_called_once_with('id', True, True)

    def test_confirmation_switch_never_bypasses_native_policy(self):
        self.main_fakes(['id'], disabled=True)
        with patch.object(adb, 'request_confirm') as confirm, contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, adb.main())
        confirm.assert_not_called()
        self.native_plan.assert_called_once_with('id', False, True)

    def test_internal_flag_never_bypasses_native_denial(self):
        self.main_fakes(['setenforce 0'], root=True, disabled=True, blocked=True)
        with patch.dict(adb.os.environ, {'DSH_INTERNAL': '1', 'DSH_NO_CONFIRM': '1'}), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(126, adb.main())
        self.assertEqual([], self.events)

    def test_main_unknown_result_nonzero_and_no_replay(self):
        self.main_fakes(['id'])
        self.shell_error = TimeoutError()
        with contextlib.redirect_stdout(io.StringIO()) as out:
            self.assertEqual(125, adb.main())
        self.assertIn('EXECUTION_UNKNOWN:', out.getvalue())

    def test_confirmation_timeout_does_not_issue_a_second_request(self):
        with patch.object(adb, 'open', return_value=io.StringIO('fake-token')):
            opener = Mock()
            opener.open.side_effect = urllib.error.URLError(TimeoutError())
            with patch.object(urllib.request, 'build_opener', return_value=opener):
                with self.assertRaises(adb.ConfirmationError): adb.request_confirm('input tap 10 10')
            self.assertEqual(1, opener.open.call_count)

    def test_confirmation_strict_json_and_address_fallback(self):
        response = Mock()
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        response.read.return_value = b'{"result":"YES"}'
        opener = Mock()
        opener.open.side_effect = [urllib.error.URLError(ConnectionRefusedError(errno.ECONNREFUSED, 'refused')), response]
        with patch.object(adb, 'open', side_effect=lambda *a: io.StringIO('fake-token')), patch.object(urllib.request, 'build_opener', return_value=opener):
            self.assertTrue(adb.request_confirm('input tap 10 10'))
            self.assertEqual(2, opener.open.call_count)
            self.assertNotIn('fake-token', opener.open.call_args.args[0].full_url)
            opener.open.side_effect = None
            opener.open.return_value = response
            response.read.return_value = b'{"result":"NO","extra":"YES"}'
            with self.assertRaises(adb.ConfirmationError): adb.request_confirm('input tap 10 10')

    def test_pairing_warning_not_connection_success_or_repeated_pair(self):
        with patch.object(pair.sys, 'argv', ['adb-pair.py', '--code', '123456', '--port', '39000']), \
             patch.object(pair, 'probe', return_value=True), patch.object(pair, 'do_pair', return_value=(True, 'PAIR_OK: 完成')) as handshake, \
             patch.object(pair, 'verify_connection', side_effect=adb.ConnectFail('端口失效')), \
             patch.object(pair.time, 'sleep'), contextlib.redirect_stdout(io.StringIO()) as out:
            self.assertEqual(0, pair.main())
        handshake.assert_called_once()
        self.assertIn('PAIR_OK:', out.getvalue())
        self.assertIn('CONNECT_WARN:', out.getvalue())
        self.assertNotIn('CONNECT_OK:', out.getvalue())

    def test_verify_only_never_pairs(self):
        with patch.object(pair.sys, 'argv', ['adb-pair.py', '--verify-only']), \
             patch.object(pair.os.path, 'isfile', return_value=True), \
             patch.object(pair, 'verify_connection') as verify, patch.object(pair, 'do_pair') as handshake:
            self.assertEqual(0, pair.main())
        verify.assert_called_once()
        handshake.assert_not_called()

    def test_invalid_pair_input_does_not_prepare_or_pair(self):
        for code in ['12345', '1234567', '１２３４５６']:
            with patch.object(pair.sys, 'argv', ['adb-pair.py', '--code', code]), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(2, pair.main())
        pair.ensure_key.assert_not_called()

    def test_script_and_wrapper_version_match_java(self):
        for file in ['adb-shell.py', 'adb-pair.py', 'adb-setup.sh']:
            self.assertIn('DSHA_ADB_SCRIPT_VERSION=17', (ASSETS / file).read_text(encoding='utf-8'))
        source = (ROOT / 'app/src/main/java/com/deepseekharness/app/bridge/AdbBridge.java').read_text(encoding='utf-8')
        self.assertIn('SCRIPT_VERSION = "17"', source)
        self.assertIn("grep -q '^# DSHA_ADB_SCRIPT_VERSION=", source)


class NativeRouteTest(unittest.TestCase):
    def test_root_or_shizuku_success_needs_no_adb_keys_or_dependencies(self):
        for code in (0, 17, 126):
            output = 'native result\n[EXIT=%d]' % code
            with patch.object(adb.sys, 'argv', ['adb-shell.py', 'id']), \
                 patch.object(adb, 'request_native_execution', return_value=adb.ShellResult(output, code)) as request, \
                 patch.object(adb.os.path, 'isfile', side_effect=AssertionError('不能读取 ADB 密钥')), \
                 patch.object(adb, 'connect_with_retry', side_effect=AssertionError('不能调用 ADB')), \
                 contextlib.redirect_stdout(io.StringIO()) as stdout:
                self.assertEqual(code, adb.main())
            request.assert_called_once_with('id', False, False)
            self.assertEqual(output + '\n', stdout.getvalue())

    def test_lost_native_response_never_falls_back_to_adb(self):
        with patch.object(adb.sys, 'argv', ['adb-shell.py', 'id']), \
             patch.object(adb, 'request_native_execution', side_effect=adb.ExecutionUnknown('已发送')), \
             patch.object(adb, 'connect_with_retry', side_effect=AssertionError('不能重放')), \
             contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(125, adb.main())

    def invoke_bridge(self, value=None, error=None):
        response = Mock()
        response.__enter__ = Mock(return_value=response); response.__exit__ = Mock(return_value=False)
        response.read.return_value = json.dumps({'result': json.dumps(value)}).encode()
        opener = Mock(); opener.open.return_value = response; opener.open.side_effect = error
        with patch.object(adb, 'open', return_value=io.StringIO('fake-token'), create=True), \
             patch.object(urllib.request, 'build_opener', return_value=opener):
            try:
                return adb.request_native_execution('id')
            finally:
                opener.open.assert_called_once()
                self.assertNotIn('fake-token', opener.open.call_args.args[0].full_url)

    def test_only_explicit_plan_can_trigger_adb(self):
        plan = {'version': 1, 'kind': 'READ', 'argv': ['id'], 'su': False}
        self.assertEqual(plan, self.invoke_bridge({'state': 'adb', 'plan': plan}))
        result = self.invoke_bridge({'state': 'completed', 'output': 'uid=0(root)\n[EXIT=0]', 'exit': 0})
        self.assertEqual(0, result.exit_code)
        for bad in ({'kind': 'READ', 'argv': ['id']}, {'state': 'adb'},
                    {'state': 'completed', 'output': 'truncated'},
                    {'state': 'completed', 'output': 'x', 'exit': 999}):
            with self.assertRaises(adb.ExecutionUnknown): self.invoke_bridge(bad)
        with self.assertRaises(adb.ExecutionUnknown): self.invoke_bridge(error=TimeoutError())


class DiscoveryTest(unittest.TestCase):
    def run_discovery(self, fail=False, removed=False):
        events = []
        class Zc:
            def get_service_info(self, type_, name, timeout):
                return types.SimpleNamespace(port=31000 if name == 'foreign' else 39000,
                    parsed_addresses=lambda: ['192.0.2.2'] if name == 'foreign' else ['192.0.2.1'])
            def close(self): events.append('close')
        class Browser:
            def __init__(self, zc, type_, listener):
                if fail: raise RuntimeError('发现失败')
                listener.add_service(zc, type_, 'foreign')
                listener.add_service(zc, type_, 'local')
                if removed: listener.remove_service(zc, type_, 'local')
            def cancel(self): events.append('cancel')
        module = types.SimpleNamespace(Zeroconf=Zc, ServiceBrowser=Browser, ServiceListener=object)
        with patch.dict(sys.modules, {'zeroconf': module}), patch.object(adb.time, 'sleep'), \
             patch('socket.socket', side_effect=AssertionError('真实网络被禁止')):
            endpoints = adb.discover_endpoints('_adb-tls-pairing._tcp.local.', .1, ['192.0.2.1'])
        return endpoints, events

    def test_foreign_device_port_is_rejected_and_cleanup_always_runs(self):
        self.assertEqual(([('192.0.2.1', 39000)], ['cancel', 'close']), self.run_discovery())

    def test_service_loss_removes_stale_port(self):
        self.assertEqual(([], ['cancel', 'close']), self.run_discovery(removed=True))

    def test_start_failure_still_closes_zeroconf(self):
        self.assertEqual(([], ['close']), self.run_discovery(fail=True))


if __name__ == '__main__':
    unittest.main(verbosity=2)
