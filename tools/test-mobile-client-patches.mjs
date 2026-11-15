// 源漂移必须拒绝；正式产物只能来自固定预构建 client 与明确 DSHA 差异（build336 C 基线）。
import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {applyMobileClientPatches,upstreamCommit,upstreamClientHash} from './apply-mobile-client-patches.mjs';
const asset='app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js';
const upstreamSource=()=>readFileSync('tools/mobile-plugin-upstream/lib/client.js','utf8');
test('移动 client 固定预构建来源与部署产物闭环，拒绝错误版本/追加字节',()=>{
  const source=upstreamSource();
  assert.equal(createHash('sha256').update(source).digest('hex'),upstreamClientHash);
  assert.equal(upstreamCommit,'646fe837440f9939228bdba0024d407eaac01128');
  assert.equal(readFileSync(asset,'utf8'),applyMobileClientPatches(source));
  assert.throws(()=>applyMobileClientPatches(Buffer.concat([Buffer.from(source),Buffer.from('\n')])),/字节不匹配/);
});
test('C 基线只保留齿轮几何让位，上游 3.3.5 的旧 :has 连接条判据被替换',()=>{
  const patched=applyMobileClientPatches(upstreamSource());
  assert.ok(patched.includes('DSHA_GEAR_FIT_V1'));
  assert.ok(patched.includes('data-mobile-nav-quick-fit="0"'));
  assert.match(patched,/const keep = trigger !== null && trigger\.getAttribute\('aria-expanded'\) === 'true' && gearVisible;/);
  assert.equal(patched.includes('triggerRow"]:has('),false,'旧连接条 :has 守卫不得残留');
});
test('C 基线删除的旧 UI/行为补丁不得重放，上游自己的流程保持原字节',()=>{
  const patched=applyMobileClientPatches(upstreamSource());
  for(const stale of ['DSHA_SESSION_INPUT_V4','DSHA_FONT_FAMILY_ROW_V1','DSHA_WIDE_TOUCH_ROW_ACTIONS_V1',
    'DSHA_BACKDROP_PRESS_V1','var(--dsha-mobile-visible-vh, 100vh)'])
    assert.equal(patched.includes(stale),false,stale);
  // 手机搜索仍按上游策略隐藏；会话打开仍走上游自己的 DSHA synthetic detail=0 流程。
  assert.ok(patched.includes('[class*="_searchRow"]'));
  assert.ok(patched.includes("// DSHA detail=0 follows the host's keyboard/programmatic open path."));
});
test('安全区变量只包一层，不生成嵌套 var',()=>{
  const patched=applyMobileClientPatches(upstreamSource());
  assert.ok(patched.includes('var(--dsha-safe-area-inset-top, env(safe-area-inset-top, 0px))'));
  assert.equal(patched.includes('var(--dsha-safe-area-inset-top, var(--dsha-safe-area-inset-top'),false);
});
test('文件传输与多语言补丁仍在，部署产物逐字节一致',()=>{
  const patched=applyMobileClientPatches(upstreamSource());
  assert.ok(patched.includes('mobileNavLocales'));
  assert.ok(patched.includes('DSHA_MOBILE_LOCALES_V1'));
  assert.ok(patched.includes('DSHAFileTransfer') || patched.includes('FileDeliveryActions'));
  assert.equal(readFileSync(asset,'utf8'),patched);
});
test('3.3.5 上游视口函数保持原字节，不复活已撤回的帧合并试验',()=>{
  const source=upstreamSource();
  const patched=applyMobileClientPatches(Buffer.from(source));
  const viewport=text=>text.slice(text.indexOf('function syncStableViewportHeight() {'),text.indexOf('/** iOS-only: mark documentElement'));
  assert.ok(viewport(source).length>500);
  assert.equal(viewport(patched),viewport(source));
  assert.ok(patched.includes('interactive-widget=resizes-content'));
});
test('3.3.5 已接管的宽屏触屏档不重放，规则与手机壳名单一致',()=>{
  const source=upstreamSource();
  const patched=applyMobileClientPatches(Buffer.from(source));
  assert.equal(patched.includes('DSHA_WIDE_TOUCH_ROW_ACTIONS_V1'),false,'上游已接管，不得重放旧补丁');
  const open='@media (min-width: 1024px) and (pointer: coarse) {';
  const at=patched.indexOf(open);
  assert.ok(at>=0,'未找到宽屏触屏档');
  let depth=0,end=-1;
  for(let i=patched.indexOf('{',at);i<patched.length;i++){
    if(patched[i]==='{')depth++;
    else if(patched[i]==='}'){depth--;if(depth===0){end=i;break;}}
  }
  assert.ok(end>at,'宽屏触屏档大括号不配平');
  const tablet=patched.slice(at,end+1);
  const hide=/\[class\*="sessionRow"\] \[class\*="_rowActions"\] :is\(([\s\S]*?)\) \{([\s\S]*?)\}/.exec(tablet);
  assert.ok(hide,'宽屏触屏档没有隐藏归档/置顶的规则');
  assert.match(hide[2],/display:\s*none !important/);
  const labels=text=>[...text.matchAll(/aria-label="([^"]+)"/g)].map(m=>m[1]).sort();
  const phone=/\[data-mobile-nav="frame"\] \[class\*="sessionRow"\] \[class\*="_rowActions"\] :is\(([\s\S]*?)\) \{/.exec(source);
  assert.ok(phone,'手机壳 #154 规则缺失');
  assert.deepEqual(labels(hide[1]),labels(phone[1]),'宽屏触屏档名单必须与手机壳一致');
  assert.equal(labels(hide[1]).length,8);
});
