'use strict';
// 按页面清单核对 screens/ 下三个主档位 × 深浅的导出图，写 screens/manifest.json 与 manifest.js（gallery.html 用）。
// 缺图或多余文件时退出码 1。用法：PLAYWRIGHT_HOME=/usr/local/lib/node_modules node tools/build-manifest.cjs
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { chromium } = require(require.resolve('playwright', { paths: [process.env.PLAYWRIGHT_HOME || process.cwd()] }));
const root = path.resolve(__dirname, '..');
(async () => {
  const args = (process.env.PW_ARGS ?? '--single-process --no-zygote --disable-gpu').split(/\s+/).filter(Boolean);
  const b = await chromium.launch({ args });
  const p = await b.newPage();
  await p.goto(pathToFileURL(path.join(root, 'index.html')).href + '?list=1', { waitUntil: 'commit' });
  await p.waitForSelector('body[data-ready="1"]');
  const pages = JSON.parse(await p.evaluate(() => document.body.textContent));
  const P = await p.evaluate(() => DSHA.PROFILES);
  const main = await p.evaluate(() => DSHA.MAIN_PROFILES);
  await b.close();
  const out = [], missing = [], extra = [];
  for (const pg of pages) {
    const want = new Set();
    for (const st of pg.states) for (const pf of main) for (const theme of ['light', 'dark']) {
      const rel = `screens/${pg.id}/${st.id}/${pf}-${theme}.png`;
      want.add(rel);
      if (!fs.existsSync(path.join(root, rel))) { missing.push(rel); continue; }
      out.push({ page: pg.id, group: pg.group, title: pg.title, source: pg.source, state: st.id, stateTitle: st.title, profile: pf, w: P[pf].w, h: P[pf].h, theme, file: rel });
    }
    const dir = path.join(root, 'screens', pg.id);
    if (fs.existsSync(dir)) for (const st of fs.readdirSync(dir)) for (const f of fs.readdirSync(path.join(dir, st))) {
      const rel = `screens/${pg.id}/${st}/${f}`;
      if (!want.has(rel)) extra.push(rel);
    }
  }
  for (const d of fs.readdirSync(path.join(root, 'screens'))) {
    if (fs.statSync(path.join(root, 'screens', d)).isDirectory() && !pages.some(pg => pg.id === d)) extra.push('screens/' + d + '/');
  }
  fs.writeFileSync(path.join(root, 'screens/manifest.json'), JSON.stringify(out, null, 1) + '\n');
  fs.writeFileSync(path.join(root, 'screens/manifest.js'), '// 由 tools/build-manifest.cjs 生成。\nwindow.MANIFEST = ' + JSON.stringify(out) + ';\n');
  console.log(`页面 ${pages.length}，状态 ${pages.reduce((a, x) => a + x.states.length, 0)}，图 ${out.length}，缺 ${missing.length}，多余 ${extra.length}`);
  missing.concat(extra).forEach(x => console.log('  ' + x));
  process.exitCode = missing.length || extra.length ? 1 : 0;
})().catch(e => { console.error(e); process.exitCode = 2; });
