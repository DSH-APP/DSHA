// 在同一个 Node 中完成原生模块检查并启动真实 DSH，避免先启动多轮 Node 再重复加载。
import {readFileSync,openSync,closeSync,unlinkSync,lstatSync} from 'node:fs';
import {createRequire,Module} from 'node:module';
import {Script} from 'node:vm';
import {pathToFileURL,fileURLToPath} from 'node:url';
// Node has already selected this preload. Consume only its exact first option,
// so fresh storage/MCP children keep all ordinary preloads without rerunning it.
const nonce=process.env.DSHA_RUNTIME_TRIAL_NONCE;
const ownOptions=['--import='+fileURLToPath(import.meta.url)+' ','--import='+import.meta.url+' '];
if(/^[a-f0-9]{32}$/.test(nonce||''))ownOptions.push('--import=/root/.dsha-runtime-trial-'+nonce+'/runtime-entry.mjs ');
const ownOption=ownOptions.find(option=>process.env.NODE_OPTIONS?.startsWith(option));
if(ownOption){
 const rest=process.env.NODE_OPTIONS.slice(ownOption.length);
 if(rest)process.env.NODE_OPTIONS=rest;else delete process.env.NODE_OPTIONS;
}
const stage=value=>{
 process.stdout.write('DSHA_TRIAL_NATIVE_STAGE '+value+'\n');
 process.stdout.write('DSHA_TRIAL_TIME '+value+' '+Math.floor(performance.now())+'\n');
};
try {
 stage('pnpm');
 // The installer owns these signed files. Validate the actual locked manager
 // entry here, without letting a second CLI parse DSH argv or start another Node.
 const pnpmRoot='/usr/local/lib/dsha-pnpm';
 if(!lstatSync(pnpmRoot+'/package.json').isFile())throw Error('Bundled pnpm manifest invalid');
 const manager=JSON.parse(readFileSync(pnpmRoot+'/package.json','utf8'));
 if(manager.name!=='pnpm'||manager.version!==process.env.DSHA_TRIAL_PNPM_VERSION||manager.bin?.pnpm!=='bin/pnpm.cjs')throw Error('Bundled pnpm version or entry mismatch');
 for(const file of [pnpmRoot+'/bin/pnpm.cjs',pnpmRoot+'/dist/pnpm.cjs']){
  const info=lstatSync(file);if(!info.isFile()||info.size<=0||info.size>64*1024*1024)throw Error('Bundled pnpm entry invalid');
  new Script(Module.wrap(readFileSync(file,'utf8').replace(/^#![^\n]*\n/,'')),{filename:file});
 }
 stage('modules');
 const packagePath='/usr/local/lib/node_modules/@deepseek-ai/dsh/package.json';
 const pkg=JSON.parse(readFileSync(packagePath,'utf8'));
 if(pkg.version!==process.env.DSHA_TRIAL_DSH_VERSION||process.arch!=='arm64'||!process.versions.node.startsWith('24.'))throw Error('Runtime version mismatch');
 const require=createRequire(packagePath);
 for(const name of ['sharp','koffi','node-pty','@deepseek-ai/node-addon-system/landlock-run'])require(name);
 stage('loader');
 const loader=require('@deepseek-ai/cordis-plugin-loader').ModuleLoader.fromInternal();
 if(!loader||typeof loader.import!=='function')throw Error('Native plugin loader unavailable');
 const paths=JSON.parse(process.env.DSHA_TRIAL_BUILTIN_ENTRIES||'{}').entries;
 if(!Array.isArray(paths)||paths.length===0)throw Error('Builtin entry list missing');
 for(let index=0;index<paths.length;index++){stage('builtin-'+index);await import(pathToFileURL(paths[index]).href)}
 stage('flock');
 const file=process.env.HOME+'/.native-check-'+process.pid,fd=openSync(file,'wx',384);
 try{await require('@deepseek-ai/node-addon-system/flock').tryLockExclusive(fd)}finally{closeSync(fd);unlinkSync(file)}
 stage('native-ready');
 process.stdout.write('DSHA_TRIAL_NATIVE_CHECK_READY\n');
}catch(error){console.error('DSHA_TRIAL_NATIVE_CHECK_FAILED',error);process.exit(78)}
