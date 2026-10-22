// Locked official BrowserLogin plus real HTTP PlatformAccount/Authorization/WebServer.
// Only synthetic account responses and an in-memory credential adapter are used.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import {createHash} from 'node:crypto';
import {pathToFileURL} from 'node:url';
import {spawnSync} from 'node:child_process';
import {browserFixture} from './rc1-browser-fixture.mjs';
import {testRuntime} from './test-runtime-fixture.mjs';

const root = path.resolve(import.meta.dirname, '..');
const runtime = testRuntime('raw');
const recipeFile = path.join(root, 'app/src/main/assets/deepseek-native-oauth-patch.json');
const recipe = JSON.parse(fs.readFileSync(recipeFile, 'utf8'));
const original = fs.readFileSync(path.join(runtime, 'node_modules', recipe.module), 'utf8');
const sha = text => createHash('sha256').update(text).digest('hex');
assert.equal(sha(original), recipe.sourceSha256);
const lock = JSON.parse(fs.readFileSync(path.join(root, 'tools/dsh-runtime/package-lock.json'), 'utf8'));
assert.equal(recipe.packageIntegrity, lock.packages['node_modules/@deepseek-ai/dsh-client-ui-settings-account'].integrity);
let patched = original;
for (const patch of recipe.patches) {
  assert.equal(patched.split(patch.before).length, 2);
  patched = patched.replace(patch.before, patch.after);
}
const check = spawnSync(process.execPath, ['--check', '--input-type=commonjs'], {input: patched, encoding: 'utf8', windowsHide: true});
assert.equal(check.status, 0, 'whole locked client must parse');
// Exercise the same archive patch implementation, including its idempotent marker policy.
const python = process.env.DSHA_PYTHON || 'python';
const chainProbe = spawnSync(python, ['-B', '-c',
  "import json,sys;from pathlib import Path;sys.path.insert(0,'tools');from runtime_stable_patches import apply;" +
  "p=json.loads(Path(sys.argv[1]).read_text(encoding='utf-8'));data=Path(sys.argv[2]).read_bytes();" +
  "chain=[(sys.argv[1],[(x['before'],x['after']) for x in p['patches']],['DSHA_NATIVE_OAUTH_NAVIGATION_V1'])];" +
  "out=apply(data,chain);assert apply(out,chain)==out;assert out!=data;print('PASS_ARCHIVE_APPLY_AND_IDEMPOTENCE')",
  recipeFile, path.join(runtime, 'node_modules', recipe.module)], {encoding:'utf8',windowsHide:true});
assert.equal(chainProbe.status, 0, chainProbe.stderr);
const results = [];
async function test(name, run) { await run(); results.push({name, status:'PASS'}); }

const fixture = await browserFixture(runtime);
try {
  await fixture.load('dsh-client-ui-settings-account', [], 'module.exports.audit={BrowserLogin};');
  await test('official popup model loses navigation when an embedded host cannot create its blank popup', async () => {
    const observed = await fixture.page.evaluate(() => {
      let popupCalls=0;
      window.open = () => {++popupCalls;return null;};
      window.__DSHA_NATIVE_PLUGINS__=true;
      const {BrowserLogin}=auditExports['@deepseek-ai/dsh-client-ui-settings-account'].audit;
      const login=new BrowserLogin(value=>value);
      login.begin();
      login.follow({attempt:{id:'unsupported-popup',phase:'waiting-browser',
        authorizeUrl:'https://platform.deepseek.com/dsh/authorize?authorize_id=synthetic-only'}});
      return {popupCalls,popupMissing:login.popup===null,markedNavigated:login.navigated};
    });
    assert.deepEqual(observed,{popupCalls:1,popupMissing:true,markedNavigated:true});
  });
  await fixture.load('dsh-client-ui-settings-account', [recipe], 'module.exports.audit={BrowserLogin};');
  await test('native asynchronous authorize navigation creates no blank popup and keeps attempt identity', async () => {
    const page = fixture.page;
    let nativeDestinations = 0;
    await page.route('https://platform.deepseek.com/dsh/authorize**', async route => {
      ++nativeDestinations;
      // Models the narrow native interception. No credential or request leaves localhost.
      await route.abort('aborted');
    });
    await page.evaluate(() => {
      window.__DSHA_NATIVE_PLUGINS__ = true;
      window.popupOpens = 0;
      window.open = () => { ++window.popupOpens; throw Error('native path must not open a blank popup'); };
      const {BrowserLogin} = auditExports['@deepseek-ai/dsh-client-ui-settings-account'].audit;
      window.browserLogin = new BrowserLogin(value => value);
      window.browserLogin.begin();
      window.nativePopup = browserLogin.popup;
      browserLogin.follow({attempt:{id:'synthetic-attempt',phase:'initializing'}});
    });
    await page.waitForTimeout(30);
    await page.evaluate(() => browserLogin.update({attempt:{id:'synthetic-attempt',phase:'waiting-browser',
      authorizeUrl:'https://platform.deepseek.com/dsh/authorize?authorize_id=synthetic-only'}}));
    await page.waitForTimeout(80);
    assert.equal(nativeDestinations, 1);
    assert.equal(await page.evaluate(() => popupOpens), 0);
    assert.equal(await page.evaluate(() => browserLogin.navigated), true);
    await page.evaluate(() => {
      browserLogin.update({attempt:{id:'other-attempt',phase:'waiting-browser',authorizeUrl:'https://platform.deepseek.com/dsh/authorize'}});
      browserLogin.update({attempt:{id:'synthetic-attempt',phase:'waiting-browser',authorizeUrl:'https://platform.deepseek.com/dsh/authorize'}});
      browserLogin.dispose();
      nativePopup.location.replace('https://platform.deepseek.com/dsh/authorize');
    });
    await page.waitForTimeout(50);
    assert.equal(nativeDestinations, 1, 'different, repeated or disposed attempts do not replay');
    assert.equal(await page.evaluate(() => nativePopup.closed), true);
  });
  await test('ordinary browser retains official synchronous preopen and asynchronous popup replace', async () => {
    const observed = await fixture.page.evaluate(async () => {
      delete window.__DSHA_NATIVE_PLUGINS__;
      const calls = [];
      const popup = {closed:false,opener:'initial',location:{replace:value => calls.push(['replace',new URL(value).pathname])},
        close(){this.closed=true;calls.push(['close']);}};
      window.open = (...args) => {calls.push(['open',...args]);return popup;};
      const {BrowserLogin} = auditExports['@deepseek-ai/dsh-client-ui-settings-account'].audit;
      const login = new BrowserLogin(value => value);
      const running = login.start(async () => {
        await new Promise(resolve => setTimeout(resolve,20));
        return {attempt:{id:'plain',phase:'waiting-browser',authorizeUrl:'https://platform.deepseek.com/dsh/authorize?authorize_id=synthetic-only'}};
      }, () => ({attempt:{id:'plain',phase:'waiting-browser',authorizeUrl:'https://platform.deepseek.com/dsh/authorize?authorize_id=synthetic-only'}}));
      const immediatelyOpened = calls[0]?.[0] === 'open';
      await running;
      const openerCleared = popup.opener === null;
      login.dispose();
      return {calls, immediatelyOpened, openerCleared};
    });
    assert.deepEqual(observed, {calls:[['open','about:blank','_blank'],['replace','/dsh/authorize'],['close']],
      immediatelyOpened:true,openerCleared:true});
  });
  await test('Gecko native OAuth flag is installed only after its native observation nonce arrives', async () => {
    const source = fs.readFileSync(path.join(root, 'app/src/main/assets/web-integration/startup-relay.js'), 'utf8');
    await fixture.page.evaluate(() => {
      delete window.__DSHA_NATIVE_PLUGINS__;
      delete window.__DSHA_NATIVE_OAUTH__;
      window.nativeMessages = [];
      window.nativeMessageListeners = [];
      window.browser = {runtime:{connectNative:() => ({postMessage:value => nativeMessages.push(value),
        onMessage:{addListener:listener => nativeMessageListeners.push(listener)}})}};
    });
    await fixture.page.addScriptTag({content:source});
    await fixture.page.evaluate(() => nativeMessageListeners[0]({type:'language',language:'en',observationNonce:'invalid'}));
    assert.equal(await fixture.page.evaluate(() => window.__DSHA_NATIVE_OAUTH__), undefined);
    await fixture.page.evaluate(() => nativeMessageListeners[0]({type:'language',language:'en',observationNonce:'a'.repeat(32)}));
    assert.equal(await fixture.page.evaluate(() => window.__DSHA_NATIVE_OAUTH__), true);
    assert.equal(await fixture.page.evaluate(() => {
      const {BrowserLogin} = auditExports['@deepseek-ai/dsh-client-ui-settings-account'].audit;
      window.open = () => {throw Error('Gecko must not create an unsupported popup');};
      const login = new BrowserLogin(value=>value);
      login.begin();
      const present = login.popup !== null && login.popup.closed === false;
      login.dispose();
      return present;
    }), true);
  });
} finally { await fixture.close(); }

const load = name => import(pathToFileURL(path.join(runtime, 'node_modules', name, 'lib/index.js')));
const [{Context}, {default:Authorization}, {default:WebServer}, {default:PlatformAccount}] = await Promise.all([
  load('@deepseek-ai/cordis'),load('@deepseek-ai/dsh-authorization'),load('@deepseek-ai/dsh-host-webserver'),
  load('@deepseek-ai/dsh-deepseek-account-platform')]);
const client = {version:'oauth-fixture',locale:'en',timezoneOffsetSeconds:0};
const delay = ms => new Promise(resolve => setTimeout(resolve,ms));
async function until(predicate) { for(let i=0;i<400;i++){if(await predicate())return;await delay(5);}throw Error('fixture phase deadline'); }
async function backend(options={}) {
  const memory = new Map();
  const context = new Context();
  const wire = {init:null,exchanges:0,cancels:0,commits:0,release:null};
  const platform = http.createServer(async (req,res) => {
    let text='';for await(const chunk of req)text+=chunk;
    const body=JSON.parse(text||'{}');
    const pathname=new URL(req.url,'http://127.0.0.1').pathname;
    const reply=value=>res.writeHead(200,{'content-type':'application/json'}).end(JSON.stringify({code:0,data:{biz_code:0,biz_data:value}}));
    if(pathname==='/auth-api/v0/dsh/auth_init') {
      wire.init=body;
      assert.equal(body.code_challenge_method,'S256');
      assert.match(body.code_challenge,/^[A-Za-z0-9_-]{43}$/);
      assert.match(body.state,/^[A-Za-z0-9_-]{43}$/);
      reply({authorize_url:`http://127.0.0.1:${platform.address().port}/dsh/authorize?authorize_id=synthetic`,authorize_id:'synthetic',expires_in:60});
    } else if(pathname==='/auth-api/v0/dsh/auth_exchange') {
      ++wire.exchanges;
      assert.equal(body.code,'synthetic-code');
      assert.equal(createHash('sha256').update(body.code_verifier).digest('base64url'),wire.init.code_challenge);
      assert.equal(body.redirect_uri,wire.init.redirect_uri);
      if(options.holdExchange)await new Promise(resolve=>wire.release=resolve);
      if(options.exchangeFails) {res.writeHead(503).end();return;}
      reply({token:'synthetic-fixture-grant',authorized_url:`http://127.0.0.1:${platform.address().port}/dsh/authorized`});
    } else if(pathname==='/auth-api/v0/dsh/auth_cancel') {++wire.cancels;reply({});}
    else {res.writeHead(404).end();}
  });
  await new Promise(resolve=>platform.listen(0,'127.0.0.1',resolve));
  context.provide('credentials',{
    async readRecord(key){return memory.get(key);},
    async describeRecord(key){return {configured:memory.has(key)};},
    async modifyRecord(key,modify){const next=await modify(memory.get(key));if(next!==undefined){memory.set(key,next);context.emit('credentials/record-updated',key);if(key.endsWith('/default'))++wire.commits;}return memory.get(key);},
    async deleteRecord(key){memory.delete(key);context.emit('credentials/record-updated',key);}
  });
  await context.plugin(Authorization);
  await context.plugin(WebServer,{host:'127.0.0.1',port:0,compression:'gzip',compressionLevel:6,compressionThresholdBytes:1024});
  await context.plugin(PlatformAccount,{platformOrigin:`http://127.0.0.1:${platform.address().port}`,allowLoopbackHttp:true,
    requestTimeoutMs:2000,attemptTimeoutMs:options.expires?80:5000});
  const account=context.deepseekAccount;
  const origin=context.webServer.getOrigin();
  const begin=async()=>{await account.startSignIn(client,origin,'web');await until(()=>account.attempt?.view.phase==='waiting-browser');};
  const callback=(suffix='',init={})=>fetch(`${origin}/oauth/callback?code=synthetic-code&state=${wire.init.state}${suffix}`,{redirect:'manual',...init});
  return {context,wire,account,origin,memory,begin,callback,async close(){wire.release?.();await context.fiber.dispose();platform.closeAllConnections();await new Promise(resolve=>platform.close(resolve));}};
}
await test('real dynamic-port callback rejects mismatched state, duplicates, wrong method and wrong path before exchange',async()=>{
  const f=await backend();try{
    await f.begin();
    assert.equal(f.wire.init.redirect_uri,f.origin+'/oauth/callback');
    for(const [suffix,init] of [['&state=duplicate',{}],['&code=duplicate',{}],['',{method:'POST'}]])assert.equal((await f.callback(suffix,init)).status,400);
    assert.equal((await fetch(f.origin+'/oauth/callback?code=synthetic-code&state=wrong')).status,400);
    assert.equal((await fetch(f.origin+'/oauth/other')).status,404);
    assert.equal(f.wire.exchanges,0);assert.equal(f.wire.commits,0);
    await f.account.cancelSignIn(f.account.attempt.view.id);
  }finally{await f.close();}
});
await test('real callback survives watch disconnect, exchanges PKCE once and redirects only after credential commit',async()=>{
  const f=await backend({holdExchange:true});try{
    await f.begin();
    const controller=new AbortController(),stream=f.account.watch(controller.signal);
    await stream.next();controller.abort();await stream.return();
    let completed=false;
    const response=f.callback().then(value=>{completed=true;return value;});
    await until(()=>f.wire.exchanges===1);
    await delay(40);assert.equal(completed,false);assert.equal(f.wire.commits,0);
    assert.equal((await f.callback()).status,410);
    f.wire.release();
    const final=await response;assert.equal(final.status,302);
    assert.equal(new URL(final.headers.get('location')).pathname,'/dsh/authorized');
    assert.equal(f.wire.commits,1);assert.equal(f.wire.exchanges,1);
    await f.account.attempt.done;
    assert.equal((await f.callback()).status,404,'route retires after this exact attempt');
    assert.equal((await f.account.getState()).attempt.phase,'succeeded');
  }finally{await f.close();}
});
await test('cancellation and expiry retire callback without committing or replaying the code',async()=>{
  for(const expires of [false,true]){
    const f=await backend({expires});try{
      await f.begin();
      if(expires)await f.account.attempt.done;else await f.account.cancelSignIn(f.account.attempt.view.id);
      assert.equal((await f.callback()).status,404);assert.equal(f.wire.exchanges,0);assert.equal(f.wire.commits,0);
      assert.equal(f.account.attempt.view.phase,expires?'expired':'cancelled');
    }finally{await f.close();}
  }
});
await test('exchange failure gives bounded no-store callback error and never commits a grant',async()=>{
  const f=await backend({exchangeFails:true});try{
    await f.begin();const reply=await f.callback();
    assert.equal(reply.status,200);assert.equal(reply.headers.get('cache-control'),'no-store');
    assert.equal(reply.headers.get('referrer-policy'),'no-referrer');
    const html=await reply.text();assert.match(html,/Sign-in failed/);assert(!html.includes(f.wire.init.state));
    assert.equal(f.wire.commits,0);await f.account.attempt.done;
    assert.equal(f.account.attempt.view.errorCode,'network');
  }finally{await f.close();}
});
console.log(JSON.stringify({scope:'LOCKED_OFFICIAL_CLIENT_BROWSER_AND_REAL_LOOPBACK_HTTP',dshVersion:recipe.dshVersion,
  officialClientSha256:recipe.sourceSha256,recipeSha256:sha(fs.readFileSync(recipeFile)),
  realAccountProviderSha256:sha(fs.readFileSync(path.join(runtime,'node_modules/@deepseek-ai/dsh-deepseek-account-platform/lib/index.js'))),
  networkScope:'synthetic localhost only; fixed public authorize navigation intercepted before network',
  deviceUsed:false,userCredentialsUsed:false,archiveApplyAndIdempotence:true,cases:results.length,results},null,2));
