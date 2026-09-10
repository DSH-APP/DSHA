#!/usr/bin/env python3
"""从最终 APK 生成官网/App 共用清单和 GitHub 发布说明，拒绝错误签名或混装。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
from urllib.parse import quote, urlparse

PUBLISH_CERT = 'e7e3a31a75946f2669194c972b3dd0c9aea3fc7c50a8b885d2dee710b22a53f5'


def retain_channels(current, previous):
    """新预览发布时保留可下载的稳定版；官网仍展示首个当前发布。"""
    if previous is None:
        return [current]
    if (previous.get('schemaVersion') != 1 or previous.get('packageName') != 'com.dsh.client'
            or previous.get('certificateSha256') != PUBLISH_CERT):
        raise ValueError('上一发布清单的结构、包名或签名不一致')
    latest = {current['channel']: current}
    for release in previous['releases']:
        channel = release.get('channel')
        if channel not in ('stable', 'preview') or not isinstance(release.get('versionCode'), int):
            raise ValueError('上一发布清单的通道或版本码无效')
        if release['versionCode'] > current['versionCode']:
            raise ValueError('新发布的版本码不能低于已有发布')
        if release['versionCode'] == current['versionCode']:
            if release['version'] != current['version'] or channel != current['channel']:
                raise ValueError('同一版本码不能更换版本名称或通道')
            continue
        if channel not in latest or release['versionCode'] > latest[channel]['versionCode']:
            latest[channel] = release
    return [current] + [value for channel, value in latest.items() if channel != current['channel']]


def run(command):
    return subprocess.check_output([str(x) for x in command], text=True, encoding='utf-8', errors='replace')


def inspect(apk, flavor, build_tools, java):
    badging = run([build_tools / ('aapt2.exe' if os.name == 'nt' else 'aapt2'), 'dump', 'badging', apk])
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    sdk = re.search(r"(?:minSdkVersion|sdkVersion):'(\d+)'", badging)
    abi = re.search(r"native-code: (.+)", badging)
    if not package or package[1] != 'com.dsh.client' or not sdk or not abi:
        raise ValueError(f'{apk.name}: 无法读取 DSHA 包信息')
    if int(sdk[1]) != (30 if flavor == 'standard' else 23) or abi[1].strip() != "'arm64-v8a'":
        raise ValueError(f'{apk.name}: 系统要求或架构不匹配')
    if 'application-debuggable' in badging:
        raise ValueError(f'{apk.name}: 不允许发布调试包')
    signing = run([java, '-jar', build_tools / 'lib/apksigner.jar', 'verify', '--verbose', '--print-certs', apk])
    certs = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]+)', signing)
    if [c.lower() for c in certs] != [PUBLISH_CERT]:
        raise ValueError(f'{apk.name}: 与历史发布签名不一致')
    digest = hashlib.sha256()
    with apk.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    return dict(filename=apk.name, flavor=flavor, minSdk=int(sdk[1]), abi='arm64-v8a',
                bytes=apk.stat().st_size, sha256=digest.hexdigest(),
                versionCode=int(package[2]), versionName=package[3])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--standard', required=True, type=Path)
    parser.add_argument('--low', required=True, type=Path)
    parser.add_argument('--build-tools', required=True, type=Path)
    parser.add_argument('--java', default='java')
    parser.add_argument('--notes', required=True, type=Path)
    parser.add_argument('--output', default='app/build/release-manifest.json', type=Path)
    parser.add_argument('--previous-manifest', type=Path, help='上一份已发布更新清单；默认复用现有输出文件，保留另一通道')
    parser.add_argument('--origin', default='https://dsha.cc')
    args = parser.parse_args()
    previous_path = args.previous_manifest or args.output
    previous = json.loads(previous_path.read_text(encoding='utf-8')) if previous_path.exists() else None
    origin = args.origin.rstrip('/')
    if urlparse(origin).scheme != 'https' or urlparse(origin).path:
        parser.error('--origin 必须是 HTTPS 站点根地址')
    artifacts = [inspect(args.standard, 'standard', args.build_tools, args.java),
                 inspect(args.low, 'low', args.build_tools, args.java)]
    version, code = artifacts[0]['versionName'], artifacts[0]['versionCode']
    if not re.fullmatch(r'\d+\.\d+(?:\.\d+)?(?:-[A-Za-z0-9.]+)?', version):
        raise ValueError('版本名称无效')
    if artifacts[1]['versionName'] != version + 'low' or artifacts[1]['versionCode'] != code:
        raise ValueError('两版 APK 版本不一致')
    notes = args.notes.read_text(encoding='utf-8').strip()
    for artifact, apk in zip(artifacts, (args.standard, args.low)):
        expected = f'dsha-{version}{"low" if artifact["flavor"] == "low" else ""}.apk'
        if artifact['filename'] != expected:
            raise ValueError(f'发布文件名应为 {expected}')
        artifact['url'] = origin + '/downloads/' + quote(version) + '/' + quote(artifact['filename'])
        apk.with_suffix('.apk.sha256').write_text(f'{artifact["sha256"]}  {apk.name}\n', encoding='utf-8')
    release = dict(version=version, versionCode=code, channel='preview' if '-' in version else 'stable',
                   pageUrl=origin + '/download/', notes=notes, artifacts=artifacts)
    manifest = dict(schemaVersion=1, packageName='com.dsh.client', certificateSha256=PUBLISH_CERT,
                    releases=retain_channels(release, previous))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    github = f'# v{version}\n\n{notes}\n\n版本码 {code}；两版共享包名及历史发布签名。\n\n'
    github += '| 版本 | 最低 Android API | 安装包 | 大小 | SHA-256 |\n|---|---:|---|---:|---|\n'
    for a in artifacts:
        url = f'https://github.com/qiannianhuanxiang/DSHA/releases/download/v{version}/{a["filename"]}'
        github += f'| {a["flavor"]} | {a["minSdk"]} | [{a["filename"]}]({url}) | {a["bytes"]/1048576:.2f} MiB | `{a["sha256"]}` |\n'
    args.output.with_name('github-release-body.md').write_text(github, encoding='utf-8')
    print(f'已核验两份 APK：{version} / {code}，清单：{args.output}')


if __name__ == '__main__':
    main()
