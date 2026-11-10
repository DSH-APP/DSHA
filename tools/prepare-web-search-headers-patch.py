#!/usr/bin/env python3
"""Generate the narrow, hash-locked 0.2.2-rc.1 web-search header/preflight recipe."""
import argparse
import hashlib
import json
from pathlib import Path
from source_text import matches_text, write_text

ROOT = Path(__file__).resolve().parents[1]
VERSION = '0.2.2-rc.1'
MODULE = '@deepseek-ai/dsh-web-search-deepseek/lib/index.js'
SOURCE_SHA = '6bde60f4f8a8bf807d588ae3630d7374358a417b241887716fda9deb67b79ea4'
ASSET = 'deepseek-web-search-headers-patch.json'

HELPERS = r'''// DSHA_WEB_SEARCH_HEADERS_V1: validate without normalizing or disclosing a credential.
function dshaSearchPreflightError(field, source, category, index = null, codePoint = null) {
	const diagnostic = {
		phase: "local-preflight",
		dispatched: false,
		field,
		source,
		category,
		index,
		codePoint,
		codePointHex: codePoint === null ? null : "U+" + codePoint.toString(16).toUpperCase().padStart(4, "0")
	};
	// Only fixed enums, field names and one offending character's numeric position.
	// No value, credential reference, endpoint, exception or filesystem path is logged.
	console.error("[deepseek-web-search] preflight rejected " + JSON.stringify(diagnostic));
	return new WebError("DeepSeek web search stopped before HTTP dispatch; no request was sent. " + JSON.stringify(diagnostic) + ". Review the indicated credential/configuration source locally. Credentials were not changed and no alternate account or key was tried.", "WEB_PROVIDER_ERROR");
}
function dshaCredentialOrigin(source) {
	switch (source) {
		case "file": return "credential-store";
		case "env":
		case "process": return "process-env";
		case "project-env": return "project-env";
		case "user-env": return "user-env";
		default: return "credential-service";
	}
}
function dshaSearchHeader(value, field, source, format = "field-value") {
	if (typeof value !== "string") throw dshaSearchPreflightError(field, source, "invalid-type");
	if (value.length === 0) throw dshaSearchPreflightError(field, source, "empty-value");
	for (let index = 0; index < value.length;) {
		const point = value.codePointAt(index);
		if (point > 255) throw dshaSearchPreflightError(field, source, "non-bytestring", index, point);
		if (point < 32 || point === 127 || point >= 128 && point <= 159) throw dshaSearchPreflightError(field, source, "control-character", index, point);
		// Tokens must be visible ASCII. Never trim, strip, re-encode or fall back.
		if (format === "credential" && (point === 32 || point > 126)) throw dshaSearchPreflightError(field, source, "credential-format", index, point);
		index += point > 65535 ? 2 : 1;
	}
	if (format === "api-version" && !/^\d{4}-\d{2}-\d{2}$/.test(value)) {
		let index = 0;
		for (; index < Math.min(value.length, 10); index++) {
			if (index === 4 || index === 7 ? value[index] !== "-" : !/[0-9]/.test(value[index])) break;
		}
		throw dshaSearchPreflightError(field, source, "api-version-format", index, index < value.length ? value.codePointAt(index) : null);
	}
	return value;
}
'''


def generate(modules):
    path = Path(modules) / MODULE
    raw = path.read_bytes()
    if hashlib.sha256(raw).hexdigest() != SOURCE_SHA:
        raise ValueError('WEB_SEARCH_OFFICIAL_SOURCE_CHANGED')
    lock = json.loads((ROOT / 'tools/dsh-runtime/package-lock.json').read_text(encoding='utf8'))
    package = json.loads((path.parent.parent / 'package.json').read_text(encoding='utf8'))
    locked = lock['packages']['node_modules/' + package['name']]
    if package['version'] != VERSION or locked['version'] != VERSION:
        raise ValueError('WEB_SEARCH_PACKAGE_VERSION_CHANGED')
    source = raw.decode('utf8')
    patches = []

    def patch(before, after):
        nonlocal source
        if source.count(before) != 1 or before == after:
            raise ValueError('WEB_SEARCH_ANCHOR_CHANGED')
        patches.append(dict(before=before, after=after))
        source = source.replace(before, after)

    patch('var DeepSeekSearchProvider = class {', HELPERS + 'var DeepSeekSearchProvider = class {')
    start = source.index('\t\toptions.recordRequest?.({')
    end = source.index('\n\t\tif (!response.ok)', start)
    before = source[start:end]
    after = '''\t\t// Build Headers and Request before recording a dispatch: fetch otherwise hides
\t\t// synchronous ByteString/URL/body errors behind a rejected promise.
\t\tlet prepared;
\t\ttry {
\t\t\tconst headers = new Headers({
\t\t\t\t...auth.headers,
\t\t\t\t"anthropic-version": dshaSearchHeader(options.apiVersion, "anthropic-version", "plugin-config.apiVersion", "api-version"),
\t\t\t\t"content-type": dshaSearchHeader("application/json", "content-type", "provider-constant"),
\t\t\t\t"accept": dshaSearchHeader("application/json", "accept", "provider-constant"),
\t\t\t\t"user-agent": dshaSearchHeader(USER_AGENT, "user-agent", "provider-constant")
\t\t\t});
\t\t\tprepared = new Request(endpoint, {
\t\t\t\tmethod: "POST",
\t\t\t\tredirect: "error",
\t\t\t\theaders,
\t\t\t\tbody: JSON.stringify(body),
\t\t\t\t...signal !== void 0 ? { signal } : {}
\t\t\t});
\t\t} catch (error) {
\t\t\tif (signal?.aborted === true || isAbortError(error)) throw searchAborted(signal, error);
\t\t\tif (error instanceof WebError) throw error;
\t\t\tthrow dshaSearchPreflightError("request", "provider-config", "request-construction");
\t\t}
\t\tthrowIfSearchAborted(signal);
\t\tlet response;
\t\ttry {
\t\t\tconst pending = fetch(prepared);
\t\t\t// This event records entering fetch, not proof of delivery or a reply.
\t\t\t// A failed observer must not abandon the already-started promise.
\t\t\ttry {
\t\t\t\toptions.recordRequest?.({ endpoint, apiVersion: options.apiVersion, body, dispatchState: "fetch-invoked" });
\t\t\t} catch {
\t\t\t\tconsole.error("[deepseek-web-search] request event unavailable after fetch invocation");
\t\t\t}
\t\t\tresponse = await pending;
\t\t} catch (error) {
\t\t\tif (signal?.aborted === true || isAbortError(error)) throw searchAborted(signal, error);
\t\t\t// Exceptions may quote header values. Keep transport failure content fixed.
\t\t\tthrow new WebError("DeepSeek search fetch failed without an HTTP response; network transmission is unconfirmed. Review network availability and the configured search endpoint locally.", "WEB_PROVIDER_ERROR");
\t\t}'''
    patch(before, after)
    patch('''\t\tif (token !== void 0 && token.length > 0) return {
\t\t\tkind: "account",
\t\t\theaders: { "x-dsh-auth-token": token }
\t\t};''', '''\t\tif (token !== void 0 && token !== "") return {
\t\t\tkind: "account",
\t\t\theaders: { "x-dsh-auth-token": dshaSearchHeader(token, "x-dsh-auth-token", "deepseek-account", "credential") }
\t\t};''')
    patch('''\t\tconst apiKey = await this.apiKey(options, signal);
\t\treturn {''', '''\t\tconst apiKey = await this.apiKey(options, signal);
\t\tconst source = options.apiKey !== void 0 && options.apiKey !== "" ? "plugin-config.apiKey" : dshaCredentialOrigin(options.dshaApiKeySource?.());
\t\tdshaSearchHeader(apiKey, "x-api-key", source, "credential");
\t\treturn {''')
    patch('''\t\t\t\t"authorization": `Bearer ${apiKey}`''', '''\t\t\t\t"authorization": dshaSearchHeader(`Bearer ${apiKey}`, "authorization", source)''')
    patch('if (options.apiKey !== void 0 && options.apiKey.length > 0) return options.apiKey;', 'if (options.apiKey !== void 0 && options.apiKey !== "") return options.apiKey;')
    patch('if (resolved !== void 0 && resolved.length > 0) return resolved;', 'if (resolved !== void 0 && resolved !== "") return resolved;')
    patch('throw new WebError(`DeepSeek search credential resolution failed: ${String(error)}`, "WEB_PROVIDER_ERROR", { cause: error });',
          'throw new WebError("DeepSeek search credential resolution failed before HTTP dispatch; no request was sent. Review the credential service locally without sharing secret values.", "WEB_PROVIDER_ERROR");')
    patch('''\tconst literalApiKey = config.apiKey !== void 0 && config.apiKey.length > 0 ? config.apiKey : void 0;
\treturn {''', '''\tconst literalApiKey = config.apiKey !== void 0 && config.apiKey.length > 0 ? config.apiKey : void 0;
\t// Per-operation metadata only: the credential resolver still supplies the
\t// unchanged value once, under the original precedence and cancellation rules.
\tlet dshaApiKeySource;
\treturn {
\t\tdshaApiKeySource: () => dshaApiKeySource,''')
    patch('''\t\t\tif (credentials !== void 0) return (await credentials.resolve(apiKeyEnv))?.value;
\t\t\tconst ambient = launchEnvironmentOf(ctx).get(apiKeyEnv);
\t\t\treturn ambient !== void 0 && ambient.value.length > 0 ? ambient.value : void 0;''', '''\t\t\tif (credentials !== void 0) {
\t\t\t\tconst resolved = await credentials.resolve(apiKeyEnv);
\t\t\t\tdshaApiKeySource = resolved?.source;
\t\t\t\treturn resolved?.value;
\t\t\t}
\t\t\tconst ambient = launchEnvironmentOf(ctx).get(apiKeyEnv);
\t\t\tdshaApiKeySource = ambient?.source;
\t\t\treturn ambient !== void 0 && ambient.value.length > 0 ? ambient.value : void 0;''')
    recipe = dict(version=1, dshVersion=VERSION, module=MODULE, sourceSha256=SOURCE_SHA,
                  packageIntegrity=locked['integrity'], patchedSha256=hashlib.sha256(source.encode('utf8')).hexdigest(),
                  purpose='Validate each search header without changing credentials or precedence; report only fixed source/category and offending index/code point; distinguish local construction from fetch invocation.',
                  patches=patches)
    return json.dumps(recipe, ensure_ascii=False, indent=2) + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--modules', type=Path)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    modules = args.modules
    if modules is None:
        pointer = json.loads((ROOT / 'app/build/test-runtimes/current.json').read_text(encoding='utf8'))
        modules = Path(pointer['raw']) / 'node_modules'
    expected = generate(modules)
    target = ROOT / 'app/src/main/assets' / ASSET
    if args.check:
        if not matches_text(target, expected):
            raise ValueError('WEB_SEARCH_PATCH_STALE')
    else:
        write_text(target, expected, encoding='utf8')
    print('PASS locked web-search header patch ' + ('matches generated source' if args.check else 'generated'))


if __name__ == '__main__':
    main()
