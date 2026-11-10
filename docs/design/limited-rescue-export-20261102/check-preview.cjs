'use strict';
// 仅检验同目录离线设计原型；不连接设备或外部网络。
// PLAYWRIGHT_HOME 指向已安装 playwright 的目录。
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { chromium } = require(require.resolve('playwright', { paths: [process.env.PLAYWRIGHT_HOME || process.cwd()] }));
const url = pathToFileURL(path.join(__dirname, 'preview.html')).href;
const out = path.join(__dirname, 'screens');

(async () => {
  fs.mkdirSync(out, { recursive: true });
  const browser = await chromium.launch();
  const errors = [];
  const shot = async (name, q, act) => {
    const page = await browser.newPage({ viewport: { width: 1700, height: 1300 } });
    page.on('pageerror', e => errors.push(name + ': ' + e.message));
    page.on('request', r => { if (!/^(file|data):/.test(r.url())) errors.push(name + ': external ' + r.url()); });
    await page.goto(url + q, { waitUntil: 'domcontentloaded' });
    if (act) await act(page);
    await page.waitForTimeout(150);
    // 屏幕内容不得横向溢出；触控目标不低于 44dp（按未缩放尺寸计）
    const r = await page.evaluate(() => {
      const over = [...document.querySelectorAll('.scr, .scr .scroll')].map(s => s.scrollWidth - s.clientWidth);
      const small = [...document.querySelectorAll('.col[data-kind=new] .scr button:not(.seg button)')]
        .filter(b => b.offsetParent && b.offsetHeight < 44).map(b => b.textContent.trim() + ':' + b.offsetHeight);
      return { over, small };
    });
    assert.ok(r.over.every(v => v <= 0), name + ' horizontal overflow ' + r.over);
    assert.deepEqual(r.small, [], name + ' small touch targets');
    await page.locator('#stage').screenshot({ path: path.join(out, name + '.png') });
    await page.close();
  };
  for (const dev of ['phone', 'land', 'tp', 'tl']) {
    await shot(`extract-failed-${dev}`, `?page=extract&state=failed&dev=${dev}`);
    await shot(`workspace-pending-${dev}`, `?page=workspace&state=pending&dev=${dev}`);
    await shot(`confirm-${dev}`, `?page=confirm&dev=${dev}&cmp=new`);
  }
  await shot('extract-pending-phone-dark-1.3x', '?page=extract&state=pending&dev=phone&theme=dark&fs=1.3');
  await shot('extract-busy-phone', '?page=extract&state=busy&dev=phone');
  await shot('workspace-pending-phone-en-1.3x', '?page=workspace&state=pending&dev=phone&lang=en&fs=1.3');
  // 交互：改后维护页点「导出可读副本」应进入救援确认
  await shot('flow-extract-to-confirm', '?page=extract&state=failed&dev=phone&cmp=new', async p => {
    await p.locator('.col[data-kind=new] [data-act=rescue]').click();
    assert.equal(await p.locator('.col[data-kind=new] [role=dialog]').count(), 1);
  });
  await browser.close();
  assert.deepEqual(errors, []);
  console.log('ok');
})().catch(e => { console.error(e); process.exit(1); });
