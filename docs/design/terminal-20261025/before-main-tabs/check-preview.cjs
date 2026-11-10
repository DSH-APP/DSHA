'use strict';
// 仅检验同目录离线设计原型；不连接设备、真实终端或外部网络。
// PLAYWRIGHT_HOME 指向已安装 playwright 的目录，默认从当前环境解析。
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { chromium } = require(require.resolve('playwright', {
  paths: [process.env.PLAYWRIGHT_HOME || process.cwd()]
}));
const root = __dirname;
const source = path.join(root, 'preview.html');
const result = { startedAt: new Date().toISOString(), scope: 'offline HTML prototype only; not Android validation', checks: [], screenshots: [], pageErrors: [], externalRequests: [] };
function check(name, value, detail = '') {
  assert.ok(value, `${name}: ${detail}`);
  result.checks.push({ name, passed: true, detail });
}
async function command(page, text) {
  await page.locator('#command-input').fill(text);
  await page.locator('#send-command').click();
}
async function inventory(page) {
  if (await page.locator('#sessions-toggle').isVisible()) {
    if (!await page.locator('#session-drawer').evaluate(el => el.open)) await page.locator('#sessions-toggle').click();
  }
}
async function newSession(page, type) {
  await inventory(page);
  await page.locator('#new-session').click();
  await page.locator(`input[name="session-type"][value="${type}"]`).check();
  await page.locator('#create-session-confirm').click();
  await page.waitForFunction(() => !document.querySelector('#new-session-dialog').open && !document.querySelector('#session-drawer').open);
}
async function selectSession(page, id) {
  await inventory(page);
  await page.locator(`[data-select-session="${id}"]`).click();
  await page.waitForFunction(() => !document.querySelector('#session-drawer').open);
}
async function closeSession(page, id, confirm = true) {
  await inventory(page);
  await page.locator(`[data-close-session="${id}"]`).click();
  if (!confirm) {
    await page.locator('#cancel-close').click();
    await page.waitForFunction(() => !document.querySelector('#close-session-dialog').open);
    if (await page.locator('#session-drawer').evaluate(el => el.open)) await page.locator('#close-drawer').click();
  } else {
    await page.locator('#confirm-close').click();
    await page.waitForFunction(() => !document.querySelector('#close-session-dialog').open && !document.querySelector('#session-drawer').open);
  }
}
async function screenshot(page, name) {
  // 等待明确反馈消失，避免截图把临时 Toast 当作常驻结构。
  await page.waitForFunction(() => document.querySelector('#feedback').textContent === '');
  await page.screenshot({ path: path.join(root, name), fullPage: false });
  result.screenshots.push(name);
}
async function dimensions(page) {
  return page.evaluate(() => {
    const terminal = document.querySelector('#terminal-screen').getBoundingClientRect();
    return { width: innerWidth, height: innerHeight, scrollWidth: document.documentElement.scrollWidth,
      terminal: { x: terminal.x, y: terminal.y, width: terminal.width, height: terminal.height },
      layout: document.querySelector('#terminal-screen').dataset.layout };
  });
}
(async () => {
  let browser;
  try {
    browser = await chromium.launch({ headless: true, args: ['--no-sandbox', '--disable-dev-shm-usage'] });
    result.browser = browser.version();
    result.loadMethod = 'page.setContent of exact file text (navigation timed out in this container)';
    const sourceText = fs.readFileSync(source, 'utf8');
    result.previewSha256 = require('node:crypto').createHash('sha256').update(sourceText).digest('hex');
    const scripts = Array.from(sourceText.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/gi), m => m[1]);
    for (const script of scripts) new (require('node:vm').Script)(script);
    check('内嵌脚本语法', scripts.length === 1);
    check('禁止网络的 CSP', sourceText.includes("connect-src 'none'"));
    check('无执行/网络/用户持久化 API', !/\b(?:eval|fetch|XMLHttpRequest|WebSocket|localStorage|sessionStorage|indexedDB)\s*\(/.test(sourceText));
    const makePage = async (width, height) => {
      const page = await browser.newPage({ viewport: { width, height }, deviceScaleFactor: 1 });
      page.on('pageerror', e => result.pageErrors.push(String(e)));
      await page.route('**/*', route => {
        const url = route.request().url();
        if (/^https?:/i.test(url)) { result.externalRequests.push(url); return route.abort(); }
        return route.continue();
      });
      // 容器内 Chromium 导航加载曾超时；直接将文件原文交给浏览器解析。
      // 不改HTML字节、不启动服务器；该方法不证明用户环境的file://打开路径。
      await page.setContent(sourceText, {waitUntil:'domcontentloaded'});
      await page.waitForSelector('.session-item', {state:'attached'});
      return page;
    };
    for (const [width, height] of [[320,568],[390,844],[740,360],[840,900],[1024,768],[1280,800]]) {
      const page = await makePage(width, height);
      check(`${width}x${height} 主导航四入口`, await page.locator('[data-nav]').count() === 4);
      await page.locator('#nav-terminal').click();
      check(`${width}x${height} 默认极简模式`, await page.locator('#terminal-screen').evaluate(e => e.classList.contains('minimal')));
      check(`${width}x${height} 极简隐藏工具栏与侧栏`, await page.locator('#terminal-header').isHidden() && await page.locator('#session-sidebar').isHidden());
      await command(page, 'jobs');
      // 极简模式从菜单返回，再开同一模拟会话；不借助隐藏工作台控件。
      await page.locator('#minimal-menu').click();
      await page.locator('#drawer-home').click();
      await page.waitForFunction(() => !document.querySelector('#session-drawer').open);
      check(`${width}x${height} 极简菜单返回主界面`, await page.locator('#main-shell').isVisible());
      await page.locator('#nav-terminal').click();
      check(`${width}x${height} 极简重进会话不变`, await page.locator('#terminal-screen').getAttribute('data-active-session') === 'pty-1');
      if (width === 390) await screenshot(page, 'phone-minimal-preview.png');
      if (width === 1024) await screenshot(page, 'tablet-minimal-preview.png');
      await page.locator('#minimal-menu').click();
      await page.locator('#session-sidebar [data-open-dialog="settings-dialog"]').click();
      await page.locator('#minimal-mode').uncheck();
      await page.locator('#settings-dialog .dialog-actions button').click();
      await page.waitForFunction(() => !document.querySelector('#settings-dialog').open);
      if (await page.locator('#session-drawer').evaluate(el => el.open)) await page.locator('#close-drawer').click();
      const d = await dimensions(page);
      check(`${width}x${height} 全视口覆盖`, d.terminal.x === 0 && d.terminal.y === 0 && d.terminal.width === width && d.terminal.height === height, JSON.stringify(d));
      check(`${width}x${height} 主壳隐藏`, await page.locator('#main-shell').isHidden());
      check(`${width}x${height} 自动断点`, d.layout === (width >= 840 ? 'sidebar' : 'drawer'));
      check(`${width}x${height} 无页面级横溢出`, d.scrollWidth <= width);
      await command(page, 'pwd');
      check(`${width}x${height} 模拟响应`, (await page.locator('#terminal-output').textContent()).includes('/demo/workspace'));
      await page.locator('#keyboard-toggle').click();
      check(`${width}x${height} 模拟键盘展开`, await page.locator('#simulated-keyboard').isVisible());
      await page.locator('[data-preset="jobs"]').click();
      await page.locator('#send-command').click();
      check(`${width}x${height} 键盘展开后提交可达`, (await page.locator('#terminal-output').textContent()).includes('没有演示任务'));
      await page.locator('#hide-keyboard').click();
      await newSession(page, 'simple');
      check(`${width}x${height} 新建 simple`, await page.locator('#terminal-screen').getAttribute('data-active-session') === 'simple-1');
      await selectSession(page, 'pty-1');
      check(`${width}x${height} 切回原 PTY 输出保留`, (await page.locator('#terminal-output').textContent()).includes('/demo/workspace'));
      await page.locator('#back-home').click();
      await page.locator('#nav-terminal').click();
      check(`${width}x${height} 退出再进保留身份`, await page.locator('#terminal-screen').getAttribute('data-active-session') === 'pty-1');
      if (width === 390) {
        await screenshot(page, 'phone-preview.png');
        await page.locator('#keyboard-toggle').click();
        await screenshot(page, 'phone-keyboard-preview.png');
      }
      if (width === 1024) await screenshot(page, 'tablet-preview.png');
      await page.close();
    }
    // 悬浮球：鼠标与触摸拖动、吸边、取消手势、焦点/闲置和窗口变化。
    const ballPage = await makePage(390,844);
    await ballPage.locator('#nav-terminal').click();
    const ball = ballPage.locator('#minimal-menu');
    let box = await ball.boundingBox();
    check('悬浮球52px圆形触控目标', Math.round(box.width) === 52 && Math.round(box.height) === 52 && await ball.evaluate(e => getComputedStyle(e).borderRadius) === '50%');
    check('悬浮球仅在终端内', await ball.isVisible());
    await ballPage.mouse.move(box.x + 26, box.y + 26);
    await ballPage.mouse.down();
    await ballPage.mouse.move(38, 430, {steps:14});
    await ballPage.mouse.up();
    box = await ball.boundingBox();
    check('拖动后吸附左边并保持全球可见', Math.abs(box.x - 16) < 2 && box.y >= 28);
    check('拖动不误开菜单', !await ballPage.locator('#session-drawer').evaluate(e => e.open));
    check('拖动不注入终端输入', await ballPage.locator('#command-input').inputValue() === '');
    await ball.click();
    check('拖动结束后的下一次点击仍可开菜单', await ballPage.locator('#session-drawer').evaluate(e => e.open));
    await ballPage.locator('#close-drawer').click();
    await ballPage.waitForFunction(() => !document.querySelector('#session-drawer').open);
    check('菜单关闭焦点回悬浮球', await ball.evaluate(e => e === document.activeElement));
    await ball.press('ArrowRight');
    box = await ball.boundingBox();
    check('键盘方向键移动不输入终端', Math.abs(box.x - (390 - 52 - 16)) < 2 && await ballPage.locator('#command-input').inputValue() === '');
    await ball.press('Enter');
    check('键盘Enter能开悬浮球菜单', await ballPage.locator('#session-drawer').evaluate(e => e.open));
    await ballPage.locator('#drawer-keyboard').click();
    await ballPage.waitForFunction(() => !document.querySelector('#session-drawer').open);
    const blocked = await ballPage.evaluate(() => {
      const ball = document.querySelector('#minimal-menu').getBoundingClientRect();
      const keys = document.querySelector('#extra-keys').getBoundingClientRect();
      const input = document.querySelector('#command-form').getBoundingClientRect();
      return ball.bottom <= keys.top && (ball.bottom <= input.top || ball.top >= input.bottom);
    });
    check('模拟键盘展开悬浮球避开附加键和输入行', blocked);
    await ballPage.locator('#hide-keyboard').click();
    // 真实浏览器触摸事件通道，不使用脚本直接设置坐标。
    const cdp = await ballPage.context().newCDPSession(ballPage);
    box = await ball.boundingBox();
    await cdp.send('Input.dispatchTouchEvent', {type:'touchStart', touchPoints:[{x:box.x + 26,y:box.y + 26,id:1}]});
    await cdp.send('Input.dispatchTouchEvent', {type:'touchMove', touchPoints:[{x:36,y:360,id:1}]});
    await cdp.send('Input.dispatchTouchEvent', {type:'touchEnd', touchPoints:[]});
    box = await ball.boundingBox();
    check('触摸拖动吸附左边且不误开', Math.abs(box.x - 16) < 2 && !await ballPage.locator('#session-drawer').evaluate(e => e.open));
    await cdp.send('Input.dispatchTouchEvent', {type:'touchStart', touchPoints:[{x:box.x + 26,y:box.y + 26,id:1}]});
    await cdp.send('Input.dispatchTouchEvent', {type:'touchMove', touchPoints:[{x:300,y:500,id:1}]});
    await cdp.send('Input.dispatchTouchEvent', {type:'touchCancel', touchPoints:[]});
    check('取消触摸不遗留拖动态或误开菜单', !await ball.evaluate(e => e.classList.contains('is-dragging')) && !await ballPage.locator('#session-drawer').evaluate(e => e.open));
    await cdp.detach();
    await ballPage.mouse.move(150,40);
    await ballPage.locator('#command-input').focus();
    await ballPage.waitForFunction(() => Number(getComputedStyle(document.querySelector('#minimal-menu')).opacity) < .6);
    check('闲置后降低透明度且仍可见', await ball.isVisible());
    await ball.focus();
    check('键盘聚焦恢复清晰', !await ball.evaluate(e => e.classList.contains('is-idle')));
    await ballPage.setViewportSize({width:740,height:360});
    await ballPage.waitForFunction(() => {
      const b = document.querySelector('#minimal-menu').getBoundingClientRect();
      return b.right <= innerWidth - 12 && b.bottom <= document.querySelector('#extra-keys').getBoundingClientRect().top;
    });
    check('横屏缩小仍约束在附加键上方', true);
    box = await ball.boundingBox();
    await ball.click();
    await ballPage.locator('#drawer-home').click();
    await ballPage.waitForFunction(() => !document.querySelector('#session-drawer').open);
    check('返回主界面悬浮球不跨页面显示', await ball.isHidden());
    await ballPage.locator('#nav-terminal').click();
    const restored = await ball.boundingBox();
    check('同页返回再进保留停靠侧和高度', Math.abs(restored.x - box.x) < 2 && Math.abs(restored.y - box.y) < 2);
    await ballPage.setViewportSize({width:390,height:844});
    await screenshot(ballPage, 'phone-floating-ball.png');
    await ball.click();
    await screenshot(ballPage, 'phone-floating-menu.png');
    await ballPage.close();
    const page = await makePage(390,844);
    await screenshot(page, 'home-preview.png');
    // 详细生命周期模拟在工作台模式验证；极简到工作台的切换不销毁任何会话。
    await page.locator('#nav-settings').click();
    await page.locator('#settings-page [data-open-dialog="settings-dialog"]').click();
    await page.locator('#minimal-mode').uncheck();
    await page.locator('#settings-dialog .dialog-actions button').click();
    await page.locator('#nav-plugins').click();
    await page.locator('#nav-terminal').click();
    await page.locator('#back-home').click();
    check('返回原主页面选中项', await page.locator('#nav-plugins').getAttribute('aria-current') === 'page');
    await page.locator('#nav-terminal').click();
    await page.locator('#command-input').fill('echo 未发送草稿');
    await page.locator('#demo-toggle').click();
    await page.locator('#back-home').click();
    await page.waitForFunction(() => Number(document.querySelector('#home-task-count').textContent) >= 2);
    check('离开终端计数仍继续', Number(await page.locator('#home-task-count').textContent()) >= 2);
    await page.locator('#nav-terminal').click();
    check('同会话草稿保留', await page.locator('#command-input').inputValue() === 'echo 未发送草稿');
    check('任务仍运行', await page.locator('#demo-toggle').getAttribute('aria-pressed') === 'true');
    await command(page, 'echo <img src=x onerror=alert(1)>');
    check('echo 不解析 HTML', await page.locator('#terminal-output img').count() === 0 && (await page.locator('#terminal-output').textContent()).includes('<img src=x onerror=alert(1)>'));
    await command(page, 'rm -rf /demo/not-real');
    check('未知命令拒绝模拟执行', (await page.locator('#terminal-output').textContent()).includes('[未执行]'));
    await newSession(page, 'pty');
    await newSession(page, 'simple');
    check('PTY/simple 独立身份', await page.locator('[data-session-id="pty-1"]').count() === 1 && await page.locator('[data-session-id="simple-1"]').count() === 1);
    await closeSession(page, 'pty-1', false);
    check('取消关闭保留目标', await page.locator('[data-session-id="pty-1"]').count() === 1);
    await closeSession(page, 'pty-1');
    check('确认只移除指定目标', await page.locator('[data-session-id="pty-1"]').count() === 0 && await page.locator('[data-session-id="pty-2"]').count() === 1 && await page.locator('[data-session-id="simple-1"]').count() === 1);
    await newSession(page, 'pty');
    check('ID递增而展示号复用', await page.locator('#terminal-screen').getAttribute('data-active-session') === 'pty-3' && await page.locator('#terminal-title').textContent() === 'PTY 1');
    await page.locator('#terminal-settings').click();
    const oldSize = await page.locator('#font-size-label').textContent();
    await page.locator('#font-larger').click();
    check('字号可调整', await page.locator('#font-size-label').textContent() !== oldSize);
    await page.locator('#wrap-output').uncheck();
    check('长行可切换局部横滚', await page.locator('#terminal-output').evaluate(e => e.classList.contains('no-wrap')));
    await page.locator('#settings-dialog .dialog-actions button').click();
    await page.locator('#sessions-toggle').click();
    await page.keyboard.press('Shift+Tab');
    check('抽屉焦点不逃逸', await page.evaluate(() => document.querySelector('#session-drawer').contains(document.activeElement)));
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => !document.querySelector('#session-drawer').open);
    check('关闭抽屉恢复触发器焦点', await page.locator('#sessions-toggle').evaluate(e => e === document.activeElement));
    const before = await page.locator('#terminal-screen').getAttribute('data-active-session');
    await page.setViewportSize({width:1024,height:768});
    await page.waitForFunction(() => document.querySelector('#terminal-screen').dataset.layout === 'sidebar');
    check('宽度变化不新建/更换会话', await page.locator('#terminal-screen').getAttribute('data-active-session') === before && await page.locator('.session-item').count() === 3);
    await page.setViewportSize({width:320,height:568});
    await page.waitForFunction(() => document.querySelector('#terminal-screen').dataset.layout === 'drawer');
    // 模拟根字号扩大两倍，检查控件可经滚动到达；并非完整 Android 字体/IME 验收。
    await page.evaluate(() => document.documentElement.style.fontSize = '32px');
    await page.locator('#terminal-settings').click();
    await page.locator('#font-smaller').click();
    await page.locator('#settings-dialog .dialog-actions button').click();
    check('窄屏两倍根字号对话框操作可达', await page.locator('#settings-dialog').isHidden());
    check('两倍根字号无页面横溢出', (await dimensions(page)).scrollWidth <= 320);
    await page.evaluate(() => document.documentElement.style.fontSize = '16px');
    for (const id of ['pty-2','pty-3','simple-1']) await closeSession(page, id);
    check('全部关闭展示空态', await page.locator('#empty-state').isVisible());
    await page.locator('#back-home').click();
    await page.locator('#nav-terminal').click();
    check('空态重进不自动新建', await page.locator('#empty-state').isVisible() && await page.locator('.session-item').count() === 0);
    await page.locator('#empty-create').click();
    await page.locator('#create-session-confirm').click();
    await page.waitForFunction(() => !document.querySelector('#new-session-dialog').open);
    check('空态可显式新建且ID不回收', await page.locator('#terminal-screen').getAttribute('data-active-session') === 'pty-4');
    const freshPage = await makePage(390,844);
    check('重新载入文档重置仅页面内存状态', await freshPage.locator('[data-session-id="pty-1"]').count() === 1 && await freshPage.locator('.session-item').count() === 1);
    await freshPage.close();
    check('无浏览器脚本异常', result.pageErrors.length === 0, JSON.stringify(result.pageErrors));
    check('无外部网络请求', result.externalRequests.length === 0, JSON.stringify(result.externalRequests));
    await page.close();
    result.passed = true;
  } catch (error) {
    result.passed = false;
    result.failure = String(error.stack || error);
    process.exitCode = 1;
  } finally {
    if (browser) await browser.close();
    result.finishedAt = new Date().toISOString();
    result.checkCount = result.checks.length;
    fs.writeFileSync(path.join(root, 'validation-results.json'), JSON.stringify(result, null, 2) + '\n');
    console.log(JSON.stringify({ passed: result.passed, checks: result.checkCount, browser: result.browser, screenshots: result.screenshots, failure: result.failure }, null, 2));
  }
})();
