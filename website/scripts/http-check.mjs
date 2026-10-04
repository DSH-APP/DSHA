import assert from 'node:assert/strict';
const base=process.argv[2]||'http://127.0.0.1:4180';
const json=await fetch(base+'/api/releases.json').then(r=>{assert.equal(r.status,200);return r.json();});
for(const route of ['/','/marketplace.html','/plugins/device-shell/','/plugins/dsh-web-mobile/','/guide/','/submit/','/download/','/about/','/security/','/api/catalog.json','/api/updates.json','/.well-known/assetlinks.json','/install/','/sitemap.xml','/robots.txt','/health.json']){
  const response=await fetch(base+route,{method:'HEAD'});assert.equal(response.status,200,route);assert.ok(response.headers.get('content-type'),route);
}
for(const apk of json.releases){
  const head=await fetch(base+apk.url,{method:'HEAD'});assert.equal(head.status,200);assert.equal(Number(head.headers.get('content-length')),apk.bytes);
  if(new URL(base).protocol==='https:'){
    assert.match(head.headers.get('strict-transport-security')||'',/max-age=\d+/,'APK HSTS');
    assert.equal(head.headers.get('x-content-type-options'),'nosniff','APK content type protection');
    assert.equal(head.headers.get('referrer-policy'),'strict-origin-when-cross-origin','APK referrer policy');
    assert.match(head.headers.get('content-security-policy')||'',/frame-ancestors 'none'/,'APK CSP');
    assert.match(head.headers.get('content-disposition')||'',/^attachment(?:;|$)/,'APK attachment');
  }
  const range=await fetch(base+apk.url,{headers:{Range:'bytes=0-3'}});assert.equal(range.status,206);const bytes=Buffer.from(await range.arrayBuffer());assert.deepEqual([...bytes],[80,75,3,4]);
  const invalid=await fetch(base+apk.url,{headers:{Range:`bytes=${apk.bytes+10}-`}});assert.equal(invalid.status,416);
}
const missing=await fetch(base+'/not-a-real-page',{method:'HEAD'});assert.equal(missing.status,404);
console.log('HTTP routes, APK sizes/ranges and 404 verified: '+base);
