"""A single host run's full byte proof, bounded metadata checks, and mandatory final byte recheck.

The receipt is scoped to the live runner parent and an independently conveyed random secret.
Standalone selectors without such a receipt continue to hash the complete tree.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import stat
import sys
import tempfile
import uuid
from runtime_fixture_proof import fingerprint, io_path, logical_path, validate

ROOT = Path(__file__).resolve().parents[1]
CONSUMER_FILES = ('tools/run-host-tests.py', 'tools/test-runtime-fixture.mjs', 'tools/test_runtime_fixture.py',
                  'tools/runtime_fixture_proof.py', 'tools/runtime_fixture_session.py')
SESSION_SCHEMA = 2
FINGERPRINT_POLICY = 'native-source-runtime-full-ids-dir-size-zero'


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def metadata(directory):
    selected = Path(directory).absolute()
    if selected.is_symlink() or getattr(selected, 'is_junction', lambda: False)():
        raise ValueError('TEST_FIXTURE_SESSION_ROOT_LINK')
    root = io_path(selected).resolve(strict=True)
    base = root.stat()
    if not stat.S_ISDIR(base.st_mode):
        raise ValueError('TEST_FIXTURE_SESSION_ROOT_TYPE')
    entries = {}
    for folder, directories, files in os.walk(root, topdown=True, followlinks=False):
        for name in sorted(directories + files):
            path = Path(folder) / name
            relative = path.relative_to(root).as_posix()
            if relative == 'dsha-test-runtime.json':
                continue
            node = path.lstat()
            link = stat.S_ISLNK(node.st_mode) or getattr(path, 'is_junction', lambda: False)()
            kind = 'LINK' if link else 'DIRECTORY' if stat.S_ISDIR(node.st_mode) else 'FILE' if stat.S_ISREG(node.st_mode) else 'SPECIAL'
            if link and name in directories:
                directories.remove(name)
            target = os.readlink(path) if link else None
            entries[relative] = fingerprint(node, kind, target)
            if len(entries) > 200000:
                raise ValueError('TEST_FIXTURE_SESSION_ENTRY_LIMIT')
    return {'root': {'dev': str(base.st_dev), 'ino': str(base.st_ino)}, 'entries': entries}


def verify_record(directory, proof, record):
    selected = Path(directory).resolve()
    if str(selected) != record.get('directory'):
        raise ValueError('TEST_FIXTURE_SESSION_DIRECTORY')
    marker = selected / 'dsha-test-runtime.json'
    if marker.is_symlink() or not marker.is_file() or sha(marker) != record.get('markerSha256'):
        raise ValueError('TEST_FIXTURE_SESSION_MARKER_CHANGED')
    if proof.get('kind') != record.get('kind') or proof.get('contentProof', {}).get('digest') != record.get('contentDigest'):
        raise ValueError('TEST_FIXTURE_SESSION_CONTENT_BINDING')
    if metadata(selected) != record.get('metadata'):
        raise ValueError('TEST_FIXTURE_SESSION_METADATA_CHANGED')


def read_receipt(receipt, secret):
    path = Path(receipt).absolute()
    if path.is_symlink() or not path.is_file() or path.resolve() != path:
        raise ValueError('TEST_FIXTURE_SESSION_RECEIPT_PATH')
    data = json.loads(path.read_text(encoding='utf-8'))
    session_id = data.get('sessionId', '')
    if data.get('schema') != SESSION_SCHEMA or data.get('fingerprintPolicy') != FINGERPRINT_POLICY \
            or not re.fullmatch('[a-f0-9]{32}', session_id) or path.name != 'proof.json' \
            or not path.parent.name.startswith('dsha-runtime-session-' + session_id + '-'):
        raise ValueError('TEST_FIXTURE_SESSION_RECEIPT_SCOPE')
    if not re.fullmatch('[a-f0-9]{64}', secret) or hashlib.sha256(secret.encode()).hexdigest() != data.get('secretSha256'):
        raise ValueError('TEST_FIXTURE_SESSION_EXPIRED_OR_FOREIGN')
    if set(data.get('consumerInputs', {})) != set(CONSUMER_FILES):
        raise ValueError('TEST_FIXTURE_SESSION_CONSUMER_SCOPE')
    for file, digest in data['consumerInputs'].items():
        if sha(ROOT / file) != digest:
            raise ValueError('TEST_FIXTURE_SESSION_CONSUMER_CHANGED')
    runtime = data.get('metadataRuntime', {})
    if os.path.normcase(str(Path(sys.executable).resolve())) != os.path.normcase(runtime.get('python', '')) \
            or sha(sys.executable) != runtime.get('pythonSha256') \
            or sys.version.split()[0] != runtime.get('pythonVersion'):
        raise ValueError('TEST_FIXTURE_SESSION_METADATA_RUNTIME_CHANGED')
    return data


def consume(directory, proof):
    receipt, secret = os.environ.get('DSHA_TEST_RUN_RECEIPT'), os.environ.get('DSHA_TEST_RUN_SECRET')
    if not receipt and not secret:
        return False
    if not receipt or not secret:
        raise ValueError('TEST_FIXTURE_SESSION_CREDENTIAL_MISSING')
    data = read_receipt(receipt, secret)
    if data.get('ownerPid') != os.getppid():
        raise ValueError('TEST_FIXTURE_SESSION_EXPIRED_OR_FOREIGN')
    record = data.get('fixtures', {}).get(str(Path(directory).resolve()))
    if record is None:
        return False
    verify_record(directory, proof, record)
    return True


def windows_process(pid):
    """Read the live process's parent and image; no process signal or UI operation."""
    import ctypes
    from ctypes import wintypes
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    class Entry(ctypes.Structure):
        _fields_ = [('dwSize', wintypes.DWORD), ('cntUsage', wintypes.DWORD),
                    ('th32ProcessID', wintypes.DWORD), ('th32DefaultHeapID', ctypes.c_size_t),
                    ('th32ModuleID', wintypes.DWORD), ('cntThreads', wintypes.DWORD),
                    ('th32ParentProcessID', wintypes.DWORD), ('pcPriClassBase', wintypes.LONG),
                    ('dwFlags', wintypes.DWORD), ('szExeFile', wintypes.WCHAR * 260)]
    kernel.CreateToolhelp32Snapshot.argtypes = [wintypes.DWORD, wintypes.DWORD]
    kernel.CreateToolhelp32Snapshot.restype = wintypes.HANDLE
    kernel.Process32FirstW.argtypes = [wintypes.HANDLE, ctypes.POINTER(Entry)]
    kernel.Process32NextW.argtypes = [wintypes.HANDLE, ctypes.POINTER(Entry)]
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    snapshot = kernel.CreateToolhelp32Snapshot(2, 0)
    if snapshot == ctypes.c_void_p(-1).value:
        raise ValueError('TEST_FIXTURE_SESSION_PARENT_UNREADABLE')
    parent = None
    try:
        entry = Entry(); entry.dwSize = ctypes.sizeof(entry)
        found = kernel.Process32FirstW(snapshot, ctypes.byref(entry))
        while found:
            if entry.th32ProcessID == pid:
                parent = int(entry.th32ParentProcessID); break
            found = kernel.Process32NextW(snapshot, ctypes.byref(entry))
    finally:
        kernel.CloseHandle(snapshot)
    if parent is None:
        raise ValueError('TEST_FIXTURE_SESSION_PARENT_UNREADABLE')
    kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel.OpenProcess.restype = wintypes.HANDLE
    kernel.QueryFullProcessImageNameW.argtypes = [wintypes.HANDLE, wintypes.DWORD,
                                                wintypes.LPWSTR, ctypes.POINTER(wintypes.DWORD)]
    process = kernel.OpenProcess(0x1000, False, pid)
    if not process:
        raise ValueError('TEST_FIXTURE_SESSION_PARENT_UNREADABLE')
    try:
        image = ctypes.create_unicode_buffer(32768); length = wintypes.DWORD(len(image))
        if not kernel.QueryFullProcessImageNameW(process, 0, image, ctypes.byref(length)):
            raise ValueError('TEST_FIXTURE_SESSION_PARENT_UNREADABLE')
        return parent, str(Path(image.value).resolve())
    finally:
        kernel.CloseHandle(process)


def verify_node(receipt, directory):
    """Windows Node/libuv's 32-bit device and 64-bit inode cannot stand in for full stat IDs.

    This worker uses the exact creator interpreter's full device/inode/ns representation.
    It requires the live Node -> owner parent chain as well as the private receipt secret.
    """
    if os.name != 'nt':
        raise ValueError('TEST_FIXTURE_SESSION_WINDOWS_WORKER_REQUIRED')
    if os.environ.get('DSHA_TEST_RUN_RECEIPT') != receipt:
        raise ValueError('TEST_FIXTURE_SESSION_RECEIPT_PATH')
    secret = os.environ.get('DSHA_TEST_RUN_SECRET', '')
    data = read_receipt(receipt, secret)
    owner, image = windows_process(os.getppid())
    if owner != data.get('ownerPid') \
            or os.path.normcase(image) != os.path.normcase(data['metadataRuntime'].get('node', '')) \
            or sha(image) != data['metadataRuntime'].get('nodeSha256'):
        raise ValueError('TEST_FIXTURE_SESSION_EXPIRED_OR_FOREIGN')
    selected = Path(directory).resolve()
    record = data.get('fixtures', {}).get(str(selected))
    if record is None:
        raise ValueError('TEST_FIXTURE_SESSION_DIRECTORY')
    marker = selected / 'dsha-test-runtime.json'
    proof = json.loads(marker.read_text(encoding='utf-8'))
    verify_record(selected, proof, record)


class FixtureSession:
    def __init__(self, output, node_executable=None):
        self.session_id = uuid.uuid4().hex
        self.secret = secrets.token_hex(32)
        self.temporary = tempfile.TemporaryDirectory(prefix='dsha-runtime-session-' + self.session_id + '-', dir=output)
        self.path = Path(self.temporary.name).resolve() / 'proof.json'
        self.fixtures = {}
        self.proofs = {}
        self.consumer_inputs = {file: sha(ROOT / file) for file in CONSUMER_FILES}
        node = node_executable or os.environ.get('DSHA_TEST_NODE') or shutil.which('node')
        if not node:
            raise ValueError('TEST_FIXTURE_SESSION_NODE_EXECUTABLE_REQUIRED')
        node = str(Path(shutil.which(str(node)) or node).resolve())
        self.metadata_runtime = {'python': str(Path(sys.executable).resolve()),
                                 'pythonSha256': sha(sys.executable),
                                 'pythonVersion': sys.version.split()[0],
                                 'node': node, 'nodeSha256': sha(node)}

    def register(self, directory, proof):
        selected = str(Path(directory).resolve())
        if selected in self.fixtures:
            verify_record(directory, proof, self.fixtures[selected])
            return
        captured = {}
        validate(directory, proof, metadata_out=captured)
        record = {'directory': selected, 'kind': proof['kind'],
                  'markerSha256': sha(Path(directory) / 'dsha-test-runtime.json'),
                  'contentDigest': proof['contentProof']['digest'], 'metadata': captured}
        verify_record(directory, proof, record)
        self.fixtures[selected], self.proofs[selected] = record, proof
        data = {'schema': SESSION_SCHEMA, 'fingerprintPolicy': FINGERPRINT_POLICY,
                'metadataRuntime': self.metadata_runtime,
                'sessionId': self.session_id, 'ownerPid': os.getpid(),
                'secretSha256': hashlib.sha256(self.secret.encode()).hexdigest(),
                'consumerInputs': self.consumer_inputs, 'fixtures': self.fixtures}
        temporary = self.path.with_suffix('.tmp')
        temporary.write_text(json.dumps(data, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
        os.replace(temporary, self.path)

    def environment(self):
        return {'DSHA_TEST_RUN_RECEIPT': str(self.path), 'DSHA_TEST_RUN_SECRET': self.secret,
                'DSHA_PYTHON': self.metadata_runtime['python']}

    def bindings(self):
        return [{'kind': record['kind'], 'directory': record['directory'],
                 'markerSha256': record['markerSha256'], 'contentDigest': record['contentDigest']}
                for record in self.fixtures.values()]

    def finalize(self):
        results = []
        try:
            for file, digest in self.consumer_inputs.items():
                if sha(ROOT / file) != digest:
                    raise ValueError('TEST_FIXTURE_SESSION_CONSUMER_CHANGED:' + file)
            for directory, record in self.fixtures.items():
                result = {key: record[key] for key in ('kind', 'directory', 'markerSha256', 'contentDigest')}
                try:
                    verify_record(directory, self.proofs[directory], record)
                    validate(directory, self.proofs[directory])
                    result['status'] = 'PASS_FINAL_COMPLETE_BYTE_RECHECK'
                except Exception as error:
                    result.update(status='FAILED', error=str(error))
                results.append(result)
        except Exception as error:
            results.append({'status': 'FAILED', 'error': str(error)})
        finally:
            self.temporary.cleanup()
        return results


if __name__ == '__main__':
    try:
        if sys.flags.optimize or len(sys.argv) != 4 or sys.argv[1] != '--verify-node-receipt':
            raise ValueError('TEST_FIXTURE_SESSION_WORKER_ARGUMENTS')
        verify_node(sys.argv[2], sys.argv[3])
        print('PASS_WINDOWS_FULL_PRECISION_METADATA')
    except Exception as error:
        # Never dump environment, receipt, credential or file contents.
        print(type(error).__name__ + ':' + str(error), file=sys.stderr)
        raise SystemExit(1)
