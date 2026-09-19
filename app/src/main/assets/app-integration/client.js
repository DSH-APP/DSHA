window.__ModuleLoader__.load({id:'dsh-app-integration', factory: () => {
  const LIMIT = 256 * 1024 * 1024;
  const prefix = 'dsha.images.revision:';
  function request(req) { return new Promise((resolve,reject) => { req.onsuccess = () => resolve(req.result); req.onerror = () => reject(req.error); }); }
  function openDatabase() {
    return new Promise((resolve,reject) => {
      const req = indexedDB.open('dsha-image-drafts',1);
      req.onupgradeneeded = () => req.result.createObjectStore('drafts',{keyPath:'id'});
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
      req.onblocked = () => reject(new Error('图片草稿存储正在被其他页面使用'));
    });
  }
  function attachmentIds(shell) { return Array.from(shell.state.getSnapshot().attachmentIds || []); }
  // alpha.2 输入框按 Session binding 管理，内部 WeakMap 不可枚举；只读取已驻留会话。
  function residentInputs(ctx) {
    const shells=new Map();
    for(const row of Object.values(ctx.sessions.list.getSnapshot().byId)) {
      const binding=ctx.sessions.binding(row.id);if(!binding)continue;
      try{shells.set(row.id,ctx.conversation.input.for(binding.ctx));}catch{}
    }
    return shells;
  }
  function usable(record, revision) {
    return record && record.revision === revision && Array.isArray(record.files) && record.files.length <= 20
      && record.files.every(f => f.blob instanceof Blob && /^image\//.test(f.type) && typeof f.name === 'string')
      && record.files.reduce((sum,f) => sum + f.blob.size,0) <= LIMIT;
  }
  function writeDraft(db, record, currentRevision) {
    return new Promise((resolve,reject) => {
      const tx = db.transaction('drafts','readwrite'), store = tx.objectStore('drafts');
      tx.oncomplete = resolve; tx.onerror = () => reject(tx.error); tx.onabort = () => reject(tx.error || new Error('图片草稿存储已取消'));
      const all = store.getAll();
      all.onsuccess = () => {
        if (currentRevision() !== record.revision) return;
        if (!record.files.length) { store.delete(record.id); return; }
        const total = all.result.filter(r => r.id !== record.id).reduce((sum,r) => sum + (r.bytes || 0),record.bytes);
        if (total > LIMIT) { tx.abort(); return; }
        store.put(record);
      };
    });
  }
  async function watchDraft(db, conversation, id, shell, alive, storage = localStorage) {
    let loading = true, changed = false, previous = JSON.stringify(attachmentIds(shell)), disposed = false;
    const key = prefix + id;
    let warned = false;
    const warn = () => { if (!warned) { warned = true; shell.notify?.('error','图片草稿未能保存到本机，退出前请保留原图并检查存储空间。'); } };
    const save = () => {
      try {
        const attachments = conversation.resolveDraftAttachments(attachmentIds(shell)).filter(a => a.kind === 'image');
        const revision = Date.now().toString(36) + '-' + Math.random().toString(36).slice(2);
        // 同步写入修订号，避免进程在 IndexedDB 提交前退出时复活已发送/删除的图片。
        storage.setItem(key,revision);
        const files = attachments.map(a => ({blob:a.file,name:a.file.name,type:a.file.type,lastModified:a.file.lastModified}));
        const bytes = files.reduce((sum,f) => sum + f.blob.size,0);
        if (files.length > 20 || bytes > LIMIT) throw new Error('图片草稿太大');
        writeDraft(db,{id,revision,files,bytes},() => storage.getItem(key)).catch(warn);
      } catch { warn(); }
    };
    const off = shell.state.subscribe(() => {
      const next = JSON.stringify(attachmentIds(shell));
      if (next === previous) return;
      previous = next; changed = true;
      if (!loading) save();
    });
    try {
      const revision = storage.getItem(key);
      const record = await request(db.transaction('drafts').objectStore('drafts').get(id));
      if (!changed && alive() && usable(record,revision) && attachmentIds(shell).length === 0) {
        const files = record.files.map(f => new File([f.blob],f.name,{type:f.type,lastModified:f.lastModified}));
        const images = conversation.createDrafts(id,files);
        const accepted = shell.actions.addAttachments(images.map(image => image.id));
        if (accepted === false) for (const image of images) conversation.releaseDraftAttachment(image.id);
      }
    } catch { warn(); }
    loading = false;
    if (alive()) save();
    else { off(); disposed = true; }
    return () => { if (!disposed) { disposed = true; off(); } };
  }
  function installReadingPosition(ctx) {
    const currentId = () => Object.values(ctx.sessions.list.getSnapshot().byId)
      .find(row => (row.retainedBy?.mainView ?? 0) > 0)?.id ?? null;
    let restoring = true, touched = false, lastSession = currentId(),
      positions = [], deadline = Date.now()+10000, lastSaved = '', leaving = false;
    const storageKey = 'dsha.reading-position';
    let previous;
    try { previous = JSON.parse(localStorage.getItem(storageKey) || 'null'); } catch {}
    let requestedRestore = false;
    const interact = () => { touched = true; restoring = false; };
    document.addEventListener('pointerdown',interact,true); document.addEventListener('keydown',interact,true);
    const pathFor = element => {
      if (element === document || element === document.scrollingElement) return {root:true};
      if (!(element instanceof Element)) return null;
      let node = element, path = [];
      while (node && node !== document.body && path.length < 16) {
        if (node.hasAttribute('data-slot')) return {slot:node.getAttribute('data-slot'),path};
        const parent = node.parentElement; if (!parent) return null;
        path.unshift(Array.prototype.indexOf.call(parent.children,node)); node = parent;
      }
      return null;
    };
    const resolve = item => {
      if (item.root) return document.scrollingElement;
      if (typeof item.slot !== 'string' || !Array.isArray(item.path)) return null;
      let node = Array.from(document.querySelectorAll('[data-slot]')).find(el => el.getAttribute('data-slot') === item.slot);
      for (const i of item.path) node = node?.children?.[i];
      return node;
    };
    const store = () => {
      if (restoring || leaving || document.hidden) return;
      try {
        const value = JSON.stringify({id:currentId(),url:location.pathname+location.search,positions});
        if (value !== lastSaved) { localStorage.setItem(storageKey,value); lastSaved = value; }
      } catch {}
    };
    const scrolled = event => {
      if (restoring || leaving || document.hidden) return;
      if (lastSession !== currentId()) { lastSession = currentId(); positions = []; }
      const node = event.target === document ? document.scrollingElement : event.target;
      const path = pathFor(node); if (!path) return;
      const key = JSON.stringify(path);
      positions = positions.filter(p => JSON.stringify(p.path) !== key);
      positions.push({path,top:node.scrollTop,left:node.scrollLeft}); positions = positions.slice(-8); store();
    };
    document.addEventListener('scroll',scrolled,true);
    const pageHide = () => { store(); leaving = true; };
    window.addEventListener('pagehide',pageHide);
    const timer = setInterval(() => {
      if (document.hidden || leaving) return;
      const snapshot = ctx.sessions.list.getSnapshot();
      if (restoring && !touched && !requestedRestore && snapshot.phase === 'ready'
          && previous?.id && snapshot.byId?.[previous.id] && currentId() !== previous.id) {
        requestedRestore = true;ctx.uiWorkspace.openSession(previous.id); return;
      }
      const id = currentId();
      if (lastSession !== id) { lastSession = id; positions = []; }
      if (restoring) {
        if (!previous || Date.now() > deadline || touched) { restoring = false; store(); return; }
        if (previous.id !== id || previous.url !== location.pathname+location.search) return;
        const pending = Array.isArray(previous.positions) ? previous.positions.slice(0,8) : [];
        let ready = true;
        for (const item of pending) {
          const node = resolve(item.path);
          if (!node || !Number.isFinite(item.top) || node.scrollHeight-node.clientHeight < item.top) { ready = false; continue; }
          node.scrollTop = Math.max(0,item.top); node.scrollLeft = Math.max(0,item.left || 0);
        }
        if (ready && document.readyState === 'complete') { positions = pending; restoring = false; store(); }
      } else store();
    },500);
    return () => { store(); clearInterval(timer); document.removeEventListener('scroll',scrolled,true);
      window.removeEventListener('pagehide',pageHide);
      document.removeEventListener('pointerdown',interact,true); document.removeEventListener('keydown',interact,true); };
  }
  function apply(ctx) {
    ctx.effect(() => {
      // Gecko 132+ 默认只缩小 visual viewport；dsh 的整屏布局需要随键盘一起重新排版。
      const viewport = document.querySelector('meta[name="viewport"]') || document.createElement('meta');
      const directives = (viewport.getAttribute('content') || 'width=device-width, initial-scale=1')
        .split(/[,;]/).map(value => value.trim()).filter(value => value && !/^interactive-widget\s*=/i.test(value));
      viewport.setAttribute('name','viewport');
      viewport.setAttribute('content',directives.concat('interactive-widget=resizes-content').join(', '));
      if (!viewport.parentNode) document.head.appendChild(viewport);
      document.documentElement.setAttribute('data-dsha-integration','ready');
      let alive = true, db, warned = false;
      const entries = new Map();
      const closeDetails = () => { try {
        if (!ctx.sidebarRight.isExpanded()) return;
        ctx.sidebarRight.toggleExpanded();
        document.documentElement.setAttribute('data-dsha-back-handled','true');
      } catch {} };
      document.addEventListener('dsha-close-details',closeDetails);
      const stopReading = installReadingPosition(ctx);
      const scan = () => {
        if (!db || !alive) return;
        const shells = residentInputs(ctx);
        for (const [id,shell] of shells) {
          if (entries.has(id)) continue;
          const entry = {shell,off:null,active:true}; entries.set(id,entry);
          watchDraft(db,ctx.conversation,id,shell,() => alive && entry.active).then(off => { if (!entry.active) off(); else entry.off = off; });
        }
        for (const [id,entry] of entries) if (shells.get(id) !== entry.shell) { entry.active = false; entry.off?.(); entries.delete(id); }
      };
      openDatabase().then(database => { if (!alive) database.close(); else { db = database; scan(); } }).catch(() => {
        if (!warned) { warned = true; residentInputs(ctx).values().next().value?.notify?.('error','图片草稿存储不可用，退出前请保留原图。'); }
      });
      const timer = setInterval(scan,250);
      return () => { alive = false; clearInterval(timer); for (const entry of entries.values()) { entry.active = false; entry.off?.(); }
        document.documentElement.removeAttribute('data-dsha-integration');
        db?.close(); stopReading(); document.removeEventListener('dsha-close-details',closeDetails); };
    },'dsha-browser-state');
  }
  return {inject:['conversation','sessions','layout','sidebarRight','uiWorkspace'],apply,watchDraft,usable,writeDraft,residentInputs};
}});
