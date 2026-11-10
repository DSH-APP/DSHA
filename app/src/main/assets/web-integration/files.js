(function () {
  // 由原生 WebMessageListener 按精确来源注入；外部页、iframe 与普通浏览器不启用。
  if (window !== window.top || !window.DshaFiles || window.__dshaFilesInstalled) return;
  window.__dshaFilesInstalled = true;
  const pending = new Map();
  const api = window.DSHA || (window.DSHA = {});
  let binding = null, active = true;
  // Native replaces this identity for each committed top-level document. It is a
  // replay fence, not isolation between trusted scripts in the same page.
  window.__dshaFilesBind = value => {
    if (!value || typeof value.nonce !== 'string' || !Number.isSafeInteger(value.generation)
        || typeof value.instance !== 'string') return;
    binding = {nonce: value.nonce, generation: value.generation, instance: value.instance};
  };
  function send(action, fields = {}, url = null) {
    if (!active || !binding) return Promise.reject(new Error('Native file bridge is not ready'));
    if (pending.size >= 16) return Promise.reject(new Error('Too many file requests'));
    const id = Array.from(crypto.getRandomValues(new Uint8Array(16)), b => b.toString(16).padStart(2, '0')).join('');
    return new Promise((resolve, reject) => {
      const task = {resolve, reject, url, action};
      pending.set(id, task);
      // Blob completion can include a user-controlled SAF/share picker. Artifact
      // requests only acknowledge queue admission; a timeout never cancels a task.
      if (!url) task.timer = setTimeout(() => {
        if (!pending.delete(id)) return;
        reject(new Error('Native file response unavailable; check Downloads before retrying'));
      }, 30000);
      try { DshaFiles.postMessage(JSON.stringify({id, action, binding, ...fields})); }
      catch (error) { finish(id); reject(error); }
    });
  }
  function finish(id) {
    const task = pending.get(id);
    if (!task) return null;
    pending.delete(id);
    clearTimeout(task.timer);
    if (task.url) URL.revokeObjectURL(task.url);
    return task;
  }
  function request(action, file) {
    if (!(file instanceof Blob)) return Promise.reject(new TypeError('Expected a File or Blob'));
    const limit = action === 'share' ? 32 * 1024 * 1024 : 5000000000;
    if (file.size > limit) return Promise.reject(new Error('File is too large'));
    if ([...pending.values()].some(task => task.url)) return Promise.reject(new Error('A file transfer is already pending'));
    if (pending.size >= 16) return Promise.reject(new Error('Too many file requests'));
    if (!active || !binding) return Promise.reject(new Error('Native file bridge is not ready'));
    if (action === 'gallery' && !file.type.startsWith('image/'))
      return Promise.reject(new TypeError('Expected an image File or Blob'));
    const url = URL.createObjectURL(file);
    return send(action, {url, bytes: file.size,
      name: file.name || 'download.bin', mime: file.type || 'application/octet-stream'}, url);
  }
  DshaFiles.onmessage = event => {
    let result;
    try { result = JSON.parse(event.data); } catch (_) { return; }
    const task = finish(result.id);
    if (!task) return;
    if (result.ok) {
      if (result.result) task.resolve(result.result);
      else if (typeof result.detail === 'string' && result.detail.startsWith('queued:'))
        task.resolve({status: 'queued', taskId: result.detail.slice(7)});
      else task.resolve({status: result.detail});
    }
    else { const error = new Error(result.detail || 'File transfer failed');
      if (result.detail === 'AbortError') error.name = 'AbortError';
      task.reject(error); }
  };
  api.canShareFile = file => file instanceof Blob && file.size <= 32 * 1024 * 1024;
  api.shareFile = file => request('share', file);
  api.saveFile = file => request('save', file);
  // Existing Blob/File callers remain bounded; large persistent files pass an
  // opaque backend artifact manifest and never materialize a Blob in this page.
  api.saveImage = file => request('gallery', file);
  api.downloadArtifact = (artifact, destination = 'downloads') => {
    if (!artifact || typeof artifact !== 'object' || Array.isArray(artifact)
        || !['downloads', 'gallery', 'saveas', 'share'].includes(destination))
      return Promise.reject(new TypeError('Invalid artifact or destination'));
    const keys = ['id', 'name', 'mime', 'bytes', 'sha256', 'version'];
    if (Object.keys(artifact).some(key => !keys.includes(key))
        || keys.some(key => !Object.prototype.hasOwnProperty.call(artifact, key))
        || typeof artifact.id !== 'string' || !/^[A-Za-z0-9_-]{16,128}$/.test(artifact.id)
        || typeof artifact.name !== 'string' || !artifact.name.trim() || artifact.name.length > 240
        || new TextEncoder().encode(artifact.name).length > 240
        || /[/\\\x00-\x1f\x7f]/.test(artifact.name) || ['.', '..'].includes(artifact.name)
        || typeof artifact.mime !== 'string' || artifact.mime.length > 160
        || !/^[a-zA-Z0-9!#$&^_.+-]+\/[a-zA-Z0-9!#$&^_.+-]+$/.test(artifact.mime)
        || !Number.isSafeInteger(artifact.bytes) || artifact.bytes < 0 || artifact.bytes > 5000000000
        || typeof artifact.sha256 !== 'string' || !/^[a-f0-9]{64}$/.test(artifact.sha256)
        || typeof artifact.version !== 'string' || !artifact.version || artifact.version.length > 256
        || /[\x00-\x1f\x7f]/.test(artifact.version))
      return Promise.reject(new TypeError('Expected an opaque artifact manifest, not a path or URL'));
    if (destination === 'share' && artifact.bytes > 32 * 1024 * 1024)
      return Promise.reject(new Error('System sharing is limited to 32 MiB. Use Download or Save as for larger files.'));
    const manifest = {};
    for (const key of keys) manifest[key] = artifact[key];
    return send('artifact', {artifact: manifest, destination});
  };
  api.openDownloads = () => send('open-downloads');
  api.getDownloads = taskId => {
    if (taskId != null && (typeof taskId !== 'string' || !/^[A-Za-z0-9_-]{1,128}$/.test(taskId)))
      return Promise.reject(new TypeError('Invalid download task id'));
    return send('downloads-status', {taskId: taskId || ''});
  };
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
    active = false;
    for (const id of [...pending.keys()]) {
      const task = finish(id);
      task.reject(new Error(task.url
        ? 'Page closed; unfinished temporary Blob transfers cannot resume. Check Downloads if the image was already queued'
        : 'Page closed; accepted downloads continue in Downloads'));
    }
  });
  window.addEventListener('pageshow', () => { active = true; });
})();
