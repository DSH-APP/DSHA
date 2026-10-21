// 源漂移必须拒绝；正式产物只能来自固定预构建client与明确DSHA差异。
import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {applyMobileClientPatches,upstreamCommit,upstreamClientHash} from './apply-mobile-client-patches.mjs';
test('移动client固定预构建来源与部署产物闭环，拒绝错误版本/追加字节',()=>{
  const source=readFileSync('tools/mobile-plugin-upstream/lib/client.js');
  assert.equal(createHash('sha256').update(source).digest('hex'),upstreamClientHash);
  assert.equal(upstreamCommit,'10e58ce0c4ed424c7fbd9c91d48db06c6061b4e4');
  assert.equal(readFileSync('app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js','utf8'),applyMobileClientPatches(source));
  assert.throws(()=>applyMobileClientPatches(Buffer.concat([source,Buffer.from('\n')])),/字节不匹配/);
});
test('3.2.1 最新上游视口函数保持原字节，不复活已撤回的帧合并试验',()=>{
  const source=readFileSync('tools/mobile-plugin-upstream/lib/client.js','utf8');
  const patched=applyMobileClientPatches(Buffer.from(source));
  const viewport=text=>text.slice(text.indexOf('function syncStableViewportHeight() {'),text.indexOf('/** iOS-only: mark documentElement'));
  assert.ok(viewport(source).length>500);
  assert.equal(viewport(patched),viewport(source));
  assert.ok(patched.includes('interactive-widget=resizes-content'));
});
