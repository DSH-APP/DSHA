import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {JSDOM} from 'jsdom';
import jsQR from 'jsqr';

const dist = new URL('../dist/', import.meta.url);
const read = name => fs.readFileSync(new URL(name, dist), 'utf8');
const details = JSON.parse(read('api/catalog.json')).entries.find(e => e.kind === 'plugin');
const original = {url:details.installSource || details.download.url, sha256:details.download.sha256, name:details.packageName, version:details.version};
const query = values => new URLSearchParams(values).toString();
function page(route, {execute = true, android = false} = {}) {
  const dom = new JSDOM(read(route.startsWith('/install/') ? 'install/index.html' : 'index.html'), {url:'https://dsha.cc' + route, runScripts:'outside-only'});
  const w = dom.window;
  if (android) Object.defineProperty(w.navigator, 'userAgent', {value:'Android'});
  if (execute) for (const script of w.document.querySelectorAll('script[src]')) w.eval(read(script.getAttribute('src').slice(1)));
  return {dom, w, $:s => w.document.querySelector(s), event:(el, type) => el.dispatchEvent(new w.Event(type, {bubbles:true, cancelable:true}))};
}
function decodedQr(svg) {
  const width = Number(svg.getAttribute('viewBox').split(' ')[2]), scale = 4, pixels = width * scale;
  const rgba = new Uint8ClampedArray(pixels * pixels * 4).fill(255);
  for (const m of svg.querySelector('path').getAttribute('d').matchAll(/M(\d+),(\d+)h1v1h-1z/g)) {
    for (let y=+m[2]*scale;y<(+m[2]+1)*scale;y++) for (let x=+m[1]*scale;x<(+m[1]+1)*scale;x++) {
      const p=(y*pixels+x)*4; rgba[p]=rgba[p+1]=rgba[p+2]=0;
    }
  }
  return jsQR(rgba,pixels,pixels)?.data;
}
test('同一来源重复生成保留摘要、名称和版本，扫码得到完整 HTTPS 参数', () => {
  const p=page('/install/?'+query(original));
  try {
    p.event(p.$('form'),'submit'); p.event(p.$('form'),'submit');
    assert.deepEqual(Object.fromEntries(new URL(p.$('[data-open-scheme]').href).searchParams), original);
    assert.equal(p.$('[data-install-actions]').hidden,false);
    const full=p.$('#install-share-url').value;
    assert.equal(decodedQr(p.$('[data-install-qr] svg')),full);
    assert.deepEqual(Object.fromEntries(new URL(full).searchParams),original);
    assert.equal(new URL(full).origin,'https://dsha.cc');
  } finally {p.w.close();}
});
test('修改输入立即撤下旧入口、二维码和地址；新来源不携带旧包摘要', () => {
  const p=page('/install/?'+query(original));
  try {
    p.$('#plugin-source').value='@example/new-plugin';p.event(p.$('#plugin-source'),'input');
    assert.equal(p.$('[data-open-app]').hasAttribute('href'),false);
    assert.equal(p.$('[data-install-actions]').hidden,true);
    assert.equal(p.$('[data-install-sharing]').hidden,true);
    assert.equal(p.$('[data-install-qr]').children.length,0);
    assert.equal(p.w.location.search,'');
    p.event(p.$('form'),'submit');
    assert.deepEqual(Object.fromEntries(new URL(p.$('#install-share-url').value).searchParams),{url:'@example/new-plugin'});
    p.$('#plugin-source').value=original.url;p.event(p.$('#plugin-source'),'input');p.event(p.$('form'),'submit');
    assert.deepEqual(Object.fromEntries(new URL(p.$('#install-share-url').value).searchParams),original);
  } finally {p.w.close();}
});
test('Android 唤起和未安装回退保留相同参数，内置入口也可扫码', () => {
  const p=page('/install/?'+query(original),{android:true});
  const builtin=page('/install/?builtin=dsh-test-builtin');
  try {
    const intent=p.$('[data-open-app]').getAttribute('href');
    assert.match(intent,/^intent:\/\/install\?/);
    const fallback=decodeURIComponent(intent.split('S.browser_fallback_url=')[1].split(';end')[0]);
    assert.equal(fallback,p.$('#install-share-url').value);
    assert.deepEqual(Object.fromEntries(new URL(fallback).searchParams),original);
    assert.equal(decodedQr(builtin.$('[data-install-qr] svg')),builtin.$('#install-share-url').value);
  } finally {p.w.close();builtin.w.close();}
});
test('无效和重复参数不能唤起；错误输入不能恢复旧入口', () => {
  for(const suffix of ['url=x&url=y','url=javascript%3Aalert(1)','url=https%3A%2F%2Fu%3Ap%40example.com','url=x&sha256=bad','url=x%00','url=x&extra=y','url=%ZZ']) {
    const p=page('/install/?'+suffix);
    try {assert.equal(p.$('[data-install-actions]').hidden,true,suffix);assert.equal(p.$('[data-open-app]').hasAttribute('href'),false,suffix);} finally {p.w.close();}
  }
  const p=page('/install/?'+query(original));
  try {p.$('#plugin-source').value='http://example.com/test';p.event(p.$('form'),'submit');assert.equal(p.$('[data-open-app]').hasAttribute('href'),false);} finally {p.w.close();}
});
test('过长二维码不截断来源，提供完整复制回退', () => {
  const url='https://example.com/'+ 'a'.repeat(1600);
  const p=page('/install/?'+query({url}));
  try {assert.equal(p.$('[data-install-qr]').children.length,0);assert.match(p.$('[data-install-qr-note]').textContent,/复制/);assert.equal(new URL(p.$('#install-share-url').value).searchParams.get('url'),url);} finally {p.w.close();}
});
test('复制权限失败后保留可选择的完整安装链接', async () => {
  const p=page('/install/?'+query(original));
  try {
    Object.defineProperty(p.w,'isSecureContext',{value:true});
    Object.defineProperty(p.w.navigator,'clipboard',{value:{writeText:()=>Promise.reject(new Error('denied'))}});
    p.w.document.execCommand=()=>false;
    p.$('[data-copy-target="install-share-url"]').click(); await new Promise(r=>setTimeout(r,0));
    assert.equal(p.w.document.activeElement,p.$('#install-share-url'));
    assert.equal(p.$('#install-share-url').selectionEnd,p.$('#install-share-url').value.length);
    assert.match(p.$('[data-toast]').textContent,/手动复制/);
  } finally {p.w.close();}
});
test('搜索空结果、重置、类别和历史回退保持页面与地址一致', async () => {
  const p=page('/?kind=plugin&q='+details.id);
  try {
    const visible=()=>Array.from(p.w.document.querySelectorAll('[data-entry]')).filter(e=>!e.hidden);
    assert.equal(visible().length,1);
    p.$('[data-search]').value='没有这个插件 987654';p.event(p.$('[data-search]'),'input');await new Promise(r=>setTimeout(r,160));
    assert.equal(visible().length,0);assert.equal(p.$('[data-empty]').hidden,false);
    p.$('[data-reset]').click();assert.equal(visible().length,JSON.parse(read('api/catalog.json')).entries.length);assert.equal(p.w.location.search,'');
    p.$('[data-kind]').value='builtin';p.event(p.$('[data-kind]'),'change');assert.ok(visible().every(e=>e.getAttribute('data-kind')==='builtin'));
    p.w.history.replaceState(null,'','/?kind=plugin&q='+details.id);p.w.dispatchEvent(new p.w.PopStateEvent('popstate'));
    assert.equal(visible().length,1);assert.equal(p.$('[data-kind]').value,'plugin');
  } finally {p.w.close();}
});
test('导航展开、链接收起、Escape 恢复焦点；无 JS 仍有完整导航与下载入口', () => {
  const p=page('/'), offline=page('/install/',{execute:false});
  try {
    const button=p.$('[data-nav-toggle]'), nav=p.$('[data-navigation]');
    button.click();assert.equal(button.getAttribute('aria-expanded'),'true');assert.ok(nav.hasAttribute('data-open'));
    p.w.document.dispatchEvent(new p.w.KeyboardEvent('keydown',{key:'Escape'}));assert.equal(button.getAttribute('aria-expanded'),'false');assert.equal(p.w.document.activeElement,button);
    button.click();nav.querySelector('a').click();assert.equal(button.getAttribute('aria-expanded'),'false');
    assert.equal(offline.w.document.documentElement.classList.contains('js'),false);
    assert.equal(offline.$('[data-navigation]').querySelectorAll('a').length,5);
    assert.ok(offline.$('a[href="/download/"]'));assert.match(offline.$('noscript').textContent,/粘贴到 App/);
    assert.equal(offline.$('[data-navigation]').hidden,false);
  } finally {p.w.close();offline.w.close();}
});
