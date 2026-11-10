// T37：切网后回收外网连接（app/src/main/assets/net-epoch.cjs）的宿主回归。
// 每个场景在独立 Node 子进程里预加载该文件，用本机假服务冒充外网（仅测试开关放行回环）。
import {mkdtempSync, rmSync, writeFileSync, symlinkSync, mkdirSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join, resolve} from 'node:path';
import {spawnSync} from 'node:child_process';
import test from 'node:test';
import assert from 'node:assert/strict';

const preload = resolve(process.env.DSHA_NET_EPOCH_SOURCE || 'app/src/main/assets/net-epoch.cjs');

// 子进程脚本：起一台本机 HTTP 服务；/fast 立即返回，/hang 不回响应头，/drip 每 300 ms 发一块，
// /stall 先发一块再停住。场景用 argv[2] 选择，结果以一行 JSON 打到 stdout。
const child = String.raw`
const http=require('node:http'),fs=require('node:fs');
const [scenario,file,target]=process.argv.slice(1);
const server=http.createServer((req,res)=>{
  if(req.url==='/fast'){res.setHeader('keep-alive','timeout=60');return res.end('ok');}
  if(req.url==='/hang')return;
  if(req.url==='/stall'){res.writeHead(200);res.write('first');return;}
  if(req.url==='/drip'){res.writeHead(200);let n=0;const t=setInterval(()=>{res.write('x');if(++n===12){clearInterval(t);res.end();}},300);return;}
  res.statusCode=404;res.end();
});
server.keepAliveTimeout=60000;
const sockets=new Set();server.on('connection',s=>{sockets.add(s);s.on('close',()=>sockets.delete(s));});
const bump=v=>fs.writeFileSync(target||file,v);
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const outcome=async p=>{try{const r=await p;const t=await r.text();return {ok:true,status:r.status,length:t.length};}catch(e){return {ok:false,code:e.cause&&e.cause.code||e.name,message:String(e.cause&&e.cause.message||e.message)};}};
server.listen(0,'127.0.0.1',async()=>{
  const url='http://127.0.0.1:'+server.address().port;
  const t0=Date.now();let result;
  if(scenario==='idle'){
    bump('a');await (await fetch(url+'/fast')).text();
    const serverSide=[...sockets][0];let closed=false;serverSide.on('close',()=>closed=true);
    await sleep(2500);bump('b');await sleep(2600);
    result={idleClosed:closed};
  }else if(scenario==='hang'||scenario==='stall'){
    bump('a');const p=Promise.race([outcome(fetch(url+'/'+scenario)),sleep(8000).then(()=>({pending:true}))]);
    await sleep(2500);bump('b');
    result=await p;
  }else if(scenario==='drip'){
    bump('a');const p=outcome(fetch(url+'/drip'));await sleep(500);bump('b');result=await p;
  }else if(scenario==='unchanged'){
    bump('a');const p=Promise.race([outcome(fetch(url+'/hang')),sleep(5500).then(()=>({pending:true}))]);
    result=await p;
  }else if(scenario==='first'){
    const p=Promise.race([outcome(fetch(url+'/hang')),sleep(5500).then(()=>({pending:true}))]);
    await sleep(2500);bump('a');result=await p;
  }
  result.ms=Date.now()-t0;
  console.log(JSON.stringify(result));process.exit(0);
});
`;

function run(scenario, extraEnv = {}, prepare) {
  const dir = mkdtempSync(join(tmpdir(), 'dsha-net-epoch-'));
  try {
    const file = join(dir, 'epoch');
    const target = prepare ? prepare(dir, file) : undefined;
    const env = {...process.env, NODE_OPTIONS: '', DSHA_NET_EPOCH_FILE: file, DSHA_NET_EPOCH_GRACE_MS: '1500',
      DSHA_NET_EPOCH_TEST_LOOPBACK: '1', ...extraEnv};
    const out = spawnSync(process.execPath, ['--require', preload, '-e', child, scenario, file, ...(target ? [target] : [])],
      {env, encoding: 'utf8', timeout: 30000});
    assert.equal(out.status, 0, out.stderr);
    const line = out.stdout.trim().split('\n').pop();
    return {result: JSON.parse(line), stderr: out.stderr};
  } finally {
    rmSync(dir, {recursive: true, force: true});
  }
}

test('idle upstream connections are closed as soon as the network epoch changes', () => {
  const {result, stderr} = run('idle');
  assert.equal(result.idleClosed, true, stderr);
  assert.match(stderr, /closed 1 idle upstream connection/);
});

test('a request without any response bytes since the change fails fast with a connection error', () => {
  const {result, stderr} = run('hang');
  assert.equal(result.ok, false);
  assert.equal(result.code, 'DSHA_NETWORK_CHANGED');
  // 轮询 2 s + 宽限 1.5 s；远小于 300 s 看门狗。
  assert.ok(result.ms < 9000, `took ${result.ms} ms`);
  assert.match(stderr, /closed 1 stalled upstream connection/);
});

test('a stream that stopped mid-body is closed after the grace period', () => {
  const {result} = run('stall');
  assert.equal(result.ok, false);
  assert.ok(result.ms < 9000, `took ${result.ms} ms`);
});

test('a stream that keeps receiving data across the change is left alone', () => {
  const {result, stderr} = run('drip');
  assert.deepEqual([result.ok, result.status, result.length], [true, 200, 12]);
  assert.doesNotMatch(stderr, /stalled/);
});

test('nothing is closed while the epoch stays the same', () => {
  assert.equal(run('unchanged').result.pending, true);
});

test('the first epoch that appears is only a baseline', () => {
  assert.equal(run('first').result.pending, true);
});

test('DSHA_NET_EPOCH=0 disables the hook', () => {
  assert.equal(run('hang', {DSHA_NET_EPOCH: '0'}).result.pending, true);
});

test('loopback connections are never tracked without the test switch', () => {
  assert.equal(run('hang', {DSHA_NET_EPOCH_TEST_LOOPBACK: '0'}).result.pending, true);
});

test('a symlinked epoch file is ignored', () => {
  const {result} = run('hang', {}, (dir, file) => {
    mkdirSync(join(dir, 'real'));
    writeFileSync(join(dir, 'real', 'epoch'), 'z');
    symlinkSync(join(dir, 'real', 'epoch'), file);
    return join(dir, 'real', 'epoch'); // 经真实路径改写，预加载只能经链接读到
  });
  assert.equal(result.pending, true);
});
