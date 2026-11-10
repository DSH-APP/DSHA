// Execute the actual patched upstream functions; network and HTML conversion are isolated fixtures.
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
import assert from 'node:assert/strict';

const [httpPath, toolPath] = process.argv.slice(2);
const [http, tool] = await Promise.all([httpPath, toolPath].map(path => readFile(path, 'utf8')));
function extract(source, start, end) {
  const a = source.indexOf(start), b = source.indexOf(end, a);
  assert.ok(a >= 0 && b > a, `missing source section ${start}`);
  return source.slice(a, b);
}
const httpMethod = extract(http, '\tasync readBody(response, finalUrl, signal) {', '\n\t/**\n\t* Read the response stream');
const httpContext = vm.createContext({
  classifyContentType: () => 'text', parseCharset: () => 'utf-8',
  decoderForCharset: () => new TextDecoder('utf-8'),
});
const Provider = vm.runInContext(`class Provider { ${httpMethod} }; Provider`, httpContext);
async function fetchBody(text, maxBodyChars, truncatedByBytes = false) {
  const provider = new Provider();
  provider.limits = { maxBodyChars };
  provider.readCapped = async () => ({bytes: new TextEncoder().encode(text), truncatedByBytes});
  return provider.readBody({headers: new Headers({'content-type':'text/plain'}), status:200},
    new URL('https://example.test/'), new AbortController().signal);
}
const emoji = '😀';
for (const n of [1, 100000]) {
  const result = await fetchBody('x'.repeat(n - 1) + emoji + 'z', n);
  assert.equal(result.body.content, 'x'.repeat(n - 1));
  assert.equal(result.truncated, true);
  assert.ok(result.body.content.isWellFormed());
  const fits = await fetchBody('x'.repeat(n) + emoji, n + 2);
  assert.equal(fits.body.content, 'x'.repeat(n) + emoji);
  assert.equal(fits.truncated, false);
}
assert.equal((await fetchBody('ordinary', 100, true)).truncated, true);

const source = extract(tool, '// DSHA_WEB_FETCH_OUTPUT_SURROGATE_BOUNDARY_V1', '\n/**\n* Pending-call presentation:');
const metaSource = extract(tool, 'function fetchMetaFromValue(value, maxOutputChars) {', '\n/**');
const context = vm.createContext({EXTERNAL_WEB_CONTENT_NOTICE:'external',
  exceedsConversionDepth: () => false, turndown:{turndown: value => value}});
const api = vm.runInContext(`${source}\n${metaSource}\n({renderBody, renderFetchOutput, formatFetchOutput, computeFetchOutput, fetchMetaFromValue})`, context);
for (const kind of ['text', 'html']) {
  const body = api.renderBody({kind, content: 'x'.repeat(199999) + emoji + 'z'}, 200000);
  assert.equal(body.text, 'x'.repeat(199999));
  assert.equal(body.sourceTruncated, true);
  assert.ok(body.text.isWellFormed());
  assert.equal(api.renderBody({kind, content: 'x' + emoji}, 3).text, 'x' + emoji);
}
const make = (url, content) => ({url, statusCode:200, body:{kind:'text', content}, truncated:false});
// Footer branch: place the pair exactly across prefix budget (cap minus 78).
const header = `Fetched https://example.test/ (HTTP 200)\n\nexternal\n\n`;
const cap = 200000;
const result = make('https://example.test/', 'x'.repeat(cap - 78 - header.length - 1) + emoji + 'z'.repeat(100));
const rendered = api.renderFetchOutput(result, cap);
assert.ok(rendered.text.isWellFormed());
assert.ok(rendered.text.length <= cap);
assert.equal(rendered.truncated, true);
assert.ok(rendered.text.endsWith('(Content truncated. Fetch a more specific URL or section for the full text.)'));
assert.equal(api.formatFetchOutput(result, cap), rendered.text);
assert.equal(api.fetchMetaFromValue(result, cap).truncated, rendered.truncated);
// Small caps cut the complete header directly, including a surrogate pair in the URL.
const short = make('https://x/' + emoji + 'tail', 'body');
const shortCap = 'Fetched https://x/'.length + 1;
const small = api.computeFetchOutput(short, shortCap);
assert.ok(small.text.isWellFormed());
assert.ok(small.text.length <= shortCap);
assert.equal(small.text, 'Fetched https://x/');
assert.equal(small.truncated, true);
const normal = api.computeFetchOutput(make('https://example.test/', 'hi ' + emoji), 200);
assert.ok(normal.text.endsWith('hi ' + emoji));
assert.equal(normal.truncated, false);
assert.ok(JSON.stringify(rendered).isWellFormed());
console.log('PASS actual provider readBody and tool rendering boundary behavior');
