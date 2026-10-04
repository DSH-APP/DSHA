;(function(root){
  'use strict';
  // Same narrow migration for Node preload and an early browser script. No source/origin trust is inferred.
  function isRequest(input){return typeof root.Request==='function'&&input instanceof root.Request;}
  function rewrite(input,init){
    if(typeof root.URL!=='function'||typeof root.Headers!=='function')return null;
    var url;try{url=new root.URL(isRequest(input)?input.url:String(input));}catch(ignored){return null;}
    if(url.protocol!=='http:'||(url.hostname!=='127.0.0.1'&&url.hostname!=='[::1]')||url.port!=='3090'||url.username||url.password)return null;
    var method=String(init&&init.method!==undefined?init.method:isRequest(input)?input.method:'GET').toUpperCase();if(method!=='GET')return null;
    if(!url.searchParams||typeof url.searchParams.forEach!=='function')return null;
    var entries=[];url.searchParams.forEach(function(value,name){if(name.toLowerCase()==='token')entries.push([name,value]);});if(!entries.length)return null;
    if(entries.length!==1||!entries[0][1]||!/^[A-Za-z0-9_-]{1,128}$/.test(entries[0][1]))throw Error('BRIDGE_CREDENTIAL_INVALID');
    var token=entries[0][1],headers=new root.Headers(init&&Object.prototype.hasOwnProperty.call(init,'headers')?init.headers:isRequest(input)?input.headers:undefined);
    if(headers.has('X-Token')&&headers.get('X-Token')!==token)throw Error('BRIDGE_CREDENTIAL_CONFLICT');
    headers.set('X-Token',token);url.searchParams.delete(entries[0][0]);
    var next={};if(init)Object.keys(init).forEach(function(key){next[key]=init[key];});next.headers=headers;
    return {input:isRequest(input)?new root.Request(url,input):url,init:next};
  }
  if(typeof root.fetch==='function'&&!root.__dshaBridgeTokenCompatV1){
    var original=root.fetch;
    root.fetch=function(input,init){try{var changed=rewrite(input,init);return changed?original.call(this,changed.input,changed.init):original.call(this,input,init);}catch(error){if(typeof root.Promise==='function')return root.Promise.reject(error);throw error;}};
    root.__dshaBridgeTokenCompatV1=true;
  }
  if(typeof module!=='undefined'&&module.exports)module.exports={rewrite:rewrite};
})(typeof globalThis!=='undefined'?globalThis:typeof self!=='undefined'?self:this);
