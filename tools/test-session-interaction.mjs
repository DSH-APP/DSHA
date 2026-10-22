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
    vm.runInContext(readFileSync(process.env.DSHA_SESSION_SELECTION_SOURCE || assets+'web-integration/session-interaction.js','utf8')+';globalThis.create = createDshaSessionSelection;',context);
    const state = context.create(() => clock);
    return { state, advance(ms) {clock += ms}, opened: () => opened,
        down(id, type='touch', event={}, current='old') { state.pointerDown({pointerType:type,pointerId:7,button:0,clientX:10,clientY:10,...event},id,current); },
        tap(id, event = {}, current = 'old') { state.click({detail:1,clientX:10,clientY:10,...event},id,current,()=>opened++); } };
}
for (const type of ['mouse','touch','pen']) test(`${type}一次有效单击即选择并打开，不等待第二击`,()=>{
    const f=fixture(); f.down('new',type); f.advance(50); f.tap('new',{pointerType:type});
    assert.equal(f.state.selected('old'),'new'); assert.equal(f.opened(),1);
    f.down('another',type); f.tap('another',{pointerType:type}); assert.equal(f.opened(),2);
});
test('React兼容MouseEvent仍保留pointerdown的触屏来源，轻移可打开',()=>{
    const f=fixture(); f.down('new'); f.advance(80); f.tap('new',{clientX:17,clientY:16});
    assert.equal(f.opened(),1);
});
test('触屏设备外接鼠标与sourceCapabilities触屏都一次打开',()=>{
    const f=fixture(); f.down('mouse','mouse'); f.tap('mouse');
    f.tap('touch',{nativeEvent:{sourceCapabilities:{firesTouchEvents:true}}});
    assert.equal(f.opened(),2);
});
test('已选会话单击仍派发打开事实，让抽屉正常关闭',()=>{
    const f=fixture(); f.down('old'); f.tap('old',{pointerType:'touch'});
    assert.equal(f.state.selected('old'),'old'); assert.equal(f.opened(),1);
});
test('滚动、拖动后即使回到原点也不导航，下一次独立轻触可恢复',()=>{
    const f=fixture(); f.down('new');
    f.state.pointerMove({pointerId:7,clientX:10,clientY:40},'new','old');
    f.tap('new',{pointerType:'touch'}); assert.equal(f.opened(),0);
    f.down('new'); f.tap('new',{pointerType:'touch'}); assert.equal(f.opened(),1);
});
test('pointercancel和长按不会转为会话打开，键盘激活可继续',()=>{
    const f=fixture(); f.down('new');
    f.state.pointerCancel({pointerId:7},'new','old'); f.tap('new',{pointerType:'touch'});
    f.down('long','pen'); f.advance(650); f.tap('long',{pointerType:'pen'});
    f.down('long-touch'); f.advance(2000); f.tap('long-touch');
    assert.equal(f.opened(),0);
    f.tap('key',{detail:0}); assert.equal(f.opened(),1);
});
test('不匹配的pointer取消不能取消另一指针，超出点击位移拒绝',()=>{
    const f=fixture(); f.down('a'); f.state.pointerCancel({pointerId:99},'a','old');
    f.tap('a',{pointerType:'touch'}); assert.equal(f.opened(),1);
    f.down('b'); f.tap('b',{pointerType:'touch',clientX:100}); assert.equal(f.opened(),1);
});
test('键盘和辅助功能显式激活直接打开；取消和右键不导航',()=>{
    const f=fixture(); f.tap('a',{detail:0}); assert.equal(f.opened(),1);
    f.tap('a',{defaultPrevented:true}); f.tap('a',{button:2}); assert.equal(f.opened(),1);
});
test('Enter和Space仅行自身单次按键激活，不冒充实际指针事件',()=>{
    assert.match(source,/if \(!event\.repeat\) dshaSessionClick\(\{detail:0\}, result\.id/);
    assert.match(source,/else if \(event\.key === ' '\)[\s\S]*?if \(!event\.repeat\) dshaSessionClick/);
    assert.match(source,/if \(event\.target !== event\.currentTarget\) return/);
    assert.match(source,/onClick: \(event\) => dshaSessionClick\(event,/);
    assert.doesNotMatch(source,/Touch or pen: tap to select, double-tap to open/);
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
