#!/usr/bin/env python3
"""把正式 Release 附带的共存测试包（.debug / .dev）合并进发布证据。

publish 节点不 checkout：本脚本随 build-signed 的 release-bundle 一起交给 publish，
publish 先按 build-info.json 里记录的 SHA-256 核对脚本本身，再执行 ``merge``。合并内容：

1. 逐个 variant 核对 evidence 目录：同一 run、同一 commit、同一 versionCode、正式包名前缀 +
   测试后缀、测试证书签名；APK 与公开测试证书的摘要必须与各自 build-info.json 一致，
   测试证书不得等于正式证书；
2. 复制 APK 与公开测试证书到 bundle：``<apk-prefix>-<variant>-<commit8>.apk`` /
   ``<apk-prefix>-test-cert.pem``；
3. ``build-info.json`` 追加 ``testPackages`` 段，并更新 ``SHA256SUMS`` 里它自己的摘要；
4. ``SHA256SUMS`` 追加测试包资产，写 ``test-package-assets.txt`` 供 publish 上传；
5. ``release-notes.md`` 追加「共存测试包」小节。

只接受本仓库生成的证据结构；资产名由脚本生成，不信任证据里的路径，拒绝目录穿越。
"""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import shutil
import sys

PRODUCTION_KIND = 'dsha-a-su-production-release-apk'
VARIANT_KIND = 'dsha-a-su-test-package-variant'
# 正式发布工作流只构建 mod 身份（bind 节点核对 repository / packageName），
# 因此这里固定 mod 的测试后缀；upstream 身份的测试包不进入正式 Release。
VARIANT_SUFFIX = {'debug': '.debug', 'dev': '.dev'}
VARIANT_VERSION_SUFFIX = {'debug': '-SunsetDebug', 'dev': '-SunsetDev'}
SHA256_RE = re.compile(r'[a-f0-9]{64}')
COMMIT_RE = re.compile(r'[a-f0-9]{40}')
CHECKSUM_RE = re.compile(r'([a-f0-9]{64})  (.+)')


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def requested_variants(value):
    """把 workflow_dispatch 的 test_packages 输入映射成要合并的 variant 列表。"""
    if not isinstance(value, str):
        raise ValueError('TEST_PACKAGE_REQUEST_INVALID')
    value = value.strip()
    if value == 'debug+dev':
        return ['debug', 'dev']
    if value in ('debug', 'dev'):
        return [value]
    raise ValueError('TEST_PACKAGE_REQUEST_INVALID: ' + repr(value))


def pem_certificate_sha256(path):
    """公开证书 PEM 的 DER 摘要，必须与 apksigner 读取到的证书摘要一致。"""
    text = path.read_text(encoding='ascii')
    bodies = re.findall(r'-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----', text, re.S)
    if len(bodies) != 1:
        raise ValueError('TEST_PACKAGE_CERT_PEM_INVALID')
    der = base64.b64decode(''.join(bodies[0].split()), validate=True)
    return hashlib.sha256(der).hexdigest()


def load_json(path):
    return json.loads(path.read_text(encoding='utf-8'))


def verify_production_evidence(info, commit):
    """核对正式 build-info 是本脚本约定的结构，返回 commit / 包名 / 证书摘要。"""
    if info.get('kind') != PRODUCTION_KIND:
        raise SystemExit('PRODUCTION_EVIDENCE_KIND_INVALID')
    source = info.get('commit')
    if not isinstance(source, str) or source != commit or not COMMIT_RE.fullmatch(source):
        raise SystemExit('PRODUCTION_EVIDENCE_COMMIT_MISMATCH')
    package = info.get('packageName')
    if not isinstance(package, str) or not package:
        raise SystemExit('PRODUCTION_EVIDENCE_PACKAGE_MISSING')
    certificate = info.get('certificateSha256')
    if not isinstance(certificate, str) or not SHA256_RE.fullmatch(certificate):
        raise SystemExit('PRODUCTION_EVIDENCE_CERTIFICATE_MISSING')
    version_code = info.get('versionCode')
    if not isinstance(version_code, int):
        raise SystemExit('PRODUCTION_EVIDENCE_VERSION_MISSING')
    return package, certificate, version_code


def verify_variant_evidence(directory, variant, production):
    """核对一个 variant 的 evidence 目录，返回合并所需的路径与元数据。"""
    directory = Path(directory)
    if not directory.is_dir():
        raise SystemExit('TEST_PACKAGE_EVIDENCE_MISSING: ' + str(directory))
    info_path = directory / 'build-info.json'
    if not info_path.is_file():
        raise SystemExit('TEST_PACKAGE_EVIDENCE_MISSING: ' + str(info_path))
    info = load_json(info_path)
    if info.get('kind') != VARIANT_KIND or info.get('variant') != variant:
        raise SystemExit('TEST_PACKAGE_EVIDENCE_IDENTITY_INVALID: ' + variant)
    commit = production['commit']
    # 必须与正式包同一 run / attempt / commit；跨 run 或手工拼装的证据一律拒绝。
    if info.get('commit') != commit:
        raise SystemExit('TEST_PACKAGE_COMMIT_MISMATCH: ' + variant)
    if str(info.get('runId')) != str(production['runId']) or str(info.get('runAttempt')) != str(production['runAttempt']):
        raise SystemExit('TEST_PACKAGE_RUN_MISMATCH: ' + variant)
    expected_package = production['packageName'] + VARIANT_SUFFIX[variant]
    if info.get('packageName') != expected_package:
        raise SystemExit('TEST_PACKAGE_PACKAGE_MISMATCH: ' + variant)
    version_name = info.get('versionName')
    if not isinstance(version_name, str) or not version_name.endswith(VARIANT_VERSION_SUFFIX[variant]):
        raise SystemExit('TEST_PACKAGE_VERSION_NAME_MISMATCH: ' + variant)
    if not isinstance(version_name, str) or not version_name.startswith(str(production['versionName'])):
        raise SystemExit('TEST_PACKAGE_VERSION_BINDING_MISMATCH: ' + variant)
    if info.get('versionCode') != production['versionCode']:
        raise SystemExit('TEST_PACKAGE_VERSION_CODE_MISMATCH: ' + variant)
    expected_debuggable = variant == 'debug'
    if info.get('debuggable') is not expected_debuggable:
        raise SystemExit('TEST_PACKAGE_DEBUGGABLE_MISMATCH: ' + variant)
    if info.get('signedWith') != 'test-cert':
        raise SystemExit('TEST_PACKAGE_SIGNING_KIND_MISMATCH: ' + variant)
    # 正式 Release 的测试包必须用固定的测试证书，一次性密钥不能作为发布资产。
    if info.get('signingKeySource') != 'configured-secret':
        raise SystemExit('TEST_PACKAGE_SIGNING_KEY_NOT_CONFIGURED: ' + variant)
    certificate = info.get('certificateSha256')
    if not isinstance(certificate, str) or not SHA256_RE.fullmatch(certificate):
        raise SystemExit('TEST_PACKAGE_CERTIFICATE_MISSING: ' + variant)
    if certificate == production['certificateSha256']:
        raise SystemExit('TEST_PACKAGE_USES_PRODUCTION_CERTIFICATE: ' + variant)
    expected_apk = '%s-%s-%s.apk' % (production['apkPrefix'], variant, commit[:8])
    if info.get('apkFile') != expected_apk:
        raise SystemExit('TEST_PACKAGE_APK_NAME_MISMATCH: ' + variant)
    apk = directory / expected_apk
    if not apk.is_file():
        raise SystemExit('TEST_PACKAGE_APK_MISSING: ' + variant)
    digest = sha256_file(apk)
    if info.get('apkSha256') != digest:
        raise SystemExit('TEST_PACKAGE_APK_DIGEST_MISMATCH: ' + variant)
    if info.get('apkSize') != apk.stat().st_size:
        raise SystemExit('TEST_PACKAGE_APK_SIZE_MISMATCH: ' + variant)
    cert_path = directory / 'test-cert.pem'
    if not cert_path.is_file():
        raise SystemExit('TEST_PACKAGE_CERT_PEM_MISSING: ' + variant)
    if pem_certificate_sha256(cert_path) != certificate:
        raise SystemExit('TEST_PACKAGE_CERT_PEM_MISMATCH: ' + variant)
    return {
        'variant': variant,
        'info': info,
        'apk': apk,
        'apk_size': apk.stat().st_size,
        'cert': cert_path,
        'certificateSha256': certificate,
    }


def read_checksums(path):
    entries = []
    for line in path.read_text(encoding='utf-8').splitlines():
        if not line.strip():
            continue
        match = CHECKSUM_RE.fullmatch(line)
        if not match:
            raise SystemExit('SHA256SUMS_FORMAT_INVALID')
        entries.append((match.group(1), match.group(2)))
    return entries


def rewrite_checksums(path, bundle, updates, new_names):
    """更新指定条目（如被追加内容的 build-info.json）后追加新资产。"""
    entries = read_checksums(path)
    names = [name for _, name in entries]
    if len(names) != len(set(names)):
        raise SystemExit('SHA256SUMS_DUPLICATE_ENTRY')
    if 'build-info.json' not in updates:
        raise SystemExit('SHA256SUMS_BUILD_INFO_MISSING')
    for digest, name in entries:
        if name in updates:
            continue
        target = Path(bundle) / name
        if not target.is_file() or sha256_file(target) != digest:
            raise SystemExit('SHA256SUMS_MISMATCH: ' + name)
    result = []
    for digest, name in entries:
        result.append((updates.get(name, digest), name))
    existing = {name for _, name in result}
    for name in new_names:
        if name in existing or name in updates:
            raise SystemExit('SHA256SUMS_DUPLICATE_ASSET: ' + name)
        result.append((sha256_file(Path(bundle) / name), name))
        existing.add(name)
    path.write_text(''.join(digest + '  ' + name + '\n' for digest, name in result), encoding='utf-8')


def append_release_notes(path, entries, certificate, cert_name, commit):
    lines = [
        '',
        '## 共存测试包（.debug / .dev）',
        '',
        '本 Release 额外附带与正式包**同一 run、同一 commit**（`%s`）构建的测试包。'
        '它们可与正式包并排安装，数据与正式包互相隔离。' % commit,
        '',
        '| 包名 | 变体 | 可调试 | 资产 | SHA-256 |',
        '|---|---|---|---|---|',
    ]
    for entry in entries:
        lines.append('| `%s` | .%s | %s | `%s` | `%s` |' % (
            entry['packageName'],
            entry['variant'],
            '是' if entry['debuggable'] else '否',
            entry['apkFile'],
            entry['apkSha256'],
        ))
    lines += [
        '',
        '- 签名：独立**测试证书** SHA-256 `%s`（公开证书文件 `%s`），**不是**正式发布证书；'
        '测试包不能覆盖正式包，正式包也不能覆盖测试包。' % (certificate, cert_name),
        '- 数据隔离：测试包与正式包的配置、会话、插件与导出数据互不影响；卸载任一包不会改动另一个包的数据。',
        '- 卸载：系统设置 → 应用 → 找到对应测试包（应用名带 Debug / Dev 后缀，包名见上表）→ 卸载；'
        '卸载会清除该测试包自己的数据。',
        '- 后续版本会在测试包内提供应用内「反馈与诊断」一键导出（仅本应用日志，本地生成、不自动上传）；'
        '当前版本尚未包含该功能。',
        '',
    ]
    with open(path, 'a', encoding='utf-8') as stream:
        stream.write('\n'.join(lines))
        if not lines[-1] == '':
            stream.write('\n')


def merge(bundle, test_packages, artifact_prefix, apk_prefix, requested):
    """合并测试包证据；返回可打印的摘要。"""
    bundle = Path(bundle)
    production_info_path = bundle / 'build-info.json'
    if not production_info_path.is_file():
        raise SystemExit('PRODUCTION_EVIDENCE_MISSING')
    notes_path = bundle / 'release-notes.md'
    if not notes_path.is_file():
        raise SystemExit('PRODUCTION_RELEASE_NOTES_MISSING')
    production_info = load_json(production_info_path)
    variants = requested_variants(requested)
    commit = production_info.get('commit')
    package, certificate, version_code = verify_production_evidence(production_info, commit)
    production = {
        'commit': commit,
        'packageName': package,
        'certificateSha256': certificate,
        'versionCode': version_code,
        'versionName': production_info.get('versionName'),
        'runId': production_info.get('runId'),
        'runAttempt': production_info.get('runAttempt'),
        'apkPrefix': apk_prefix,
    }
    evidences = []
    for variant in variants:
        directory = Path(test_packages) / (artifact_prefix + variant)
        evidences.append(verify_variant_evidence(directory, variant, production))
    certificates = {evidence['certificateSha256'] for evidence in evidences}
    if len(certificates) != 1:
        raise SystemExit('TEST_PACKAGE_CERTIFICATE_DIFFERS_BETWEEN_VARIANTS')
    test_certificate = certificates.pop()
    if test_certificate == production['certificateSha256']:
        raise SystemExit('TEST_PACKAGE_USES_PRODUCTION_CERTIFICATE')
    cert_name = apk_prefix + '-test-cert.pem'
    cert_path = bundle / cert_name
    if cert_path.exists():
        raise SystemExit('TEST_PACKAGE_ASSET_ALREADY_EXISTS: ' + cert_name)
    shutil.copyfile(evidences[0]['cert'], cert_path)
    if sha256_file(cert_path) != sha256_file(evidences[0]['cert']):
        raise SystemExit('TEST_PACKAGE_CERT_COPY_FAILED')
    entries = []
    assets = []
    for evidence in evidences:
        variant = evidence['variant']
        asset_name = '%s-%s-%s.apk' % (apk_prefix, variant, commit[:8])
        target = bundle / asset_name
        if target.exists():
            raise SystemExit('TEST_PACKAGE_ASSET_ALREADY_EXISTS: ' + asset_name)
        shutil.copyfile(evidence['apk'], target)
        if sha256_file(target) != evidence['info']['apkSha256']:
            raise SystemExit('TEST_PACKAGE_ASSET_COPY_FAILED: ' + asset_name)
        assets.append(asset_name)
        entries.append({
            'variant': variant,
            'packageName': evidence['info']['packageName'],
            'versionName': evidence['info']['versionName'],
            'apkFile': asset_name,
            'apkSize': evidence['apk_size'],
            'apkSha256': evidence['info']['apkSha256'],
            'debuggable': evidence['info']['debuggable'],
            'signedWith': 'test-cert',
            'signingKeySource': evidence['info']['signingKeySource'],
            'certificateSha256': evidence['info']['certificateSha256'],
        })
    assets.append(cert_name)
    production_info['testPackages'] = entries
    production_info['testCertificateSha256'] = test_certificate
    production_info_path.write_text(
        json.dumps(production_info, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    rewrite_checksums(bundle / 'SHA256SUMS', bundle,
                      updates={'build-info.json': sha256_file(production_info_path)},
                      new_names=assets)
    (bundle / 'test-package-assets.txt').write_text('\n'.join(assets) + '\n', encoding='utf-8')
    append_release_notes(notes_path, entries, test_certificate, cert_name, commit)
    return {
        'commit': commit,
        'testPackages': entries,
        'testCertificateSha256': test_certificate,
        'assets': assets,
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description='合并正式 Release 附带的共存测试包证据')
    subparsers = parser.add_subparsers(dest='command', required=True)
    merge_parser = subparsers.add_parser('merge', help='把测试包证据并入已核对的发布 bundle')
    merge_parser.add_argument('--bundle', required=True, help='含 build-info.json / SHA256SUMS / release-notes.md 的目录')
    merge_parser.add_argument('--test-packages', required=True, help='download-artifact 解出的测试包证据根目录')
    merge_parser.add_argument('--artifact-prefix', required=True, help='证据 artifact 名（含末尾连字符）')
    merge_parser.add_argument('--apk-prefix', required=True, help='交付 APK / 证书文件名前缀')
    merge_parser.add_argument('--requested', required=True, help='debug+dev / debug / dev')
    args = parser.parse_args(argv)
    try:
        result = merge(args.bundle, args.test_packages, args.artifact_prefix, args.apk_prefix, args.requested)
    except ValueError as error:
        raise SystemExit(str(error)) from None
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == '__main__':
    sys.exit(main())
