#!/usr/bin/env python3
"""Host-only fixtures for the managed ADB virtual-screen launch handshake."""
import builtins
import importlib.util
import io
import json
from pathlib import Path
import re
import unittest
from unittest import mock

ASSET = Path(__file__).resolve().parents[1] / 'app/src/main/assets/adb-shell.py'
SPEC = importlib.util.spec_from_file_location('dsha_adb_shell', ASSET)
adb = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(adb)

TICKET = '0123456789abcdef0123456789abcdef0123456789abcdef'
SOURCE = '/data/app/com.dsh.client/base.apk'
COMMAND = ('app_process -Djava.class.path=' + SOURCE + ' /system/bin '
           'com.deepseekharness.app.vscreen.VirtualScreenCore --launch --port 8800 --package com.dsh.clienu')


def plan(ticket=TICKET):
    return dict(version=1, kind='VIRTUAL_SCREEN',
                argv=['app_process', '-Djava.class.path=' + SOURCE, '/system/bin',
                      'com.deepseekharness.app.vscreen.VirtualScreenCore', '--launch', '--port', '8800', '--package', 'com.dsh.clienu'],
                sourceApk=SOURCE, selfPackage="com.dsh.clienu", nativeAuthorization='managed-vscreen-start',
                nativeTicket=ticket, su=False)


class Response:
    def __init__(self, body): self.body = body
    def __enter__(self): return self
    def __exit__(self, *args): return False
    def read(self, limit=-1): return self.body[:limit] if limit >= 0 else self.body


class FakeDevice:
    def __init__(self, events): self.events = events
    def connect(self, **kwargs): return True
    def shell(self, command, **kwargs):
        self.events.append(('send', command))
        marker = re.search(r'(__DSHA_EXIT_[a-f0-9]+__=)', command).group(1)
        return ('\n' + marker + '0\n').encode()
    def close(self): pass


class ADBVirtualScreenBridgeTest(unittest.TestCase):
    def test_ticket_mode_rejects_root_or_caller_selected_endpoint(self):
        parsed = adb.parse_args(['--vscreen-launch', '--timeout', '20', '--connect-timeout', '20', '--', COMMAND])
        self.assertTrue(parsed[5])
        self.assertEqual(COMMAND, parsed[6])
        self.assertNotIn('--token', parsed[6])
        self.assertEqual(TICKET, adb.read_vscreen_ticket(io.BytesIO(TICKET.encode('ascii'))))
        for invalid in (TICKET[:-1], TICKET + '0'):
            with self.subTest(invalid_length=len(invalid)), self.assertRaises(ValueError):
                adb.read_vscreen_ticket(io.BytesIO(invalid.encode('ascii')))
        for args in (['--vscreen-launch', '--su', '--', COMMAND],
                     ['--vscreen-launch', '--port', '5555', '--', COMMAND],
                     ['--vscreen-launch', '--vscreen-launch', '--', COMMAND]):
            with self.subTest(args=args), self.assertRaises(ValueError): adb.parse_args(args)

    def test_start_plan_is_returned_only_for_matching_ticket_and_exact_core_argv(self):
        body = json.dumps({'result': json.dumps({'state': 'adb', 'plan': plan()})}).encode()
        requests = []

        class Opener:
            def open(self, request, timeout):
                requests.append(request)
                return Response(body)

        original_open = builtins.open
        def open_fixture(path, *args, **kwargs):
            if path == '/root/.dsh/.bridge_token': return io.BytesIO(b'bridge-secret')
            return original_open(path, *args, **kwargs)
        with mock.patch('builtins.open', side_effect=open_fixture), \
             mock.patch('urllib.request.build_opener', return_value=Opener()):
            result = adb.request_native_vscreen_start(COMMAND, TICKET)
        self.assertEqual(plan(), result)
        self.assertIn('/device/vscreen/start?', requests[0].full_url)
        self.assertEqual(b'bridge-secret', requests[0].get_header('X-token'))
        self.assertNotIn('--token', result['argv'])
        self.assertNotIn('DSHA_VSCREEN_STARTED', json.dumps(result))

        wrong_ticket = json.dumps({'result': json.dumps({'state': 'adb', 'plan': plan('f' * 48)})}).encode()
        with mock.patch('builtins.open', side_effect=open_fixture), \
             mock.patch('urllib.request.build_opener', return_value=type('Opener', (), {'open': lambda self, *a, **k: Response(wrong_ticket)})()):
            with self.assertRaises(adb.policy.Blocked): adb.request_native_vscreen_start(COMMAND, TICKET)

    def test_ticket_is_consumed_before_one_remote_send_and_unknown_never_sends(self):
        events = []
        devices = []
        def create_device(*args, **kwargs):
            device = FakeDevice(events); devices.append(device); return device
        original_open = builtins.open
        def open_fixture(path, *args, **kwargs):
            if path == adb.KEY: return io.BytesIO(b'private')
            if path == adb.KEYPUB: return io.BytesIO(b'public')
            return original_open(path, *args, **kwargs)
        with mock.patch('builtins.open', side_effect=open_fixture), \
             mock.patch.object(adb, 'request_native_vscreen_commit', side_effect=lambda ticket: events.append(('commit', ticket))):
            result = adb.run_on_endpoint(create_device, lambda public, private: object(), plan(),
                                         '127.0.0.1', 5555, adb.time.monotonic() + 10, 5)
        self.assertEqual(0, result.exit_code)
        self.assertEqual(['commit', 'send'], [event[0] for event in events])
        self.assertEqual(1, len(devices))

        events.clear()
        with mock.patch('builtins.open', side_effect=open_fixture), \
             mock.patch.object(adb, 'request_native_vscreen_commit', side_effect=adb.ExecutionUnknown('lost commit')):
            with self.assertRaises(adb.ExecutionUnknown):
                adb.run_on_endpoint(create_device, lambda public, private: object(), plan(),
                                    '127.0.0.1', 5555, adb.time.monotonic() + 10, 5)
        self.assertEqual([], events)


if __name__ == '__main__': unittest.main(verbosity=2)
