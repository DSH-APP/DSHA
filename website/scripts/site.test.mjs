import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {sourceIdentity, validateReleaseIdentity} from './release-input.mjs';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../dist');
const read=filename=>fs.readFileSync(path.join(root,filename),'utf8');
const catalog=JSON.parse(read('api/catalog.json')),release=JSON.parse(read('api/releases.json'));
const html=[];
function scan(dir){for(const e of fs.readdirSync(dir,{withFileTypes:true})){const p=path.join(dir,e.name);if(e.isDirectory())scan(p);else if(e.name.endsWith('.html'))html.push(p);}}
scan(root);
const decode=value=>value.replaceAll('&amp;','&').replaceAll('&quot;','"').replaceAll('&#39;',"'");
test('发布脚本可被 JavaScript 引擎解析',()=>{for(const file of fs.readdirSync(path.join(root,'assets')).filter(f=>f.endsWith('.js')))execFileSync(process.execPath,['--check',path.join(root,'assets',file)],{windowsHide:true});});
test('目录版本与当前 APK 发布清单一致',()=>{assert.equal(catalog.schemaVersion,1);assert.equal(catalog.dsha,release.version);assert.equal(catalog.dsh,release.dshVersion);assert.ok(release.versionCode>0);assert.equal(release.channel,'stable');});
test('源码、受管描述符与实际APK清单有相同身份',()=>{
  const repository=path.resolve(process.env.DSHA_SOURCE_ROOT||path.resolve(root,'../..'));
  const current=JSON.parse(read('api/updates.json')).releases[0];
  const source=sourceIdentity(repository);
  assert.deepEqual(release.sourceIdentity,source);
  validateReleaseIdentity(current,source);
  for(const key of ['version','versionCode','dshVersion','runtimeId']){
    assert.throws(()=>validateReleaseIdentity({...current,[key]:key==='versionCode'?current.versionCode-1:'stale'},source),/不匹配/);
  }
});
test('所有页面提供中文标题、单一主标题和静态主要内容',()=>{assert.ok(html.length>=12);for(const file of html){const body=fs.readFileSync(file,'utf8');assert.match(body,/<html lang="zh-CN"/);assert.equal((body.match(/<h1\b/g)||[]).length,1,file);assert.match(body,/<title>[^<]+<\/title>/);assert.match(body,/<main[^>]+id="main"/);assert.match(body,/<link rel="canonical" href="https:\/\/dsha\.cc/);}});
test('全部同站链接、脚本、样式和锚点都指向实际产物',()=>{for(const file of html){const body=fs.readFileSync(file,'utf8');for(const match of body.matchAll(/\b(?:href|src)="([^"]+)"/g)){const value=decode(match[1]);if(/^(https?:|mailto:|data:)/.test(value))continue;const url=new URL(value,'https://dsha.cc/'+path.relative(root,file).replaceAll('\\','/'));let target=path.join(root,decodeURIComponent(url.pathname));if(fs.existsSync(target)&&fs.statSync(target).isDirectory())target=path.join(target,'index.html');assert.ok(fs.existsSync(target),`${file}: ${value}`);if(url.hash&&target.endsWith('.html'))assert.ok(fs.readFileSync(target,'utf8').includes(`id="${url.hash.slice(1)}"`),`失效锚点 ${value}`);}}});
test('目录和详情静态可读，内置能力没有伪安装按钮',()=>{const home=read('index.html');for(const e of catalog.entries){assert.ok(home.includes(`data-entry="${e.id}"`));const detail=read(`plugins/${e.id}/index.html`);assert.ok(detail.includes(e.name));assert.ok(detail.includes('权限与数据'));if(e.kind==='builtin'){assert.ok(detail.includes('在 App 中管理'));assert.ok(!detail.includes('data-copy-target="install-url"'));}assert.ok(!detail.includes('data-demo-install'));}assert.ok(!home.includes('已加入工作区'));});
test('APK真实存在，大小、sidecar和发布清单一致',()=>{assert.equal(release.releases.length,2);for(const r of release.releases){const file=path.join(root,r.url);assert.equal(fs.statSync(file).size,r.bytes);assert.match(r.sha256,/^[0-9a-f]{64}$/);assert.equal(read(r.url+'.sha256').split(/\s+/)[0],r.sha256);assert.ok(read('download/index.html').includes(r.sha256));assert.equal(r.architecture,'arm64-v8a');}});
test('技能下载摘要正确且归档包含当前适配文档和许可证',()=>{for(const e of catalog.entries.filter(e=>e.kind==='skill')){const file=path.join(root,e.download.url);const actual=createHash('sha256').update(fs.readFileSync(file)).digest('hex');assert.equal(actual,e.download.sha256);const names=execFileSync('tar',['-tzf',file],{encoding:'utf8',windowsHide:true});assert.ok(names.includes(`${e.id}/SKILL.md`));assert.ok(names.includes(`${e.id}/LICENSE`));const skill=read(`downloads/skills/${e.id}/SKILL.md`);assert.ok(skill.includes('/root/dsh-bin/adb-shell'));assert.ok(!/^\s*(?:\$\s*)?termux-dialog\b/m.test(skill));assert.ok(!skill.includes('DSH_INTERNAL=1'));assert.match(skill,/^---\nname: /);}});
test('详情分享具有持久可见回退字段',()=>{for(const e of catalog.entries){const content=read(`plugins/${e.id}/index.html`);assert.ok(content.includes('id="share-url"'));assert.ok(content.includes(`value="https://dsha.cc/plugins/${e.id}/"`));}});
test('投稿在主脚本不可用时仍有 GitHub 入口',()=>{const content=read('submit/index.html').replace(/<noscript>[\s\S]*?<\/noscript>/g,'');assert.ok(content.includes('直接前往 GitHub 投稿'));assert.ok(!/<form[^>]+action="https?:/.test(content));assert.ok(!content.includes('type="password"'));});
test('页面无需第三方字体或运行脚本，没有部署凭据与旧演示存储',()=>{for(const file of html){const content=fs.readFileSync(file,'utf8');assert.ok(!/<script[^>]+src="https?:/.test(content));assert.ok(!content.includes('fonts.googleapis.com'));assert.ok(!content.includes('dsha-demo-installed'));assert.ok(!/\b(?:ssh-rsa|BEGIN PRIVATE KEY|BEGIN OPENSSH PRIVATE KEY)\b/.test(content));assert.ok(!/<script(?:\s[^>]*)?>\s*[^<\s]/.test(content));assert.ok(!/\sstyle=/.test(content));}});
test('兼容性说明区分发布通道、APK 最低系统与运行测试',()=>{const content=read('download/index.html');assert.ok(content.includes('Android 11+'));assert.ok(content.includes('Android 6+'));assert.ok(content.includes('不代表每个系统版本和设备能力都已完成真机验收'));assert.ok(content.includes(release.version+(release.channel==='stable'?' 正式版':' 预览版')));assert.ok(!content.includes(release.version+' 预发布'));});

test('社区历史记录、当前技能文档与内置内容核对有不同范围',()=>{
  for(const e of catalog.entries.filter(e=>e.kind==='plugin'&&!['dsh-batch-tool-calls','dsh-any-background','dsh-peak-chip'].includes(e.id))){
    assert.equal(e.dsh,'unknown');
    assert.notEqual(e.compatibleDsha,release.version);
  }
  const repository=path.resolve(process.env.DSHA_SOURCE_ROOT||path.resolve(root,'../..'));
  for(const e of catalog.entries.filter(e=>e.kind==='skill')){
    assert.equal(e.dsh,'unknown');
    assert.equal(e.compatibleDsha,'当前设备未复验');
    assert.equal(e.historicalVerification.testedDsh,'0.1.2-rc.1');
    assert.match(e.verification,/历史|旧 rc1.1/);
    assert.ok(e.requirements.some(value=>/授权|通道/.test(value)));
    assert.equal(read(`downloads/skills/${e.id}/SKILL.md`),fs.readFileSync(path.join(repository,'agent-skills',e.id,'SKILL.md'),'utf8'));
    assert.ok(!e.download.url.includes('rc1.1'));
    assert.ok(!read(`plugins/${e.id}/index.html`).includes('DSHA rc1.1 适配版'));
  }
  for(const e of catalog.entries.filter(e=>e.kind==='builtin')){
    const folder=e.packageName==='dsh-app-integration'?'app-integration':`builtin-plugins/${e.packageName}`;
    const pkg=JSON.parse(fs.readFileSync(path.join(repository,'app/src/main/assets',folder,'package.json'),'utf8'));
    assert.equal(e.version,pkg.version);
    assert.equal(e.compatibleDsha,release.version);
    assert.equal(e.dsh,catalog.dsh);
    assert.equal(e.verificationKind,'signed-apk-content');
  }
});
test('搜索分类 URL 允许值均有对应选项',()=>{const content=read('index.html');for(const value of ['all','builtin','skill','plugin','device','workflow'])assert.ok(content.includes(`value="${value}"`));});
test('更新接口、官网和域名关联均来自已核验 APK',()=>{const feed=JSON.parse(read('api/updates.json'));assert.equal(feed.packageName,'com.dsh.client');assert.ok(feed.releases.length>=1 && feed.releases.length<=2);assert.equal(new Set(feed.releases.map(r=>r.channel)).size,feed.releases.length);const current=feed.releases[0];assert.equal(current.versionCode,release.versionCode);assert.equal(current.version,release.version);assert.equal(current.channel,release.channel);for(const a of current.artifacts){const shown=release.releases.find(r=>r.flavor===a.flavor);assert.equal(new URL(a.url).pathname,shown.url);for(const key of ['bytes','sha256','minSdk','abi','versionName'])assert.equal(a[key],shown[key]);}const association=JSON.parse(read('.well-known/assetlinks.json'))[0];assert.equal(association.target.package_name,feed.packageName);assert.equal(association.target.sha256_cert_fingerprints[0].replaceAll(':','').toLowerCase(),feed.certificateSha256);assert.equal(feed.certificateSha256,'e7e3a31a75946f2669194c972b3dd0c9aea3fc7c50a8b885d2dee710b22a53f5');});
test('插件安装入口提供实际唤起页和未安装 APK 回退',()=>{const body=read('install/index.html');assert.ok(body.includes('data-install-page'));assert.ok(body.includes('data-open-app'));assert.ok(body.includes('href="/download/"'));for(const entry of catalog.entries.filter(e=>e.kind==='builtin')){assert.ok(read(`plugins/${entry.id}/index.html`).includes('/install/?builtin='+encodeURIComponent(entry.packageName)));}assert.ok(read('index.html').includes('href="/install/"'));});
test('指南显示当前版本与已支持的唤起行为，历史实测信息单独保留',()=>{const guide=read('guide/index.html');assert.ok(guide.includes('下载 '+release.version));assert.ok(guide.includes('网站可以唤起 DSHA'));assert.ok(!guide.includes('没有网站唤起安装的深链入口'));assert.ok(!guide.includes('rc1.1 包含'));assert.ok(read('plugins/device-shell/index.html').includes('已核对 DSHA'));assert.ok(read('assets/qrcode-LICENSE.txt').includes('Permission is hereby granted'));});

test('新增社区插件提供固定版本、真实摘要及独立测试范围',()=>{for(const id of ['dsh-batch-tool-calls','dsh-any-background']){const entry=catalog.entries.find(e=>e.id===id);assert.ok(entry);assert.ok(entry.download.url.includes(entry.version));assert.match(entry.download.sha256,/^[a-f0-9]{64}$/);assert.ok(entry.verification.includes('隔离'));assert.ok(read(`plugins/${id}/index.html`).includes(entry.download.sha256));assert.notEqual(entry.compatibleDsha,release.version);}});


test('三个收录插件的站内下载保持作者原包字节与来源',()=>{
  const expected={
    'dsh-batch-tool-calls':['1.0.0','da52f28308abed7faae3d4ba69f2bf44cf58f5595a1e5167681d45d2cbccadc4',71],
    'dsh-any-background':['0.2.8','a90f02cbebfdae0c6fff428038df9096f0d6ad3bc94538c4266eb7bd700d7a7e',72],
    'dsh-peak-chip':['4.1.8','437d649065fd1f47d334981ad6587425c21607c49f0d1620a5c57be05875d406',78]
  };
  for(const [id,[version,sha,issue]] of Object.entries(expected)){
    const entry=catalog.entries.find(e=>e.id===id);assert.ok(entry);assert.equal(entry.version,version);
    const url=new URL(entry.download.url);assert.equal(url.origin,'https://dsha.cc');
    assert.equal(url.pathname,`/downloads/plugins/${id}-${version}.tgz`);
    const bytes=fs.readFileSync(path.join(root,url.pathname));
    assert.equal(createHash('sha256').update(bytes).digest('hex'),sha);assert.equal(bytes.length,entry.download.bytes);
    assert.equal(read(url.pathname+'.sha256').split(/\s+/)[0],sha);
    assert.equal(entry.issue,`https://github.com/DSH-APP/DSHA/issues/${issue}`);
    const detail=read(`plugins/${id}/index.html`);
    for(const value of [entry.issue,entry.download.upstreamUrl,entry.download.url+'.sha256'])assert.ok(detail.includes(value));
  }
});

test('峰谷插件保留作者测试与敏感接触面的真实边界',()=>{
  const entry=catalog.entries.find(e=>e.id==='dsh-peak-chip');
  assert.equal(entry.testedByAuthor,true);assert.equal(entry.dsh,'0.1.5-rc.2');assert.notEqual(entry.compatibleDsha,release.version);
  const detail=read('plugins/dsh-peak-chip/index.html');
  for(const value of ['作者测试 DSHA','api.deepseek.com','API Key','.bridge_token','原生静态审阅','未执行第三方代码'])assert.ok(detail.includes(value),value);
});
