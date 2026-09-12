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
if (process.argv.includes('--composer')) {
  const client=resolve(runtime,'node_modules/@deepseek-ai/dsh-client-ui-conversation/lib/client.js');
  if (!client.startsWith(build)) throw new Error('输入检查只能修改隔离运行时');
  let content=readFileSync(client,'utf8');
  for (const {before,after} of JSON.parse(readFileSync('app/src/main/assets/composer-enter-patch.json','utf8')).patches) {
    if (content.includes(after)) continue;
    if (content.split(before).length!==2) throw new Error('输入检查的上游源码不匹配');
    content=content.replace(before,after);
  }
  writeFileSync(client,content);
}
mkdirSync(resolve(home, 'profiles/web'), { recursive: true });
const plugins = process.argv.includes('--builtins') ? ['dsh-device-shell-guide', 'dsh-task-notifier', 'dsh-status-overlay', 'dsh-web-mobile', 'dsh-app-integration'] : [];
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
      if (process.argv.includes('--workspace') || process.argv.includes('--composer')) {
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
} catch (error) {
  console.error(String(error));
  console.error(log.replace(/(token=)[A-Za-z0-9_-]+/g, '$1***'));
  process.exitCode = 1;
} finally {
  if (!exited) {
    if (process.platform === 'win32') {
      try { execFileSync('taskkill.exe', ['/PID', String(child.pid), '/T', '/F'], { windowsHide: true, stdio: 'ignore' }); }
      catch { child.kill(); }
    } else { try { process.kill(-child.pid, 'SIGTERM'); } catch { child.kill(); } }
  }
}
