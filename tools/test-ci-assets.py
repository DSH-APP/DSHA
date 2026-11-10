#!/usr/bin/env python3
"""Exercise fixed source input pins and real ZIP pack/restore in private fixtures."""
import contextlib
from email.message import Message
import errno
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch
import urllib.response
import warnings
import zipfile

SPEC = importlib.util.spec_from_file_location('ci_assets_under_test', Path(__file__).with_name('ci-assets.py'))
assets = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(assets)


def sha(data):
    return hashlib.sha256(data).hexdigest()


class CiAssetsTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='dsha-ci-assets-test-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.assets = self.root / 'app/src/main/assets'
        self.assets.mkdir(parents=True)
        self.recovery = self.root / 'tools/recovery-runtime'
        (self.recovery / 'archives').mkdir(parents=True)
        self.root_patch = patch.object(assets, 'ROOT', self.root)
        self.root_patch.start()
        self.addCleanup(self.root_patch.stop)
        self.blobs = {name: ('original source:' + name).encode() for name in assets.NAMES}
        for name, data in self.blobs.items():
            (self.assets / name).write_bytes(data)
        self.descriptor = {'inputs': {name: sha(data) for name, data in self.blobs.items()
                                     if not name.endswith('.inputs.json')
                                     and name not in ('python-support.bin', 'adb-wheels.tar.gz')}}
        self.input_lock = {'schema': 1, 'archives': [
            {'asset': 'python-support.bin', 'sha256': sha(self.blobs['python-support.bin']),
             'bytes': len(self.blobs['python-support.bin'])}]}
        self.adb_lock = {'schema': 1, 'archiveSha256': sha(self.blobs['adb-wheels.tar.gz'])}
        self.recovery_blobs = {'recovery-rootfs.bin': b'pinned recovery rootfs',
                               'recovery-dsh-runtime.bin': b'pinned recovery dsh'}
        self.recovery_lock = {'schema': 1, 'archives': [
            {'asset': name, 'sha256': sha(data)} for name, data in self.recovery_blobs.items()]}
        for name, data in self.recovery_blobs.items():
            (self.recovery / 'archives' / name).write_bytes(data)
        self.save_locks()

    def save_locks(self):
        (self.assets / 'runtime-descriptor.json').write_text(json.dumps(self.descriptor), encoding='utf-8')
        (self.root / 'tools/ci-build-inputs.lock.json').write_text(json.dumps(self.input_lock), encoding='utf-8')
        (self.assets / 'adb-wheels.lock.json').write_text(json.dumps(self.adb_lock), encoding='utf-8')
        (self.recovery / 'lock.json').write_text(json.dumps(self.recovery_lock), encoding='utf-8')

    def pack(self, target=None):
        target = target or self.root / 'verified-inputs.zip'
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            assets.main(['--pack', str(target)])
        return target, json.loads(output.getvalue())

    def zip_inputs(self, prefix='', extra=(), changes=None, omitted=()):
        entries = {name: (self.root / name).read_bytes() for name in assets.allowed()}
        entries.update(changes or {})
        package = self.root / 'source-fixture.zip'
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            with zipfile.ZipFile(package, 'w') as archive:
                for name, data in sorted(entries.items()):
                    if name not in omitted:
                        archive.writestr(prefix + name, data)
                for name, data in extra:
                    archive.writestr(name, data)
        return package

    def restore(self, package, source=False, sha256=None):
        argv = ['--archive', str(package), '--sha256', sha256 or assets.digest(package)]
        if source:
            argv.append('--source-archive')
        with contextlib.redirect_stdout(io.StringIO()):
            assets.main(argv)

    def zip_with_raw_extra_name(self, name):
        # ZipInfo normalizes the host separator on Windows before writestr().
        # Change only the equal-length local/central name bytes so this remains
        # a real unsafe ZIP fixture on every host, with unchanged payload CRCs.
        encoded = name.encode('ascii')
        placeholder = 'x' * len(encoded)
        package = self.zip_inputs(extra=[(placeholder, b'unsafe')])
        original = package.read_bytes()
        marker = placeholder.encode('ascii')
        self.assertEqual(original.count(marker), 2, 'one local and one central filename')
        package.write_bytes(original.replace(marker, encoded))
        with zipfile.ZipFile(package) as archive:
            self.assertIn(name, [entry.orig_filename for entry in archive.infolist()])
        return package

    @contextlib.contextmanager
    def transport(self, data, redirects=None, token='fixture-token'):
        requests = []
        redirects = redirects or {}

        def respond(request):
            requests.append((request.full_url, {k.lower(): v for k, v in request.header_items()}))
            headers = Message()
            code = 200
            body = data
            if request.full_url in redirects:
                headers['Location'] = redirects[request.full_url]
                code, body = 302, b''
            response = urllib.response.addinfourl(io.BytesIO(body), headers, request.full_url, code)
            response.msg = 'Found' if code == 302 else 'OK'
            return response

        # 替换环境对象，不读取真实凭据；仅 HTTP 传输层为假，重定向处理真实运行。
        env = {'GITHUB_TOKEN': token} if token is not None else {}
        with patch.object(assets.os, 'environ', env), \
                patch.object(assets.urllib.request.HTTPSHandler, 'https_open', side_effect=respond), \
                patch.object(assets.urllib.request.HTTPHandler, 'http_open', side_effect=AssertionError('HTTP forbidden')):
            yield requests

    def test_exact_inputs_verify_and_normal_pack_keeps_original_bytes(self):
        assets.verify_sources()
        package, receipt = self.pack()
        self.assertEqual(receipt['sha256'], sha(package.read_bytes()))
        self.assertEqual(receipt['bytes'], package.stat().st_size)
        with zipfile.ZipFile(package) as archive:
            self.assertEqual(set(archive.namelist()), assets.allowed())
            self.assertEqual(len(archive.namelist()), len(assets.allowed()))
            for name, data in self.blobs.items():
                self.assertEqual(archive.read('app/src/main/assets/' + name), data)
            for name, data in self.recovery_blobs.items():
                self.assertEqual(archive.read('tools/recovery-runtime/archives/' + name), data)
        self.assertNotIn('tools/ci-build-inputs.lock.json', assets.allowed(), 'source lock is already in the source checkout')

    def test_bad_python_digest_and_size_are_rejected(self):
        original = self.blobs['python-support.bin']
        (self.assets / 'python-support.bin').write_bytes(b'X' + original[1:])
        with self.assertRaisesRegex(ValueError, 'CI_ASSET_SOURCE_MISMATCH: python-support.bin'):
            assets.verify_sources()
        (self.assets / 'python-support.bin').write_bytes(original)
        self.input_lock['archives'][0]['bytes'] += 1
        self.save_locks()
        with self.assertRaisesRegex(ValueError, 'CI_ASSET_SOURCE_MISMATCH: python-support.bin'):
            assets.verify_sources()

    def test_retired_pc_binary_is_not_packed_or_restored_from_historical_source(self):
        retired = 'app/libs/dsha-connect.aar'
        self.assertNotIn(retired, assets.allowed())
        self.assertFalse((self.root / 'tools/remote').exists())
        assets.verify_sources()
        package = self.zip_inputs(extra=[(retired, b'historical PC binary')])
        with self.assertRaisesRegex(ValueError, 'CI_ASSET_MEMBER_SET'):
            self.restore(package)
        opened = []
        original_open = zipfile.ZipFile.open

        def track_open(archive, name, *args, **kwargs):
            opened.append(name.filename if isinstance(name, zipfile.ZipInfo) else name)
            return original_open(archive, name, *args, **kwargs)

        with patch.object(zipfile.ZipFile, 'open', track_open):
            self.restore(package, source=True)
        self.assertNotIn(retired, opened)
        self.assertFalse((self.root / retired).exists())
        packed, _ = self.pack()
        with zipfile.ZipFile(packed) as archive:
            self.assertNotIn(retired, archive.namelist())

    def test_bad_adb_cannot_use_modified_descriptor_as_fallback(self):
        changed = b'changed wheels'
        (self.assets / 'adb-wheels.tar.gz').write_bytes(changed)
        self.descriptor['inputs']['adb-wheels.tar.gz'] = sha(changed)
        self.save_locks()
        with self.assertRaisesRegex(ValueError, 'CI_ASSET_PIN_CONFLICT: adb-wheels.tar.gz'):
            assets.verify_sources()
        self.descriptor['inputs'].pop('adb-wheels.tar.gz')
        self.save_locks()
        with self.assertRaisesRegex(ValueError, 'CI_ASSET_SOURCE_MISMATCH: adb-wheels.tar.gz'):
            assets.verify_sources()

    def test_missing_separate_locks_do_not_trust_local_bytes(self):
        for relative, error in [('tools/ci-build-inputs.lock.json', 'CI_BUILD_INPUT_LOCK_MISSING'),
                                ('app/src/main/assets/adb-wheels.lock.json', 'CI_ADB_WHEELS_LOCK_MISSING')]:
            with self.subTest(relative=relative):
                path = self.root / relative
                original = path.read_bytes()
                path.unlink()
                with self.assertRaisesRegex(ValueError, error):
                    assets.verify_sources()
                path.write_bytes(original)

    def test_invalid_pin_shapes_fail_without_using_local_digest(self):
        python_path = self.root / 'tools/ci-build-inputs.lock.json'
        adb_path = self.assets / 'adb-wheels.lock.json'
        for invalid in ([], {'schema': 1, 'archives': [{'asset': 'python-support.bin', 'sha256': 123, 'bytes': 1}]}):
            with self.subTest(python=invalid):
                python_path.write_text(json.dumps(invalid), encoding='utf-8')
                with self.assertRaisesRegex(ValueError, 'CI_BUILD_INPUT_LOCK_INVALID'):
                    assets.verify_sources()
        self.save_locks()
        for invalid in ([], {'schema': 1, 'archiveSha256': 'not a fixed sha256'}):
            with self.subTest(adb=invalid):
                adb_path.write_text(json.dumps(invalid), encoding='utf-8')
                with self.assertRaisesRegex(ValueError, 'CI_ADB_WHEELS_LOCK_INVALID'):
                    assets.verify_sources()

    def test_unknown_archive_cannot_borrow_separate_pin(self):
        self.input_lock['archives'].append({'asset': 'unknown.bin', 'sha256': sha(b'unknown'), 'bytes': 7})
        self.save_locks()
        with self.assertRaisesRegex(ValueError, 'CI_BUILD_INPUT_LOCK_INVALID'):
            assets.verify_sources()
        self.input_lock['archives'].pop()
        self.save_locks()
        (self.assets / 'unknown.bin').write_bytes(b'unknown')
        with patch.object(assets, 'NAMES', assets.NAMES + ['unknown.bin']):
            with self.assertRaisesRegex(ValueError, 'CI_ASSET_SOURCE_MISMATCH: unknown.bin'):
                assets.verify_sources()

    def test_missing_normal_descriptor_pin_has_no_fallback(self):
        self.descriptor['inputs'].pop('pnpm-runtime.bin')
        self.save_locks()
        with self.assertRaisesRegex(ValueError, 'CI_ASSET_SOURCE_MISMATCH: pnpm-runtime.bin'):
            assets.verify_sources()

    def test_recovery_still_requires_its_original_lock(self):
        (self.recovery / 'archives/recovery-rootfs.bin').write_bytes(b'other recovery')
        with self.assertRaisesRegex(ValueError, 'CI_RECOVERY_ARCHIVE_MISMATCH'):
            assets.verify_sources()

    def test_existing_pack_output_is_preserved(self):
        target = self.root / 'already-present.zip'
        target.write_bytes(b'original output remains')
        before = target.stat()
        with self.assertRaises(FileExistsError):
            self.pack(target)
        self.assertEqual(target.read_bytes(), b'original output remains')
        self.assertEqual(target.stat().st_mtime_ns, before.st_mtime_ns)

    def test_pack_then_restore_fixture_verifies_the_same_fixed_inputs(self):
        package, receipt = self.pack()
        binary_paths = [self.assets / name for name in self.blobs]
        binary_paths += [self.recovery / 'archives' / name for name in self.recovery_blobs]
        for path in binary_paths:
            path.unlink()
        output = io.StringIO()
        with self.transport(package.read_bytes()) as requests:
            with contextlib.redirect_stdout(output):
                assets.main(['--url', 'https://example.invalid/fixed-inputs.zip', '--sha256', receipt['sha256']])
        self.assertEqual(len(requests), 1)
        self.assertEqual(requests[0][0], 'https://example.invalid/fixed-inputs.zip')
        self.assertNotIn('authorization', requests[0][1])
        self.assertIn('PASS locked CI asset inputs', output.getvalue())
        assets.verify_sources()
        for name, data in self.blobs.items():
            self.assertEqual((self.assets / name).read_bytes(), data)

    def test_wrong_download_sha_never_publishes_inputs(self):
        package, _ = self.pack()
        target = self.assets / 'python-support.bin'
        target.unlink()
        with self.transport(package.read_bytes()):
            with self.assertRaisesRegex(ValueError, 'CI_ASSET_BUNDLE_SHA256'):
                assets.main(['--url', 'https://example.invalid/fixed-inputs.zip', '--sha256', '0' * 64])
        self.assertFalse(target.exists())

    def test_local_bundle_reuse_needs_no_download(self):
        package, _ = self.pack()
        before = package.read_bytes()
        (self.assets / 'python-support.bin').unlink()
        with patch.object(assets, 'download', side_effect=AssertionError('unexpected download')):
            self.restore(package)
        self.assertEqual(package.read_bytes(), before)
        assets.verify_sources()

    def test_full_source_extracts_only_fixed_members_with_or_without_prefix(self):
        for prefix in ('', 'source-release/'):
            with self.subTest(prefix=prefix):
                source = self.root / 'README.md'
                source.write_bytes(b'checkout source stays')
                package = self.zip_inputs(prefix, [(prefix + 'README.md', b'old archive source'),
                                                   (prefix + 'tools/ci-build-inputs.lock.json', b'untrusted lock')])
                originals = {name: (self.root / name).read_bytes() for name in assets.allowed()}
                for name in originals:
                    (self.root / name).unlink()
                opened = []
                original_open = zipfile.ZipFile.open

                def track_open(archive, name, *args, **kwargs):
                    opened.append(name.filename if isinstance(name, zipfile.ZipInfo) else name)
                    return original_open(archive, name, *args, **kwargs)

                with patch.object(zipfile.ZipFile, 'open', track_open):
                    self.restore(package, source=True)
                self.assertEqual(set(opened), {prefix + name for name in originals})
                self.assertEqual(len(opened), len(assets.allowed()))
                self.assertEqual(source.read_bytes(), b'checkout source stays')
                self.assertFalse((self.root / 'source-release').exists())
                for name, data in originals.items():
                    self.assertEqual((self.root / name).read_bytes(), data)

    def test_normal_bundle_rejects_extra_source_and_prefixed_members(self):
        for prefix, extra in [('', [('README.md', b'source')]), ('release/', [])]:
            with self.subTest(prefix=prefix):
                package = self.zip_inputs(prefix, extra)
                with self.assertRaisesRegex(ValueError, 'CI_ASSET_MEMBER_SET'):
                    self.restore(package)

    def test_local_sha_and_missing_pin_fail_without_publishing(self):
        package = self.zip_inputs()
        target = self.assets / 'adb-wheels.tar.gz'
        target.write_bytes(b'untouched local input')
        for expected in ('0' * 64, assets.digest(package)):
            with self.subTest(sha=expected):
                if expected != '0' * 64:
                    self.descriptor['inputs'].pop('pnpm-runtime.bin')
                    self.save_locks()
                with self.assertRaisesRegex(ValueError, 'CI_ASSET_BUNDLE_SHA256|CI_ASSET_SOURCE_MISMATCH'):
                    self.restore(package, source=True, sha256=expected)
                self.assertEqual(target.read_bytes(), b'untouched local input')

    def test_bad_candidate_never_publishes_any_input_or_archive_lock(self):
        for name in ('app/src/main/assets/python-support.bin',
                     'tools/recovery-runtime/archives/recovery-rootfs.bin'):
            with self.subTest(name=name):
                # 归档自己的锁不能替代 checkout 的 pin。
                package = self.zip_inputs(changes={name: b'bad candidate'}, extra=[
                    ('tools/recovery-runtime/lock.json', b'untrusted archive pin')])
                targets = {relative: self.root / relative for relative in assets.allowed()}
                originals = {relative: path.read_bytes() for relative, path in targets.items()}
                for path in targets.values():
                    path.write_bytes(b'existing input remains')
                with self.assertRaisesRegex(ValueError, 'CI_ASSET_SOURCE_MISMATCH|CI_RECOVERY_ARCHIVE_MISMATCH'):
                    self.restore(package, source=True)
                for relative, path in targets.items():
                    self.assertEqual(path.read_bytes(), b'existing input remains')
                    path.write_bytes(originals[relative])
                self.assertEqual(json.loads((self.recovery / 'lock.json').read_text()), self.recovery_lock)

    def test_source_missing_duplicate_and_multiple_prefixes_are_rejected(self):
        name = 'app/src/main/assets/adb-wheels.tar.gz'
        cases = [([], [name]), ([(name, b'duplicate')], []),
                 ([('other/' + name, b'ambiguous target')], []),
                 ([('other/' + name, self.blobs['adb-wheels.tar.gz'])], [name])]
        for extra, omitted in cases:
            with self.subTest(extra=extra, omitted=omitted):
                package = self.zip_inputs(extra=extra, omitted=omitted)
                with self.assertRaisesRegex(ValueError, 'CI_ASSET_MEMBER_SET'):
                    self.restore(package, source=True)
                assets.verify_sources()

    def test_archive_links_and_noncanonical_paths_are_rejected(self):
        link = zipfile.ZipInfo('app/src/main/assets/adb-wheels.tar.gz')
        link.create_system = 3
        link.external_attr = (stat.S_IFLNK | 0o777) << 16
        package = self.zip_inputs(extra=[(link, b'elsewhere')], omitted=[link.filename])
        with self.assertRaisesRegex(ValueError, 'CI_ASSET_MEMBER_TYPE'):
            self.restore(package, source=True)
        for name in ('../outside', '/absolute', 'source//README', 'source/./README', 'source/../README',
                     'source\\README', 'C:/README'):
            with self.subTest(name=name):
                package = self.zip_with_raw_extra_name(name)
                # Windows may normalize filename while preserving orig_filename;
                # that mismatch must fail before a normalized path is selected.
                error = 'CI_ASSET_PATH|CI_ASSET_MEMBER_SET' if '\\' in name else 'CI_ASSET_PATH'
                with self.assertRaisesRegex(ValueError, error):
                    self.restore(package, source=True)
        assets.verify_sources()

    def test_target_symlink_rejected_before_any_publish(self):
        package = self.zip_inputs()
        first = self.assets / 'adb-wheels.tar.gz'
        first.write_bytes(b'first untouched')
        last = self.recovery / 'archives/recovery-rootfs.bin'
        last.unlink()
        try:
            last.symlink_to(first)
        except NotImplementedError:
            self.skipTest('host has no symlink implementation; run this case on Linux')
        except OSError as error:
            if getattr(error, 'winerror', None) == 1314 or error.errno in (
                    errno.EPERM, errno.EACCES, errno.ENOSYS, errno.EOPNOTSUPP):
                self.skipTest('host cannot create symlinks; run this case on Linux: ' + type(error).__name__)
            raise
        with self.assertRaisesRegex(ValueError, 'CI_ASSET_LINK'):
            self.restore(package, source=True)
        self.assertEqual(first.read_bytes(), b'first untouched')
        self.assertTrue(last.is_symlink())

    def test_private_github_auth_is_dropped_across_real_redirect_handler(self):
        url = 'https://api.github.com/repos/owner/repo/releases/assets/663673858'
        next_url = 'https://objects.example.invalid/blob?signature=fake'
        final_url = 'https://objects.example.invalid/final'
        with self.transport(b'archive', {url: next_url, next_url: final_url}) as requests:
            assets.download(url, self.root / 'download.zip')
        self.assertEqual([request[0] for request in requests], [url, next_url, final_url])
        self.assertEqual(requests[0][1]['authorization'], 'Bearer fixture-token')
        self.assertEqual(requests[0][1]['accept'], 'application/octet-stream')
        for _, headers in requests[1:]:
            self.assertNotIn('authorization', headers)
        self.assertEqual((self.root / 'download.zip').read_bytes(), b'archive')

    def test_auth_scope_is_exact_and_public_api_needs_no_token(self):
        api = 'https://api.github.com/repos/owner/repo/releases/assets/663673858'
        urls = ['https://example.invalid/inputs.zip', api + '?download=1', api + '/',
                api + '#fragment', api.replace('api.github.com', 'api.github.com.evil.invalid'),
                api.replace('api.github.com', 'api.github.com:443'),
                api.replace('/repo/', '/../'), api.replace('/663673858', '/0'),
                api.replace('/releases/assets/663673858', '/contents/file.zip')]
        for url in urls:
            with self.subTest(url=url), self.transport(b'archive') as requests:
                assets.download(url, self.root / 'download.zip')
                self.assertNotIn('authorization', requests[0][1])
                self.assertNotIn('accept', requests[0][1])
        with self.transport(b'archive', token=None) as requests:
            assets.download(api, self.root / 'download.zip')
        self.assertNotIn('authorization', requests[0][1])
        self.assertEqual(requests[0][1]['accept'], 'application/octet-stream')

    def test_http_and_redirect_downgrade_are_rejected_before_transport(self):
        api = 'https://api.github.com/repos/owner/repo/releases/assets/663673858'
        for url in ('http://example.invalid/inputs.zip', 'file:///tmp/inputs.zip',
                    'https://user:password@example.invalid/inputs.zip'):
            with self.subTest(url=url), self.transport(b'archive') as requests:
                with self.assertRaisesRegex(ValueError, 'CI_ASSET_HTTPS_REQUIRED'):
                    assets.download(url, self.root / 'download.zip')
                self.assertEqual(requests, [])
        with self.transport(b'archive', {api: 'http://objects.example.invalid/inputs.zip'}) as requests:
            with self.assertRaisesRegex(ValueError, 'CI_ASSET_HTTPS_REQUIRED'):
                assets.download(api, self.root / 'download.zip')
        self.assertEqual(len(requests), 1)

    def test_redirect_strips_all_sensitive_headers_even_on_same_host(self):
        request = assets.urllib.request.Request('https://api.github.com/start', headers={
            'Authorization': 'fake', 'Proxy-Authorization': 'fake-proxy', 'Cookie': 'fake-cookie', 'Accept': '*/*'})
        next_request = assets.HttpsRedirect().redirect_request(
            request, None, 302, 'Found', Message(), 'https://api.github.com/next')
        self.assertEqual({k.lower(): v for k, v in next_request.header_items()}, {'accept': '*/*'})

    def test_restore_cli_requires_sha_and_excludes_pack_source_mode(self):
        for args in (['--archive', 'local.zip'], ['--pack', 'out.zip', '--source-archive']):
            with self.subTest(args=args), contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit):
                    assets.main(args)


if __name__ == '__main__':
    unittest.main()
