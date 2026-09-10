#!/usr/bin/env python3
"""按仓库锁文件安装 Linux arm64 依赖，并生成已适配的离线覆盖层。"""
import argparse
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--work', type=Path, default=ROOT / 'app/build/locked-dsh-runtime')
    parser.add_argument('--cache', type=Path)
    parser.add_argument('--offline', action='store_true', help='仅使用已填充的 npm 缓存，缺包直接失败')
    args = parser.parse_args()
    work = args.work.resolve()
    if ROOT / 'app/build' not in work.parents:
        parser.error('npm 安装目录必须位于本项目 app/build 下')
    work.mkdir(parents=True, exist_ok=True)
    for name in ('package.json', 'package-lock.json'):
        shutil.copyfile(ROOT / 'tools/dsh-runtime' / name, work / name)
    npm = shutil.which('npm.cmd') or shutil.which('npm')
    if not npm:
        parser.error('需要 Node 24 和 npm 11')
    command = [npm, 'ci', '--prefix', str(work), '--os=linux', '--cpu=arm64', '--libc=glibc',
               '--ignore-scripts', '--no-audit', '--no-fund']
    if args.cache:
        command += ['--cache', str(args.cache.resolve())]
    if args.offline:
        command.append('--offline')
    subprocess.run(command, check=True)
    spec = importlib.util.spec_from_file_location('builder', ROOT / 'tools/build-dsh-runtime.py')
    builder = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(builder)
    version = json.loads((ROOT / 'tools/dsh-runtime/package.json').read_text(encoding='utf-8'))['dependencies']['@deepseek-ai/dsh']
    report = builder.build(work / 'node_modules', ROOT / 'app/src/main/assets/dsh-runtime.bin', version)
    (work / 'build-report.json').write_text(json.dumps(report, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    print('新版离线覆盖层已生成：', report['sha256'])


if __name__ == '__main__':
    main()
