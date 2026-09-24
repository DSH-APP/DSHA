// 从当前锁定 DSH 的实际前端获取 React/DOM/Primitives；不使用另一个版本的渲染器冒充。
import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import {createRequire} from 'node:module';
const require=createRequire(import.meta.url);
export async function browserFixture(runtime=process.env.DSHA_TEST_RUNTIME||JSON.parse(fs.readFileSync('app/build/test-runtimes/current.json','utf8')).raw) {
  const root=path.resolve(runtime,'node_modules/@deepseek-ai/dsh-web-frontend/dist');
  const html=fs.readFileSync(path.join(root,'index.html'),'utf8');
  const entry=html.match(/src="\.\/(assets\/index-[^"]+\.js)"/)[1];
  let bootstrap=fs.readFileSync(path.join(root,entry),'utf8');
  const moduleMatch=bootstrap.match(/function ([A-Za-z_$][\w$]*)\(\)\{return\{react:/);
  if(!moduleMatch)throw Error('current frontend static module anchor changed');
  const boundary=bootstrap.includes('const Jr=globalThis.dshDesktopBoot') ? 'const Jr=globalThis.dshDesktopBoot' : 'const uo=globalThis.dshDesktopBoot';
  const boundaryIndex=bootstrap.indexOf(boundary);
  if(boundaryIndex<0)throw Error('current frontend boot boundary changed');
  bootstrap=bootstrap.slice(0,boundaryIndex)+
    `globalThis.auditModules=${moduleMatch[1]}();globalThis.auditExports={};window.__ModuleLoader__={load:({id,factory})=>{auditExports[id]=factory(name=>{if(!(name in auditModules))throw Error('Missing static module '+name);return auditModules[name]})}};`;
  const server=http.createServer((req,res)=>{
    if(req.url==='/'){res.setHeader('Content-Type','text/html');res.end('<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head><body><div id="root"></div><script type="module" src="/assets/__audit.js"></script></body></html>');return;}
    if(req.url==='/assets/__audit.js'){res.setHeader('Content-Type','text/javascript');res.end(bootstrap);return;}
    const file=path.resolve(root,'.'+decodeURIComponent(req.url.split('?')[0]));
    if(!file.startsWith(root+path.sep)||!fs.existsSync(file)||!fs.statSync(file).isFile()){res.writeHead(404);res.end();return;}
    res.setHeader('Content-Type',file.endsWith('.css')?'text/css':'text/javascript');res.end(fs.readFileSync(file));
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  let playwright;
  try { playwright=require(process.env.DSHA_PLAYWRIGHT||'playwright'); }
  catch { playwright=require(path.join(process.env.USERPROFILE||'', '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright')); }
  const browser=await playwright.chromium.launch({headless:true});
  const page=await browser.newPage({viewport:{width:360,height:800},deviceScaleFactor:1,hasTouch:true});
  const errors=[];page.on('pageerror',e=>errors.push(String(e)));
  await page.goto(`http://127.0.0.1:${server.address().port}/`);
  await page.waitForFunction(()=>globalThis.auditModules);
  return {page,browser,errors,async close(){await browser.close();await new Promise(resolve=>server.close(resolve));},
    async load(name,recipes=[],extra='') {
      let source=fs.readFileSync(path.resolve(runtime,'node_modules/@deepseek-ai',name,'lib/client.js'),'utf8');
      for(const recipe of recipes)for(const patch of recipe.patches){
        if(source.split(patch.before).length!==2)throw Error('Patch anchor: '+patch.before.slice(0,80));
        source=source.replace(patch.before,(patch.prependAsset?fs.readFileSync('app/src/main/assets/'+patch.prependAsset,'utf8')+'\n':'')+patch.after);
      }
      source=source.replace('return module.exports;',extra+'\nreturn module.exports;');
      await page.addScriptTag({content:source});
      const css=path.resolve(runtime,'node_modules/@deepseek-ai',name,'lib/client.css');
      if(fs.existsSync(css))await page.addStyleTag({content:fs.readFileSync(css,'utf8')});
    }};
}
