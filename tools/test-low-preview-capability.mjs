import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';

// Evaluate the exact Java-embedded probe. A malformed probe must not silently
// route an unsupported WebView to the normal page path.
const source=readFileSync('app/src/main/java/com/deepseekharness/app/ui/WebPreviewActivity.java','utf8');
const expression=source.match(/CAPABILITY_CHECK\s*=([\s\S]*?);\s*\n\s*private /)?.[1];
assert.ok(expression,'WebView capability probe missing');
const pieces=[...expression.matchAll(/"(?:\\.|[^"\\])*"/g)].map(match=>JSON.parse(match[0]));
const probe=pieces.join('');
assert.ok(probe.startsWith('(function()'),'unexpected probe body');

function run(changes={}) {
  const page={
    document:{createElement:()=>({noModule:true})},
    Promise:{withResolvers(){}},fetch(){},WebSocket:function(){},
    TextEncoder:function(){},ReadableStream:function(){},AbortController:function(){},
    AbortSignal:{any(){},timeout(){}},
    Iterator:{from(){},prototype:{filter(){}}},
  };
  Object.assign(page,changes);page.window=page;
  return vm.runInNewContext(probe,page);
}
assert.equal(run(),'');
assert.match(run({Promise:undefined}),/Promise/);
assert.match(run({AbortSignal:undefined}),/AbortSignal/);
assert.match(run({Iterator:undefined}),/Iterator/);
assert.match(run({document:{createElement:()=>({})}}),/JavaScript modules/);

const low=readFileSync('app/src/low/java/com/deepseekharness/app/ui/PreviewFallback.java','utf8');
assert.doesNotMatch(low,/Chrome\/|WebSettings\.getDefaultUserAgent|<\s*118/);
assert.match(low,/getCurrentWebViewPackage\(\)/);
console.log('Low preview: actual capability probe handles missing APIs; selection has no UA threshold.');
