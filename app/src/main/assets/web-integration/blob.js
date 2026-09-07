(function(){
  if (window.__dshaBlobListener) return;
  window.__dshaBlobListener = true;
  window.addEventListener('message', event => {
    if (event.source !== window && event.source !== null || event.origin && event.origin !== 'null' && event.origin !== location.origin
        || event.data !== 'dsha-blob-port' || !event.ports[0]) return;
    const port = event.ports[0]; let ack;
    port.onmessage = async event => {
      const value = JSON.parse(event.data);
      if (value.type === 'ack') { const resolve = ack; ack = null; resolve?.(); return; }
      if (value.type !== 'blob' || !(value.url.startsWith('blob:'+location.origin+'/') || value.url.startsWith('data:'))) return;
      try {
        const response = await fetch(value.url);
        if (!response.ok || !response.body) throw new Error('网页文件已过期，请重新导出');
        const reader = response.body.getReader(); let total = 0, sequence = 0;
        try {
          while (true) {
            const chunk = await reader.read(); if (chunk.done) break;
            for (let offset=0;offset<chunk.value.length;offset+=24576) {
              const bytes = chunk.value.subarray(offset,offset+24576); total += bytes.length;
              if (total > 2147483648) throw new Error('文件超过 2 GiB');
              let binary=''; for (let i=0;i<bytes.length;i++) binary+=String.fromCharCode(bytes[i]);
              await new Promise((resolve,reject) => {
                const timer=setTimeout(()=>reject(new Error('文件传输超时')),30000);
                ack=()=>{clearTimeout(timer);resolve()};
                port.postMessage(JSON.stringify({type:'chunk',sequence:sequence++,data:btoa(binary)}));
              });
            }
          }
          port.postMessage(JSON.stringify({type:'done',bytes:total}));
        } finally { await reader.cancel().catch(()=>{}); }
      } catch(error) { port.postMessage(JSON.stringify({type:'error',message:String(error.message).slice(0,150)})); }
    };
  });
})();
