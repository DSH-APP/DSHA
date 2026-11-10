'use strict';
// 仅检验同目录离线设计原型；不连接设备或外部网络。
// PLAYWRIGHT_HOME 指向已安装 playwright 的目录。
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { chromium } = require(require.resolve('playwright', { paths: [process.env.PLAYWRIGHT_HOME || process.cwd()] }));
const url = pathToFileURL(path.join(__dirname, 'preview.html')).href;
const out = path.join(__dirname, 'screens');

(async () => {
  const browser = await chromium.launch();
  const errors = [];
  const shot = async (name, q, act) => {
    const page = await browser.newPage({ viewport: { width: 1700, height: 1400 } });
    page.on('pageerror', e => errors.push(name + ': ' + e.message));
    page.on('request', r => { if (!/^(file|data):/.test(r.url())) errors.push(name + ': external ' + r.url()); });
    await page.goto(url + q, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(200);
    if (act) await act(page);
    await page.waitForTimeout(300);
    // 屏幕内容不得横向溢出
    const over = await page.evaluate(() => [...document.querySelectorAll('#screen .scroll')].map(s => s.scrollWidth - s.clientWidth));
    assert.ok(over.every(v => v <= 0), name + ' horizontal overflow ' + over);
    await page.locator('#device').screenshot({ path: path.join(out, name + '.png') });
    await page.close();
  };
  const sel = n => async p => { for (let i = 0; i < n; i++) await p.locator('#screen .cbx:not([disabled])').nth(i + (n > 3 ? 0 : 0)).click(); };
  const inspectAll = async p => {
    for (let i = 0; i < 4; i++) await p.locator('#screen .row .cbx:not([disabled]), #screen td .cbx:not([disabled]), #screen .crow .cbx:not([disabled])').nth(i).click();
    await p.locator('#screen [data-a="inspect"]').first().click();
    await p.waitForTimeout(3300);
  };
  require('node:fs').mkdirSync(out, { recursive: true });
  for (const d of ['phone', 'tl']) await shot('orig-' + d, `?v=o&device=${d}&scenario=shot`);
  for (const v of ['a', 'b', 'c']) {
    for (const d of ['phone', 'tp', 'tl']) await shot(`${v}-${d}`, `?v=${v}&device=${d}`);
    await shot(`${v}-phone-dark-1.3x`, `?v=${v}&device=phone&theme=dark&fs=1.3`);
    await shot(`${v}-phone-detail`, `?v=${v}&device=phone`, p => p.locator('#screen [data-a="open"]').nth(4).click());
    await shot(`${v}-phone-inspected`, `?v=${v}&device=phone`, inspectAll);
    await shot(`${v}-tl-inspected`, `?v=${v}&device=tl`, inspectAll);
    await shot(`${v}-phone-empty`, `?v=${v}&device=phone&scenario=empty`);
  }
  await shot('a-phone-running', '?v=a&device=phone', async p => { await sel(3)(p); await p.locator('#screen [data-a="inspect"]').click(); await p.waitForTimeout(900); });
  await shot('a-tl-detail', '?v=a&device=tl', p => p.locator('#screen [data-a="open"]').nth(5).click());
  await shot('a-phone-shot', '?v=a&device=phone&scenario=shot');
  await shot('c-tp-menu', '?v=c&device=tp', p => p.locator('#screen [data-a="menu"]').nth(4).click());
  await shot('c-phone-selmenu', '?v=c&device=phone', p => p.locator('#screen [data-a="selmenu"]').click());
  await shot('a-phone-many', '?v=a&device=phone&scenario=many', p => p.locator('#screen .scroll').evaluate(e => { e.scrollTop = e.scrollHeight; }));
  await browser.close();
  assert.deepEqual(errors, []);
  console.log('ok');
})().catch(e => { console.error(e); process.exit(1); });
