'use strict';
/*
 * 导出 @2x PNG 并自检。只打开同目录的离线预览，拦截一切非 file:/data: 请求。
 * 用法：
 *   PLAYWRIGHT_HOME=/usr/local/lib/node_modules node tools/render.cjs [--pages a,b] [--profiles main|edge|all|id,id] [--themes light,dark] [--variant proposal] [--check-only]
 *   --variant proposal 渲染打磨提案层，产物写 screens-proposal/（不进 manifest）
 * 产物：screens/<page>/<state>/<profile>-<theme>.png；清单另由 tools/build-manifest.cjs 生成
 * 自检（任一失败则退出码 1）：
 *   - 页面脚本错误 / 控制台 error / 外部请求 / 缺失图标
 *   - .scroll 与 .dev 横向溢出
 *   - 可见按钮、.entry、.nav-item、.rail-item、[data-touch] 小于 44×44dp（.chip/.tag/[data-dense] 例外）
 *   - 文字被 .app 右缘截断（元素右边界超出 .app）
 */
const path = require('node:path');
const fs = require('node:fs');
const { pathToFileURL } = require('node:url');
const { chromium } = require(require.resolve('playwright', { paths: [process.env.PLAYWRIGHT_HOME || process.cwd()] }));

const root = path.resolve(__dirname, '..');
const url = pathToFileURL(path.join(root, 'index.html')).href;
const arg = (name, def) => { const i = process.argv.indexOf('--' + name); return i > 0 ? process.argv[i + 1] : def; };
const variant = arg('variant', '');
const outDir = path.join(root, variant ? 'screens-' + variant : 'screens');
const checkOnly = process.argv.includes('--check-only');

(async () => {
  // 本机 proroot 环境下 Chromium 多进程导航会卡死；默认用单进程。可用 PW_ARGS='' 关闭。
  const launchArgs = (process.env.PW_ARGS ?? '--single-process --no-zygote --disable-gpu').split(/\s+/).filter(Boolean);
  const browser = await chromium.launch({ args: launchArgs });
  // 单进程模式下关闭再新建 context 会崩溃：全程只用一个 context / page，按档位改视口。
  const shared = await browser.newContext({ viewport: { width: 400, height: 400 }, deviceScaleFactor: 2 });
  const sharedPage = await shared.newPage();
  const problems = [];
  const errorsOf = page => {
    const errs = [];
    page.on('pageerror', e => errs.push('pageerror ' + e.message));
    page.on('console', m => { if (m.type() === 'error' || /missing icon/.test(m.text())) errs.push('console ' + m.text()); });
    page.on('request', r => { if (!/^(file|data):/.test(r.url())) errs.push('external ' + r.url()); });
    return errs;
  };
  const go = async (page, q) => {
    for (let i = 0; ; i++) {
      try { await page.goto(url + q, { waitUntil: 'commit', timeout: 10000 }); await page.waitForSelector('body[data-ready="1"]', { timeout: 10000 }); return; }
      catch (e) { if (i >= 2) throw e; }
    }
  };

  // 清单
  const lp = sharedPage;
  const lerr = errorsOf(lp);
  await go(lp, '?list=1');
  const pages = JSON.parse(await lp.evaluate(() => document.body.textContent));
  const profilesAll = await lp.evaluate(() => DSHA.PROFILES);
  const mainP = await lp.evaluate(() => DSHA.MAIN_PROFILES);
  const edgeP = await lp.evaluate(() => DSHA.EDGE_PROFILES);
  if (lerr.length) problems.push({ where: 'list', errs: lerr.slice() });

  const wantPages = arg('pages') ? arg('pages').split(',') : null;
  const pf = arg('profiles', 'main');
  const profiles = pf === 'main' ? mainP : pf === 'edge' ? edgeP : pf === 'all' ? mainP.concat(edgeP) : pf.split(',');
  const themes = arg('themes', 'light,dark').split(',');
  const manifest = [];
  let shots = 0;

  for (const prof of profiles) {
    const P = profilesAll[prof];
    if (!P) { problems.push({ where: prof, errs: ['未知档位'] }); continue; }
    const page = sharedPage;
    await page.setViewportSize({ width: P.w, height: P.h });
    const errs = lerr;
    for (const pg of pages) {
      if (wantPages && !wantPages.includes(pg.id)) continue;
      for (const st of pg.states) {
        for (const theme of themes) {
          errs.length = 0;
          const q = `?page=${encodeURIComponent(pg.id)}&profile=${prof}&theme=${theme}&state=${encodeURIComponent(st.id)}${variant ? '&variant=' + variant : ''}`;
          const where = `${pg.id}/${st.id}/${prof}-${theme}`;
          try { await go(page, q); } catch (e) { problems.push({ where, errs: ['加载失败 ' + e.message] }); continue; }
          await page.waitForTimeout(60);
          const found = await page.evaluate(() => {
            const out = [];
            const app = document.querySelector('.app');
            const dev = document.querySelector('.dev');
            const ar = app.getBoundingClientRect();
            if (dev.scrollWidth > dev.clientWidth + 1) out.push('设备横向溢出 ' + (dev.scrollWidth - dev.clientWidth));
            document.querySelectorAll('.scroll').forEach(s => { if (s.scrollWidth > s.clientWidth + 1) out.push('.scroll 横向溢出 ' + (s.scrollWidth - s.clientWidth)); });
            const visible = el => { const r = el.getBoundingClientRect(); if (r.width === 0 || r.height === 0) return null;
              const cs = getComputedStyle(el); if (cs.visibility === 'hidden' || cs.display === 'none') return null;
              if (r.bottom <= ar.top || r.top >= ar.bottom) return null; return r; };
            const name = el => (el.className && typeof el.className === 'string' ? '.' + el.className.trim().split(/\s+/).join('.') : el.tagName) + ' "' + (el.textContent || el.getAttribute('aria-label') || '').trim().slice(0, 16) + '"';
            document.querySelectorAll('button,.btn,.entry,.nav-item,.rail-item,.ibtn,[data-touch]').forEach(el => {
              if (el.closest('[data-dense]') || el.matches('.chip,.tag,[data-dense]')) return;
              const r = visible(el); if (!r) return;
              if (r.width < 43.5 || r.height < 43.5) out.push('触控过小 ' + Math.round(r.width) + '×' + Math.round(r.height) + ' ' + name(el));
            });
            document.querySelectorAll('.app *').forEach(el => {
              if (!el.children.length && el.textContent.trim()) {
                const r = visible(el); if (!r) return;
                if (r.right > ar.right + 1 && !el.closest('[data-hscroll]')) out.push('超出右缘 ' + Math.round(r.right - ar.right) + ' ' + name(el));
              }
            });
            return [...new Set(out)];
          });
          const all = errs.concat(found);
          if (all.length) problems.push({ where, errs: all });
          if (!checkOnly) {
            const file = path.join(outDir, pg.id, st.id, `${prof}-${theme}.png`);
            fs.mkdirSync(path.dirname(file), { recursive: true });
            await page.locator('.dev').screenshot({ path: file });
            manifest.push({ page: pg.id, group: pg.group, title: pg.title, state: st.id, stateTitle: st.title, profile: prof, w: P.w, h: P.h, theme, file: path.relative(root, file) });
          }
          shots++;
        }
      }
    }
  }
  await browser.close();
  console.log(`页面 ${pages.length}，截图 ${shots}，问题 ${problems.length}`);
  for (const p of problems) console.log('✗ ' + p.where + '\n    ' + p.errs.join('\n    '));
  process.exitCode = problems.length ? 1 : 0;
})().catch(e => { console.error(e); process.exitCode = 2; });
