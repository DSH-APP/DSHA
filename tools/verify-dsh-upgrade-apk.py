#!/usr/bin/env python3
"""从实际 APK 验证新版 dsh、发布补丁、内置插件及减重资产摘要。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import tarfile
import zipfile
from test_runtime_fixture import runtime as verified_runtime

PREFIX = 'usr/local/lib/node_modules/@deepseek-ai/dsh'


def verify(path):
    root = Path(__file__).resolve().parent.parent
    dsh_version = json.loads((root / 'tools/dsh-runtime/package.json').read_text(encoding='utf-8'))['dependencies']['@deepseek-ai/dsh']
    expected_runtime_sha256 = hashlib.sha256((root / 'app/src/main/assets/dsh-runtime.bin').read_bytes()).hexdigest()
    with zipfile.ZipFile(path) as apk:
        version = apk.read('assets/offline-rootfs.version').decode().strip()
        assert version == '10', '环境版本错误'
        digest = hashlib.sha256()
        with apk.open('assets/offline-rootfs.bin') as stream:
            for chunk in iter(lambda: stream.read(1048576), b''):
                digest.update(chunk)
        assert digest.hexdigest() == apk.read('assets/offline-rootfs.sha256').decode().strip()
        wanted = {
            PREFIX + '/package.json': ('"version": "' + dsh_version + '"').encode(),
            PREFIX + '/node_modules/@deepseek-ai/dsh-fs-local/lib/index.js': b'DSHA_ATOMIC_PUBLISH_V1',
            PREFIX + '/node_modules/@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js': (
                b'DSHA_SESSION_DIRECT_HINTS_V1',
                b'import { publishSessionExclusive as link } from "dsha-runtime-fs";'
            ),
            # 0.1.7 stores current sessions as V4 and ships the guarded V3→V4
            # migration. The previous V2→V3 module is no longer a root-level
            # runtime package; requiring its old marker would reject a valid
            # upgrade archive before any data check could run.
            PREFIX + '/node_modules/@deepseek-ai/dsh-session-format-v3-to-v4/lib/index.js': b'sessionFormatV3ToV4',
            PREFIX + '/node_modules/@deepseek-ai/dsh-storage-json/lib/index.js': b'DSHA_PROROOT_DIRECT_RECORD_HINTS_V1',
            PREFIX + '/node_modules/dsha-runtime-fs/index.js': b'publishSessionExclusive',
            PREFIX + '/node_modules/dsha-session-compat/index.js': b'wrapDshaLegacyStage',
            PREFIX + '/node_modules/@deepseek-ai/dsh-client-modules/lib/index.js': b'DSHA_COMBO_CACHE_V1',
            PREFIX + '/node_modules/@deepseek-ai/dsh-llm-deepseek/package.json': (
                b'"name": "@deepseek-ai/dsh-llm-deepseek"',
                ('"version": "' + dsh_version + '"').encode(),
            ),
            PREFIX + '/node_modules/@deepseek-ai/dsh-llm-deepseek/lib/index.js': (
                b'DSHA_DEEPSEEK_MESSAGES_PROJECTED_TOOL_CALL_V1',
                b'if (block.type === "tool-call") return [dshaMessagesProjectedToolCall(block)];',
            ),
            PREFIX + '/node_modules/@deepseek-ai/dsh-client-ui-conversation/lib/client.js': b'DSHA_CLAIM_DECOR_OVERFLOW_STYLE_V1',
            PREFIX + '/node_modules/dsha-client-combo-cache/index.js': b'createComboCache',
        }
        # 新增预览、文件交付和反馈模块必须与锁定官方包逐字节一致，不能被旧适配覆盖。
        untouched = {}
        runtime = verified_runtime('raw',os.environ.get('DSHA_TEST_RUNTIME')) / 'node_modules'
        frontend = runtime / '@deepseek-ai/dsh-web-frontend/dist'
        entry_match = re.search(r'src="\./(assets/index-[^"?]+\.js)',
                                (frontend / 'index.html').read_text(encoding='utf-8'))
        assert entry_match is not None, '锁定网页入口无法识别'
        frontend_entry = entry_match.group(1)
        for name, entry in [('dsh-client-ui-sidebar-documentpreview', 'lib/client.js'),
                            ('dsh-client-ui-deliverables', 'lib/client.js'),
                            ('dsh-command-feedback', 'lib/index.js'),
                            ('dsh-client-ui-sidebar-right', 'lib/client.js'),
                            ('dsh-client-ui-sidebar-files', 'lib/client.js'),
                            ('dsh-client-ui-model-selection', 'lib/client.js'),
                            ('dsh-web-frontend', 'dist/' + frontend_entry)]:
            source = runtime / '@deepseek-ai' / name / entry
            untouched[PREFIX + '/node_modules/@deepseek-ai/' + name + '/' + entry] = hashlib.sha256(source.read_bytes()).hexdigest()
        preserved_features = list(untouched)
        aliases, expanded = 0, 0
        legacy_workflow_aliases = {
            PREFIX + '/node_modules/@deepseek-ai/dsh-workflow-worker-thread': 'dsh-workflow-ptc',
            'usr/local/lib/node_modules/@deepseek-ai/dsh-workflow-worker-thread': 'dsh/node_modules/@deepseek-ai/dsh-workflow-ptc',
        }
        assets = ['offline-rootfs.bin']
        runtime_sha256 = None
        if 'assets/offline-rootfs.layout' in apk.namelist():
            assert apk.read('assets/offline-rootfs.layout').strip() == b'split-runtime-v1'
            assets.append('dsh-runtime.bin')
            runtime_sha256 = hashlib.sha256(apk.read('assets/dsh-runtime.bin')).hexdigest()
            assert runtime_sha256 == apk.read('assets/dsh-runtime.sha256').decode().strip()
            assert runtime_sha256 == expected_runtime_sha256, 'APK 没有打入当前生成的 dsh-runtime.bin'
        seen = set()
        for asset in assets:
            with apk.open('assets/' + asset) as stream, tarfile.open(fileobj=stream, mode='r|gz') as archive:
                for item in archive:
                    name = item.name.removeprefix('./').rstrip('/')
                    if not item.isdir():
                        assert name not in seen, '两个归档含重复运行时文件：' + name
                        seen.add(name)
                    if item.isfile():
                        expanded += item.size
                    if name in wanted:
                        expected = wanted.pop(name)
                        content = archive.extractfile(item).read()
                        for marker in expected if isinstance(expected, tuple) else (expected,):
                            assert marker in content, name + ': ' + marker.decode(errors='replace')
                    if name in untouched:
                        assert hashlib.sha256(archive.extractfile(item).read()).hexdigest() == untouched.pop(name), name
                    if name in legacy_workflow_aliases:
                        assert item.issym(), '旧工作流包名必须是只读兼容链接：' + name
                        assert item.linkname == legacy_workflow_aliases.pop(name), name
                    if name.startswith('usr/local/lib/node_modules/') and not name.startswith((PREFIX+'/', 'usr/local/lib/node_modules/npm/')):
                        assert item.issym() or item.isdir(), '新版运行时之外有重复全局依赖：' + name
                        aliases += int(item.issym())
                    assert not name.startswith(('root/dsha-device-shell-guide/', 'root/dsha-task-notifier/',
                                                'root/dsha-status-overlay/', 'root/dsha-web-mobile/')), '混入旧版内置插件'
        assert not wanted, str(wanted)
        assert not untouched, '新版功能模块缺失：' + str(untouched)
        assert not legacy_workflow_aliases, '缺少旧工作流包名兼容链接：' + str(legacy_workflow_aliases)
        assert aliases > 100, '缺少共享依赖别名'
        assert expanded == int(apk.read('assets/offline-rootfs.bytes'))
        tools_lock = json.loads((Path(__file__).resolve().parent / 'ubuntu-tools/packages.lock.json').read_text())
        packages = {Path(row['Filename']).name: row['SHA256'] for row in tools_lock['packages']}
        with apk.open('assets/ubuntu-tools.bin') as stream, tarfile.open(fileobj=stream, mode='r|gz') as tools:
            for member in tools:
                if member.name.endswith('.deb'):
                    assert member.isfile() and member.name in packages, '离线工具归档存在未知软件包'
                    assert hashlib.sha256(tools.extractfile(member).read()).hexdigest() == packages.pop(member.name)
        assert not packages, 'APK 缺少锁定的 Ubuntu 软件包'
        assert b'dpkg --configure' in apk.read('assets/install-ubuntu-tools.sh')
        plugins = {}
        for name in ('dsh-device-shell-guide', 'dsh-task-notifier', 'dsh-status-overlay', 'dsh-web-mobile', 'dsh-tool-vscreen'):
            package = json.loads(apk.read('assets/builtin-plugins/' + name + '/package.json'))
            plugins[name] = package['version']
        client = apk.read('assets/app-integration/client.js')
        assert b'resolveDraftAttachments' in client and b'createDrafts(id,files)' in client
        assert b'draftImages(' not in client and b'closeDetails()' not in client
        assert b'runner.listPlugins(agent)' in apk.read('assets/app-integration/runtime-plugins.js')
        assert b'dynamicCordisRunner' in apk.read('assets/app-integration/index.js')
        assert b'validate_graph' in apk.read('assets/backup-plugin-graph.py')
        assert b'REPLACED_TOOLS' in apk.read('assets/environment-data.py')
        mobile = apk.read('assets/builtin-plugins/dsh-web-mobile/lib/client.js')
        # v3.0.0 owns the right-panel opener through its host selector and
        # files-panel helper instead of calling the old sidebarRight service
        # directly. Keep checking the actual v3 contract and the built-in
        # session menu/delete path.
        assert b'data-sidebar-right-expand' in mobile and b'function openFilesPanel' in mobile
        assert b'installSessionMenuDelete' in mobile
        assert b'connection.requestRejection' in apk.read('assets/builtin-plugins/dsh-web-mobile/lib/index.js')
        assert 'assets/builtin-plugins/dsh-web-mobile/lib/delete-session.js' in apk.namelist()
        assert b'NARB_DISABLE_NATIVE_CACHE' in apk.read('assets/dsha-runtime-env.sh')
        migration = apk.read('assets/rc1-migration.py')
        assert b'RESTORED_SETTINGS_IMPORTED_FOR_RETRY' in migration
        assert b'legacy-agent-presets' in migration
        return dict(apk=str(path), dsh=dsh_version, environment=version, plugins=plugins,
                    preservedOfficialFeatures=preserved_features,
                    sharedModuleAliases=aliases, expandedBytes=expanded, ubuntuToolPackages=len(tools_lock['packages']),
                    rootfsSha256=digest.hexdigest(), dshRuntimeSha256=runtime_sha256)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apks', nargs='+', type=Path)
    args = parser.parse_args()
    print(json.dumps([verify(path) for path in args.apks], indent=2, ensure_ascii=False))
