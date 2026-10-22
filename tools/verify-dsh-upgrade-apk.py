#!/usr/bin/env python3
"""从实际 APK 验证新版 dsh、发布补丁、内置插件及减重资产摘要。"""
from verification_require import require
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import tarfile
import zipfile
from test_runtime_fixture import runtime as verified_runtime
from runtime_stable_patches import load_chains, apply as apply_stable_patches

PREFIX = 'usr/local/lib/node_modules/@deepseek-ai/dsh'
SIDEBAR_RIGHT = '@deepseek-ai/dsh-client-ui-sidebar-right/lib/client.js'
SIDEBAR_RECIPE = 'sidebar-layout-recovery-patch.json'
FRONTEND_ENTRY = '@deepseek-ai/dsh-web-frontend/dist/assets/index-CN7NIWNw.js'
CONVERSATION = '@deepseek-ai/dsh-client-ui-conversation/lib/client.js'
CONVERSATION_SPECIAL_INPUTS = ('lexical-claim-patch.json', 'conversation-materialized-patch.json')
REVIEWED_OFFICIAL_CHAINS = {
    SIDEBAR_RIGHT: (SIDEBAR_RECIPE,),
    FRONTEND_ENTRY: ('tooltip-interaction-patch.json',),
    CONVERSATION: ('composer-enter-patch.json', 'queue-dock-patch.json'),
}


def official_conversation_content(raw, assets, version):
    """Independently verify the retired transform and the retained special patch."""
    proof = json.loads((assets / CONVERSATION_SPECIAL_INPUTS[0]).read_text(encoding='utf-8'))
    text = raw.decode('utf-8')
    require(proof.get('version') == 1 and proof.get('module') == CONVERSATION
            and proof.get('dshVersion') == version and proof.get('retired') is True
            and proof.get('patches') == []
            and proof.get('sourceSha256') == hashlib.sha256(raw).hexdigest(),
            'OFFICIAL_COMMAND_CHIP_RETIREMENT_PROOF_CHANGED')
    require(text.count('class CommandChipNode') == 1 and 'TOKEN_STYLE' not in text
            and 'node.splitText(token.length)' not in text
            and 'DSHA_CLAIM_DECOR_OVERFLOW_STYLE_V1' not in text,
            'OFFICIAL_COMMAND_CHIP_ARCHITECTURE_CHANGED')
    recipe = json.loads((assets / CONVERSATION_SPECIAL_INPUTS[1]).read_text(encoding='utf-8'))
    require(recipe.get('module') == CONVERSATION and recipe.get('dshVersion') == version
            and isinstance(recipe.get('patches'), list) and len(recipe['patches']) == 1,
            'OFFICIAL_CONVERSATION_SPECIAL_RECIPE_CHANGED')
    for patch in recipe['patches']:
        require(set(patch) == {'before', 'after'} and text.count(patch['before']) == 1,
                'OFFICIAL_CONVERSATION_SPECIAL_ANCHOR_CHANGED')
        text = text.replace(patch['before'], patch['after'])
    return text.encode('utf-8')


def official_feature_digest(apk, assets, version, module, raw):
    """Keep raw protection except for these explicitly reviewed complete chains."""
    expected = raw
    if module in REVIEWED_OFFICIAL_CHAINS:
        chains, _ = load_chains(assets, version)
        chain = chains.get(module, [])
        require(tuple(row[0] for row in chain) == REVIEWED_OFFICIAL_CHAINS[module],
                'OFFICIAL_FEATURE_PRODUCTION_CHAIN_CHANGED:' + module)
        names = {'runtime-patches.json'}
        for asset in REVIEWED_OFFICIAL_CHAINS[module] + (CONVERSATION_SPECIAL_INPUTS if module == CONVERSATION else ()):
            recipe = json.loads((assets / asset).read_text(encoding='utf-8'))
            names.add(asset)
            names.update(patch['prependAsset'] for patch in recipe['patches'] if 'prependAsset' in patch)
        for name in sorted(names):
            require(apk.read('assets/' + name) == (assets / name).read_bytes(),
                    'OFFICIAL_FEATURE_APK_RECIPE_CHANGED:' + name)
        if module == CONVERSATION:
            expected = official_conversation_content(raw, assets, version)
        expected = apply_stable_patches(expected, chain)
    return hashlib.sha256(expected).hexdigest()


def verify_ubuntu_tools(apk, lock, lock_sha256, expected_archive_sha256):
    layout = apk.read('assets/ubuntu-tools.layout').strip()
    require(layout in (b'deb-install-v1', b'configured-overlay-v1'), 'Ubuntu 工具布局未知')
    digest = hashlib.sha256()
    with apk.open('assets/ubuntu-tools.bin') as stream:
        for block in iter(lambda: stream.read(1048576), b''):
            digest.update(block)
    require(digest.hexdigest() == expected_archive_sha256, 'APK Ubuntu 工具归档与源不符')
    if layout == b'configured-overlay-v1':
        manifest = json.loads(apk.read('assets/ubuntu-tools.manifest.json'))
        require(manifest.get('schema') == 1
                and manifest.get('archiveSha256') == digest.hexdigest()
                and manifest.get('packageLockSha256') == lock_sha256
                and manifest.get('packages') == len(lock['packages']), 'Ubuntu 工具制备证明不符')
        found = {}
        with apk.open('assets/ubuntu-tools.bin') as stream, tarfile.open(fileobj=stream, mode='r|gz') as tools:
            for member in tools:
                name = member.name.removeprefix('./')
                if name not in ('var/lib/dpkg/status', 'root/.dsha-ubuntu-tools-version'):
                    continue
                require(member.isfile() and name not in found and member.size <= 16 * 1024 * 1024,
                        'Ubuntu 工具状态成员无效')
                found[name] = tools.extractfile(member).read()
        require(set(found) == {'var/lib/dpkg/status', 'root/.dsha-ubuntu-tools-version'}, 'Ubuntu 工具状态缺失')
        require(found['root/.dsha-ubuntu-tools-version'].decode('ascii').strip() == lock_sha256,
                'Ubuntu 工具版本标记不符')
        status = found['var/lib/dpkg/status']
        require(hashlib.sha256(status).hexdigest() == manifest.get('statusSha256'), 'Ubuntu dpkg 状态摘要不符')
        packages = {}
        for paragraph in status.decode('utf-8').split('\n\n'):
            row = dict(line.split(': ', 1) for line in paragraph.splitlines()
                       if ': ' in line and not line.startswith(' '))
            if 'Package' in row:
                require(row['Package'] not in packages, 'Ubuntu dpkg 包记录重复')
                packages[row['Package']] = row
        for row in lock['packages']:
            actual = packages.get(row['Package'], {})
            require(actual.get('Version') == row['Version'] and actual.get('Status') == 'install ok installed',
                    'Ubuntu 工具未按锁完成配置：' + row['Package'])
        return
    packages = {Path(row['Filename']).name: row['SHA256'] for row in lock['packages']}
    with apk.open('assets/ubuntu-tools.bin') as stream, tarfile.open(fileobj=stream, mode='r|gz') as tools:
        for member in tools:
            if member.name.endswith('.deb'):
                require(member.isfile() and member.name in packages, '离线工具归档存在未知软件包')
                require(hashlib.sha256(tools.extractfile(member).read()).hexdigest() == packages.pop(member.name),
                        'Ubuntu deb 摘要不符')
    require(not packages, 'APK 缺少锁定的 Ubuntu 软件包')


def verify_builtin_plugins(apk, expected):
    registry = json.loads(apk.read('assets/builtin-plugins.json'))
    require(registry == expected and registry.get('schema') == 1, 'APK 内置插件名单与签名源不符')
    plugins = {}
    for row in registry['plugins']:
        name = row['name']
        require(name not in plugins, 'APK 内置插件名单重复')
        tree = 'app-integration' if name == 'dsh-app-integration' else 'builtin-plugins/' + name
        package = json.loads(apk.read('assets/' + tree + '/package.json'))
        require(package.get('name') == name and isinstance(package.get('version'), str)
                and package['version'], 'APK 内置插件包名或版本错误：' + name)
        require(apk.getinfo('assets/' + tree + '/' + row['entrypoint']).file_size > 0,
                'APK 内置插件缺少声明入口：' + name)
        plugins[name] = package['version']
    return plugins


def verify(path):
    root = Path(__file__).resolve().parent.parent
    dsh_version = json.loads((root / 'tools/dsh-runtime/package.json').read_text(encoding='utf-8'))['dependencies']['@deepseek-ai/dsh']
    expected_runtime_sha256 = hashlib.sha256((root / 'app/src/main/assets/dsh-runtime.bin').read_bytes()).hexdigest()
    with zipfile.ZipFile(path) as apk:
        version = apk.read('assets/offline-rootfs.version').decode().strip()
        require((version == '10'), '环境版本错误')
        digest = hashlib.sha256()
        with apk.open('assets/offline-rootfs.bin') as stream:
            for chunk in iter(lambda: stream.read(1048576), b''):
                digest.update(chunk)
        require((digest.hexdigest() == apk.read('assets/offline-rootfs.sha256').decode().strip()), 'VERIFICATION_FAILED')
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
            PREFIX + '/node_modules/dsha-client-combo-cache/index.js': b'createComboCache',
        }
        # Unpatched features keep exact locked raw bytes. The explicitly
        # reviewed active chains run before archive construction; compare their
        # complete bytes, retaining raw protection for every other module.
        untouched = {}
        runtime = verified_runtime('raw',os.environ.get('DSHA_TEST_RUNTIME')) / 'node_modules'
        frontend = runtime / '@deepseek-ai/dsh-web-frontend/dist'
        entry_match = re.search(r'src="\./(assets/index-[^"?]+\.js)',
                                (frontend / 'index.html').read_text(encoding='utf-8'))
        require((entry_match is not None), '锁定网页入口无法识别')
        frontend_entry = entry_match.group(1)
        for name, entry in [('dsh-client-ui-sidebar-documentpreview', 'lib/client.js'),
                            ('dsh-client-ui-deliverables', 'lib/client.js'),
                            ('dsh-command-feedback', 'lib/index.js'),
                            ('dsh-client-ui-sidebar-right', 'lib/client.js'),
                            ('dsh-client-ui-sidebar-files', 'lib/client.js'),
                            ('dsh-client-ui-model-selection', 'lib/client.js'),
                            ('dsh-client-ui-conversation', 'lib/client.js'),
                            ('dsh-web-frontend', 'dist/' + frontend_entry)]:
            source = runtime / '@deepseek-ai' / name / entry
            module = '@deepseek-ai/' + name + '/' + entry
            untouched[PREFIX + '/node_modules/' + module] = official_feature_digest(
                apk, root / 'app/src/main/assets', dsh_version, module, source.read_bytes())
        preserved_features = list(untouched)
        aliases, expanded = 0, 0
        legacy_workflow_aliases = {
            PREFIX + '/node_modules/@deepseek-ai/dsh-workflow-worker-thread': 'dsh-workflow-ptc',
            'usr/local/lib/node_modules/@deepseek-ai/dsh-workflow-worker-thread': 'dsh/node_modules/@deepseek-ai/dsh-workflow-ptc',
        }
        assets = ['offline-rootfs.bin']
        runtime_sha256 = None
        if 'assets/offline-rootfs.layout' in apk.namelist():
            require((apk.read('assets/offline-rootfs.layout').strip() == b'split-runtime-v1'), 'VERIFICATION_FAILED')
            assets.append('dsh-runtime.bin')
            runtime_sha256 = hashlib.sha256(apk.read('assets/dsh-runtime.bin')).hexdigest()
            require((runtime_sha256 == apk.read('assets/dsh-runtime.sha256').decode().strip()), 'VERIFICATION_FAILED')
            require((runtime_sha256 == expected_runtime_sha256), 'APK 没有打入当前生成的 dsh-runtime.bin')
        seen = set()
        for asset in assets:
            with apk.open('assets/' + asset) as stream, tarfile.open(fileobj=stream, mode='r|gz') as archive:
                for item in archive:
                    name = item.name.removeprefix('./').rstrip('/')
                    if not item.isdir():
                        require((name not in seen), '两个归档含重复运行时文件：' + name)
                        seen.add(name)
                    if item.isfile():
                        expanded += item.size
                    if name in wanted:
                        expected = wanted.pop(name)
                        content = archive.extractfile(item).read()
                        for marker in expected if isinstance(expected, tuple) else (expected,):
                            require((marker in content), name + ': ' + marker.decode(errors='replace'))
                    if name in untouched:
                        require((hashlib.sha256(archive.extractfile(item).read()).hexdigest() == untouched.pop(name)), name)
                    if name in legacy_workflow_aliases:
                        require((item.issym()), '旧工作流包名必须是只读兼容链接：' + name)
                        require((item.linkname == legacy_workflow_aliases.pop(name)), name)
                    if name.startswith('usr/local/lib/node_modules/') and not name.startswith((PREFIX+'/', 'usr/local/lib/node_modules/npm/')):
                        require((item.issym() or item.isdir()), '新版运行时之外有重复全局依赖：' + name)
                        aliases += int(item.issym())
                    require((not name.startswith(('root/dsha-device-shell-guide/', 'root/dsha-task-notifier/',
                                                'root/dsha-status-overlay/', 'root/dsha-web-mobile/'))), '混入旧版内置插件')
        require((not wanted), str(wanted))
        require((not untouched), '新版功能模块缺失：' + str(untouched))
        require((not legacy_workflow_aliases), '缺少旧工作流包名兼容链接：' + str(legacy_workflow_aliases))
        require((aliases > 100), '缺少共享依赖别名')
        require((expanded == int(apk.read('assets/offline-rootfs.bytes'))), 'VERIFICATION_FAILED')
        tools_lock_file = root / 'tools/ubuntu-tools/packages.lock.json'
        tools_lock = json.loads(tools_lock_file.read_text())
        verify_ubuntu_tools(apk, tools_lock, hashlib.sha256(tools_lock_file.read_bytes()).hexdigest(),
                           hashlib.sha256((root / 'app/src/main/assets/ubuntu-tools.bin').read_bytes()).hexdigest())
        require((b'dpkg --configure' in apk.read('assets/install-ubuntu-tools.sh')), 'VERIFICATION_FAILED')
        plugins = verify_builtin_plugins(
            apk, json.loads((root / 'app/src/main/assets/builtin-plugins.json').read_text(encoding='utf-8')))
        client = apk.read('assets/app-integration/client.js')
        require((b'resolveDraftAttachments' in client and b'createDrafts(id,files)' in client), 'VERIFICATION_FAILED')
        require((b'draftImages(' not in client and b'closeDetails()' not in client), 'VERIFICATION_FAILED')
        require((b'runner.listPlugins(agent)' in apk.read('assets/app-integration/runtime-plugins.js')), 'VERIFICATION_FAILED')
        require((b'dynamicCordisRunner' in apk.read('assets/app-integration/index.js')), 'VERIFICATION_FAILED')
        require((b'validate_graph' in apk.read('assets/backup-plugin-graph.py')), 'VERIFICATION_FAILED')
        require('assets/environment-data.py' not in apk.namelist(), 'APK 仍部署已退役的旧个人数据写入器')
        mobile = apk.read('assets/builtin-plugins/dsh-web-mobile/lib/client.js')
        # v3.0.0 owns the right-panel opener through its host selector and
        # files-panel helper instead of calling the old sidebarRight service
        # directly. Keep checking the actual v3 contract and the built-in
        # session menu/delete path.
        require((b'data-sidebar-right-expand' in mobile and b'function openFilesPanel' in mobile), 'VERIFICATION_FAILED')
        require((b'installSessionMenuDelete' in mobile), 'VERIFICATION_FAILED')
        require((b'connection.requestRejection' in apk.read('assets/builtin-plugins/dsh-web-mobile/lib/index.js')), 'VERIFICATION_FAILED')
        require(('assets/builtin-plugins/dsh-web-mobile/lib/delete-session.js' in apk.namelist()), 'VERIFICATION_FAILED')
        require((b'NARB_DISABLE_NATIVE_CACHE' in apk.read('assets/dsha-runtime-env.sh')), 'VERIFICATION_FAILED')
        migration = apk.read('assets/rc1-migration.py')
        require(b'RESTORED_SETTINGS_IMPORTED_FOR_RETRY' in migration, 'Production settings migration is missing')
        require(b'legacy-agent-presets' in migration, 'Legacy preset reader is missing')
        require('assets/fresh-settings-policy-patch.json' not in apk.namelist(), 'Fresh-only settings policy must not enter production')
        return dict(apk=str(path), dsh=dsh_version, environment=version, plugins=plugins,
                    preservedOfficialFeatures=preserved_features,
                    sharedModuleAliases=aliases, expandedBytes=expanded, ubuntuToolPackages=len(tools_lock['packages']),
                    rootfsSha256=digest.hexdigest(), dshRuntimeSha256=runtime_sha256)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apks', nargs='+', type=Path)
    args = parser.parse_args()
    print(json.dumps([verify(path) for path in args.apks], indent=2, ensure_ascii=False))
