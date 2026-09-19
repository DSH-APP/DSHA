// 在独立测试数据目录启动真实 dsh，检查官方鉴权和静态入口；不调用付费模型。
import { spawn, execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync, readFileSync, cpSync, symlinkSync, existsSync } from 'node:fs';
import { resolve, sep } from 'node:path';
import { createServer } from 'node:net';
import { createRequire } from 'node:module';

const runtime = resolve(process.argv[2]);
const home = resolve(process.argv[3]);
const build = resolve('app/build') + sep;
if (!home.startsWith(build)) throw new Error('测试数据目录必须位于 app/build');
if (process.argv.includes('--models-entry')) {
  const patch=JSON.parse(readFileSync('app/src/main/assets/models-navigation-patch.json','utf8'));
  const client=resolve(runtime,'node_modules',patch.module);
  if (!client.startsWith(build)) throw new Error('模型入口检查只能修改隔离运行时');
  let content=readFileSync(client,'utf8').replace(/\r\n/g,'\n');
  for(const {before,after} of patch.patches){
    if(content.includes(after))continue;
    if(content.split(before).length!==2)throw new Error('模型入口的上游源码不匹配');
    content=content.replace(before,after);
  }
  writeFileSync(client,content);
}
if (process.argv.includes('--composer')) {
  const client=resolve(runtime,'node_modules/@deepseek-ai/dsh-client-ui-conversation/lib/client.js');
  if (!client.startsWith(build)) throw new Error('输入检查只能修改隔离运行时');
  let content=readFileSync(client,'utf8').replace(/\r\n/g,'\n');
  for (const {before,after} of JSON.parse(readFileSync('app/src/main/assets/composer-enter-patch.json','utf8')).patches) {
    if (content.includes(after)) continue;
    if (content.split(before).length!==2) throw new Error('输入检查的上游源码不匹配');
    content=content.replace(before,after);
  }
  writeFileSync(client,content);
}
mkdirSync(resolve(home, 'profiles/web'), { recursive: true });
const plugins = process.argv.includes('--builtins') ? ['dsh-device-shell-guide', 'dsh-task-notifier', 'dsh-status-overlay', 'dsh-web-mobile', 'dsh-computer-use-android', 'dsh-auto-review', 'dsh-app-integration'] : [];
const dependencies = {};
for (const name of plugins) {
  const source = name === 'dsh-app-integration' ? resolve('app/src/main/assets/app-integration')
    : resolve('app/src/main/assets/builtin-plugins', name);
  const destination = resolve(runtime, 'node_modules', name);
  if (!destination.startsWith(build)) throw new Error('测试插件必须位于 app/build');
  cpSync(source, destination, { recursive: true });
  const link = resolve(home, 'profiles/web/node_modules', name);
  mkdirSync(resolve(home, 'profiles/web/node_modules'), { recursive: true });
  if (!existsSync(link)) symlinkSync(destination, link, process.platform === 'win32' ? 'junction' : 'dir');
  dependencies[name] = 'link:' + destination;
}
writeFileSync(resolve(home, 'profiles/web/package.json'), JSON.stringify({
  name: 'dsha-runtime-smoke', private: true, dependencies,
  dsh: { profile: { bundles: ['@deepseek-ai/dsh-base', '@deepseek-ai/dsh-web-app', ...plugins], patchReload: 'startup' } },
}));
const listener = createServer();
await new Promise(done => listener.listen(0, '127.0.0.1', done));
const port = listener.address().port;
await new Promise(done => listener.close(done));
const child = spawn(process.execPath, [resolve(runtime, 'node_modules/@deepseek-ai/dsh/lib/bin.js'),
  'web', '--no-open', '--host', '127.0.0.1', '--port', String(port)], {
  cwd: home, env: { ...process.env, DSH_HOME: home, BROWSER: 'true', DEEPSEEK_API_KEY: '',
    DSH_CONFIRM: '1', DSH_PERMISSION_MODE: 'workspace-write', SSH_CONNECTION: '127.0.0.1 1 127.0.0.1 22' },
  stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true, detached: process.platform !== 'win32',
});
let log = '', authUrl, exited = false;
child.on('exit', () => { exited = true; });
for (const stream of [child.stdout, child.stderr]) stream.on('data', chunk => {
  log += chunk; log = log.slice(-100_000);
  authUrl = log.match(new RegExp(`http://127\\.0\\.0\\.1:${port}/\\?token=[A-Za-z0-9_-]{43}(?![A-Za-z0-9_-])`))?.[0];
});
try {
  const deadline = Date.now() + 60_000;
  while (!authUrl && !exited && Date.now() < deadline) await new Promise(done => setTimeout(done, 200));
  if (!authUrl) throw new Error(exited ? 'dsh 在鉴权链接就绪前退出' : '启动超时');
  let exchange;
  for (let attempt = 0; attempt < 15; attempt++) {
    try { exchange = await fetch(authUrl, { redirect: 'manual', signal: AbortSignal.timeout(3000) }); break; }
    catch (error) { if (attempt === 14) throw error; await new Promise(done => setTimeout(done, 400)); }
  }
  if (exchange.status !== 303 || exchange.headers.get('location') !== '/') throw new Error('启动凭据未获得 303 根路径跳转');
  const cookie = exchange.headers.get('set-cookie')?.split(';')[0];
  if (!cookie?.startsWith('dsh-auth-')) throw new Error('官方 Cookie 缺失');
  const base = `http://127.0.0.1:${port}/`;
  const unauthorized = await fetch(base);
  if (unauthorized.status !== 401) throw new Error('未登录入口没有拒绝访问');
  const authorized = await fetch(base, { headers: { cookie } });
  const html = await authorized.text();
  if (authorized.status !== 200 || !html.includes('<html')) throw new Error('登录后没有取得实际网页');
  const wrong = await fetch(base + '?token=' + 'X'.repeat(43), { redirect: 'manual' });
  if (wrong.status !== 401) throw new Error('无效启动凭据没有被拒绝');
  if (process.argv.includes('--browser')) {
    const { chromium } = createRequire(import.meta.url)(process.env.DSHA_PLAYWRIGHT || 'playwright');
    const browser = await chromium.launch({ executablePath: process.env.DSHA_BROWSER, headless: true });
    try {
      const page = await browser.newPage({ viewport: { width: 393, height: 852 }, isMobile: true,
        hasTouch: true, deviceScaleFactor: 1, userAgent: 'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36' });
      const errors = [];
      page.setDefaultTimeout(15_000);
      page.on('pageerror', error => errors.push(String(error).replace(/(token=)[A-Za-z0-9_-]+/g, '$1***')));
      await page.goto(authUrl, { waitUntil: 'domcontentloaded' });
      await page.waitForTimeout(6000);
      if (process.argv.includes('--workspace') || process.argv.includes('--composer') || process.argv.includes('--header-popover')) {
        const notice = page.getByRole('button', { name: '继续', exact: true });
        if (await notice.count() && await notice.isVisible()) await notice.click();
        const later = page.getByRole('button', { name: '稍后配置', exact: true });
        await later.waitFor({ state: 'visible', timeout: 8000 }).catch(() => {});
        if (await later.isVisible()) await later.click();
        if (process.argv.includes('--device-layout')) {
          const fab=page.locator('[data-mobile-nav="fab"]');
          await fab.waitFor({state:'visible'});
          const bounds=await fab.boundingBox();
          if(!bounds || Math.abs(bounds.x)>1 || Math.abs(bounds.y)>1 || bounds.width<44 || bounds.height<44)
            throw new Error('首页侧栏入口没有贴齐左上角：'+JSON.stringify(bounds));
          await page.screenshot({path:resolve(home,'browser-home-top-left.png'),fullPage:true});
        }
        await page.getByRole('button', { name: '选择工作区', exact: true }).click();
        await page.getByRole('button', { name: '编辑路径', exact: true }).click();
        const pathInput = page.locator('input:visible').first();
        await pathInput.fill(home);
        await pathInput.press('Enter');
        await page.getByRole('button', { name: '打开', exact: true }).click();
        await page.waitForTimeout(3500);
        if (process.argv.includes('--header-popover')) {
          // 在真实 alpha.2 页面和移动插件上挂载同版 ConversationSessionHeader
          // 结构，覆盖活动会话 + 预设 + 子代理 + 后台任务的最拥挤组合。
          await page.evaluate(() => {
            const phase=document.createElement('section');phase.dataset.phase='active';phase.dataset.dshaHeaderFixture='';
            phase.className='wSkVaW_root';phase.style.cssText='position:fixed;inset:72px 0 auto 0;height:120px;overflow:hidden;background:var(--dsw-alias-bg-base);z-index:1000';
            const header=document.createElement('header');header.className='wSkVaW_header';
            const row=document.createElement('div');row.className='wSkVaW_titleRow';
            const leading=document.createElement('div');leading.className='wSkVaW_headerLeading';
            const cluster=document.createElement('div');cluster.className='wSkVaW_titleCluster';
            const crumbs=document.createElement('nav');crumbs.className='wSkVaW_crumbs';crumbs.innerHTML='<button class="wSkVaW_crumb wSkVaW_crumbCurrent">很长的活动会话标题</button><span class="ZKlsPq_root "><button class="ZKlsPq_trigger" aria-haspopup="tree"><span class="ZKlsPq_count">3 个子代理</span></button></span>';
            const actions=document.createElement('div');actions.className='wSkVaW_headerActions';
            const preset=document.createElement('span');preset.className='dsha-preset-header-anchor';preset.innerHTML='<button data-dsha-agent-preset="header"><span>用户自建超长预设</span></button>';
            const jobs=document.createElement('span');jobs.className='QsffPG_root';jobs.innerHTML='<button class="QsffPG_trigger" aria-expanded="false"><span class="QsffPG_count">2 个后台任务运行中</span></button>';
            jobs.querySelector('button').addEventListener('click',()=>{const old=jobs.querySelector('.QsffPG_menu');if(old){old.remove();jobs.querySelector('button').setAttribute('aria-expanded','false');return;}jobs.querySelector('button').setAttribute('aria-expanded','true');const menu=document.createElement('ul');menu.className='QsffPG_menu';menu.setAttribute('aria-label','后台任务');for(let i=0;i<8;i++){const li=document.createElement('li');li.className='QsffPG_row';li.textContent=`bash task ${i+1}　运行中　${i+1}秒`;menu.appendChild(li);}jobs.appendChild(menu);});
            const files=document.createElement('button');files.dataset.mobileNav='files';files.setAttribute('aria-label','文件浏览');
            actions.append(preset,jobs,files);cluster.append(crumbs,actions);
            const utilities=document.createElement('div');utilities.className='wSkVaW_headerUtilities';
            const corner=document.createElement('div');corner.className='wSkVaW_headerCorner';
            row.append(leading,cluster,utilities,corner);header.appendChild(row);phase.appendChild(header);
            document.querySelector('[data-mobile-nav="frame"]').appendChild(phase);
          });
          await page.waitForTimeout(200);
          const layout=await page.evaluate(()=>{const phase=document.querySelector('[data-dsha-header-fixture]');const leading=phase.querySelector('.wSkVaW_headerLeading');const cluster=phase.querySelector('.wSkVaW_titleCluster');const row=phase.querySelector('.wSkVaW_titleRow');return {leading:getComputedStyle(leading).flexGrow,leadingWidth:leading.getBoundingClientRect().width,cluster:cluster.getBoundingClientRect().toJSON(),row:row.getBoundingClientRect().toJSON(),overflow:getComputedStyle(phase).overflow};});
          if(layout.leading!=='0'||layout.leadingWidth!==0||layout.cluster.width<280||Math.abs(layout.cluster.top-layout.row.top)>1)
            throw new Error('拥挤顶栏没有保持 alpha.2 单行布局：'+JSON.stringify(layout));
          const trigger=page.locator('[data-dsha-header-fixture] .QsffPG_trigger');await trigger.click();
          const menu=page.getByRole('list',{name:'后台任务'});await menu.waitFor({state:'visible'});await page.waitForTimeout(100);
          const popup=await page.evaluate(()=>{const phase=document.querySelector('[data-dsha-header-fixture]');const menu=phase.querySelector('.QsffPG_menu');return {menu:menu.getBoundingClientRect().toJSON(),viewport:{width:innerWidth,height:innerHeight},phaseOverflow:getComputedStyle(phase).overflow,frameOverflow:getComputedStyle(document.querySelector('[data-mobile-nav="frame"]')).overflow};});
          if(popup.menu.left<0||popup.menu.top<0||popup.menu.right>popup.viewport.width||popup.menu.bottom>popup.viewport.height||popup.phaseOverflow!=='hidden')
            throw new Error('后台任务弹窗越界或改坏会话滚动边界：'+JSON.stringify(popup));
          await page.screenshot({path:resolve(home,'browser-header-jobs.png'),fullPage:true});
          await trigger.click();await menu.waitFor({state:'hidden'});
          await page.evaluate(()=>document.querySelector('[data-dsha-header-fixture]')?.remove());
        }
        if (process.argv.includes('--composer')) {
          const viewport = await page.locator('meta[name="viewport"]').getAttribute('content');
          if (!viewport.includes('interactive-widget=resizes-content') || !viewport.includes('width=device-width'))
            throw new Error('键盘布局视口设置未生效或原有缩放参数丢失');
          let sends=0;
          await page.route('**/api/session/prompt', async route => {
            sends++;
            const body=route.request().postDataJSON();
            await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({type:'server-response',rpcId:body.rpcId,result:{ok:true,value:{accepted:true}}})});
          });
          const editor=page.locator('[data-composer-input]');
          if(process.argv.includes('--device-layout')) {
            const typography=await page.evaluate(()=>Object.fromEntries(['[data-composer-input]','[data-composer-placeholder]']
              .map(selector=>[selector,document.querySelector(selector)?getComputedStyle(document.querySelector(selector)).fontSize:null])));
            writeFileSync(resolve(home,'device-typography.json'),JSON.stringify(typography,null,2));
            if(Object.values(typography).some(size=>size!=='13px')) throw new Error('输入层与占位字未统一到 13px：'+JSON.stringify(typography));
          }
          if (await editor.getAttribute('enterkeyhint')!=='enter') throw new Error('输入法未提示换行');
          await editor.fill('第一行');await editor.press('End');await editor.press('Enter');await page.keyboard.insertText('第二行');
          await editor.press('Shift+Enter');await page.keyboard.insertText('第三行');
          const text=await editor.innerText();
          if (!/第一行\n+第二行\n+第三行/.test(text) || sends!==0) throw new Error('普通回车未换行或意外发送');
          await editor.evaluate(el => {
            el.dispatchEvent(new CompositionEvent('compositionstart',{bubbles:true}));
            el.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',ctrlKey:true,isComposing:true,bubbles:true,cancelable:true}));
            el.dispatchEvent(new CompositionEvent('compositionend',{data:'中文',bubbles:true}));
            el.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',bubbles:true,cancelable:true}));
          });
          await page.waitForTimeout(300);
          if (sends!==0 || !(await editor.innerText()).includes('第三行')) throw new Error('中文组合输入误发或丢字');
          await page.screenshot({path:resolve(home,'browser-enter-newline.png'),fullPage:true});
          writeFileSync(resolve(home,'composer-result.json'),JSON.stringify({sends,text,viewport,enterKeyHint:await editor.getAttribute('enterkeyhint')},null,2));
        }
        const draft = 'DSHA 0.1.5 本地草稿恢复验证';
        await page.locator('[data-composer-input]').fill(draft);
        await page.locator('input[type="file"]').setInputFiles({ name: 'dsha-fixture.png', mimeType: 'image/png',
          buffer: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jG0kAAAAASUVORK5CYII=', 'base64') });
        await page.waitForTimeout(1600);
        if (await page.getByText('图片草稿未能保存到本机', { exact: false }).count()) throw new Error('新版草稿 API 不兼容');
        await page.reload({ waitUntil: 'domcontentloaded' });
        await page.locator('[data-composer-input]').waitFor();
        await page.waitForTimeout(2200);
        const configureLater = page.getByRole('button', { name: '稍后配置', exact: true });
        if (await configureLater.isVisible()) await configureLater.click();
        if (!(await page.locator('[data-composer-input]').innerText()).includes(draft)) throw new Error('文本草稿重载丢失');
        if (!await page.locator('img[src^="blob:"]').count()) throw new Error('图片草稿重载丢失');
        await page.screenshot({ path: resolve(home, 'browser-drafts.png'), fullPage: true });
        writeFileSync(resolve(home, 'browser-drafts-buttons.json'), JSON.stringify(await page.evaluate(() => Array.from(document.querySelectorAll('button')).map(el=>({
          label:el.getAttribute('aria-label'),text:el.innerText,nav:el.getAttribute('data-mobile-nav'),rect:el.getBoundingClientRect().toJSON()
        }))), null, 2));
        await page.getByRole('button', { name: '打开目录', exact: true }).click();
        await page.getByRole('button', { name: '文件浏览', exact: true }).click();
        await page.locator('[data-sidebar-right-panel][data-sidebar-right-open]').waitFor();
        await page.waitForTimeout(1100);
        const panel = await page.locator('[data-sidebar-right-panel][data-sidebar-right-open]').boundingBox();
        if (!panel || panel.x < -1 || panel.x + panel.width > 394) throw new Error('文件侧栏未进入手机可视区域');
        await page.screenshot({ path: resolve(home, 'browser-files.png'), fullPage: true });
        await page.evaluate(() => document.dispatchEvent(new Event('dsha-close-details')));
        await page.locator('[data-sidebar-right-panel][data-sidebar-right-open]').waitFor({ state: 'hidden' });
      }
      if (process.argv.includes('--models-entry')) {
        for(const label of ['继续','稍后配置']) {
          const button=page.getByRole('button',{name:label,exact:true});
          if(await button.count() && await button.isVisible())await button.click();
        }
        // 原生入口事件应当通过真实布局服务展开默认收起的窄屏侧栏。
        await page.evaluate(()=>{window.__DSHA_OPEN_MODELS__=true;window.dispatchEvent(new Event('dsha-open-models'));});
        await page.getByRole('button',{name:'添加自定义提供方',exact:true}).waitFor({state:'visible'});
        await page.screenshot({path:resolve(home,'models-entry.png'),fullPage:true});
      }
      const state = await page.evaluate(() => ({ text: document.body.innerText.slice(0, 9000),
        integration: document.documentElement.getAttribute('data-dsha-integration'),
        mobile: Boolean(document.querySelector('[data-mobile-nav="frame"]')),
        width: innerWidth, scrollWidth: document.documentElement.scrollWidth,
        inputs: Array.from(document.querySelectorAll('input,textarea,[contenteditable="true"]')).map(input => ({
          label: input.getAttribute('aria-label'), placeholder: input.getAttribute('placeholder'), value: input.value,
          html: input.outerHTML.slice(0, 800),
        })),
        editors: Array.from(document.querySelectorAll('[role="textbox"],[contenteditable]')).map(el => el.outerHTML.slice(0, 800)),
        buttons: Array.from(document.querySelectorAll('button')).slice(0, 70).map(button => ({
          text: button.innerText, label: button.getAttribute('aria-label'), title: button.title,
        })),
      }));
      writeFileSync(resolve(home, 'browser-state.json'), JSON.stringify({ ...state, errors }, null, 2));
      await page.screenshot({ path: resolve(home, 'browser.png'), fullPage: true });
      console.log(JSON.stringify({ browser: state, errors }));
      if (errors.length || (plugins.length && (state.integration !== 'ready' || !state.mobile))) throw new Error('浏览器插件未完整激活');
    } finally { await browser.close(); }
  }
  console.log(JSON.stringify({ status: 'PASS', auth: '303 + Cookie + HTTP 200', unauthenticated: 401,
    invalidToken: 401, htmlBytes: Buffer.byteLength(html), plugins, platform: process.platform, arch: process.arch }));
  if (process.argv.includes('--hold')) {
    writeFileSync(resolve(home,'connection.json'),JSON.stringify({authUrl,base,port}),{mode:0o600});
    const until=Date.now()+300000;
    while(!existsSync(resolve(home,'stop'))&&Date.now()<until)await new Promise(done=>setTimeout(done,250));
  }
} catch (error) {
  console.error(String(error));
  console.error(log.replace(/(token=)[A-Za-z0-9_-]+/g, '$1***'));
  process.exitCode = 1;
} finally {
  writeFileSync(resolve(home, "startup.log"),log.replace(/(token=)[A-Za-z0-9_-]+/g, "$1***"));
  if (!exited) {
    if (process.platform === 'win32') {
      try { execFileSync('taskkill.exe', ['/PID', String(child.pid), '/T', '/F'], { windowsHide: true, stdio: 'ignore' }); }
      catch { child.kill(); }
    } else { try { process.kill(-child.pid, 'SIGTERM'); } catch { child.kill(); } }
  }
}
