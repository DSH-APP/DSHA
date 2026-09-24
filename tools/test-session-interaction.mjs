import { testRuntime } from './test-runtime-fixture.mjs';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';
const assets = 'app/src/main/assets/';
const runtime = testRuntime('raw');
const original = readFileSync(runtime + '/node_modules/@deepseek-ai/dsh-client-ui-workspace/lib/client.js', 'utf8');
const policy = JSON.parse(readFileSync(assets + 'session-interaction-patch.json', 'utf8'));
let source = original;
for (const patch of policy.patches) {
    const after = (patch.prependAsset ? readFileSync(assets + patch.prependAsset, 'utf8') + '\n' : '') + patch.after;
    assert.equal(source.split(patch.before).length - 1, 1);
    source = source.replace(patch.before, after);
}
new vm.Script(source);
for (const patch of policy.patches) {
    const after = (patch.prependAsset ? readFileSync(assets + patch.prependAsset, 'utf8') + '\n' : '') + patch.after;
    assert.equal(source.split(after).length - 1, 1);
    assert.equal(source.split(patch.before).length - 1, after.split(patch.before).length - 1);
}
function fixture() {
    let clock = 100, opened = 0;
    const context = vm.createContext({});
    vm.runInContext(readFileSync(assets+'web-integration/session-interaction.js','utf8')+';globalThis.create = createDshaSessionSelection;',context);
    const state = context.create(() => clock);
    return { state, advance(ms) {clock += ms}, opened: () => opened,
        tap(id, event = {}, current = 'old') { state.click({detail:1,clientX:10,clientY:10,...event},id,current,()=>opened++); } };
}
test('单击选择、同一会话双击打开，第三次点击不会再打开',()=>{
    const f=fixture(); f.tap('new'); assert.equal(f.state.selected('old'),'new'); assert.equal(f.opened(),0);
    f.advance(220); f.tap('new'); assert.equal(f.opened(),1);
    f.advance(50); f.tap('new'); assert.equal(f.opened(),1);
});
test('不同会话、超时和拖动距离不误作双击',()=>{
    const f=fixture(); f.tap('a'); f.advance(100); f.tap('b'); assert.equal(f.opened(),0);
    f.advance(501); f.tap('b'); assert.equal(f.opened(),0);
    f.advance(100); f.tap('b',{clientY:100}); assert.equal(f.opened(),0);
});
test('键盘和辅助功能显式激活直接打开；取消和右键不导航',()=>{
    const f=fixture(); f.tap('a',{detail:0}); assert.equal(f.opened(),1);
    f.tap('a',{defaultPrevented:true}); f.tap('a',{button:2}); assert.equal(f.opened(),1);
});
test('实际导航改变后不会沿用旧高亮，订阅可清理',()=>{
    const f=fixture(); let notices=0; const stop=f.state.subscribe(()=>notices++);
    f.tap('new'); assert.equal(notices,1); assert.equal(f.state.selected('external'),'external');
    stop(); f.state.select('other','old'); assert.equal(notices,1);
});
test('长列表单击只通知旧高亮和新高亮行',()=>{
    const f=fixture(); let notices=0;
    const rows=Array.from({length:1000},(_,i)=>'row-'+i);
    const stops=rows.map(id=>f.state.subscribe(()=>notices++,'old',id));
    f.state.select('row-777','old');
    assert.equal(notices,1);
    f.state.select('row-888','old');
    assert.equal(notices,3);
    for(const stop of stops)stop();
});
test('移动抽屉不会把单击选中误判为已打开',()=>{
    const mobile=readFileSync(assets+'builtin-plugins/dsh-web-mobile/lib/client.js','utf8');
    assert.ok(mobile.includes("target.closest('[data-dsha-session-select]') !== null"));
    assert.ok(mobile.includes("dsha-session-open"));
    assert.ok(mobile.includes('onDshaSessionOpen') || mobile.includes('onSessionOpened'));
});
test('打开会话的效果不再主动 focus，显式编辑路径仍存在',()=>{
    const patches=JSON.parse(readFileSync(assets+'composer-enter-patch.json','utf8')).patches;
    const focus=patches.find(p=>p.after.includes('DSHA_COMPOSER_EXPLICIT_FOCUS_V1'));
    assert.ok(focus); assert.ok(!focus.after.includes('.focus('));
    const client=readFileSync(runtime+'/node_modules/@deepseek-ai/dsh-client-ui-conversation/lib/client.js','utf8');
    const anchors=client.split(focus.before).length-1;
    assert.ok(anchors===1 || (anchors===0 && client.includes(focus.after)));
    const patched=anchors===1?client.replace(focus.before,focus.after):client;
    assert.ok(patched.includes('editor?.getRootElement()?.focus({ preventScroll: true });'));
});
