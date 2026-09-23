(function(){
 var nonce='__DSHA_TRIAL_NONCE__',expectedHome=__DSHA_TRIAL_HOME__,ready=false,opened=false,message=false,sent=false,acknowledged=false,failed=false,activeSocket=null;
 var Original=window.WebSocket;
 function Socket(url,protocols){var socket=protocols===undefined?new Original(url):new Original(url,protocols);try{var address=new URL(url,location.href);if(address.host===location.host&&address.pathname==='/api/remote.mux'){
  activeSocket=socket;opened=false;message=false;
  socket.addEventListener('open',function(){if(activeSocket===socket)opened=true});
  socket.addEventListener('close',function(){if(activeSocket===socket){opened=false;message=false}});
  socket.addEventListener('message',function(event){if(activeSocket!==socket)return;try{var frame=JSON.parse(event.data),value=frame.value;
   if(frame.type==='item'&&typeof frame.streamId==='string'&&value&&value.type==='ready'&&typeof value.clientId==='string'&&value.clientId.length&&value.host&&value.host.home===expectedHome)message=true;
  }catch(e){}});
 }}catch(e){}return socket}
 Socket.prototype=Original.prototype;Object.setPrototypeOf(Socket,Original);window.WebSocket=Socket;
 function post(failure){if(sent||acknowledged)return;sent=true;fetch('/dsha-runtime-trial/'+nonce,{method:'POST',credentials:'same-origin',headers:{'content-type':'application/json'},body:JSON.stringify({nonce:nonce,ready:ready,transport:opened&&message,failure:failure})}).then(function(r){sent=false;if(r.ok){acknowledged=true;clearInterval(timer)}},function(){sent=false})}
 window.addEventListener('dsha-startup',function(event){try{var value=JSON.parse(event.detail);if(value.type==='ready')ready=true;if(value.type==='issue'&&value.fatal){failed=true;post(true)}}catch(e){}});
 var timer=setInterval(function(){if(failed){post(true);return}var root=document.getElementById('root'),boot=document.querySelector('[data-dsh-boot]');if(!boot&&root&&root.children.length)ready=true;if(ready&&opened&&message)post(false)},500);
})();
