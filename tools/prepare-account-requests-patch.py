#!/usr/bin/env python3
"""Generate narrow account request patches from hash-locked official 0.2.2-rc.1 modules."""
import argparse
import hashlib
import json
from pathlib import Path
from source_text import write_text, matches_text

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'
VERSION = '0.2.2-rc.1'
SERVER = '@deepseek-ai/dsh-deepseek-account-platform/lib/index.js'
CLIENT = '@deepseek-ai/dsh-client-ui-settings-account/lib/client.js'
HASHES = {
    SERVER: '3b82b41e3e94566e43105a2552c0a4804983be39e1e16ad8b0d0646c76ec0057',
    CLIENT: '0482b179466e1aaae6d7ff5738f679a8a62b39a0f1c496bda64bef342eb7b7e0',
}


def generate(modules):
    lock = json.loads((ROOT / 'tools/dsh-runtime/package-lock.json').read_text(encoding='utf8'))
    sources = {}
    for module, digest in HASHES.items():
        file = modules / module
        data = file.read_bytes()
        if hashlib.sha256(data).hexdigest() != digest:
            raise ValueError('ACCOUNT_REQUEST_OFFICIAL_SOURCE_CHANGED: ' + module)
        package = json.loads(file.parent.parent.joinpath('package.json').read_text(encoding='utf8'))
        locked = lock['packages']['node_modules/' + package['name']]
        if package['version'] != VERSION or locked['version'] != VERSION:
            raise ValueError('ACCOUNT_REQUEST_PACKAGE_VERSION_CHANGED')
        sources[module] = data.decode('utf8')

    source = sources[SERVER]
    patches = []
    def patch(before, after):
        if source.count(before) != 1 or before == after:
            raise ValueError('ACCOUNT_REQUEST_ANCHOR_CHANGED')
        patches.append(dict(before=before, after=after))

    # Log only existing fixed endpoint/validation metadata. Never serialize an
    # Error, headers, response body, grant, issuer query, or caller metadata.
    start = source.index('async function platformRequest(url, init, signal) {')
    end = source.index('\n/**\n* Accept a browser-accessible loopback', start)
    before = source[start:end]
    after = before.replace('async function platformRequest(url, init, signal) {',
        '// DSHA_ACCOUNT_REQUEST_DIAGNOSTICS_V1: IDs are process-local counters, never credentials.\n'
        'let dshaAccountRequestSequence = 0;\n'
        'async function platformRequest(url, init, signal) {')
    after = after.replace('\tconst path = new URL(url).pathname;',
        '\tconst path = new URL(url).pathname;\n'
        '\tconst requestId = ++dshaAccountRequestSequence;\n'
        '\tconst report = (event, fields) => console.error("[deepseek-account] " + event + " " + JSON.stringify({ requestId, ...fields }));')
    after = after.replace('console.error("[deepseek-account] request failed", {', 'report("request failed", {')
    after = after.replace('console.error("[deepseek-account] response rejected", {', 'report("response rejected", {')
    after = after.replace('\t\t\terrorCode: "network"\n',
        '\t\t\terrorCode: response.ok ? "network" : "http-error",\n'
        '\t\t\tfailureKind: response.ok ? "missing-body" : "http-response",\n'
        '\t\t\thttpClass: response.status === 403 ? "forbidden" : response.status === 429 ? "rate-limited" : response.status >= 500 ? "server-error" : "other"\n')
    # The thrown code stays "network": adding a new wire error enum would also
    # require upstream remote schemas and locale dictionaries to change.
    patch(before, after)
    patch('\tdetailsLifetime = new AbortController();',
          '\tdetailsLifetime = new AbortController();\n'
          '\t// DSHA_ACCOUNT_PROFILE_SINGLE_FLIGHT_V1: pending reads only; no settled cache.\n'
          '\tprofileReads = /* @__PURE__ */ new Map();')
    start = source.index('\tasync getDetail(field, headers, captured) {')
    end = source.index('\n\t/** Deployment account headers', start)
    before = source[start:end]
    body_start = before.index('\t\ttry {')
    body = before[body_start:before.rindex('\n\t}')]
    after = before[:body_start] + '''\t\t// Include every wire header, not just locale: two deployments/callers
\t\t// with different effective cookies or identity must not share a response.
\t\tconst key = field === "profile" ? JSON.stringify([stored.issuer, stored.token, [...new Headers(headers)].sort(([a], [b]) => a.localeCompare(b))]) : null;
\t\tconst existing = key === null ? void 0 : this.profileReads.get(key);
\t\tif (existing?.lifetime === lifetime) return existing.promise;
\t\tconst read = async () => {
''' + '\n'.join('\t' + line for line in body.splitlines()) + '''
\t\t};
\t\tif (key === null) return read();
\t\tconst pending = { lifetime, promise: void 0 };
\t\tpending.promise = read().finally(() => {
\t\t\tif (this.profileReads.get(key) === pending) this.profileReads.delete(key);
\t\t});
\t\tthis.profileReads.set(key, pending);
\t\treturn pending.promise;
\t}'''
    patch(before, after)
    patch('\tinvalidateDetails() {\n\t\tthis.lastProfile = void 0;',
          '\tinvalidateDetails() {\n\t\tthis.profileReads.clear();\n\t\tthis.lastProfile = void 0;')

    client_before = 'if (value?.status === "failed") console.error("[deepseek-account] account read failed", { field });'
    if sources[CLIENT].count(client_before) != 1:
        raise ValueError('ACCOUNT_CLIENT_DIAGNOSTIC_ANCHOR_CHANGED')
    client_patches = [dict(before=client_before, after='// DSHA_ACCOUNT_CLIENT_DIAGNOSTIC_V1: fixed field survives embedded console forwarding.\n\t\t\t\t\tif (value?.status === "failed") console.error("[deepseek-account] account read failed " + JSON.stringify({ field }));')]
    results = {}
    for name, module, edits in [('deepseek-account-requests-patch.json', SERVER, patches),
                               ('deepseek-account-client-diagnostic-patch.json', CLIENT, client_patches)]:
        package_name = module.split('/lib/')[0]
        recipe = dict(version=1, dshVersion=VERSION, module=module,
                      sourceSha256=HASHES[module], packageIntegrity=lock['packages']['node_modules/' + package_name]['integrity'],
                      purpose='Coalesce only equivalent in-flight profile reads; preserve credential and wire behavior; log bounded non-secret HTTP diagnostics as one string.',
                      patches=edits)
        results[name] = json.dumps(recipe, ensure_ascii=False, indent=2) + '\n'
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--modules', type=Path)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    modules = args.modules
    if modules is None:
        pointer = json.loads((ROOT / 'app/build/test-runtimes/current.json').read_text(encoding='utf8'))
        modules = Path(pointer['raw']) / 'node_modules'
    for name, expected in generate(modules).items():
        target = ASSETS / name
        if args.check:
            if not matches_text(target, expected):
                raise ValueError('ACCOUNT_REQUEST_PATCH_STALE: ' + name)
        else:
            write_text(target, expected, encoding='utf8')
    print('PASS locked account request patches ' + ('match generated source' if args.check else 'generated'))


if __name__ == '__main__':
    main()
