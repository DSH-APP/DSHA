'use strict';
// 仅检验同目录离线设计原型；不连接设备、不验证 Android 渲染。
// 用法：PLAYWRIGHT_HOME=/usr/local/lib/node_modules node check-preview.cjs [截图输出目录]
const assert = require('node:assert/strict');
const path = require('node:path');
const fs = require('node:fs');
const { chromium } = require(require.resolve('playwright', { paths: [process.env.PLAYWRIGHT_HOME || process.cwd()] }));

// 部分容器里 file:// 导航不触发 load，直接注入正文（CSP 仍生效）。
const html = fs.readFileSync(path.join(__dirname, 'preview.html'), 'utf8');
const shots = process.argv[2] || '';
const results = [];
const check = (name, ok, detail = '') => { assert.ok(ok, `${name}: ${detail}`); results.push(name); };

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1600, height: 1100 } });
  const errors = [], external = [];
  page.on('pageerror', e => errors.push(String(e)));
  page.on('request', r => { if (!/^(file|data|about):/.test(r.url())) external.push(r.url()); });
  await page.setContent(html, { waitUntil: 'domcontentloaded' });

  // 1. 选举逻辑与 IslandText 一致（对照 IslandTextTest 的语义）
  const logic = await page.evaluate(() => {
    const { rank, pick, pickForDisplay, shortText, clip, toolName } = window.__preview.logic;
    const r = (phase, started, o = {}) => ({ id: Math.random(), phase, started, active: true, dismissed: false, stopping: false, tool: '', target: '', endedAt: 0, reason: '', ...o });
    const now = 100000;
    const wait = r('waiting', 50), tool = r('tool', 10), reply = r('reply', 1);
    const tieA = r('tool', 5), tieB = r('tool', 9);
    const done = r('reply', 1, { active: false, reason: 'completed', endedAt: now - 3000 });
    const old = r('reply', 1, { active: false, reason: 'completed', endedAt: now - 9000 });
    return {
      order: [rank(wait), rank(r('x', 0, { stopping: true })), rank(tool), rank(reply), rank(r('other', 0)), rank(r('unknown', 0)), rank(r('tool', 0, { dismissed: true }))],
      waitWins: pick([reply, tool, wait]) === wait,
      tie: pick([tieB, tieA]) === tieA,
      grace: pickForDisplay([done], now) === done,
      expired: pickForDisplay([old], now) === null,
      activeBeatsDone: pickForDisplay([done, reply], now) === reply,
      toolCap: shortText(r('tool', 0, { tool: 'read', target: 'a.md' }), true),
      toolCapEn: shortText(r('tool', 0, { tool: 'read', target: 'a.md' }), false),
      long: clip('一二三四五六七八九', true),
      tn: toolName('websearch · q')
    };
  });
  check('rank 顺序 5/4/3/2/1/0/0', JSON.stringify(logic.order) === '[5,4,3,2,1,0,0]', JSON.stringify(logic.order));
  check('等你确认优先', logic.waitWins);
  check('同级先开始优先', logic.tie);
  check('结束后 8 秒内保留', logic.grace && logic.expired);
  check('活跃任务优先于保留中的完成态', logic.activeBeatsDone);
  check('胶囊文本', logic.toolCap === '执行·read' && logic.toolCapEn === 'Tool read' && logic.long === '一二三四五六七' && logic.tn === 'webs', JSON.stringify(logic));

  // 2. 每个方案 × 系统状态 × 设备都能渲染
  for (const device of ['phone', 'tabP', 'tabL']) {
    for (const scheme of ['old', 'a', 'b', 'c']) {
      for (const sys of ['api35', 'q0', 'q1ok', 'q1off']) {
        await page.evaluate(([d, s, y]) => { Object.assign(window.__preview.ui, { device: d, scheme: s, sys: y }); window.__preview.render(); }, [device, scheme, sys]);
        const n = await page.locator('#devices .app').count();
        assert.equal(n, 1, `${device}/${scheme}/${sys}`);
      }
    }
  }
  check('3 设备 × 4 方案 × 4 系统状态渲染', true);

  // 3. 截图状态复刻：现状 + 36.0 显示“无法确认系统状态”
  await page.evaluate(() => { Object.assign(window.__preview.ui, { device: 'tabP', scheme: 'old', sys: 'q0' }); window.__preview.render(); });
  check('现状复刻截图文案', (await page.locator('#devices').innerText()).includes('无法确认系统状态。（API 36）'));

  // 4. 交互：等你确认场景 → B 方案高亮第 5 级；总开关关闭后计时器开关置灰
  await page.evaluate(() => { Object.assign(window.__preview.ui, { scheme: 'b', sys: 'q1ok' }); window.__preview.render(); });
  await page.locator('[data-preset="ask"]').click();
  check('B 高亮等你确认', (await page.locator('#devices .lv.on .no').innerText()) === '5');
  check('系统胶囊为等你确认', (await page.locator('#system .cap').innerText()).includes('等你确认'));
  await page.locator('#devices button.sw[data-k="island"]').click();
  check('总开关关 → 计时器置灰', (await page.locator('#devices button.sw[data-k="chrono"]').getAttribute('aria-disabled')) === 'true');
  check('总开关关 → 无胶囊', (await page.locator('#system .cap').count()) === 0);
  await page.locator('#devices button.sw[data-k="island"]').click();

  // 5. C 方案测试胶囊：无活跃任务时发出，8 秒保留
  await page.evaluate(() => { window.__preview.tasks = []; Object.assign(window.__preview.ui, { scheme: 'c', sys: 'q0' }); window.__preview.render(); });
  await page.locator('#devices [data-act="test"]').click();
  check('测试胶囊（36.0 虚线）', (await page.locator('#system .cap.maybe').innerText()).includes('上岛测试'));

  // 6. 前台时不显示通知
  await page.evaluate(() => { window.__preview.ui.fg = true; window.__preview.render(); });
  check('前台不发通知', (await page.locator('#system .nt').count()) === 0);
  await page.evaluate(() => { window.__preview.ui.fg = false; window.__preview.render(); });

  // 7. 方案选择写出决定
  await page.locator('[data-choice="a"]').click();
  check('方案选择写出决定', (await page.locator('#decision').inputValue()).includes('A 紧凑行'));

  if (shots) {
    await page.evaluate(() => { window.__preview.tasks = window.__preview.PRESETS.three.make(Date.now()); Object.assign(window.__preview.ui, { device: 'tabP', scheme: 'all', sys: 'q0' }); window.__preview.render(); });
    await page.waitForTimeout(200);
    await page.screenshot({ path: path.join(shots, 'island-all.png'), fullPage: false });
    for (const s of ['b', 'c']) {
      await page.evaluate(sc => { Object.assign(window.__preview.ui, { scheme: sc }); window.__preview.render(); }, s);
      await page.waitForTimeout(150);
      await page.screenshot({ path: path.join(shots, `island-${s}.png`) });
    }
    await page.evaluate(() => { Object.assign(window.__preview.ui, { device: 'phone', scheme: 'all', theme: 'dark' }); window.__preview.render(); });
    await page.waitForTimeout(150);
    await page.screenshot({ path: path.join(shots, 'island-phone-dark.png') });
  }
  check('无页面错误', errors.length === 0, errors.join('\n'));
  check('无外部请求', external.length === 0, external.join('\n'));
  await browser.close();
  console.log(`通过 ${results.length} 项：\n- ${results.join('\n- ')}`);
})().catch(e => { console.error(e); process.exit(1); });
