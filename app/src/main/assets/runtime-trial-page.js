(function(){
 var nonce='__DSHA_TRIAL_NONCE__',expectedHome=__DSHA_TRIAL_HOME__,ready=false,opened=false,message=false,sent=false,acknowledged=false,failed=false,activeSocket=null;
 var rootReady=false,socketConstructed=false,socketClosed=false,readyFrameSeen=false,clientIdValid=false,homeMatched=false,postAttempted=false,postHttpStatus=0,postNetworkError=false,timerObserved=false,freezeObserved=false,diagnosticTicks=0;
 // Best-effort facts only. The nonce is matched in native memory, never logged.
 // This bridge cannot mark renderer acceptance or cause another network request.
 function diagnose(){try{if(window.DshaTrialDiagnostic&&typeof window.DshaTrialDiagnostic.postMessage==='function')window.DshaTrialDiagnostic.postMessage(JSON.stringify({nonce:nonce,webReady:ready,rootReady:rootReady,socketConstructed:socketConstructed,socketOpen:opened,socketClosed:socketClosed,readyFrameSeen:readyFrameSeen,clientIdValid:clientIdValid,homeMatched:homeMatched,matchedReady:message,postAttempted:postAttempted,postAck:acknowledged,postHttpStatus:postHttpStatus,postNetworkError:postNetworkError,documentHidden:!!document.hidden,navigatorOnline:window.navigator.onLine,timerObserved:timerObserved,freezeObserved:freezeObserved}))}catch(e){}}
 var Original=window.WebSocket;
 function Socket(url,protocols){var socket=protocols===undefined?new Original(url):new Original(url,protocols);try{var address=new URL(url,location.href);if(address.host===location.host&&address.pathname==='/api/remote.mux'){
  activeSocket=socket;opened=false;message=false;socketConstructed=true;socketClosed=false;readyFrameSeen=false;clientIdValid=false;homeMatched=false;diagnose();
  socket.addEventListener('open',function(){if(activeSocket===socket){opened=true;diagnose()}});
  socket.addEventListener('close',function(){if(activeSocket===socket){opened=false;message=false;socketClosed=true;diagnose()}});
  socket.addEventListener('message',function(event){if(activeSocket!==socket)return;try{var frame=JSON.parse(event.data),value=frame.value;
   if(frame.type==='item'&&typeof frame.streamId==='string'&&value&&value.type==='ready'){readyFrameSeen=true;clientIdValid=typeof value.clientId==='string'&&value.clientId.length>0;homeMatched=!!value.host&&value.host.home===expectedHome;diagnose()}
   if(frame.type==='item'&&typeof frame.streamId==='string'&&value&&value.type==='ready'&&typeof value.clientId==='string'&&value.clientId.length&&value.host&&value.host.home===expectedHome){message=true;diagnose()}
  }catch(e){}});
 }}catch(e){}return socket}
 Socket.prototype=Original.prototype;Object.setPrototypeOf(Socket,Original);window.WebSocket=Socket;
 function post(failure){if(sent||acknowledged)return;sent=true;postAttempted=true;diagnose();fetch('/dsha-runtime-trial/'+nonce,{method:'POST',credentials:'same-origin',headers:{'content-type':'application/json'},body:JSON.stringify({nonce:nonce,ready:ready,transport:opened&&message,failure:failure})}).then(function(r){sent=false;postHttpStatus=Number.isInteger(r.status)&&r.status>=100&&r.status<=599?r.status:0;if(r.ok){acknowledged=true;clearInterval(timer)}diagnose()},function(){sent=false;postNetworkError=true;diagnose()})}
 window.addEventListener('dsha-startup',function(event){try{var value=JSON.parse(event.detail);if(value.type==='ready'){ready=true;diagnose()}if(value.type==='issue'&&value.fatal){failed=true;post(true)}}catch(e){}});
 if(typeof document.addEventListener==='function'){
  document.addEventListener('visibilitychange',diagnose);
  document.addEventListener('freeze',function(){freezeObserved=true;diagnose()});
 }
 diagnose();
 var timer=setInterval(function(){var firstTick=!timerObserved;timerObserved=true;diagnosticTicks++;if(firstTick||diagnosticTicks>=20){diagnosticTicks=0;diagnose()}if(failed){post(true);return}var root=document.getElementById('root'),boot=document.querySelector('[data-dsh-boot]');var wasReady=ready;rootReady=!!(!boot&&root&&root.children.length);if(!boot&&root&&root.children.length)ready=true;if(ready!==wasReady)diagnose();if(ready&&opened&&message)post(false)},500);
})();
