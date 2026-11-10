'use strict';
/*
 * 适配度审计：找出需要压缩的组件与文案（不出图，只量尺寸）。
 * 用法：
 *   PLAYWRIGHT_HOME=/usr/local/lib/node_modules node tools/audit-fit.cjs [--profiles a,b] [--pages a,b] [--variant proposal] [--out file.json]
 * 每屏量这些指标（单位 dp，浅色主题即可，深浅不影响尺寸）：
 *   chrome    不随内容滚动的固定区高度 = .app 高 − 主滚动区可视高；比例 chromePct
 *   pages     主滚动区内容高 / 可视高（≥3 屏说明信息过长）
 *   fold      首个主按钮（.btn.pri）顶部是否在首屏可视区内（手机扣掉悬浮胶囊）
 *   trunc     被省略号截断的单行文字
 *   wrapBtn   折成多行的按钮 / 胶囊 / 分段 / 标签文字
 *   wrapTitle 折成 3 行以上的卡片标题、条目标题、页标题
 *   longLine  说明文字单行超过 46 个汉字宽（宽屏阅读行过长）
 *   wideCard  卡片宽于 760dp（宽屏单栏拉伸）
 *   popup     弹层宽 / 高 / 宽高比 / 正文可视高；正文可视高 < 120dp 或宽高比 > 2.2 记为 popupBad
 */
const path = require('node:path');
const fs = require('node:fs');
const { pathToFileURL } = require('node:url');
const { chromium } = require(require.resolve('playwright', { paths: [process.env.PLAYWRIGHT_HOME || process.cwd()] }));

const root = path.resolve(__dirname, '..');
const url = pathToFileURL(path.join(root, 'index.html')).href;
const arg = (name, def) => { const i = process.argv.indexOf('--' + name); return i > 0 ? process.argv[i + 1] : def; };

(async () => {
  const launchArgs = (process.env.PW_ARGS ?? '--single-process --no-zygote --disable-gpu').split(/\s+/).filter(Boolean);
  const browser = await chromium.launch({ args: launchArgs });
  const ctx = await browser.newContext({ viewport: { width: 400, height: 400 }, deviceScaleFactor: 1 });
  const page = await ctx.newPage();
  const go = async q => {
    for (let i = 0; ; i++) {
      try { await page.goto(url + q, { waitUntil: 'commit', timeout: 10000 }); await page.waitForSelector('body[data-ready="1"]', { timeout: 10000 }); return; }
      catch (e) { if (i >= 2) throw e; }
    }
  };
  await go('?list=1');
  const pages = JSON.parse(await page.evaluate(() => document.body.textContent));
  const all = await page.evaluate(() => DSHA.PROFILES);
  const profiles = (arg('profiles') || 'phone,phone-landscape,tablet-portrait,tablet-landscape').split(',');
  const wantPages = arg('pages') ? arg('pages').split(',') : null;
  const variant = arg('variant', '');
  const rows = [];

  for (const prof of profiles) {
    const P = all[prof];
    await page.setViewportSize({ width: P.w, height: P.h });
    for (const pg of pages) {
      if (wantPages && !wantPages.includes(pg.id)) continue;
      for (const st of pg.states) {
        await go(`?page=${encodeURIComponent(pg.id)}&profile=${prof}&theme=light&state=${encodeURIComponent(st.id)}${variant ? '&variant=' + variant : ''}`);
        const m = await page.evaluate(() => {
          const app = document.querySelector('.app');
          const ar = app.getBoundingClientRect();
          const scrolls = [...document.querySelectorAll('.app .scroll')].filter(s => s.clientHeight > 40);
          // 主滚动区：可视面积最大者
          const main = scrolls.sort((a, b) => b.clientHeight * b.clientWidth - a.clientHeight * a.clientWidth)[0];
          const nav = document.querySelector('.app .nav');
          const navTop = nav ? nav.getBoundingClientRect().top : ar.bottom;
          const visibleH = main ? Math.min(main.getBoundingClientRect().bottom, navTop) - main.getBoundingClientRect().top : 0;
          const chrome = main ? Math.round(ar.height - visibleH) : Math.round(ar.height);
          const pagesN = main ? +(main.scrollHeight / Math.max(1, visibleH)).toFixed(2) : 1;
          const label = el => (el.textContent || el.getAttribute('aria-label') || '').replace(/\s+/g, ' ').trim().slice(0, 40);
          const shown = el => { const r = el.getBoundingClientRect(); if (!r.width || !r.height) return false;
            const cs = getComputedStyle(el); return cs.visibility !== 'hidden' && cs.display !== 'none'; };
          let fold = null;
          const pri = [...document.querySelectorAll('.app .btn.pri')].filter(shown)[0];
          if (pri) {
            const r = pri.getBoundingClientRect();
            const limit = main && main.contains(pri) ? Math.min(main.getBoundingClientRect().bottom, navTop) : ar.bottom;
            fold = { text: label(pri), top: Math.round(r.top - ar.top), visible: r.bottom <= limit + 1 };
          }
          const lh = el => { const cs = getComputedStyle(el); const v = parseFloat(cs.lineHeight); return isNaN(v) ? parseFloat(cs.fontSize) * 1.3 : v; };
          const lines = el => {
            // 只量文字节点的行盒（图标 svg 不算一行），按字号一半的容差合并同一行
            const fsz = parseFloat(getComputedStyle(el).fontSize) || 14;
            const tops = [];
            const walk = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
            for (let n = walk.nextNode(); n; n = walk.nextNode()) {
              if (!n.textContent.trim()) continue;
              const rg = document.createRange(); rg.selectNodeContents(n);
              for (const r of rg.getClientRects()) if (r.width > 1 && !tops.some(t => Math.abs(t - r.top) < fsz * 0.5)) tops.push(r.top);
            }
            return tops.length;
          };
          const trunc = [], wrapBtn = [], wrapTitle = [], longLine = [], wideCard = [];
          document.querySelectorAll('.app *').forEach(el => {
            if (!shown(el)) return;
            const cs = getComputedStyle(el);
            const hasText = [...el.childNodes].some(n => n.nodeType === 3 && n.textContent.trim());
            if (hasText && cs.textOverflow === 'ellipsis' && el.scrollWidth > el.clientWidth + 1) trunc.push(label(el));
            if (hasText && cs.webkitLineClamp && cs.webkitLineClamp !== 'none' && el.scrollHeight > el.clientHeight + 1) trunc.push(label(el) + '（多行截断）');
          });
          document.querySelectorAll('.app .btn, .app button, .app .chip, .app .tag, .app .seg>*, .app .pill, .app [class*="tabs"]>*').forEach(el => {
            if (!shown(el) || !el.textContent.trim()) return;
            if (lines(el) > 1) wrapBtn.push(label(el));
          });
          document.querySelectorAll('.app .h, .app .pg-title, .app .t, .app h1, .app h2, .app h3').forEach(el => {
            if (!shown(el) || !el.textContent.trim()) return;
            const n = lines(el);
            if (n >= 3) wrapTitle.push(label(el) + ` ×${n}`);
          });
          document.querySelectorAll('.app p, .app .s, .app .pg-desc, .app .note>div, .app .desc, .app .sub').forEach(el => {
            if (!shown(el) || !el.textContent.trim()) return;
            const fs = parseFloat(getComputedStyle(el).fontSize);
            const r = el.getBoundingClientRect();
            const chars = r.width / fs;
            if (chars > 46 && lines(el) >= 1 && el.textContent.trim().length > 46) longLine.push(label(el) + ` ≈${Math.round(chars)}字/行`);
          });
          document.querySelectorAll('.app .card').forEach(el => {
            if (!shown(el)) return;
            const w = el.getBoundingClientRect().width;
            if (w > 760) wideCard.push(Math.round(w));
          });
          let popup = null;
          const pp = document.querySelector('.app .pp');
          if (pp) {
            const r = pp.getBoundingClientRect();
            const body = pp.querySelector('.pp-body');
            const bh = body ? body.getBoundingClientRect().height : 0;
            // 分栏模式按正文列算宽高比（左栏是标题与操作，不是被拉宽的正文）
            const ratio = +((pp.classList.contains('m-side') && body ? body.getBoundingClientRect().width : r.width) / r.height).toFixed(2);
            popup = { rule: pp.dataset.rule, w: Math.round(r.width), h: Math.round(r.height), ratio, bodyH: Math.round(bh),
              scrollPct: body && body.scrollHeight > body.clientHeight + 1 ? Math.round(body.clientHeight / body.scrollHeight * 100) : 100,
              bad: bh < 120 || ratio > 2.2 };
          }
          const uniq = a => [...new Set(a)];
          return { appH: Math.round(ar.height), chrome, chromePct: Math.round(chrome / ar.height * 100), pages: pagesN, fold,
            popup, trunc: uniq(trunc), wrapBtn: uniq(wrapBtn), wrapTitle: uniq(wrapTitle), longLine: uniq(longLine), wideCard: uniq(wideCard) };
        });
        rows.push({ page: pg.id, title: pg.title, group: pg.group, state: st.id, stateTitle: st.title, profile: prof, ...m });
      }
    }
  }
  await browser.close();
  const out = arg('out', '/tmp/uitb/fit.json');
  fs.mkdirSync(path.dirname(out), { recursive: true });
  fs.writeFileSync(out, JSON.stringify(rows, null, 1));
  console.log(`屏 ${rows.length} → ${out}`);
})().catch(e => { console.error(e); process.exitCode = 2; });
