#!/usr/bin/env python3
"""隔离 fs/urllib 的真实 adb-shell 消费者；不读设备凭据，不连接任何端口。"""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
from types import SimpleNamespace
import sys
import unittest
from unittest import mock
import urllib.error
import urllib.request

sys.dont_write_bytecode = True
ASSET = Path(__file__).resolve().parents[1] / 'app/src/main/assets/adb-shell.py'
SPEC = importlib.util.spec_from_file_location('port_test_adb', ASSET)
adb = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(adb)


class Fixture(contextlib.ExitStack):
    def __init__(self, raw=b'3091', env='3097'):
        super().__init__()
        self.raw, self.env = raw, env
        self.fields = dict(st_mode=0o100600, st_dev=1, st_ino=2, st_size=len(raw), st_mtime_ns=1, st_ctime_ns=1)
        self.reads = self.closes = self.opens = 0
        self.on_open = self.on_read = lambda: None
        self.missing = False

    def info(self): return SimpleNamespace(**self.fields)

    def lstat(self, path):
        assert path == adb.BRIDGE_PORT_FILE
        if self.missing: raise FileNotFoundError()
        return self.info()

    def open(self, path, flags):
        assert path == adb.BRIDGE_PORT_FILE
        assert flags == os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK
        self.opens += 1
        self.on_open()
        return 7

    def read(self, fd, count):
        assert fd == 7 and count == 6
        self.reads += 1
        raw = self.raw[:count]
        self.on_read()
        return raw

    def close(self, fd):
        assert fd == 7
        self.closes += 1

    def __enter__(self):
        super().__enter__()
        # These operations are mocked, so Windows needs flag stand-ins for the
        # Android/POSIX contract. Preserve native values where available; never
        # remove NOFOLLOW/NONBLOCK from the production reader to suit the host.
        for key, fallback in [('O_NOFOLLOW', 0x20000), ('O_NONBLOCK', 0x800)]:
            self.enter_context(mock.patch.object(adb.os, key, getattr(adb.os, key, fallback), create=True))
        for key, value in dict(lstat=self.lstat, open=self.open, fstat=lambda fd: self.info(), read=self.read, close=self.close).items():
            self.enter_context(mock.patch.object(adb.os, key, value))
        # Keep oversized-parser inputs in the fixture instead of exporting them
        # to the real host environment (Windows caps one variable at 32767).
        self.enter_context(mock.patch.object(adb.os, 'environ', {'DSHA_BRIDGE_PORT': self.env}))
        return self


class PortTest(unittest.TestCase):
    def test_decimal_file_precedes_environment_and_rereads(self):
        with Fixture() as f:
            self.assertEqual(3091, adb.bridge_port())
            f.raw = b'3092'
            self.assertEqual(3092, adb.bridge_port())
            f.missing = True
            self.assertEqual(3097, adb.bridge_port())
            adb.os.environ['DSHA_BRIDGE_PORT'] = '3098'
            self.assertEqual(3098, adb.bridge_port())
            self.assertEqual(2, f.closes)

    def test_only_bounded_ascii_decimal_environment(self):
        for raw, want in [('1', 1), ('65535', 65535), ('03091', 3091)]:
            with self.subTest(raw=raw[:20]), Fixture(env=raw) as f:
                f.missing = True
                self.assertEqual(want, adb.bridge_port())
                self.assertEqual(0, f.opens)

    def test_unpublished_port_blocks_instead_of_guessing_fixed_port(self):
        # 3090 可能属于同机另一个 DSHA 安装；文件和环境都无效时必须拦截，不能回退。
        for raw in ['', '0', '65536', '0xc13', '3e3', '+3091', '3091.0', ' 3091', '3091\n',
                    '٣٠٩١', '²', '９９９９', '9' * 100000]:
            with self.subTest(raw=raw[:20]), Fixture(env=raw) as f:
                f.missing = True
                with self.assertRaises(adb.policy.Blocked) as caught:
                    adb.bridge_port()
                self.assertIn('BRIDGE_PORT_UNAVAILABLE', str(caught.exception))

    def test_unpublished_port_sends_no_credential_from_any_consumer(self):
        with Fixture(env='') as f, mock.patch('builtins.open', mock.mock_open(read_data='fixture-token')):
            f.missing = True
            send = mock.Mock()
            with mock.patch.object(urllib.request, 'build_opener', return_value=SimpleNamespace(open=send)):
                for operation in ['plan', 'start', 'commit', 'execute']:
                    with self.subTest(operation=operation):
                        # commit 未发送时也必须是 Blocked，不能报成「可能已执行」。
                        with self.assertRaises(adb.policy.Blocked):
                            if operation == 'plan': adb.request_device_plan('id')
                            elif operation == 'start': adb.request_native_vscreen_start('fixture', 'fixture-ticket')
                            elif operation == 'commit': adb.request_native_vscreen_commit('fixture-ticket')
                            else: adb.request_native_execution('id')
            self.assertEqual(0, send.call_count)

    def test_invalid_bytes_permissions_and_types_fall_back(self):
        for raw in [b'', b'65536', b'0', b'3091\n', b' 3091', b'309100', b'0xc13', b'3e3', b'\xb3\xb0\xb9\xb1', b'\xff']:
            with self.subTest(raw=raw), Fixture(raw) as f:
                self.assertEqual(3097, adb.bridge_port())
                self.assertEqual(f.opens, f.closes)
        for mode in [0o100644, 0o120600, 0o010600, 0o040600]:
            with self.subTest(mode=mode), Fixture() as f:
                f.fields['st_mode'] = mode
                self.assertEqual(3097, adb.bridge_port())
                self.assertEqual(0, f.opens)

    def test_open_and_read_races_are_bounded_and_closed(self):
        for phase in ['on_open', 'on_read']:
            for key, value in [('st_dev', 2), ('st_ino', 3), ('st_size', 5), ('st_mode', 0o100644),
                               ('st_mtime_ns', 2), ('st_ctime_ns', 2), ('st_mode', 0o010600)]:
                with self.subTest(phase=phase, key=key, value=value), Fixture() as f:
                    setattr(f, phase, lambda: f.fields.update({key: value}))
                    self.assertEqual(3097, adb.bridge_port())
                    self.assertEqual(1, f.closes)
                    self.assertEqual(int(phase == 'on_read'), f.reads)
        with Fixture() as f:
            f.on_read = lambda: setattr(f, 'missing', True)
            self.assertEqual(3097, adb.bridge_port())
            self.assertEqual(1, f.closes)

    def test_growth_short_read_and_io_failures_never_escape(self):
        with Fixture(b'309100') as f:
            f.fields['st_size'] = 4
            self.assertEqual(3097, adb.bridge_port())
            self.assertEqual(1, f.reads)
        with Fixture(b'309') as f:
            f.fields['st_size'] = 4
            self.assertEqual(3097, adb.bridge_port())
        for operation in ['lstat', 'open', 'fstat', 'read', 'close']:
            with self.subTest(operation=operation), Fixture(), mock.patch.object(adb.os, operation, side_effect=OSError('fixture')):
                self.assertEqual(3097, adb.bridge_port())

    def test_all_python_consumers_reread_and_keep_header_credentials(self):
        calls = []
        with Fixture() as f, mock.patch('builtins.open', mock.mock_open(read_data='fixture-token')):
            def request(req, timeout):
                calls.append(req)
                path = req.full_url.split('?')[0]
                value = 'VSCREEN_START_COMMITTED' if path.endswith('/commit') else json.dumps(
                    dict(version=1, kind='READ') if path.endswith('/plan') else
                    dict(state='adb', plan=dict(kind='VIRTUAL_SCREEN', nativeTicket='fixture-ticket', nativeAuthorization='managed-vscreen-start')))
                return io.BytesIO(json.dumps({'result': value}).encode())
            with mock.patch.object(urllib.request, 'build_opener', return_value=SimpleNamespace(open=request)):
                for port in [3091, 3092]:
                    f.raw = str(port).encode()
                    adb.request_device_plan('id')
                    adb.request_native_vscreen_start('fixture command', 'fixture-ticket')
                    adb.request_native_vscreen_commit('fixture-ticket')
                    for req in calls[-3:]:
                        self.assertTrue(req.full_url.startswith('http://127.0.0.1:' + str(port) + '/'))
                        self.assertEqual('fixture-token', req.get_header('X-token'))
                        self.assertNotIn('fixture-token', req.full_url)
                self.assertEqual(6, len(calls))

    def test_401_legacy_and_unknown_do_not_retry_or_produce_a_plan(self):
        for mode in ['401', 'legacy', 'unknown']:
            for operation in ['plan', 'start', 'commit']:
                with self.subTest(mode=mode, operation=operation), Fixture(), mock.patch('builtins.open', mock.mock_open(read_data='fixture-token')):
                    send = mock.Mock()
                    if mode == '401': send.side_effect = urllib.error.HTTPError('http://fixture', 401, 'fixture', {}, None)
                    elif mode == 'unknown': send.side_effect = TimeoutError('fixture')
                    else: send.side_effect = lambda *a, **k: io.BytesIO(b'{"result":"[UNAUTHORIZED]"}')
                    with mock.patch.object(urllib.request, 'build_opener', return_value=SimpleNamespace(open=send)):
                        expected = adb.ExecutionUnknown if operation == 'commit' and mode != 'legacy' else adb.policy.Blocked
                        with self.assertRaises(expected):
                            if operation == 'plan': adb.request_device_plan('id')
                            elif operation == 'start': adb.request_native_vscreen_start('fixture', 'fixture-ticket')
                            else: adb.request_native_vscreen_commit('fixture-ticket')
                        self.assertEqual(1, send.call_count)


if __name__ == '__main__':
    unittest.main()
