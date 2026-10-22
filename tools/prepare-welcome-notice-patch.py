#!/usr/bin/env python3
"""从完整摘要锁定的官方 rc2 模块生成首次说明受管补丁，不改 npm 原件。"""
import argparse
import hashlib
import json
from pathlib import Path
from source_text import write_text, matches_text

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'
MODULE = '@deepseek-ai/dsh-client-ui-settings-models/lib/client.js'
VERSION = '0.2.1-rc.2'
SOURCE_SHA256 = '190498e15358bcd3fe6810fe6368023a20b0403e3ea493bc979eca9bdb593b28'


def generate(module, lock_path=None):
    data = module.read_bytes()
    if hashlib.sha256(data).hexdigest() != SOURCE_SHA256:
        raise ValueError('WELCOME_NOTICE_OFFICIAL_SOURCE_CHANGED')
    package = json.loads(module.parent.parent.joinpath('package.json').read_text(encoding='utf8'))
    lock = json.loads((lock_path or ROOT / 'tools/dsh-runtime/package-lock.json').read_text(encoding='utf8'))
    locked = lock['packages']['node_modules/@deepseek-ai/dsh-client-ui-settings-models']
    if package['name'] != '@deepseek-ai/dsh-client-ui-settings-models' or package['version'] != VERSION or locked['version'] != VERSION:
        raise ValueError('WELCOME_NOTICE_PACKAGE_VERSION_CHANGED')
    source = data.decode('utf8')
    patches = []
    for anchor, template in [('function WelcomeNotice(props) {', 'WelcomeNotice.js'),
                             ('var WelcomeNoticeStore = class {', 'WelcomeNoticeStore.js')]:
        start = source.index('\t\t' + anchor)
        end = source.index('\t\t//#endregion', start)
        before = source[start:end]
        after = ''.join('\t\t' + line + '\n' for line in (ROOT / 'tools/welcome-notice' / template).read_text(encoding='utf8').splitlines())
        if source.count(before) != 1:
            raise ValueError('WELCOME_NOTICE_ANCHOR_CHANGED')
        patches.append(dict(before=before, after=after))
    anchor = 'const welcomeController = new WelcomeNoticeStore(ctx.configForms.get(WELCOME_NOTICE_SETTINGS_NAMESPACE));'
    if source.count(anchor) != 1:
        raise ValueError('WELCOME_NOTICE_SHARED_MIRROR_ANCHOR_CHANGED')
    patches.append(dict(before=anchor, after=anchor.replace('WELCOME_NOTICE_SETTINGS_NAMESPACE));',
                                                         'WELCOME_NOTICE_SETTINGS_NAMESPACE), ctx.configForms.describe());')))
    copy = {
        'en': {
            'welcomeWaiting': 'Saving is still pending. You can keep waiting, return to the notice, or enter for this visit only. Leaving the wait does not cancel the save request; it may still complete later.',
            'welcomeWaitLeft': 'You left the wait. The previous save request was not cancelled and may still complete later. You can retry saving or enter for this visit only.',
            'welcomeSessionHint': 'Entering for this visit only does not save your acknowledgement. The notice will appear again on refresh unless a save request later completes.',
            'welcomeDiagnostics': 'Save diagnostics',
            'welcomeReturn': 'Return to notice',
            'welcomeSessionOnly': 'I have read this; enter this visit only (not saved)',
            'welcomeSaving': 'Saving…',
            'welcomeRetry': 'Retry save'
        },
        'zh': {
            'welcomeWaiting': '确认状态仍在保存中。可以继续等待、返回说明或仅本次进入。离开等待不会取消后台保存请求，稍后仍可能保存成功。',
            'welcomeWaitLeft': '已返回说明。先前的保存请求没有取消，稍后仍可能保存成功。可以重试保存，或仅本次进入。',
            'welcomeSessionHint': '仅本次进入不会保存已读确认；刷新后会再次显示说明，除非后台保存请求随后完成。',
            'welcomeDiagnostics': '保存诊断信息',
            'welcomeReturn': '返回说明',
            'welcomeSessionOnly': '我已阅读，仅本次进入（未保存）',
            'welcomeSaving': '正在保存…',
            'welcomeRetry': '重试保存'
        }
    }
    for language, anchor in [('en', '\t\t\twelcomeContinue: "Continue",\n'), ('zh', '\t\t\twelcomeContinue: "继续",\n')]:
        if source.count(anchor) != 1:
            raise ValueError('WELCOME_NOTICE_COPY_ANCHOR_CHANGED')
        after = anchor + ''.join('\t\t\t' + key + ': ' + json.dumps(value, ensure_ascii=False) + ',\n' for key, value in copy[language].items())
        patches.append(dict(before=anchor, after=after))
    return json.dumps(dict(version=1, dshVersion=VERSION, module=MODULE,
                           sourceSha256=SOURCE_SHA256, packageIntegrity=locked['integrity'],
                           patches=patches), ensure_ascii=False, indent=2) + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--module', type=Path, help='官方原始 npm 安装树中的 lib/client.js')
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--lock', type=Path, help='显式待适配锁；默认使用当前生产锁，不改写锁文件')
    args = parser.parse_args()
    module = args.module
    if module is None:
        selected = json.loads((ROOT / 'app/build/test-runtimes/current.json').read_text(encoding='utf8'))
        module = Path(selected['raw']) / 'node_modules' / MODULE
    expected = generate(module, args.lock)
    target = ASSETS / 'welcome-notice-patch.json'
    if args.check:
        if not matches_text(target, expected):
            raise ValueError('WELCOME_NOTICE_PATCH_STALE')
    else:
        write_text(target, expected, encoding='utf8')
    print('PASS locked rc2 welcome notice patch' + (' matches generated source' if args.check else ' generated'))


if __name__ == '__main__':
    main()
