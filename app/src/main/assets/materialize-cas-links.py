#!/usr/bin/env python3
"""Startup-only CAS migration entry; same locks, transaction and V1 receipt.

Do not import the package/download manager for this local operation. The builtin
registry remains validated by the shared registration module before any write.
No receipt or root timestamp is used to skip the full mutable profile scan.
"""
import time

_ENTRY_STARTED = time.monotonic()

import importlib.util
import json
import os
import re
import sys


class PluginCancelled(Exception):
    pass


def load(name, filename):
    descriptor = importlib.util.spec_from_file_location(name, os.path.join(os.path.dirname(__file__), filename))
    module = importlib.util.module_from_spec(descriptor)
    descriptor.loader.exec_module(module)
    return module


def task_file(suffix):
    key = os.environ.get('DSHA_PLUGIN_TASK', '')
    return local('/root/.dsha-plugin-task-' + key + suffix) if re.fullmatch('[a-f0-9]{32}', key) else ''


def check_cancel():
    path = task_file('.cancel')
    if path and os.path.exists(path):
        raise PluginCancelled('已取消；未完成事务及原件保留，请检查后恢复')


def read_json(path, default=None):
    if not os.path.isfile(path):
        return default
    with open(path, encoding='utf-8-sig') as stream:
        return json.load(stream)


def write_json(path, value):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    temporary = path + '.tmp'
    with open(temporary, 'w', encoding='utf-8') as stream:
        json.dump(value, stream, indent=2, ensure_ascii=False)
        stream.flush(); os.fsync(stream.fileno())
    os.replace(temporary, path)


_last_progress = 0


def progress(stage, message, current=0, total=0, cancellable=True):
    global _last_progress
    if cancellable:
        check_cancel()
    now = time.monotonic()
    if current and now - _last_progress < .3 and current != total:
        return
    _last_progress = now
    path = task_file('.json')
    if path:
        write_json(path, dict(stage=stage, message=message, current=current, total=total, cancellable=cancellable))


def result(status, message, **extra):
    cas_timing('entry-total', time.monotonic() - _ENTRY_STARTED)
    print('PLUGIN_RESULT: ' + json.dumps(dict(status=status, message=message, **extra), ensure_ascii=False), flush=True)


def cas_timing(phase, elapsed, **counts):
    # Only phase names, durations and aggregate counters; no paths or content.
    print('CAS_TIMING: ' + json.dumps(dict(phase=phase, elapsedMs=round(elapsed * 1000, 3), **counts),
                                     separators=(',', ':')), flush=True)


def main():
    global builtin, local, DSH_HOME
    try:
        if len(sys.argv) != 1:
            raise ValueError('此启动入口不接受参数')
        builtin = load('cas_builtin', 'register-builtin-plugins.py')
        local, DSH_HOME = builtin.local, builtin.DSH_HOME
        transaction = load('cas_transactions', 'plugin-transactions.py').Transactions(globals())
        cas_timing('entry-imports', time.monotonic() - _ENTRY_STARTED)
        check_cancel()
        with builtin.operation_lock(check_cancel, timing=cas_timing):
            try:
                copied = transaction.materialize_cas_links()
            except PluginCancelled:
                raise
            except Exception as error:
                pending = transaction.pending()
                result('pending' if pending else 'error', str(error),
                       pendingOperations=[os.path.basename(path) for path in pending], automaticRetry=False)
                return 1
        # The success line is printed after both locks have released. Native
        # callers still require a complete zero exit and an untruncated receipt.
        result('ok', '旧 pnpm 内容链接已按原字节实体化' if copied['changed'] else '无需修复旧 pnpm 内容链接',
               **copied, algorithm='DSHA_PNPM_CAS_COPY_V1', profile='web', storeReadOnly=True)
        return 0
    except PluginCancelled as error:
        result('cancelled', str(error))
    except Exception as error:
        result('error', str(error), automaticRetry=False)
    return 1


if __name__ == '__main__':
    sys.exit(main())
