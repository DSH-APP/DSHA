// Current DSH test runtime selection. Old historical fixtures must opt in explicitly.
import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
const root=path.resolve(import.meta.dirname,'..');
const sha=file=>createHash('sha256').update(fs.readFileSync(file)).digest('hex');
const json=file=>JSON.parse(fs.readFileSync(file,'utf8'));
const canonical=value=>value&&typeof value==='object'?(Array.isArray(value)?value.map(canonical):Object.fromEntries(Object.keys(value).sort().map(key=>[key,canonical(value[key])]))):value;
const proofHash=entries=>createHash('sha256').update(JSON.stringify(canonical(entries))).digest('hex');
export function verifyRuntimeContent(directory,proof){
  if(proof.version!==2||proof.installation!=='npm-ci-ignore-scripts'||!/^[a-f0-9]{32}$/.test(proof.installationId||''))throw Error('TEST_FIXTURE_REINSTALL_REQUIRED');
  const expected=proof.contentProof;
  if(expected?.executablePolicy!==(process.platform==='win32'?'windows-no-posix-mode':'posix-mode'))throw Error('TEST_FIXTURE_REINSTALL_REQUIRED');
  if(!expected||expected.algorithm!=='sha256'||!expected.entries||proofHash(expected.entries)!==expected.digest)throw Error('TEST_FIXTURE_CONTENT_PROOF_INVALID');
  const selected=path.resolve(directory);if(fs.lstatSync(selected).isSymbolicLink())throw Error('TEST_FIXTURE_ROOT_LINK');
  const root=fs.realpathSync(selected),entries={};let count=0;
  const inside=target=>{const rel=path.relative(root,target);return rel===''||rel!=='..'&&!rel.startsWith('..'+path.sep)&&!path.isAbsolute(rel);};
  const walk=(folder,relative)=>{
    for(const name of fs.readdirSync(folder).sort()){
      const file=path.join(folder,name),key=relative?relative+'/'+name:name,node=fs.lstatSync(file);
      if(key==='dsha-test-runtime.json'){if(!node.isFile())throw Error('TEST_FIXTURE_MARKER_TYPE');continue;}
      if(key.length>4096||/[\\\r\n\0]/.test(key))throw Error('TEST_FIXTURE_PATH');
      if(++count>200000)throw Error('TEST_FIXTURE_ENTRY_LIMIT');
      if(node.isSymbolicLink()){
        const target=fs.readlinkSync(file);if(!target||target.length>4096||/[\r\n\0]/.test(target))throw Error('TEST_FIXTURE_LINK_FORMAT');
        if(!inside(fs.realpathSync(file)))throw Error('TEST_FIXTURE_LINK_OUTSIDE:'+key);entries[key]={type:'LINK',target};
      }else if(node.isDirectory()){entries[key]={type:'DIRECTORY'};walk(file,key);}
      else if(node.isFile()){
        const fd=fs.openSync(file,fs.constants.O_RDONLY|(fs.constants.O_NOFOLLOW||0));let hash;
        try{const opened=fs.fstatSync(fd);if(opened.dev!==node.dev||opened.ino!==node.ino||opened.size!==node.size||!opened.isFile())throw Error('TEST_FIXTURE_FILE_CHANGED');
          const digest=createHash('sha256'),buffer=Buffer.alloc(1048576);let n;while((n=fs.readSync(fd,buffer,0,buffer.length,null))>0)digest.update(buffer.subarray(0,n));hash=digest.digest('hex');
          const after=fs.lstatSync(file);if(after.dev!==node.dev||after.ino!==node.ino||after.size!==node.size||after.mtimeMs!==node.mtimeMs||after.mode!==node.mode)throw Error('TEST_FIXTURE_FILE_CHANGED');
        }finally{fs.closeSync(fd);}
        entries[key]={type:'FILE',size:node.size,executable:process.platform==='win32'?0:node.mode&0o111,sha256:hash};
      }else throw Error('TEST_FIXTURE_SPECIAL:'+key);
    }
  };walk(root,'');
  if(entries['package.json']?.type!=='FILE'||entries['package-lock.json']?.type!=='FILE'||entries.node_modules?.type!=='DIRECTORY')throw Error('TEST_FIXTURE_PACKAGE_METADATA');
  if(proofHash(entries)!==expected.digest||JSON.stringify(canonical(entries))!==JSON.stringify(canonical(expected.entries))) {
    const key=[...new Set([...Object.keys(entries),...Object.keys(expected.entries)])].sort().find(name=>JSON.stringify(canonical(entries[name]))!==JSON.stringify(canonical(expected.entries[name])));
    throw Error('TEST_FIXTURE_BYTES_OR_MEMBERS_CHANGED:'+key+':'+JSON.stringify(entries[key])+':'+JSON.stringify(expected.entries[key]));
  }
  return true;
}
// A live host runner may reuse its byte proof. Every selection still checks current inputs,
// the exact marker, root identity, metadata and member set; final full hashing binds the run.
export function verifyRuntimeSession(directory,proof){
  const receipt=process.env.DSHA_TEST_RUN_RECEIPT,secret=process.env.DSHA_TEST_RUN_SECRET;
  if(!receipt&&!secret)return false;
  if(!receipt||!secret)throw Error('TEST_FIXTURE_SESSION_CREDENTIAL_MISSING');
  const file=path.resolve(receipt),node=fs.lstatSync(file);
  if(node.isSymbolicLink()||!node.isFile()||fs.realpathSync(file)!==file)throw Error('TEST_FIXTURE_SESSION_RECEIPT_PATH');
  const data=json(file);
  if(data.schema!==2||data.fingerprintPolicy!=='native-source-runtime-full-ids-dir-size-zero'||!/^[a-f0-9]{32}$/.test(data.sessionId||'')||path.basename(file)!=='proof.json'||!path.basename(path.dirname(file)).startsWith('dsha-runtime-session-'+data.sessionId+'-'))throw Error('TEST_FIXTURE_SESSION_RECEIPT_SCOPE');
  if(!/^[a-f0-9]{64}$/.test(secret)||data.ownerPid!==process.ppid||createHash('sha256').update(secret).digest('hex')!==data.secretSha256)throw Error('TEST_FIXTURE_SESSION_EXPIRED_OR_FOREIGN');
  const consumers=['tools/run-host-tests.py','tools/test-runtime-fixture.mjs','tools/test_runtime_fixture.py','tools/runtime_fixture_proof.py','tools/runtime_fixture_session.py'];
  if(JSON.stringify(Object.keys(data.consumerInputs||{}).sort())!==JSON.stringify(consumers.sort()))throw Error('TEST_FIXTURE_SESSION_CONSUMER_SCOPE');
  for(const [input,digest] of Object.entries(data.consumerInputs||{}))if(sha(path.join(root,input))!==digest)throw Error('TEST_FIXTURE_SESSION_CONSUMER_CHANGED');
  const selected=path.resolve(directory),record=data.fixtures?.[selected];
  if(!record)return false;
  if(record.directory!==selected||sha(path.join(selected,'dsha-test-runtime.json'))!==record.markerSha256)throw Error('TEST_FIXTURE_SESSION_MARKER_CHANGED');
  if(proof.kind!==record.kind||proof.contentProof?.digest!==record.contentDigest)throw Error('TEST_FIXTURE_SESSION_CONTENT_BINDING');
  if(process.platform==='win32'){
    // libuv exposes a 32-bit volume ID, while Python 3.12 preserves all 64 bits;
    // Windows file IDs may also exceed libuv's inode width. Never truncate either.
    // The same creator interpreter checks the full IDs and live Node -> owner chain.
    const runtime=data.metadataRuntime;
    if(!runtime||process.env.DSHA_PYTHON!==runtime.python||fs.realpathSync(process.execPath).toLowerCase()!==runtime.node?.toLowerCase()||sha(process.execPath)!==runtime.nodeSha256)throw Error('TEST_FIXTURE_SESSION_METADATA_RUNTIME_CHANGED');
    const pythonStat=fs.lstatSync(runtime.python);
    if(pythonStat.isSymbolicLink()||!pythonStat.isFile()||fs.realpathSync(runtime.python).toLowerCase()!==runtime.python.toLowerCase()||sha(runtime.python)!==runtime.pythonSha256)throw Error('TEST_FIXTURE_SESSION_METADATA_RUNTIME_CHANGED');
    const worker=spawnSync(runtime.python,['-B',path.join(root,'tools/runtime_fixture_session.py'),'--verify-node-receipt',file,selected],{env:process.env,encoding:'utf8',windowsHide:true,timeout:120000,maxBuffer:1048576});
    if(worker.error||worker.status!==0||worker.stdout.trim()!=='PASS_WINDOWS_FULL_PRECISION_METADATA'){
      const code=/TEST_FIXTURE_SESSION_[A-Z_]+/.exec(worker.stderr||'')?.[0]||'TEST_FIXTURE_SESSION_METADATA_WORKER_FAILED';
      throw Error(code);
    }
    return true;
  }
  const rootStat=fs.lstatSync(selected,{bigint:true});
  if(rootStat.isSymbolicLink()||!rootStat.isDirectory()||rootStat.dev.toString()!==record.metadata?.root?.dev||rootStat.ino.toString()!==record.metadata?.root?.ino)throw Error('TEST_FIXTURE_SESSION_ROOT_CHANGED');
  const entries={};let count=0;
  const walk=(folder,relative)=>{
    for(const name of fs.readdirSync(folder).sort()){
      const entry=path.join(folder,name),key=relative?relative+'/'+name:name;
      if(key==='dsha-test-runtime.json')continue;
      if(++count>200000)throw Error('TEST_FIXTURE_SESSION_ENTRY_LIMIT');
      const stat=fs.lstatSync(entry,{bigint:true}),type=stat.isSymbolicLink()?'LINK':stat.isDirectory()?'DIRECTORY':stat.isFile()?'FILE':'SPECIAL';
      entries[key]={type,dev:stat.dev.toString(),ino:stat.ino.toString(),size:type==='DIRECTORY'?'0':stat.size.toString(),mtimeNs:stat.mtimeNs.toString(),executable:process.platform==='win32'?0:Number(stat.mode&0o111n)};
      if(type==='LINK')entries[key].target=fs.readlinkSync(entry);
      if(type==='DIRECTORY')walk(entry,key);
    }
  };walk(selected,'');
  if(JSON.stringify(canonical(entries))!==JSON.stringify(canonical(record.metadata.entries)))throw Error('TEST_FIXTURE_SESSION_METADATA_CHANGED');
  return true;
}
export function testRuntime(kind='raw', environment=kind==='raw'?'DSHA_TEST_RUNTIME':'DSHA_TEST_MANAGED_RUNTIME') {
  if(!['raw','managed'].includes(kind))throw Error('TEST_FIXTURE_KIND_INVALID');
  const expected=json(path.join(root,'tools/dsh-runtime/package.json')).dependencies['@deepseek-ai/dsh'];
  let directory=process.env[environment];
  if(!directory){const pointer=path.join(root,'app/build/test-runtimes/current.json');if(!fs.existsSync(pointer))throw Error('Run python tools/prepare-test-runtime.py to prepare current test inputs');directory=json(pointer)[kind];}
  directory=path.resolve(directory);
  if(fs.lstatSync(directory).isSymbolicLink()||!fs.lstatSync(directory).isDirectory())throw Error('TEST_FIXTURE_ROOT_LINK_OR_TYPE');
  const actual=json(path.join(directory,'node_modules/@deepseek-ai/dsh/package.json')).version;
  if(actual!==expected)throw Error(`Wrong runtime generation: expected ${expected}, found ${actual} at ${directory}`);
  const proofFile=path.join(directory,'dsha-test-runtime.json');
  if(!fs.existsSync(proofFile))throw Error('Missing reproducible fixture provenance: '+directory);
  if(fs.lstatSync(proofFile).isSymbolicLink()||!fs.lstatSync(proofFile).isFile())throw Error('TEST_FIXTURE_MARKER_TYPE');
  const proof=json(proofFile);
  if(proof.kind!==kind||proof.dshVersion!==expected||proof.lockSha256!==sha(path.join(root,'tools/dsh-runtime/package-lock.json')))throw Error('Stale or wrong fixture kind/version: '+directory);
  for(const [file,digest] of Object.entries({...proof.archiveRecipeInputs,...proof.overlayInputs}))if(sha(path.join(root,file))!==digest)throw Error('Fixture patch inputs changed: '+file);
  if(!verifyRuntimeSession(directory,proof))verifyRuntimeContent(directory,proof);
  return directory;
}
export function requireNativeHost(runtime) {
  const proof=JSON.parse(fs.readFileSync(path.join(runtime,'dsha-test-runtime.json'),'utf8'));
  if(proof.platform?.os!==process.platform||proof.platform?.cpu!==process.arch)throw Error(`Native test platform mismatch: fixture ${proof.platform?.os}/${proof.platform?.cpu}, host ${process.platform}/${process.arch}`);
}
