'use strict';
// 只读离线HTML，交互不连接手机/服务。与终端回归脚本使用不同结果/截图文件。
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const {chromium} = require(require.resolve('playwright', {paths:[process.env.PLAYWRIGHT_HOME || process.cwd()]}));
const root = __dirname;
const html = fs.readFileSync(path.join(root,'preview.html'),'utf8');
const result = {startedAt:new Date().toISOString(), scope:'offline source-mapped HTML only, not Android', loadMethod:'page.setContent exact HTML; no server', previewSha256:crypto.createHash('sha256').update(html).digest('hex'), checks:[], pageErrors:[], requests:[], screenshots:[]};
function check(name, value, detail='') {assert.ok(value, name+': '+detail);result.checks.push({name,passed:true,detail});}
async function closeDialog(page) {await page.locator('#app-dialog-cancel').click();await page.waitForFunction(()=>!document.querySelector('#app-dialog').open);}
async function confirm(page) {await page.locator('#app-dialog-confirm').click();await page.waitForFunction(()=>!document.querySelector('#app-dialog').open);}
async function shot(page,name) {await page.waitForFunction(()=>document.querySelector('#feedback').textContent===''); await page.screenshot({path:path.join(root,name)});result.screenshots.push(name);}
async function leaveTerminal(page) {await page.locator('#minimal-menu').click();await page.locator('#drawer-home').click();await page.waitForFunction(()=>!document.querySelector('#session-drawer').open);}
(async()=>{
  let browser;
  try {
    new (require('node:vm').Script)(html.match(/<script>([\s\S]*?)<\/script>/)[1]);
    browser = await chromium.launch({headless:true,args:['--no-sandbox','--disable-dev-shm-usage']});result.browser=browser.version();
    async function makePage(width=390,height=844) {
      const page=await browser.newPage({viewport:{width,height}});page.on('pageerror',e=>result.pageErrors.push(String(e)));
      await page.route('**/*',r=>{result.requests.push(r.request().url());return r.abort();});
      await page.setContent(html,{waitUntil:'domcontentloaded'});await page.waitForSelector('[data-session-id="pty-1"]',{state:'attached'});return page;
    }
    for(const [width,height] of [[320,568],[390,844],[740,360],[840,900],[1024,768],[1280,800]]) {
      const page=await makePage(width,height);
      for (const tab of ['launch','plugins','settings']) {
        await page.locator('#nav-'+tab).click();
        check(`${width}×${height} ${tab}有实质内容`,(await page.locator('#'+tab+'-page').innerText()).length>120);
        check(`${width}×${height} ${tab}主壳不横溢出`,await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth && document.querySelector('#main-content').scrollWidth<=innerWidth));
        check(`${width}×${height} ${tab}导航完整可见`,await page.locator('#nav-terminal').evaluate(el=>{const r=el.getBoundingClientRect();return r.top>=0&&r.bottom<=innerHeight;}));
        if(width===390 || width===1024) await shot(page,`${width===390?'phone':'tablet'}-${tab}-v2.png`);
      }
      await page.locator('[data-subpage="config"]').click();
      check(`${width}×${height}配置子页独立返回`,await page.locator('#app-subpage').isVisible()&&await page.locator('#settings-page').isHidden());
      await page.locator('#cfg-runtime').selectOption('proroot');
      await page.locator('#cfg-autoPort').uncheck();await page.locator('#cfg-port').fill('42000');
      await page.locator('#config-save').click();
      check(`${width}×${height}配置保存按钮可达`,(await page.locator('#config-save-status').textContent()).includes('已保存到本页内存'));
      await page.locator('#nav-terminal').click();await leaveTerminal(page);
      check(`${width}×${height}终端返回原子页与草稿`,await page.locator('#app-subpage').isVisible()&&await page.locator('#cfg-runtime').inputValue()==='proroot'&&await page.locator('#cfg-port').inputValue()==='42000');
      await page.locator('#subpage-back').click();
      check(`${width}×${height}子页返回对应设置Tab`,await page.locator('#nav-settings').getAttribute('aria-current')==='page'&&await page.locator('#settings-page').isVisible());
      await page.close();
    }
    const page=await makePage();
    await page.locator('#nav-terminal').click();await page.locator('#command-input').fill('demo start');await page.locator('#send-command').click();await leaveTerminal(page);
    await page.locator('#web-start').click();check('启动显示准备而非立即成功',(await page.locator('#run-state').textContent()).includes('准备'));
    check('启动期间主按钮禁用避免重复',await page.locator('#web-start').isDisabled());
    await page.waitForFunction(()=>document.querySelector('#run-state').textContent==='Web 已就绪');
    check('启动日志记录鉴权阶段',(await page.locator('#launch-log').textContent()).includes('等待鉴权'));
    await page.locator('#web-stop').click();await closeDialog(page);
    check('取消停止保持Web就绪',(await page.locator('#run-state').textContent())==='Web 已就绪');
    await page.locator('#web-stop').click();await confirm(page);
    await page.waitForFunction(()=>document.querySelector('#run-state').textContent==='尚未启动');
    check('停止Web不关闭终端或计数',await page.locator('.session-item').count()===1&&(await page.locator('#home-task-status').textContent()).includes('运行中'));
    await page.locator('.prototype-tools summary').click();await page.locator('#demo-fail-start').check();await page.locator('#web-start').click();
    await page.waitForFunction(()=>document.querySelector('#run-state').textContent.includes('启动失败'));
    check('失败不显示READY成功',(await page.locator('#web-port').textContent())==='—');
    await page.locator('#demo-environment').selectOption('missing');await page.locator('#nav-terminal').click();
    check('未准备时终端门禁不新建会话',await page.locator('#app-dialog').isVisible()&&await page.locator('.session-item').count()===1);await closeDialog(page);
    await page.locator('#remote-workspaces').click();check('未准备可看电脑工作区',await page.locator('#subpage-title').textContent()==='电脑工作区');
    await page.locator('[data-app-action="remote-pair"]').click();await confirm(page);check('电脑配对只增加虚构未连接条目',(await page.locator('#remote-hosts').textContent()).includes('未连接'));await page.locator('#subpage-back').click();
    await page.locator('#demo-environment').selectOption('ready');
    await page.locator('#web-start').click();await page.locator('#web-stop').click();await confirm(page);
    await page.waitForFunction(()=>document.querySelector('#run-state').textContent==='尚未启动');
    await page.locator('#nav-plugins').click();await page.locator('#plugins-installed-tab').click();
    check('随包四条+虚构一条且不声称本机列表',await page.locator('.plugin-card').count()===5&&(await page.locator('#plugins-page').textContent()).includes('不是这台手机'));
    await page.locator('#plugin-search').fill('status-overlay');check('插件搜索真的过滤',await page.locator('.plugin-card').count()===1);
    await page.locator('#nav-settings').click();await page.locator('#nav-plugins').click();check('切Tab保留搜索与分段',await page.locator('#plugin-search').inputValue()==='status-overlay'&&await page.locator('#plugin-installed').isVisible());
    await page.locator('#plugin-search').fill('不存在<img src=x>');check('插件无匹配空态可见且不注入HTML',await page.locator('#plugin-empty').isVisible()&&await page.locator('#plugin-list img').count()===0);
    await page.locator('#plugin-search').fill('');await page.locator('#hide-builtin').check();check('隐藏内置只剩虚构用户样例',await page.locator('.plugin-card').count()===1);
    await page.locator('[data-plugin-action="toggle"][data-plugin-id="notes"]').click();check('异步启停等待期间禁用',await page.locator('[data-plugin-action="toggle"][data-plugin-id="notes"]').isDisabled());
    await page.waitForFunction(()=>document.querySelector('[data-plugin-action="toggle"][data-plugin-id="notes"]').getAttribute('aria-checked')==='true');
    check('用户样例启用反馈区分模拟',(await page.locator('[data-plugin-state]').textContent()).includes('模拟'));
    await page.locator('[data-plugin-action="details"][data-plugin-id="notes"]').click();check('详情展开真实可见',await page.locator('.plugin-details').isVisible());
    await page.locator('[data-plugin-action="remove"][data-plugin-id="notes"]').click();await closeDialog(page);check('取消删除保留样例',await page.locator('.plugin-card').count()===1);
    await page.locator('[data-plugin-action="remove"][data-plugin-id="notes"]').click();await confirm(page);check('删除后过滤空态',await page.locator('#plugin-empty').isVisible());
    await page.locator('#hide-builtin').uncheck();await page.locator('[data-plugin-action="toggle"][data-plugin-id="mobile"]').click();check('禁用随包组件有风险确认',await page.locator('#app-dialog-title').textContent()==='禁用随包组件？');await closeDialog(page);
    await page.locator('#plugin-sort').selectOption('za');check('名称倒序生效',(await page.locator('.plugin-card h3').first().textContent())==='dsh-web-mobile');
    await page.locator('#plugins-market-tab').click();await page.locator('#plugin-url').fill('javascript:alert(1)');await page.locator('#plugin-resolve').click();check('拒绝危险/不支持来源',(await page.locator('#plugin-link-status').textContent()).includes('不符合'));
    for(const [source,type] of [['@scope/demo@1.0.0','npm 包名'],['https://example.invalid/demo.zip','HTTPS 压缩包'],['https://github.com/example/demo-plugin','GitHub 仓库']]) {
      await page.locator('#plugin-url').fill(source);await page.locator('#plugin-resolve').click();check(type+'离线识别但不假装联网',(await page.locator('#plugin-link-status').textContent()).includes(type)&&await page.locator('#app-dialog').isVisible());await closeDialog(page);
    }
    await page.locator('#plugin-resolve').click();await confirm(page);check('模拟安装增加固定样例且切管理',await page.locator('#plugin-installed').isVisible()&&await page.locator('.plugin-card[data-plugin-id="sample"]').count()===1);
    await page.locator('#plugins-updates').click();check('离线更新不假称最新版',(await page.locator('#app-dialog-body').textContent()).includes('未检查'));await closeDialog(page);
    await shot(page,'phone-plugin-management-v2.png');
    await page.locator('#nav-settings').click();await page.locator('#eco-toggle').click();check('省电只更新模拟偏好',await page.locator('#eco-toggle').getAttribute('aria-checked')==='true');
    await page.locator('#language-choice').click();check('十种语言加跟随系统',await page.locator('#language-select option').count()===11);await page.locator('#language-select').selectOption('English');await confirm(page);check('语言选择不谎称全页翻译',(await page.locator('#language-summary').textContent()).includes('预览仍为中文'));
    await page.locator('[data-subpage="config"]').click();await page.locator('#cfg-autoPort').uncheck();
    await page.locator('#cfg-port').fill('3091');await page.locator('#config-save').click();check('拒绝桥接保留端口',(await page.locator('#config-save-status').textContent()).includes('保留端口'));
    await page.locator('#cfg-port').fill('42001');await page.locator('#cfg-runtime').selectOption('proroot');await page.locator('#cfg-dns').selectOption('native');
    await page.locator('#subpage-back').click();await page.locator('[data-subpage="config"]').click();check('未保存子页草稿保留',await page.locator('#cfg-port').inputValue()==='42001'&&await page.locator('#cfg-dns').inputValue()==='native');
    await page.locator('#config-save').click();await page.locator('#nav-launch').click();await page.locator('#web-start').click();await page.waitForFunction(()=>document.querySelector('#run-state').textContent==='Web 已就绪');
    check('下次模拟启动采用新首选端口',await page.locator('#web-port').textContent()==='42001*');
    await page.locator('#nav-settings').click();await page.locator('[data-subpage="config"]').click();await page.locator('#cfg-port').fill('43000');await page.locator('#config-save').click();
    await page.locator('#nav-launch').click();check('运行中改配置不篡改既有实例端口',await page.locator('#web-port').textContent()==='42001*');
    await page.locator('#safe-start').click();await confirm(page);await page.waitForFunction(()=>document.querySelector('#run-state').textContent==='安全模式 · 已就绪');
    check('运行中可先停止再安全启动',await page.locator('#web-port').textContent()==='43000*');
    await page.locator('#web-restart').click();await confirm(page);await page.waitForFunction(()=>document.querySelector('#run-state').textContent==='Web 已就绪');
    check('安全启动后普通重启恢复普通模式',true);
    for(const sub of ['install','backup','grants','overlay','updates','diagnostics','about']) {
      await page.locator('#nav-settings').click();await page.locator(`[data-subpage="${sub}"]`).click();check(sub+'子页有对应结构与可达内容',await page.locator('#subpage-body > div:not([hidden]) .app-card').count()===1&&(await page.locator('#subpage-body').innerText()).trim().length>0);
      if(sub==='backup') {await page.locator('[data-app-action="backup-preview"]').click();await closeDialog(page);check('取消备份预览不假称已生成',await page.locator('#backup-status').textContent()==='未生成备份文件。');await page.locator('[data-app-action="backup-preview"]').click();await confirm(page);check('完成备份流程也明确没生成文件',(await page.locator('#backup-status').textContent()).includes('没有生成归档'));}
      if(sub==='install') {await page.locator('[data-app-action="component-check"]').click();await page.waitForFunction(()=>document.querySelector('#component-status').textContent.includes('演示结束'));check('组件模拟检查仍标未实测',(await page.locator('#component-list').textContent()).includes('未实测'));}
      if(sub==='updates') {await page.locator('[data-app-action="updates-preview"]').click();check('更新离线结果保持未检查',(await page.locator('#updates-status').textContent()).includes('未检查'));}
    }
    await page.setViewportSize({width:320,height:568});await page.evaluate(()=>document.documentElement.style.fontSize='32px');
    for(const tab of ['launch','plugins','settings']) {await page.locator('#nav-'+tab).click();check('两倍字号 '+tab+' 内容不横溢出',await page.evaluate(()=>document.querySelector('#main-content').scrollWidth<=innerWidth));}
    await page.locator('#language-choice').click();await page.locator('#language-select').focus();await page.keyboard.press('Tab');await page.keyboard.press('Tab');check('含select的弹层焦点仍在弹层内',await page.evaluate(()=>document.querySelector('#app-dialog').contains(document.activeElement)));await closeDialog(page);
    await page.evaluate(()=>document.documentElement.style.fontSize='16px');await page.setViewportSize({width:1024,height:768});await page.locator('[data-subpage="config"]').click();await page.locator('#main-content').evaluate(e=>e.scrollTop=0);await shot(page,'tablet-config-v2.png');
    check('所有DOM id唯一',await page.evaluate(()=>{const ids=[...document.querySelectorAll('[id]')].map(e=>e.id);return new Set(ids).size===ids.length;}));
    const fresh=await makePage();check('刷新文档不会保留样例副作用',await fresh.locator('#run-state').textContent()==='尚未启动'&&await fresh.locator('.plugin-card').count()===5);await fresh.close();
    await page.close();check('没有脚本异常',result.pageErrors.length===0,JSON.stringify(result.pageErrors));check('没有外部请求',result.requests.length===0,JSON.stringify(result.requests));result.passed=true;
  } catch(error) {result.passed=false;result.failure=String(error.stack||error);process.exitCode=1;}
  finally {if(browser)await browser.close();result.finishedAt=new Date().toISOString();result.checkCount=result.checks.length;fs.writeFileSync(path.join(root,'validation-main-tabs.json'),JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify({passed:result.passed,checks:result.checkCount,screenshots:result.screenshots,failure:result.failure},null,2));}
})();
