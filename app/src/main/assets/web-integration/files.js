(function () {
  // 由原生 WebMessageListener 按精确来源注入；外部页、iframe 与普通浏览器不启用。
  if (window !== window.top || !window.DshaFiles || window.__dshaFilesInstalled) return;
  window.__dshaFilesInstalled = true;
  const pending = new Map();
  const api = window.DSHA || (window.DSHA = {});
  function request(action, file) {
    if (!(file instanceof Blob)) return Promise.reject(new TypeError('Expected a File or Blob'));
    const limit = action === 'share' ? 32 * 1024 * 1024 : 2147483648;
    if (file.size > limit) return Promise.reject(new Error('File is too large'));
    if (pending.size) return Promise.reject(new Error('A file transfer is already pending'));
    const id = Array.from(crypto.getRandomValues(new Uint8Array(16)), b => b.toString(16).padStart(2, '0')).join('');
    const url = URL.createObjectURL(file);
    return new Promise((resolve, reject) => {
      pending.set(id, {resolve, reject, url});
      try {
        DshaFiles.postMessage(JSON.stringify({id, action, url, bytes: file.size,
          name: file.name || 'download.bin', mime: file.type || 'application/octet-stream'}));
      } catch (error) { pending.delete(id); URL.revokeObjectURL(url); reject(error); }
    });
  }
  DshaFiles.onmessage = event => {
    let result;
    try { result = JSON.parse(event.data); } catch (_) { return; }
    const task = pending.get(result.id);
    if (!task) return;
    pending.delete(result.id);
    URL.revokeObjectURL(task.url);
    if (result.ok) task.resolve({status: result.detail});
    else { const error = new Error(result.detail || 'File transfer failed');
      if (result.detail === 'AbortError') error.name = 'AbortError';
      task.reject(error); }
  };
  api.canShareFile = file => file instanceof Blob && file.size <= 32 * 1024 * 1024;
  api.shareFile = file => request('share', file);
  api.saveFile = file => request('save', file);
  // 原始 download 属性只在 DOM 中可得，不能等 DownloadListener 收到 UUID Blob URL 后再猜。
  document.addEventListener('click', event => {
    const link = event.target?.closest?.('a[download]');
    if (!link || event.defaultPrevented || !(link.href.startsWith('blob:' + location.origin + '/')
        || link.href.startsWith('data:'))) return;
    event.preventDefault();
    const url = link.href, name = link.download || 'download.bin';
    fetch(url).then(response => {
      if (!response.ok) throw new Error('Unable to read the file');
      return response.blob();
    }).then(blob => api.saveFile(new File([blob], name, {type: blob.type})))
      .catch(error => { if (error.name !== 'AbortError') alert('文件未保存 / File not saved: ' + error.message); });
  }, true);
  window.addEventListener('pagehide', () => {
    for (const task of pending.values()) { URL.revokeObjectURL(task.url); task.reject(new Error('Page closed')); }
    pending.clear();
  });
})();
