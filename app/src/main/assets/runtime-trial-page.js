(function(){
 var nonce='__DSHA_TRIAL_NONCE__',expectedHome=__DSHA_TRIAL_HOME__,ready=false,opened=false,message=false,sent=false,done=false,failed=false,activeSocket=null;
 // 诊断字段：网页到底走到哪一步（启动遮罩/boot 标记、mux socket 状态与关闭码）。
 // 只在 4 秒后按 2 秒节流上报进度，成功与致命失败的上报路径保持不变。
 var booting=false,socketState='none',socketCode=0,socketClean=false,started=Date.now(),lastPost=0,PROGRESS_AFTER=4000,PROGRESS_EVERY=2000;
 var Original=window.WebSocket;
 function Socket(url,protocols){var socket=protocols===undefined?new Original(url):new Original(url,protocols);try{var address=new URL(url,location.href);if(address.host===location.host&&address.pathname==='/api/remote.mux'){
   activeSocket=socket;opened=false;message=false;socketState='connecting';socketCode=0;socketClean=false;
   socket.addEventListener('open',function(){if(activeSocket===socket){opened=true;socketState='open'}});
   socket.addEventListener('close',function(event){if(activeSocket!==socket)return;opened=false;message=false;socketState='closed';socketCode=event&&typeof event.code==='number'?event.code:-1;socketClean=!!(event&&event.wasClean)});
   socket.addEventListener('error',function(){if(activeSocket===socket)socketState='error'});
   socket.addEventListener('message',function(event){if(activeSocket!==socket)return;try{var frame=JSON.parse(event.data),value=frame.value;
    if(frame.type==='item'&&typeof frame.streamId==='string'&&value&&value.type==='ready'&&typeof value.clientId==='string'&&value.clientId.length&&value.host&&value.host.home===expectedHome)message=true;
   }catch(e){}});
  }}catch(e){}return socket}
 Socket.prototype=Original.prototype;Object.setPrototypeOf(Socket,Original);window.WebSocket=Socket;
 function payload(kind){return {nonce:nonce,ready:ready,transport:opened&&message,failure:kind==='fail',kind:kind,boot:booting,socket:{state:socketState,code:socketCode,clean:socketClean}}}
 function post(kind){if(sent||done)return;var now=Date.now();
  if(kind==='progress'&&(now-started<PROGRESS_AFTER||now-lastPost<PROGRESS_EVERY))return;
  lastPost=now;sent=true;
  fetch('/dsha-runtime-trial/'+nonce,{method:'POST',credentials:'same-origin',headers:{'content-type':'application/json'},body:JSON.stringify(payload(kind))}).then(function(r){sent=false;if(r.ok&&kind!=='progress'){done=true;clearInterval(timer)}},function(){sent=false})}
 window.addEventListener('dsha-startup',function(event){try{var value=JSON.parse(event.detail);if(value.type==='ready')ready=true;if(value.type==='issue'&&value.fatal){failed=true;post('fail')}}catch(e){}});
 var timer=setInterval(function(){var root=document.getElementById('root'),boot=document.querySelector('[data-dsh-boot]');booting=!!boot;if(!boot&&root&&root.children.length)ready=true;
  if(failed){post('fail');return}if(ready&&opened&&message){post('ack');return}post('progress')},500);
})();
