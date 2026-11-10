import assert from 'node:assert/strict';
import {readFileSync, writeFileSync, mkdirSync} from 'node:fs';
import vm from 'node:vm';

// Pre-diagnostic page from 8321228e, vendored so shallow CI checkouts need no git history.
const oldSource = readFileSync('tools/fixtures/trial-renderer/runtime-trial-page.8321228e.js', 'utf8');
const newSource = readFileSync('app/src/main/assets/runtime-trial-page.js', 'utf8');
const nonce = 'a'.repeat(32), home = '/isolated-fixture-home';
function setup(source, bridge = 'present') {
  const events = new Map(), documentEvents = new Map(), sockets = [], posts = [], reports = [];
  let timer, timerCount = 0, rootReady = false, response = 'ok';
  class Socket {
    constructor() { this.events = new Map(); sockets.push(this); }
    addEventListener(type, callback) { this.events.set(type, callback); }
    fire(type, data) { this.events.get(type)?.({data:JSON.stringify(data)}); }
  }
  const window = {WebSocket:Socket, navigator:{onLine:true}, addEventListener:(type, fn) => events.set(type, fn)};
  if (bridge !== 'absent') window.DshaTrialDiagnostic = {postMessage(text) {
    if (bridge === 'throws') throw new Error('unavailable');
    reports.push(JSON.parse(text));
  }};
  const document = {hidden:false, addEventListener:(type, fn) => documentEvents.set(type, fn),
    getElementById:() => rootReady ? {children:[{}]} : null, querySelector:() => null};
  const context = vm.createContext({window,document,JSON,Object,Number,URL,
    location:{host:'127.0.0.1:41821',href:'http://127.0.0.1:41821/'},
    setInterval(fn, delay) { assert.equal(delay, 500); timerCount++; timer = fn; return 1; },
    clearInterval(id) { assert.equal(id, 1); timer = undefined; },
    fetch(url, options) {
      // Copy across the VM boundary, preserving the exact POST body bytes. Prototype
      // identity differs between the two contexts even when headers are identical.
      posts.push({url,options:JSON.parse(JSON.stringify(options))});
      return response === 'reject' ? Promise.reject(new Error('fixture')) : Promise.resolve({ok:response==='ok',status:response==='ok'?200:503});
    }
  });
  vm.runInContext(source.replace('__DSHA_TRIAL_NONCE__', nonce).replace('__DSHA_TRIAL_HOME__', JSON.stringify(home)), context);
  return {reports,posts,sockets,document,
    socket(path='/api/remote.mux') { return new window.WebSocket('ws://127.0.0.1:41821'+path); },
    ready(socket, expectedHome=home, client='fixture-client') { socket.fire('message',{type:'item',streamId:'s',value:{type:'ready',clientId:client,host:{home:expectedHome}}}); },
    root(value=true) { rootReady=value; },
    online(value) { window.navigator.onLine=value; },
    response(value) { response=value; },
    startup(value) { events.get('dsha-startup')?.({detail:JSON.stringify(value)}); },
    event(type) { documentEvents.get(type)?.(); },
    async tick(count=1) { for(let i=0;i<count;i++){timer?.();await Promise.resolve();await Promise.resolve();} },
    timerCount:() => timerCount
  };
}
const cases = [];
async function differential(name, scenario, bridge='present') {
  const before=setup(oldSource,bridge), after=setup(newSource,bridge);
  await scenario(before); await scenario(after);
  assert.deepEqual(after.posts,before.posts,name+': diagnostics changed acceptance traffic');
  assert.equal(after.timerCount(),1,name+': must retain exactly one existing 500ms timer');
  for(const report of after.reports){
    assert.equal(report.nonce,nonce);
    for(const [key,value] of Object.entries(report)) {
      if(key==='nonce')continue;
      if(key==='postHttpStatus')assert.ok(Number.isInteger(value)&&value>=0&&value<=599);
      else assert.equal(typeof value,'boolean',key);
    }
    const safe=JSON.stringify({...report,nonce:undefined});
    assert.ok(!safe.includes(home)&&!safe.includes('fixture-client')&&!safe.includes('private'));
  }
  cases.push({name,posts:after.posts.length,reports:after.reports.length});
  return after;
}

const waiting = await differential('no socket never accepts; telemetry bounded at ten seconds',async f=>{f.root();await f.tick(101);});
assert.equal(waiting.posts.length,0);
assert.ok(waiting.reports.length<=9,'unchanged waiting cannot emit per-tick telemetry');
assert.equal(waiting.reports.at(-1).timerObserved,true);
assert.equal(waiting.reports.at(-1).socketConstructed,false);

const mismatch = await differential('home mismatch remains unaccepted and reports only mismatch',async f=>{f.root();const s=f.socket();s.fire('open');f.ready(s,'/private-other-home');await f.tick(3);});
assert.equal(mismatch.posts.length,0);assert.equal(mismatch.reports.at(-1).readyFrameSeen,true);assert.equal(mismatch.reports.at(-1).homeMatched,false);

await differential('missing client id remains unaccepted',async f=>{f.root();const s=f.socket();s.fire('open');f.ready(s,home,'');await f.tick(3);});
await differential('unrelated websocket remains unaccepted',async f=>{f.root();const s=f.socket('/other');s.fire('open');f.ready(s);await f.tick(3);});
const success = await differential('real gate sends exactly the original one POST and records acknowledgment',async f=>{f.root();const s=f.socket();s.fire('open');f.ready(s);await f.tick(30);});
assert.equal(success.posts.length,1);assert.equal(success.reports.at(-1).postAck,true);assert.equal(success.reports.at(-1).postHttpStatus,200);
await differential('closed old socket cannot satisfy a replacement',async f=>{f.root();const a=f.socket();a.fire('open');const b=f.socket();f.ready(a);b.fire('open');await f.tick();b.fire('close');f.ready(b);await f.tick();});
await differential('startup event readiness preserves original gate',async f=>{const s=f.socket();s.fire('open');f.ready(s);f.startup({type:'ready'});await f.tick(5);});
await differential('fatal startup preserves original failure POST',async f=>{f.startup({type:'issue',fatal:true});await f.tick(5);});
await differential('existing network-failure behavior remains byte-identical without telemetry retry',async f=>{f.root();const s=f.socket();s.fire('open');f.ready(s);f.response('reject');await f.tick();f.response('ok');await f.tick(3);});
await differential('bridge absent preserves full acceptance',async f=>{f.root();const s=f.socket();s.fire('open');f.ready(s);await f.tick(3);},'absent');
await differential('bridge exception cannot alter full acceptance',async f=>{f.root();const s=f.socket();s.fire('open');f.ready(s);await f.tick(3);},'throws');
const hidden = await differential('visibility and freeze telemetry never declares success',async f=>{f.document.hidden=true;f.event('visibilitychange');f.event('freeze');await f.tick(21);});
assert.equal(hidden.posts.length,0);assert.equal(hidden.reports.at(-1).documentHidden,true);assert.equal(hidden.reports.at(-1).freezeObserved,true);
const offlineHint = await differential('raw navigator online hint is only observed and cannot alter a genuine handshake',async f=>{f.online(false);f.root();const s=f.socket();s.fire('open');f.ready(s);await f.tick(3);});
assert.equal(offlineHint.posts.length,1);assert.equal(offlineHint.reports.at(-1).navigatorOnline,false);
assert.equal(success.reports.at(-1).navigatorOnline,true);

mkdirSync('app/build/trial-renderer-diagnostic',{recursive:true});
writeFileSync('app/build/trial-renderer-diagnostic/js-differential.json',JSON.stringify({passed:cases.length,cases,scope:'Production R2/R3 page JS; deterministic event fixture, exact acceptance POST comparison; no real Android/WebView freezing claim'},null,2));
console.log('PASS_TRIAL_RENDERER_DIAGNOSTIC_DIFFERENTIAL '+cases.length);
