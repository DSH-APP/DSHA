#!/usr/bin/env python3
"""只测试私有命令助手；所有 adb 调用均由 fake 接管。"""
import importlib.util
import contextlib
import io
import json
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch, Mock

spec = importlib.util.spec_from_file_location('audit_control', Path(__file__).with_name('control-adb-flow-audit.py'))
control = importlib.util.module_from_spec(spec)
spec.loader.exec_module(control)


class AuditControlTest(unittest.TestCase):
    def setUp(self):
        self.block = patch('subprocess.run', side_effect=AssertionError('真实 adb 被禁止'))
        self.block.start()
        self.addCleanup(self.block.stop)

    def test_pair_code_only_enters_stdin_not_commandline(self):
        data = control.request('pair', port='39001', connect_port='39002', code='102938')
        with patch('subprocess.run', return_value=Mock(returncode=0)) as call:
            control.send(['fake-adb', '-s', 'fake-usb'], data)
        args, kwargs = call.call_args
        self.assertNotIn('102938', repr(args))
        self.assertEqual('102938', json.loads(kwargs['input'])['code'])
        self.assertIn('umask 077', args[0][-1])
        self.assertIn('command-' + data['id'] + '.json', args[0][-1])

    def test_invalid_code_and_ports_fail_before_adb(self):
        for code in ('12345', '1234567', '１２３４５６', 'abcdef'):
            with self.assertRaises(ValueError): control.request('pair', code=code)
        for port in ('0', '65536', '-1', '1;id'):
            with self.assertRaises(ValueError): control.request('verify', connect_port=port)

    def test_printed_success_and_errors_hide_code(self):
        for value in ({'output': 'echo 102938 in an unexpected error'}, {'error': 'code=102938'}, {'outcome': 'PASS'}):
            self.assertNotIn('102938', control.display(value, '102938'))
        self.assertNotIn('345678', control.display({'error': 'another six digit 345678'}))

    def test_missing_listener_never_sends(self):
        with patch.object(control, 'read_report', return_value={'listening': False}), patch.object(control, 'send') as send:
            with self.assertRaises(RuntimeError): control.run(['fake'], control.request('prepare'))
        send.assert_not_called()

    def test_accepted_is_not_reported_as_completed(self):
        data = control.request('pair', code='102938')
        responses = [{'listening': True}, {'id': data['id'], 'ok': True, 'accepted': True},
                     {'job_id': data['id'], 'outcome': 'PAIRED_UNVERIFIED', 'busy': False}]
        with patch.object(control, 'read_report', side_effect=responses), patch.object(control, 'send') as send:
            self.assertEqual('PAIRED_UNVERIFIED', control.run(['fake'], data)['outcome'])
        send.assert_called_once()

    def test_no_wait_allows_followup_rotate_without_replay(self):
        data = control.request('verify', ui=True)
        with patch.object(control, 'read_report', side_effect=[{'listening': True}, {'ok': True, 'accepted': True}]), \
             patch.object(control, 'send') as send:
            self.assertTrue(control.run(['fake'], data, wait=False)['accepted'])
        send.assert_called_once()

    def test_missing_result_times_out_without_resending_pair(self):
        data = control.request('pair', code='102938')
        with patch.object(control, 'read_report', return_value={'listening': True}), \
             patch.object(control, 'send') as send, patch.object(control.time, 'monotonic', side_effect=[0, 99]):
            with self.assertRaises(RuntimeError): control.run(['fake'], data, timeout=1)
        send.assert_called_once()

    def test_sender_error_does_not_echo_adb_stderr_or_input(self):
        data = control.request('pair', code='102938')
        with patch('subprocess.run', return_value=Mock(returncode=1, stderr=b'102938')):
            with self.assertRaises(RuntimeError) as error: control.send(['fake'], data)
        self.assertNotIn('102938', str(error.exception))

    def test_report_path_cannot_read_other_private_files(self):
        for path in ('../preferences.json', 'command-102938.json', '.bridge_token'):
            with self.assertRaises(ValueError): control.read_report(['fake'], path)

    def test_accidental_code_argument_is_rejected_without_echo(self):
        with patch.object(control.sys, 'argv', ['control', 'pair', '--code', '102938']), \
             contextlib.redirect_stderr(io.StringIO()) as err:
            with self.assertRaises(SystemExit): control.main()
        self.assertNotIn('102938', err.getvalue())


if __name__ == '__main__':
    unittest.main(verbosity=2)
