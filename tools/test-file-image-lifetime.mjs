// Actual shipped mobile controls in the locked rc5 browser; original-bundle mode
// records the pre-fix failure using a preserved byte-for-byte local artifact.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {browserFixture} from './rc1-browser-fixture.mjs';
const negative=process.argv.includes('--expect-original');
const bundle=process.env.DSHA_IMAGE_LIFETIME_BUNDLE||'app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js';
const source=fs.readFileSync(bundle,'utf8');
const fixture=await browserFixture();
const records=[];
try {
  const page=fixture.page;page.setDefaultTimeout(5000);
  await page.addScriptTag({content:source.replace('var __cache = {};','window.mobileRequire=__localRequire; var __cache = {};')});
  await page.evaluate(()=>{
    document.documentElement.lang='zh-CN';
    window.saved=[];window.readerCalls=0;window.chunkBytes=0;window.blobCalls=0;window.delayed=false;window.releaseImage=null;
    window.DSHA={downloadArtifact:async()=>({status:'queued',taskId:'unused'}),saveImage:async file=>{
      saved.push({size:file.size,name:file.name,type:file.type,...file.size<100?{bytes:[...new Uint8Array(await file.arrayBuffer())]}:{}});
      return {status:'queued',taskId:'image-'+saved.length};
    }};
    const nativeFetch=window.fetch;
    window.fetch=async(url,options)=>{
      const response=await nativeFetch(url,options);
      const get=response.body.getReader.bind(response.body);
      response.body.getReader=(...args)=>{
        readerCalls++;const reader=get(...args),read=reader.read.bind(reader);
        reader.read=async()=>{const value=await read();chunkBytes+=value.value?.byteLength||0;return value;};return reader;
      };
      const blob=response.blob.bind(response);response.blob=()=>{blobCalls++;return blob();};
      if(delayed){window.delayedSignal=options?.signal;return new Promise(resolve=>releaseImage=()=>resolve(response));}
      return response;
    };
    window.install=()=>window.stop=mobileRequire('./components/file-share-controls.js').installNativeFileActions();
    install();
    window.small=URL.createObjectURL(new Blob([new Uint8Array([137,80,78,71])],{type:'image/png'}));
    window.large=URL.createObjectURL(new Blob([new Uint8Array(33*1024*1024)],{type:'image/png'}));
    DSHAFileTransfer.imageMenu(small,'small.png');
  });
  await page.getByRole('button',{name:'保存到相册',exact:true}).click();
  await page.waitForFunction(()=>saved.length===1);
  assert.deepEqual(await page.evaluate(()=>saved[0]),{size:4,name:'small.png',type:'image/png',bytes:[137,80,78,71]});
  await page.evaluate(()=>DSHAFileTransfer.imageMenu(large,'large.png'));
  await page.getByRole('button',{name:'保存到相册',exact:true}).click();
  await page.waitForFunction(()=>saved.length===2);
  const copy=await page.evaluate(()=>({readerCalls,chunkBytes,blobCalls,size:saved[1].size}));
  assert.equal(copy.size,33*1024*1024);
  if(negative){assert.equal(copy.readerCalls,2);assert.equal(copy.chunkBytes,4+33*1024*1024);assert.equal(copy.blobCalls,0);}
  else {assert.equal(copy.readerCalls,0);assert.equal(copy.chunkBytes,0);assert.equal(copy.blobCalls,2);}
  records.push({test:'large-image-native-blob-backing',status:'PASS',expectedNegative:negative,...copy});
  await page.evaluate(()=>{delayed=true;DSHAFileTransfer.imageMenu(small,'late.png');});
  await page.getByRole('button',{name:'保存到相册',exact:true}).click();
  await page.waitForFunction(()=>typeof releaseImage==='function');
  await page.evaluate(()=>{stop();document.querySelector('[data-mobile-nav="file-share-toast"]')?.remove();releaseImage();});
  await page.waitForTimeout(100);
  const cancelled=await page.evaluate(()=>({saved:saved.length,aborted:delayedSignal?.aborted===true,notice:!!document.querySelector('[data-mobile-nav="file-share-toast"]'),face:!!window.DSHAFileTransfer}));
  assert.equal(cancelled.saved,negative?3:2);assert.equal(cancelled.aborted,!negative);assert.equal(cancelled.notice,negative);assert.equal(cancelled.face,false);
  records.push({test:'disposed-effect-late-blob',status:'PASS',expectedNegative:negative,...cancelled});
  if(!negative){
    await page.evaluate(()=>{
      delayed=false;install();window.metadataReads=0;window.bodyCancelled=0;
      window.fetch=async()=>({ok:true,headers:new Headers({'content-type':'image/png','content-length':'5000000001'}),
        body:{cancel:async()=>bodyCancelled++},blob:async()=>{metadataReads++;throw Error('oversized body must not be read');}});
      DSHAFileTransfer.imageMenu(small,'too-large.png');
    });
    await page.getByRole('button',{name:'保存到相册',exact:true}).click();
    await page.waitForFunction(()=>bodyCancelled===1);
    assert.deepEqual(await page.evaluate(()=>({metadataReads,bodyCancelled,saved:saved.length})),{metadataReads:0,bodyCancelled:1,saved:2});
    records.push({test:'oversize-before-blob-read',status:'PASS'});
  }
  await page.evaluate(()=>{stop();URL.revokeObjectURL(small);URL.revokeObjectURL(large);});
  assert.deepEqual(fixture.errors,[]);
} finally {await fixture.close();}
const result={schema:1,status:'PASS',expectedNegative:negative,browser:process.env.DSHA_BROWSER||'chromium',bundle:path.resolve(bundle),
  bundleSha256:createHash('sha256').update(source).digest('hex'),records,
  scope:'Actual mobile controls and browser Blob storage; native bridge is injected; no Android RSS, 5GB allocation, image codec or real gallery acceptance claim.'};
if(process.env.DSHA_IMAGE_LIFETIME_OUTPUT)fs.writeFileSync(process.env.DSHA_IMAGE_LIFETIME_OUTPUT,JSON.stringify(result,null,2)+'\n');
console.log(JSON.stringify(result,null,2));
