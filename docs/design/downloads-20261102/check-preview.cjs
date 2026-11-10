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
    page.on('request', r => { if (!r.url().startsWith('file:') && !r.url().startsWith('data:')) errors.push(name + ': external ' + r.url()); });
    await page.goto(url + q, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(300);
    if (act) await act(page);
    await page.locator('#device').screenshot({ path: path.join(out, name + '.png') });
    // 文字不得横向溢出屏幕
    const over = await page.evaluate(() => { const s = document.getElementById('screen'); return s.scrollWidth - s.clientWidth; });
    assert.ok(over <= 0, name + ' horizontal overflow ' + over);
    await page.close();
  };
  require('node:fs').mkdirSync(out, { recursive: true });
  await shot('phone', '?device=phone');
  await shot('phone-filter', '?device=phone', p => p.locator('.segf [data-v="failed"]').click());
  await shot('phone-detail', '?device=phone', async p => { await p.locator('.rhit').nth(5).click(); await p.waitForTimeout(350); });
  await shot('phone-detail-bottom', '?device=phone', async p => { await p.locator('.rhit').nth(5).click(); await p.locator('.tech summary').click(); await p.locator('.pagebody').evaluate(e => { e.scrollTop = e.scrollHeight; }); });
  await shot('phone-detail-downloading', '?device=phone', async p => { await p.locator('.rhit').nth(4).click(); await p.waitForTimeout(800); });
  await shot('phone-detail-failed', '?device=phone', async p => { await p.locator('.rhit').nth(1).click(); await p.waitForTimeout(350); });
  await shot('phone-detail-dark-1.3x', '?device=phone&theme=dark&fs=1.3', async p => { await p.locator('.rhit').nth(5).click(); await p.waitForTimeout(350); });
  await shot('phone-cancel-confirm', '?device=phone', async p => { await p.locator('.rhit').nth(4).click(); await p.locator('.mrow').click(); });
  await shot('phone-back-restores', '?device=phone', async p => { await p.locator('.listpane').evaluate(e => { e.scrollTop = 300; }); await p.locator('.rhit').nth(7).click(); await p.locator('[data-fk="pback"]').click(); const top = await p.locator('.listpane').evaluate(e => e.scrollTop); if (top < 250) throw new Error('scroll not restored ' + top); });
  await shot('phone-menu', '?device=phone', p => p.locator('[data-a="menu"]').nth(5).click());
  await shot('phone-remove', '?device=phone', async p => { await p.locator('[data-a="menu"]').nth(5).click(); await p.locator('#menu [data-act="remove"]').click(); });
  await shot('phone-dark-1.3x', '?device=phone&theme=dark&fs=1.3');
  await shot('phone-shot', '?device=phone&scenario=shot');
  await shot('phone-old', '?device=phone&scenario=shot&variant=old');
  await shot('phone-share-flow', '?device=phone&scenario=share', p => p.waitForTimeout(4500));
  await shot('phone-empty', '?device=phone&scenario=empty');
  await shot('tablet-p', '?device=tablet-p');
  await shot('tablet-p-detail', '?device=tablet-p', async p => { await p.locator('.rhit').nth(5).click(); await p.waitForTimeout(350); });
  await shot('tablet-l', '?device=tablet-l');
  await shot('tablet-l-complete', '?device=tablet-l', p => p.locator('.rhit').nth(5).click());
  await shot('tablet-l-failed-dark', '?device=tablet-l&scenario=failed&theme=dark');
  await browser.close();
  assert.deepEqual(errors, []);
  console.log('ok');
})().catch(e => { console.error(e); process.exit(1); });
