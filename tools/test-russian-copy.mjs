// 验证签名显示补丁；保留技术标识与用户原文。
import fs from 'node:fs';import vm from 'node:vm';import assert from 'node:assert/strict';
const A='app/src/main/assets/',N=(process.env.DSHA_TEST_RUNTIME||'app/build/locked-dsh-runtime')+'/node_modules/';
const recipe=JSON.parse(fs.readFileSync(A+'russian-plugin-ui-patch.json','utf8'));
const helper=recipe.patches[0].after.split('\nfunction packageText')[0];
const ctx=vm.createContext({});vm.runInContext(helper,ctx);
for(const id of ['dsh-device-shell-guide','dsh-status-overlay','dsh-task-notifier']){
 ctx.id=id;assert.match(vm.runInContext('dshaBuiltinDescription(id,undefined,v=>v.ru)',ctx),/[а-я]/i);assert.ok(vm.runInContext('dshaBuiltinDescription(id,undefined,v=>v.en)',ctx));
}
assert.equal(vm.runInContext('dshaBuiltinDescription("user-plugin",undefined,v=>v.ru)',ctx),undefined);
assert.equal(vm.runInContext('dshaBuiltinDescription("user-plugin","user content 中文",v=>v)',ctx),'user content 中文');
const follow=JSON.parse(fs.readFileSync('tools/i18n/russian-followup-patches.json','utf8'));
for(const r of follow){let text=fs.readFileSync(N+r.module,'utf8');for(const p of r.patches){assert.equal(text.split(p.before).length,2,r.asset);text=text.replace(p.before,()=>p.after);}if(!r.module.includes('/dist/'))new vm.Script(text);}
const boot=JSON.parse(fs.readFileSync(A+'russian-loading-copy-patch.json','utf8'));
for(const lang of ['ru','ru-RU','en','zh'])for(const p of boot.patches){const value=vm.runInNewContext(p.after,{__DSHA_LANGUAGE__:lang,document:{documentElement:{lang}}});if(lang.startsWith('ru'))assert.match(value,/[а-я]/i);else assert.equal(value,JSON.parse(p.before));}
const ru=JSON.parse(fs.readFileSync('tools/i18n/russian-followup-patches.json')).find(r=>r.asset==='russian-permission-copy-patch.json');const a=vm.runInNewContext('('+ru.patches[0].after+')');assert.equal(a['auto.label'],'Автопроверка');assert.equal(a['auto.confirm.enable'],'Включить автопроверку');assert.equal(a['preset.fullAccess'],'Полный доступ');assert.match(a['auto.confirm.description'],/без песочницы/);
console.log('PASS missing plugin metadata, unknown/user text, all web recipe syntax, loading locale and permission copy');
