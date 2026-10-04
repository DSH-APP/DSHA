import assert from 'node:assert/strict';
import { readFile, writeFile, mkdir, mkdtemp, symlink, rm, readdir } from 'node:fs/promises';
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { randomBytes } from 'node:crypto';
import { once } from 'node:events';
import { createRequire } from 'node:module';
import path from 'node:path';
import { zstdDecompressSync } from 'node:zlib';

const root=path.resolve(import.meta.dirname,'..');
const expectedDsh=JSON.parse(await readFile(path.join(root,'tools/dsh-runtime/package.json'),'utf8')).dependencies['@deepseek-ai/dsh'];
const fixture=JSON.parse(await readFile(path.join(root,'app/build/test-runtimes/current.json'),'utf8'));
const runtime=path.resolve(process.env.DSHA_TEST_RUNTIME||fixture.raw);
const actual=JSON.parse(await readFile(path.join(runtime,'node_modules/@deepseek-ai/dsh/package.json'),'utf8'));
assert.equal(actual.version,expectedDsh);
const tempParent=path.join(root,'app/build/tmp');await mkdir(tempParent,{recursive:true});
const temp=await mkdtemp(path.join(tempParent,'recovery-profile-'));
const instance=randomBytes(16).toString('hex'),token=randomBytes(32).toString('hex'),profile=`dsha-emergency-${instance}`;
const temporaryKey=process.env.DSHA_TEST_TEMPORARY_KEY==='1'?`sk-DSHA-SYNTHETIC-${randomBytes(24).toString('hex')}`:'';
const home=path.join(temp,'home'),data=path.join(home,'.dsh'),profileRoot=path.join(data,'profiles',profile),plugin=path.join(home,'recovery-agent');
await mkdir(profileRoot,{recursive:true});await mkdir(plugin,{recursive:true});
await writeFile(path.join(plugin,'package.json'),JSON.stringify({name:'dsha-recovery-agent',version:'0.1.0',private:true,type:'module'}));
const agentSource=await readFile(path.join(root,'app/src/main/assets/recovery-agent.js'),'utf8');
assert.equal(agentSource.split("open('/root/.recovery-stop',").length,2,'test must translate only the fixed guest stop path to its own host HOME');
await writeFile(path.join(plugin,'recovery-agent.js'),agentSource.replace("open('/root/.recovery-stop',",`open(${JSON.stringify(path.join(home,'.recovery-stop'))},`));
await writeFile(path.join(plugin,'test-observer.js'),`import { writeFile } from 'node:fs/promises';
export const inject=['agents','tools','workspaceRegistry','deepseekLlmApiExtensions'];
export function apply(ctx){ctx.on('agent/created',async({agent})=>{
  const started=performance.now();
  const extension=await ctx.deepseekLlmApiExtensions.prepare({signal:new AbortController().signal});
  await writeFile(process.env.DSHA_RECOVERY_TEST_SESSION,JSON.stringify({header:agent.session.header,tools:ctx.tools.schemas(agent).map(row=>row.name).sort(),workspaces:ctx.workspaceRegistry.list().map(row=>({id:row.id,path:row.path,title:row.title})),extensionPackages:extension.fields.dsh_plugin_packages?.packages,extensionPrepareMs:Math.round(performance.now()-started)}));
});}\n`);
await symlink(path.join(runtime,'node_modules'),path.join(plugin,'node_modules'),process.platform==='win32'?'junction':'dir');
await symlink(path.join(runtime,'node_modules'),path.join(profileRoot,'node_modules'),process.platform==='win32'?'junction':'dir');
await writeFile(path.join(profileRoot,'package.json'),(await readFile(path.join(root,'app/src/main/assets/recovery-profile-package.json'),'utf8')).replaceAll('@PROFILE@',profile));
await writeFile(path.join(profileRoot,'cordis.patch.yml'),(await readFile(path.join(root,'app/src/main/assets/recovery-profile.patch.yml'),'utf8')).replaceAll('@PLUGIN_ROOT@',plugin.replaceAll('\\','/')).replaceAll('@RECOVERY_HOME@',home.replaceAll('\\','/'))
  +`\n- insert:\n    - id: recovery-test-observer\n      name: '${plugin.replaceAll('\\','/')}/test-observer.js'\n`);
await writeFile(path.join(data,'settings.yaml'),'{}\n');
let calls=0;
const broker=createServer((req,res)=>{
  if(req.method!=='POST'||req.url!=='/v1/diagnostics'||req.headers.authorization!==`Bearer ${token}`){res.writeHead(403).end();return;}
  req.resume();calls++;res.writeHead(200,{'Content-Type':'application/json'}).end('{"status":"ok"}');
});
await new Promise(resolve=>broker.listen(0,'127.0.0.1',resolve));
let output='',exited=false,child,browser,browserStorageEvidence;
try{
  const env={...process.env,HOME:home,USERPROFILE:home,DSH_HOME:data,DSH_TELEMETRY_DISABLED:'1',DSH_CONFIRM:'1',BROWSER:'true',DSH_PERMISSION_MODE:'read-only',
    DSHA_RECOVERY_INSTANCE_ID:instance,DSHA_RECOVERY_GENERATION:'1',DSHA_RECOVERY_BROKER_PORT:String(broker.address().port),DSHA_RECOVERY_BROKER_TOKEN:token,
    DSHA_RECOVERY_TEST_SESSION:path.join(temp,'session-observation.json')};
  delete env.DEEPSEEK_API_KEY;delete env.OPENAI_API_KEY;
  if(temporaryKey)env.DEEPSEEK_API_KEY=temporaryKey;
  child=spawn(process.execPath,['--expose-internals',path.join(runtime,'node_modules/@deepseek-ai/dsh/lib/bin.js'),'--profile',profile,'--no-open','--host','127.0.0.1','--port','0'],{cwd:home,env,windowsHide:true,stdio:['ignore','pipe','pipe']});
  child.stdout.on('data',chunk=>{output+=chunk;});child.stderr.on('data',chunk=>{output+=chunk;});child.once('exit',()=>{exited=true;});
  const deadline=Date.now()+60000;
  while(!exited&&!output.includes(`DSHA_RECOVERY_AGENT_READY:${instance}`)&&!output.includes('DSHA_RECOVERY_AGENT_FAILED')&&Date.now()<deadline)await new Promise(resolve=>setTimeout(resolve,100));
  assert.match(output,new RegExp(`DSHA_RECOVERY_AGENT_READY:${instance}`),'complete rc2 profile must publish checked readiness');
  assert.equal(calls,1);
  assert.equal(exited,false);
  // 等 Web 绑定并实际取回 HTML；不把工具初始化完成替代 HTTP 就绪。
  let auth;
  const urlDeadline=Date.now()+20000;
  while(!exited&&!auth&&Date.now()<urlDeadline){auth=output.match(/http:\/\/127\.0\.0\.1:\d+\/[^\s\x1b]*/)?.[0];if(!auth)await new Promise(resolve=>setTimeout(resolve,100));}
  assert.ok(auth,'DSH official URL must be emitted');
  assert.doesNotMatch(output,/entries did not activate/,'complete profile services must activate');
  const handshake=await fetch(auth,{redirect:'manual',signal:AbortSignal.timeout(10000)});
  assert.equal(handshake.status,303);
  const cookie=handshake.headers.get('set-cookie')?.split(';')[0];assert.ok(cookie?.startsWith('dsh-auth-'));
  const response=await fetch(new URL('/',auth),{headers:{Cookie:cookie},signal:AbortSignal.timeout(10000)});
  assert.ok(response.ok);assert.match(await response.text(),/<html/i);
  const require=createRequire(import.meta.url);
  let playwright;
  try{playwright=require(process.env.DSHA_PLAYWRIGHT||'playwright');}
  catch{playwright=require(path.join(process.env.USERPROFILE||'','.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright'));}
  browser=await playwright.chromium.launch({headless:true,executablePath:process.env.DSHA_BROWSER});
  const browserContext=await browser.newContext({viewport:{width:393,height:852},isMobile:true,hasTouch:true,locale:'zh-CN'});
  const equals=cookie.indexOf('=');await browserContext.addCookies([{name:cookie.slice(0,equals),value:cookie.slice(equals+1),url:new URL('/',auth).href}]);
  const page=await browserContext.newPage();page.setDefaultTimeout(15000);
  const pageErrors=[],clientWarnings=[];
  page.on('pageerror',error=>pageErrors.push(String(error)));
  page.on('console',message=>{if(['warning','error'].includes(message.type()))clientWarnings.push(message.text());});
  await page.goto(new URL('/',auth).href,{waitUntil:'domcontentloaded'});
  try{
    // 没有模型凭据也必须能关闭配置提示并编辑草稿，不把下载到 HTML 当作浏览器可用。
    const dismiss=page.getByRole('button',{name:/^(稍后配置|Configure later|Later|Skip for now|继续|Continue)$/i});
    for(let attempt=0;attempt<30;attempt++){
      const visible=await dismiss.all();for(const button of visible)if(await button.isVisible())await button.click();
      if(await page.locator('[contenteditable="true"]:visible,textarea:visible').count())break;
      await page.waitForTimeout(200);
    }
    const composer=page.locator('[contenteditable="true"]:visible,textarea:visible').first();
    const later=page.getByRole('button',{name:/^(稍后配置|Configure later|Later|Skip for now)$/i});
    await later.waitFor({state:'visible',timeout:8000}).catch(()=>{});if(await later.isVisible())await later.click();
    await page.getByText('DSHA Recovery',{exact:true}).first().waitFor({state:'visible'});
    assert.equal(await page.getByText('选择一个工作区开始',{exact:true}).count(),0,'empty workspace screen cannot pass');
    await composer.waitFor({state:'visible'});await composer.click();
    const draft='DSHA 应急输入回归：只验证草稿，不发送模型请求。';
    await composer.fill(draft);
    assert.equal(await composer.evaluate(element=>element instanceof HTMLTextAreaElement?element.value:element.textContent),draft);
    assert.equal(await composer.evaluate(element=>element===document.activeElement),true,'composer must acquire focus');
    const send=page.getByRole('button',{name:/^(发送消息|Send message)$/});await send.waitFor({state:'visible'});
    assert.equal(await send.isEnabled(),true,'a selected emergency workspace must allow submission once the draft is nonempty');
    let observed;
    const observationDeadline=Date.now()+10000;
    while(!observed&&Date.now()<observationDeadline){try{observed=JSON.parse(await readFile(path.join(temp,'session-observation.json'),'utf8'));}catch{await page.waitForTimeout(100);}}
    assert.ok(observed,'real session creation must be observed');
    assert.equal(path.resolve(observed.header.cwd),path.resolve(home,'workspace'),'session cwd must bind only this temporary emergency HOME');
    assert.equal(observed.header.agentPreset,'dsha-emergency');
    assert.deepEqual(observed.tools,['dsha_diagnostics','dsha_propose','dsha_read','dsha_result','dsha_targets']);
    assert.ok(observed.extensionPackages?.some(row=>row.name==='dsha-recovery-agent'&&row.version==='0.1.0'),
      'real rc2 DeepSeek request extension must accept the recovery agent manifest');
    assert.ok(Number.isInteger(observed.extensionPrepareMs)&&observed.extensionPrepareMs<5000,
      `host fixture request-extension preparation was slow: ${observed.extensionPrepareMs}ms`);
    assert.equal(observed.workspaces.length,1);assert.equal(path.resolve(observed.workspaces[0].path),path.resolve(home,'workspace'));
    await page.waitForTimeout(500);
    assert.deepEqual(pageErrors,[],'actual browser must have no uncaught page errors');
    assert.deepEqual(clientWarnings.filter(message=>/did not activate|waiting for service|failed to (?:load|activate)|missing service/i.test(message)),[],'required client services must activate');
    const reports=path.join(root,'app/build/reports/recovery-browser');await mkdir(reports,{recursive:true});
    await page.screenshot({path:path.join(reports,'workbench.png'),fullPage:true});
    await writeFile(path.join(reports,'result.json'),JSON.stringify({dshVersion:actual.version,browser:browser.version(),viewport:{width:393,height:852},workspaceSelected:true,sessionCwdInEmergencyHome:true,agentPreset:observed.header.agentPreset,scopedTools:observed.tools,composerVisible:true,composerEditable:true,sendEnabled:true,pageErrors,clientWarnings},null,2)+'\n');
    if(temporaryKey){
      browserStorageEvidence=await page.evaluate(async needle=>{
        const matches=[];let storageValues=0,indexedDbValues=0;
        for(const [label,storage] of [['localStorage',localStorage],['sessionStorage',sessionStorage]])for(let i=0;i<storage.length;i++){
          const key=storage.key(i),value=storage.getItem(key);storageValues++;if(String(key).includes(needle)||String(value).includes(needle))matches.push(`${label}:entry-${i}`);
        }
        const databases=await indexedDB.databases();
        for(const info of databases){if(!info.name)continue;const database=await new Promise((resolve,reject)=>{const request=indexedDB.open(info.name);request.onsuccess=()=>resolve(request.result);request.onerror=()=>reject(request.error);});
          try{for(const store of database.objectStoreNames)await new Promise((resolve,reject)=>{
            const transaction=database.transaction(store,'readonly'),request=transaction.objectStore(store).openCursor();
            request.onerror=()=>reject(request.error);request.onsuccess=()=>{const cursor=request.result;if(!cursor){resolve();return;}
              indexedDbValues++;if(JSON.stringify([cursor.key,cursor.value]).includes(needle))matches.push(`IndexedDB:${info.name}/${store}`);cursor.continue();};
          });}finally{database.close();}
        }
        return {storageValues,indexedDbValues,matches};
      },temporaryKey);
      assert.deepEqual(browserStorageEvidence.matches,[],'temporary API key must not enter browser persistent storage');
      assert.equal(JSON.stringify(await browserContext.cookies()).includes(temporaryKey),false,'temporary API key must not enter cookies');
    }
  }catch(error){
    console.error(JSON.stringify({pageErrors,clientWarnings,body:(await page.locator('body').innerText()).slice(0,7000)},null,2));throw error;
  }
  await browser.close();browser=undefined;
  // 和正式应急环境一样由自己的哨兵正常注销；不以外部 kill 冒充持久化完成。
  const stopped=once(child,'exit');await writeFile(path.join(home,'.recovery-stop'),instance+'\n');
  const exit=await Promise.race([stopped,new Promise((_,reject)=>setTimeout(()=>reject(Error('RECOVERY_NORMAL_STOP_TIMEOUT')),10000))]);
  assert.equal(exit[0],0,'normal emergency shutdown must exit successfully');
  if(temporaryKey){
    assert.equal(output.includes(temporaryKey),false,'temporary API key must not enter startup or shutdown logs');
    const needle=Buffer.from(temporaryKey),matches=[];let filesScanned=0,compressedFilesScanned=0;
    async function scan(directory){for(const entry of await readdir(directory,{withFileTypes:true})){
      const file=path.join(directory,entry.name);if(entry.isSymbolicLink())continue;
      if(entry.isDirectory()){await scan(file);continue;}if(!entry.isFile())continue;
      let bytes=await readFile(file);filesScanned++;
      if(bytes.includes(needle)||bytes.toString('utf16le').includes(temporaryKey))matches.push(path.relative(temp,file));
      if(file.endsWith('.zstd')){bytes=zstdDecompressSync(bytes);compressedFilesScanned++;if(bytes.includes(needle))matches.push(path.relative(temp,file)+':decompressed');}
    }}
    await scan(temp);assert.deepEqual(matches,[],'temporary API key must not enter profile, credentials, session, log or workspace files');
    const reports=path.join(root,'app/build/reports/recovery-browser');
    await writeFile(path.join(reports,'temporary-key.json'),JSON.stringify({dshVersion:actual.version,syntheticKeyOnly:true,modelRequestsSent:0,normalExitCode:exit[0],filesScanned,compressedFilesScanned,browserStorageEvidence,filesystemMatches:matches,processOutputContainsKey:false},null,2)+'\n');
    console.log(`PASS: synthetic temporary API key stayed out of ${filesScanned} fixture files, decompressed sessions, browser storage/cookies and process logs after normal shutdown; no model request`);
  }
  console.log('PASS: isolated empty rc2 profile, checked repair roster, native auth cookie, real Chromium workbench/composer edit+focus and zero page errors');
}catch(error){
  console.error((temporaryKey?output.replaceAll(temporaryKey,'[synthetic-key]'):output).replaceAll(token,'[broker-token]').replace(/([?&](?:token|auth|key)=)[^\s&]+/g,'$1[redacted]').slice(-14000));throw error;
}finally{
  if(browser)await browser.close();
  if(child&&!exited){const stopped=once(child,'exit');child.kill();await Promise.race([stopped,new Promise(resolve=>setTimeout(resolve,5000))]);}
  await new Promise(resolve=>broker.close(resolve));
  // 只删除本测试新建、已确认位于 build/tmp 内且没有存活进程的夹具。
  if((!child||exited)&&path.dirname(path.resolve(temp))===path.resolve(tempParent))await rm(temp,{recursive:true,force:true});
}
