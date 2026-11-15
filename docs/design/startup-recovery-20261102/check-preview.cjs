'use strict';
// 仅检验同目录离线设计原型；不连接设备或外部网络。
// 用法：PLAYWRIGHT_HOME=/usr/local/lib/node_modules node check-preview.cjs
const assert = require('node:assert/strict');
const path = require('node:path');
const fs = require('node:fs');
const { pathToFileURL } = require('node:url');
const { chromium } = require(require.resolve('playwright', { paths: [process.env.PLAYWRIGHT_HOME || process.cwd()] }));
const url = pathToFileURL(path.join(__dirname, 'preview.html')).href;
const out = path.join(__dirname, 'screens');
const SCEN = ['idle', 'running', 'starting', 'stopped', 'port', 'plugin', 'exit', 'config', 'health', 'page', 'env', 'interrupted', 'pending', 'busy', 'readerr', 'mixed'];

(async () => {
  const browser = await chromium.launch();
  const errors = [];
  fs.mkdirSync(out, { recursive: true });
  const page = await browser.newPage({ viewport: { width: 1800, height: 1400 } });
  page.on('pageerror', e => errors.push(e.message));
  page.on('request', r => { if (!/^(file|data):/.test(r.url())) errors.push('external ' + r.url()); });
  const go = async (q, act) => {
    // file:// 的 load 事件在本机 Chromium 上偶发不触发；改等提交后的屏幕节点。
    // 只重试导航本身（最多 3 次），断言不重试。
    for (let i = 0; ; i++) {
      try { await page.goto(url + q, { waitUntil: 'commit', timeout: 8000 }); await page.waitForSelector('#screen .app', { timeout: 8000 }); break; }
      catch (e) { if (i >= 2) throw e; }
    }
    if (act) await act();
    await page.waitForTimeout(120);
    const over = await page.evaluate(() => [...document.querySelectorAll('#screen .scroll')].map(s => s.scrollWidth - s.clientWidth));
    assert.ok(over.every(v => v <= 0), q + ' horizontal overflow ' + over);
  };
  const shot = name => page.locator('#device').screenshot({ path: path.join(out, name + '.png') });
  // 全部方案 × 状态 × 设备：无脚本错误、无横向溢出；记录里不得再出现二义文案。
  for (const v of ['o', 'a', 'b', 'c']) for (const s of SCEN) for (const d of ['phone', 'tp', 'tl']) {
    await go(`?v=${v}&scenario=${s}&device=${d}`);
    if (v !== 'o') assert.ok(!(await page.locator('#screen').innerText()).includes('启动中／已中断'), `${v}/${s} ambiguous label`);
  }
  for (const [n, q] of [
    ['orig-tp-idle', '?v=o&scenario=idle'],
    ['a-tp-idle', '?v=a&scenario=idle'], ['a-phone-idle', '?v=a&scenario=idle&device=phone'],
    ['a-phone-port', '?v=a&scenario=port&device=phone'], ['a-phone-pending', '?v=a&scenario=pending&device=phone'],
    ['a-tl-mixed', '?v=a&scenario=mixed&device=tl'], ['a-phone-light-1.3x-plugin', '?v=a&scenario=plugin&device=phone&theme=light&fs=1.3'],
    ['b-phone-config', '?v=b&scenario=config&device=phone'], ['c-tl-env', '?v=c&scenario=env&device=tl'],
  ]) { await go(q); await shot(n); }
  await go('?v=a&scenario=mixed&device=phone', () => page.locator('#screen [data-a="rec"]').nth(1).click());
  await shot('a-phone-record-detail');
  await go('?v=a&scenario=config&device=phone&outcome=fail', async () => {
    await page.locator('#screen .status [data-a="do"]').first().click();
    await page.locator('#screen [data-a="run"]').click();
  });
  assert.ok((await page.locator('#screen .banner.bad').count()) === 1, 'failure banner');
  await shot('a-phone-failed-result');
  await browser.close();
  assert.deepEqual(errors, []);
  console.log('ok');
})().catch(e => { console.error(e); process.exit(1); });
