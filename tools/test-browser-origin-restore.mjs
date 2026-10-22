// Real IndexedDB/Blob transactions across two genuine HTTP origins; no Android device calls.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import http from 'node:http';
import {createHash} from 'node:crypto';
import {createRequire} from 'node:module';
import path from 'node:path';
import {browserFixture} from './rc1-browser-fixture.mjs';
import {testRuntime} from './test-runtime-fixture.mjs';
const require=createRequire(import.meta.url);
let playwright; try {playwright=require(process.env.DSHA_PLAYWRIGHT||'playwright');} catch {playwright=require(path.join(process.env.USERPROFILE,'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright'));}
const source=fs.readFileSync('app/src/main/assets/app-integration/browser-origin-state.js','utf8');
const secret='a'.repeat(64), parts=[], transferCookies=[]; let manifest, allowed=true;
const bytes=async req=>{const rows=[];for await(const part of req)rows.push(part);return Buffer.concat(rows);};
const server=http.createServer(async(req,res)=>{
  res.setHeader('cache-control','no-store');
  if(req.url==='/'){res.setHeader('content-type','text/html');res.end('<!doctype html><body><h1>Fixture</h1><p id="status"></p><script>'+source+'</script>');return;}
  if(req.headers.origin){res.setHeader('access-control-allow-origin',req.headers.origin);res.setHeader('access-control-allow-headers','X-DSHA-Restore, Content-Type');res.setHeader('access-control-allow-methods','GET,POST,OPTIONS');}
  if(req.method==='OPTIONS'){res.writeHead(204);res.end();return;}
  transferCookies.push(req.headers.cookie);
  if(req.headers['x-dsha-restore']!==secret||!allowed){res.writeHead(403);res.end();return;}
  if(req.url==='/lease'){res.end('{}');return;}
  if(req.url==='/manifest'){
    if(req.method==='POST')manifest=JSON.parse((await bytes(req)).toString());
    res.setHeader('content-type','application/json');res.end(JSON.stringify(manifest));return;
  }
  const match=req.url.match(/^\/(part|verify)\/(\d+)$/);
  if(match){const i=Number(match[2]);if(match[1]==='verify'){
    const value=await bytes(req);res.writeHead(value.equals(parts[i])?200:409);res.end('{}');return;
  }if(req.method==='POST')parts[i]=await bytes(req);res.setHeader('content-type','application/octet-stream');res.end(req.method==='POST'?'{}':parts[i]);return;}
  res.end('{}');
});
const target=http.createServer((req,res)=>{res.setHeader('content-type','text/html');res.end('<!doctype html><body><h1>Fixture</h1><p id="status"></p><script>'+source+'</script>');});
await Promise.all([new Promise(r=>server.listen(0,'127.0.0.1',r)),new Promise(r=>target.listen(0,'127.0.0.1',r))]);
const oldOrigin=`http://127.0.0.1:${server.address().port}`,newOrigin=`http://127.0.0.1:${target.address().port}`;
const engine=process.env.DSHA_BROWSER||'chromium', browser=await playwright[engine].launch({headless:true});
try {
  const context=await browser.newContext();const old=await context.newPage(),page=await context.newPage();
  await old.goto(oldOrigin);
  await old.evaluate(async()=>{
    localStorage.setItem('dsh.conversation.fixture',JSON.stringify({draft:'previous draft',view:null,viewRequest:null}));
    localStorage.setItem('dsh.conversation.fresh',JSON.stringify({draft:'unsubmitted text',view:null,viewRequest:null}));
    localStorage.setItem('dsh.conversation.rc2-chain',JSON.stringify({draft:'legacy unsubmitted fixture',view:null,viewRequest:null}));
    localStorage.setItem('dsh.conversation.contentWidth','920');
    localStorage.setItem('credential-token','NEVER COPY');localStorage.setItem('dsha.images.lease:old',JSON.stringify({id:'images',owner:'DSHA_IMAGE_DRAFT_V1',state:'active'}));
    const record={id:'images',revision:'previous-revision',owner:'DSHA_IMAGE_DRAFT_V1',bytes:6,files:[{name:'fixture.png',type:'image/png',lastModified:1,blob:new Blob([new Uint8Array([0,255,3,7,128,42])],{type:'image/png'})}]};
    localStorage.setItem('dsha.images.revision:images',record.revision);
    const opening=indexedDB.open('dsha-image-drafts',1);opening.onupgradeneeded=()=>opening.result.createObjectStore('drafts',{keyPath:'id'});
    await new Promise((resolve,reject)=>{opening.onerror=()=>reject(opening.error);opening.onsuccess=()=>{const db=opening.result,tx=db.transaction('drafts','readwrite');tx.objectStore('drafts').put(record);tx.oncomplete=()=>{db.close();resolve();};tx.onerror=()=>reject(tx.error);};});
  });
  await old.evaluate(async({secret,transport})=>{
    const snapshot=await DshaOriginState.capture(localStorage,indexedDB);
    await fetch(transport+'/manifest',{method:'POST',credentials:'omit',headers:{'X-DSHA-Restore':secret},body:JSON.stringify(snapshot.manifest)});
    for(let i=0;i<snapshot.parts.length;i++)await fetch(transport+'/part/'+i,{method:'POST',credentials:'omit',headers:{'X-DSHA-Restore':secret},body:snapshot.parts[i]});
  },{secret,transport:oldOrigin});
  assert.ok(!JSON.stringify(manifest).includes('NEVER COPY'));assert.ok(!manifest.rows.some(row=>row.key.includes('lease')));
  assert.ok(!manifest.rows.some(row=>row.key==='dsh.conversation.contentWidth'),'global width is not a session draft');
  manifest.partSha256=parts.map(part=>createHash('sha256').update(part).digest('hex'));
  await page.goto(newOrigin);
  await page.evaluate(()=>localStorage.setItem('dsh.conversation.fixture',JSON.stringify({draft:'current draft',view:null,viewRequest:null})));
  const imported=await page.evaluate(async({secret,transport})=>{
    const call=async(path,options={})=>{const res=await fetch(transport+path,{...options,credentials:'omit',headers:{'X-DSHA-Restore':secret}});if(!res.ok)throw Error('HTTP_'+res.status);return res;};
    const value=await(await call('/manifest')).json(),blobs=[];for(let i=0;i<value.parts.length;i++)blobs.push(await(await call('/part/'+i)).blob());
    const result=await DshaOriginState.restore(value,blobs,localStorage,indexedDB,()=>call('/lease'),async(blob,i)=>{await call('/verify/'+i,{method:'POST',body:blob});return true;});
    const db=await new Promise(resolve=>{const opening=indexedDB.open('dsha-image-drafts',1);opening.onsuccess=()=>resolve(opening.result);});
    const record=await new Promise(resolve=>{const request=db.transaction('drafts','readonly').objectStore('drafts').get('images');request.onsuccess=()=>resolve(request.result);});db.close();
    return {result,current:JSON.parse(localStorage.getItem('dsh.conversation.fixture')).draft,fresh:JSON.parse(localStorage.getItem('dsh.conversation.fresh')).draft,
      image:Array.from(new Uint8Array(await record.files[0].blob.arrayBuffer())),lease:localStorage.getItem('dsha.images.lease:old'),credential:localStorage.getItem('credential-token')};
  },{secret,transport:oldOrigin});
  assert.equal(imported.current,'current draft');assert.equal(imported.fresh,'unsubmitted text');assert.deepEqual(imported.image,[0,255,3,7,128,42]);assert.equal(imported.lease,null);assert.equal(imported.credential,null);assert.equal(imported.result.conflicts,1);
  assert.equal(await old.evaluate(()=>JSON.parse(localStorage.getItem('dsh.conversation.fixture')).draft),'previous draft');
  const boundaries=await page.evaluate(async()=>{
    const opening=indexedDB.open('dsha-image-drafts',1);
    const db=await new Promise(resolve=>{opening.onsuccess=()=>resolve(opening.result);});
    const get=id=>new Promise(resolve=>{const r=db.transaction('drafts','readonly').objectStore('drafts').get(id);r.onsuccess=()=>resolve(r.result);});
    const text=JSON.stringify({draft:'old text',view:'old-view',viewRequest:null});
    localStorage.setItem('dsh.conversation.blank',JSON.stringify({draft:'',view:'new-view',viewRequest:{view:'pending-view',focus:true}}));
    const simple={version:1,source:'http://127.0.0.1:3080',rows:[{key:'dsh.conversation.blank',value:text}],images:[],parts:[],unknown:[]};
    await DshaOriginState.restore(simple,[],localStorage,indexedDB);
    const merged=JSON.parse(localStorage.getItem('dsh.conversation.blank'));
    const make=(id,name='old.png')=>({version:1,source:'http://127.0.0.1:3080',rows:[],images:[{id,revision:'saved',owner:'DSHA_IMAGE_DRAFT_V1',bytes:3,files:[{part:0,name,type:'image/png',lastModified:1,bytes:3}]}],parts:[3],partSha256:['a'.repeat(64)],unknown:[]});
    const blob=new Blob([new Uint8Array([1,2,3])],{type:'image/png'});
    localStorage.setItem('dsha.images.deleted:deleted',JSON.stringify({owner:'DSHA_IMAGE_DRAFT_V1',id:'deleted',revision:'saved',kind:'mobile-nav.session.delete'}));
    const deleted=await DshaOriginState.restore(make('deleted'),[blob],localStorage,indexedDB);
    const dead=await get('deleted');
    let sourceRejected=false;try{await DshaOriginState.restore({...make('corrupt'),rows:[{key:'dsh.conversation.must-not-write',value:text}]},[blob],localStorage,indexedDB,async()=>{},async()=>false);}catch(e){sourceRejected=e.message==='RESTORE_SOURCE_DIGEST';}
    let guardCalls=0,cancelled=false;
    try{await DshaOriginState.restore(make('late-cancel'),[blob],localStorage,indexedDB,async()=>{if(++guardCalls===3)throw Error('INSTANCE_CHANGED');});}catch(e){cancelled=e.message==='INSTANCE_CHANGED';}
    const late=await get('late-cancel');
    const one=await DshaOriginState.restore(make('metadata','first.png'),[blob],localStorage,indexedDB);
    const two=await DshaOriginState.restore(make('metadata','second.png'),[blob],localStorage,indexedDB);
    db.close();return {merged,tombstoneConflict:deleted.conflicts,deletedAbsent:dead===undefined,sourceRejected,noTextWrite:localStorage.getItem('dsh.conversation.must-not-write')===null,
      cancelled,lateAbsent:late===undefined,metadataIdsDiffer:one.candidate!==two.candidate};
  });
  assert.equal(boundaries.merged.draft,'old text');assert.equal(boundaries.merged.view,'new-view');assert.deepEqual(boundaries.merged.viewRequest,{view:'pending-view',focus:true});
  for(const name of ['deletedAbsent','sourceRejected','noTextWrite','cancelled','lateAbsent','metadataIdsDiffer'])assert.equal(boundaries[name],true,name);
  assert.equal(boundaries.tombstoneConflict,1);
  allowed=false;
  const cancelled=await page.evaluate(async({secret,transport})=>{
    try{await DshaOriginState.restore({version:1,source:transport,rows:[{key:'dsh.conversation.fresh',value:JSON.stringify({draft:'interrupted',view:null,viewRequest:null})}],images:[],parts:[]},[],localStorage,indexedDB,async()=>{const r=await fetch(transport+'/lease',{credentials:'omit',headers:{'X-DSHA-Restore':secret}});if(!r.ok)throw Error('INSTANCE_CHANGED');});return false;}catch(e){return e.message==='INSTANCE_CHANGED';}
  },{secret,transport:oldOrigin});
  assert.equal(cancelled,true,'generation change must stop a target mutation');
  assert.ok(transferCookies.every(value=>value===undefined),'migration fetches must omit authentication cookies');
  console.log(JSON.stringify({engine,originsDistinct:oldOrigin!==newOrigin,applied:imported.result.applied,conflicts:imported.result.conflicts,imageBytes:imported.image.length,sourceRetained:true,credentialCopied:false,cancelCase:cancelled,boundaries}));
} finally {await browser.close();await Promise.all([new Promise(r=>server.close(r)),new Promise(r=>target.close(r))]);}

// Execute the current official module's React effect, Lexical input shell, persistence
// store and hydration functions. Only audit exports are added; their bodies stay intact.
const raw=testRuntime('raw'), official=await browserFixture(raw);
try {
  const moduleFile=path.join(raw,'node_modules/@deepseek-ai/dsh-client-ui-conversation/lib/client.js');
  await official.load('dsh-client-ui-conversation',[],
    'exports.originAudit={createConversationStore,SessionInputShell,DefaultConversationViews,readConversationDraft,parseStoredDraft,resolveDraftInput};');
  await official.page.addScriptTag({content:source});
  await official.page.evaluate(()=>{
    const a=auditExports['@deepseek-ai/dsh-client-ui-conversation'].originAudit;
    const React=auditModules.react.default||auditModules.react;
    const ReactDOM=auditModules['react-dom'].default||auditModules['react-dom'];
    const store=a.createConversationStore().create('rc2-chain'),shell=new a.SessionInputShell({});
    const root=ReactDOM.createRoot(document.getElementById('root'));
    window.originOfficial={a,store,shell,root};
    root.render(React.createElement(a.DefaultConversationViews,{
      useSession:pick=>pick({blank:true}),useConversation:pick=>pick({activeTargets:new Set()}),
      useConversationViews:pick=>pick([]),useInspectCall:pick=>pick(null),useStore:pick=>pick(store.getSnapshot()),
      inputActions:shell.actions,actions:store.actions,bindDraftPersistence:write=>shell.bindDraftPersistence(write),
      renderSlot:()=>null,openView:()=>{},
    }));
  });
  await official.page.waitForFunction(()=>typeof JSON.parse(localStorage.getItem('dsh.conversation.rc2-chain')||'{}').draft==='object');
  const chain=await official.page.evaluate(async row=>{
    const {a,store,shell,root}=originOfficial;
    const before=JSON.parse(localStorage.getItem(row.key));
    root.unmount();shell.dispose();
    const result=await DshaOriginState.restore({version:1,source:'http://127.0.0.1:3080',rows:[row],images:[],parts:[],unknown:[]},[],localStorage,indexedDB);
    return {before,result,hydrated:a.readConversationDraft('rc2-chain'),reopened:a.createConversationStore().create('rc2-chain').getSnapshot()};
  },manifest.rows.find(row=>row.key==='dsh.conversation.rc2-chain'));
  assert.deepEqual(chain.before,{draft:{text:'',references:[]},view:null,viewRequest:null},'official mount must persist the real semantic empty object');
  assert.equal(chain.result.applied,1);assert.equal(chain.result.conflicts,0);
  assert.deepEqual(chain.hydrated,{text:'legacy unsubmitted fixture',references:[]},'official rc2 hydration still accepts restored legacy strings');
  assert.equal(chain.reopened.draft,'legacy unsubmitted fixture');
  const schema=await official.page.evaluate(async()=>{
    const {a}=originOfficial, key='dsh.conversation.semantic-boundaries';
    const wrap=draft=>JSON.stringify({draft,view:'target-view',viewRequest:{view:'target-request',focus:true}});
    const reference={source:'reference',ref:'@file',offset:0,length:5,label:'file',clipboardText:'@file',appearance:'file',invalid:false};
    const referenceDraft={text:'@file',references:[reference]},commandDraft={text:'/go ',references:[],command:{token:'/go '}};
    const sourceDraft=a.resolveDraftInput('legacy source'), row={key,value:JSON.stringify({draft:sourceDraft,view:null,viewRequest:null})};
    const run=async(target,rowOverride=row)=>{
      if(target===null)localStorage.removeItem(key);else localStorage.setItem(key,wrap(target));
      const before=localStorage.getItem(key);
      const result=await DshaOriginState.restore({version:1,source:'http://127.0.0.1:3080',rows:[rowOverride],images:[],parts:[],unknown:[]},[],localStorage,indexedDB);
      return {result,preserved:localStorage.getItem(key)===before,after:JSON.parse(localStorage.getItem(key))};
    };
    const empty=await run(a.resolveDraftInput(''));
    const live=await run(a.resolveDraftInput('current text'));
    const refs=await run(referenceDraft),command=await run(commandDraft),whitespace=await run(a.resolveDraftInput(' '));
    const invalids=[
      {text:'',references:[],command:{token:'/go '}},
      {text:'',references:[],credential:'not-a-draft-field'},
      {text:'@file',references:[{...reference,offset:1}]},
      {text:'@file',references:[{...reference,length:4}]},
      {text:'@file',references:[{...reference,appearance:'unknown'}]},
      {text:'@file',references:[{...reference,lease:'not-a-reference-field'}]},
      {text:'/go ',references:[],command:{token:'/go ',credential:'not-a-command-field'}},
    ];
    const rejected=[];for(const draft of invalids){const result=await run(draft);rejected.push({valid:DshaOriginState.textValid(key,wrap(draft)),preserved:result.preserved,conflicts:result.result.conflicts});}
    const malformedSource={key,value:JSON.stringify({draft:invalids[0],view:null,viewRequest:null})};
    const badSource=await run(null,malformedSource);
    const validRefs=await run(null,{key,value:JSON.stringify({draft:referenceDraft,view:null,viewRequest:null})});
    const hydratedRefs=a.readConversationDraft('semantic-boundaries');
    const validCommand=await run(null,{key,value:JSON.stringify({draft:commandDraft,view:null,viewRequest:null})});
    const hydratedCommand=a.readConversationDraft('semantic-boundaries');
    const tooMany={text:'x'.repeat(4097),references:Array.from({length:4097},(_,offset)=>({...reference,offset,length:1,clipboardText:'x'}))};
    const widthKey='dsh.conversation.contentWidth';localStorage.setItem(widthKey,'1080');
    const width=await DshaOriginState.restore({version:1,source:'http://127.0.0.1:3080',rows:[{key:widthKey,value:'920'}],images:[],parts:[],unknown:[]},[],localStorage,indexedDB);
    const snapshot=await DshaOriginState.capture(localStorage,indexedDB);
    return {empty,live,refs,command,whitespace,rejected,badSource,validRefs,validCommand,hydratedRefs,hydratedCommand,
      officialAcceptsReference:a.parseStoredDraft(referenceDraft)!==undefined,officialAcceptsCommand:a.parseStoredDraft(commandDraft)!==undefined,
      bounded:!DshaOriginState.textValid(key,wrap(tooMany)),width,widthPreserved:localStorage.getItem(widthKey)==='1080',widthCaptured:snapshot.manifest.rows.some(row=>row.key===widthKey)};
  });
  assert.equal(schema.empty.result.applied,1);assert.equal(schema.empty.after.view,'target-view');
  assert.deepEqual(schema.empty.after.viewRequest,{view:'target-request',focus:true});
  assert.deepEqual(schema.empty.after.draft,{text:'legacy source',references:[]});
  for(const name of ['live','refs','command','whitespace']){assert.equal(schema[name].preserved,true,name);assert.equal(schema[name].result.applied,0,name);assert.equal(schema[name].result.conflicts,1,name);}
  for(const item of schema.rejected){assert.equal(item.valid,false);assert.equal(item.preserved,true);assert.equal(item.conflicts,1);}
  assert.equal(schema.badSource.result.applied,0);assert.equal(schema.badSource.after,null);
  assert.equal(schema.validRefs.result.applied,1);assert.equal(schema.validCommand.result.applied,1);
  assert.deepEqual(schema.hydratedRefs,schema.validRefs.after.draft);assert.deepEqual(schema.hydratedCommand,schema.validCommand.after.draft);
  for(const name of ['officialAcceptsReference','officialAcceptsCommand','bounded','widthPreserved'])assert.equal(schema[name],true,name);
  assert.equal(schema.widthCaptured,false);assert.equal(schema.width.applied,0);assert.equal(schema.width.conflicts,0);
  assert.deepEqual(official.errors,[]);
  console.log(JSON.stringify({status:'PASS',engine,officialVersion:JSON.parse(fs.readFileSync(path.join(raw,'node_modules/@deepseek-ai/dsh-client-ui-conversation/package.json'),'utf8')).version,
    officialModuleSha256:createHash('sha256').update(fs.readFileSync(moduleFile)).digest('hex'),officialReactAutoPersistence:true,legacyHydration:true,
    structuredReferenceAndCommandHydration:true,targetViewAndRequestRetained:true,realNewDraftsPreserved:true,boundedSchema:true,widthExcluded:true,invalidSnapshotsRetained:schema.rejected.length,
    chain:{before:chain.before,result:chain.result,hydrated:chain.hydrated}}));
}finally{await official.close();}
