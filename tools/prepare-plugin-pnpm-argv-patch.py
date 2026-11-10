#!/usr/bin/env python3
"""Preserve pnpm 10's command-first npm passthrough in the unlocked plugin policy."""
import argparse
import hashlib
import json
from pathlib import Path
from source_text import write_text, matches_text

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'
MODULE = '@deepseek-ai/dsh-plugin-manager/lib/index.js'
SOURCE_SHA = '6f0fdc6a6e4cd2bc9d969ba582636729ade450e415f0fc20bbef9da3f65823f3'
NAME = 'plugin-pnpm-argv-patch.json'


def generate(modules):
    source = (modules / MODULE).read_bytes()
    if hashlib.sha256(source).hexdigest() != SOURCE_SHA:
        raise ValueError('PLUGIN_PNPM_ARGV_OFFICIAL_SOURCE_CHANGED')
    package = json.loads((modules / '@deepseek-ai/dsh-plugin-manager/package.json').read_text(encoding='utf8'))
    lock = json.loads((ROOT / 'tools/dsh-runtime/package-lock.json').read_text(encoding='utf8'))
    if package['version'] != '0.2.2-rc.1' or lock['packages']['node_modules/@deepseek-ai/dsh-plugin-manager']['version'] != package['version']:
        raise ValueError('PLUGIN_PNPM_ARGV_VERSION_CHANGED')
    policy = json.loads((ASSETS / 'plugin-manager-policy-patch.json').read_text(encoding='utf8'))
    old = policy['patches'][0]['after']
    if old.count('DSHA_PLUGIN_UNLOCKED_PNPM_V2') != 1 or source.decode('utf8').count(policy['patches'][0]['before']) != 1:
        raise ValueError('PLUGIN_PNPM_ARGV_POLICY_CHANGED')
    # Keep the complete V2 after form verbatim. Existing APKs and the signed
    # full-chain admission must still recognize that historical policy anchor.
    added = '''
	// DSHA_PLUGIN_PNPM_ARGV_V3: pnpm 10 dispatches view/info/show to npm only
	// when the command is argv[0]. Policy flags in options.args also leaked
	// into the registry preflight and made pnpm try to execute a binary "view".
	// Move only this exact managed suffix, keeping caller executable-prefix
	// arguments, registry, source spec and every open lifecycle setting intact.
	if (process.env.DSHA_PLUGIN_UNLOCKED === "1") {
		const policyFlags = [
			"--config.dangerously-allow-all-builds=true", "--config.ignore-scripts=false",
			"--config.ignore-dep-scripts=false", "--config.update-notifier=false",
			"--config.fetch-retries=1", "--config.fetch-retry-mintimeout=1000",
			"--config.fetch-retry-maxtimeout=3000", "--config.fetch-timeout=60000",
			"--reporter=append-only"
		];
		const prefix = options.args ?? [];
		if (JSON.stringify(prefix.slice(-policyFlags.length)) !== JSON.stringify(policyFlags))
			throw new Error("DSHA_PLUGIN_PNPM_POLICY_PREFIX_CHANGED");
		options = { ...options, args: prefix.slice(0, -policyFlags.length) };
		args = [...args, ...policyFlags];
	}'''
    return dict(version=1, dshVersion='0.2.2-rc.1', module=MODULE, sourceSha256=SOURCE_SHA,
                patches=[dict(before=old, after=old + added)])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--modules', type=Path)
    parser.add_argument('--write', action='store_true')
    args = parser.parse_args()
    modules = args.modules
    if modules is None:
        selected = json.loads((ROOT / 'app/build/test-runtimes/current.json').read_text(encoding='utf8'))
        modules = Path(selected['raw']) / 'node_modules'
    text = json.dumps(generate(modules), ensure_ascii=False, indent=2) + '\n'
    target = ASSETS / NAME
    if args.write:
        write_text(target, text, encoding='utf8')
    elif not matches_text(target, text, encoding='utf8'):
        raise ValueError('PLUGIN_PNPM_ARGV_RECIPE_STALE')
    print('PASS plugin pnpm argv recipe: exact 0.2.2-rc.1 source / preserved V2 / command-first V3')


if __name__ == '__main__':
    main()
