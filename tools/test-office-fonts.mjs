import { testRuntime } from './test-runtime-fixture.mjs';
import fs from 'node:fs';import path from 'node:path';import vm from 'node:vm';import assert from 'node:assert/strict';
const runtime=testRuntime('managed');
const source=fs.readFileSync(path.join(runtime,'node_modules/@deepseek-ai/libreoffice-kit/lib/index.js'),'utf8');
const start=source.indexOf('function systemFontDirectories('),end=source.indexOf('function mobileAssetFontDirectories(',start);
assert.ok(start>=0&&end>start);
const resolve=vm.runInNewContext(source.slice(start,end)+'\nsystemFontDirectories',{
 posix:path.posix,win32:path.win32,homedir:()=>'/home/fixture',process:{platform:'linux',env:{}},mobileAssetFontDirectories:()=>[],Set});
assert.ok(!resolve('linux','/home/fixture',{}).includes('/system/fonts'));
const dirs=resolve('linux','/home/fixture',{DSHA_ANDROID_RUNTIME:'1'});
assert.ok(dirs.includes('/system/fonts'));assert.ok(dirs.includes('/usr/share/fonts'));assert.ok(dirs.includes('/home/fixture/.fonts'));
assert.equal(resolve('linux','/home/fixture',{DSHA_ANDROID_RUNTIME:'1',XDG_DATA_DIRS:'/system:/usr/share'}).filter(p=>p==='/system/fonts').length,1);
assert.ok(!resolve('win32','C:\\Users\\fixture',{DSHA_ANDROID_RUNTIME:'1',SystemRoot:'C:\\Windows'}).includes('/system/fonts'));
console.log('PASS: Android reuses existing system fonts; Linux/Windows defaults, user locations and deduplication preserved.');
