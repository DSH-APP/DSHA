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
  // rc1 输入框按 Session binding 管理；缓存由列表/绑定变化失效，普通滚动不扫描目录。
  function residentInputs(ctx) {
    const shells=new Map();
    for(const row of Object.values(ctx.sessions.list.getSnapshot().byId)) {
      const binding=ctx.sessions.binding(row.id);if(!binding)continue;
      try{shells.set(row.id,ctx.conversation.input.for(binding.ctx));}catch{}
    }
    return shells;
  }
  function createSessionIndex(ctx) {
    let source, inputSource, current = null, shells = new Map();
    return {
      invalidate() { source = undefined; inputSource = undefined; },
      refresh() {
        const byId = ctx.sessions.list.getSnapshot().byId;
        if (byId === source) return;
        source = byId;
        current = Object.values(byId).find(row => (row.retainedBy?.mainView ?? 0) > 0)?.id ?? null;
      },
      current() { this.refresh(); return current; },
      inputs() {
        this.refresh();
        if (inputSource !== source) { shells = residentInputs(ctx); inputSource = source; }
        return shells;
      }
    };
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
  function installReadingPosition(ctx, index = createSessionIndex(ctx)) {
    const currentId = () => index.current();
    let restoring = true, touched = false, lastSession = currentId(),
      positions = [], deadline = Date.now()+10000, lastSaved = '', leaving = false,
      dirty = false, scrollFrame = 0, storeTimer = 0;
    const storageKey = 'dsha.reading-position';
    const pendingScrolls = new Set();
    let previous, previousRaw = null;
    try {
      previousRaw = localStorage.getItem(storageKey);
      previous = JSON.parse(previousRaw || 'null');
      if (previousRaw) lastSaved = previousRaw;
    } catch {}
    let requestedRestore = false;
    const scheduleStore = () => {
      dirty = true;
      if (storeTimer) clearTimeout(storeTimer);
      storeTimer = setTimeout(() => { storeTimer = 0; store(); },180);
    };
    const interact = () => {
      touched = true;
      if (restoring) { restoring = false; scheduleStore(); }
    };
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
    const syncSession = () => {
      const id = currentId();
      if (lastSession !== id) { lastSession = id; positions = []; dirty = true; }
      return id;
    };
    function store(force = false) {
      if (restoring || leaving || (!force && document.hidden)) return;
      const id = syncSession();
      if (!dirty) return;
      try {
        const value = JSON.stringify({id,url:location.pathname+location.search,positions});
        if (value !== lastSaved) { localStorage.setItem(storageKey,value); lastSaved = value; }
      } catch {}
      dirty = false;
    }
    const flushScrolls = force => {
      if (restoring || leaving || (!force && document.hidden)) { pendingScrolls.clear(); return; }
      syncSession();
      let changed = false;
      for (const node of pendingScrolls) {
        const path = pathFor(node); if (!path) continue;
        const key = JSON.stringify(path);
        positions = positions.filter(p => JSON.stringify(p.path) !== key);
        positions.push({path,top:node.scrollTop,left:node.scrollLeft});
        positions = positions.slice(-8); changed = true;
      }
      pendingScrolls.clear();
      if (changed) { dirty = true; if (!force) scheduleStore(); }
    };
    const scrolled = event => {
      if (restoring || leaving || document.hidden) return;
      const node = event.target === document ? document.scrollingElement : event.target;
      // 同一帧内同一个滚动容器只处理一次，避免触摸滚动每个事件都走 DOM 路径并同步写存储。
      if (node && (pendingScrolls.size < 16 || pendingScrolls.has(node))) pendingScrolls.add(node);
      if (!scrollFrame) scrollFrame = requestAnimationFrame(() => { scrollFrame = 0; flushScrolls(false); });
    };
    document.addEventListener('scroll',scrolled,{capture:true,passive:true});
    const pageHide = () => {
      if (scrollFrame) { cancelAnimationFrame(scrollFrame); scrollFrame = 0; }
      if (storeTimer) { clearTimeout(storeTimer); storeTimer = 0; }
      flushScrolls(true); store(true); leaving = true;
    };
    window.addEventListener('pagehide',pageHide);
    const timer = setInterval(() => {
      if (document.hidden || leaving) return;
      if (!restoring) { clearInterval(timer); return; }
      const snapshot = ctx.sessions.list.getSnapshot();
      if (restoring && !touched && !requestedRestore && snapshot.phase === 'ready'
          && previous?.id && snapshot.byId?.[previous.id] && currentId() !== previous.id) {
        requestedRestore = true;ctx.uiWorkspace.openSession(previous.id); return;
      }
      const id = syncSession();
      if (restoring) {
        if (!previous || Date.now() > deadline || touched) { restoring = false; dirty = true; store(); return; }
        if (previous.id !== id || previous.url !== location.pathname+location.search) return;
        const pending = Array.isArray(previous.positions) ? previous.positions.slice(0,8) : [];
        let ready = true;
        for (const item of pending) {
          const node = resolve(item.path);
          if (!node || !Number.isFinite(item.top) || node.scrollHeight-node.clientHeight < item.top) { ready = false; continue; }
          node.scrollTop = Math.max(0,item.top); node.scrollLeft = Math.max(0,item.left || 0);
        }
        if (ready && document.readyState === 'complete') { positions = pending; restoring = false; }
      } else store();
    },750);
    const offSessions = ctx.sessions.list.subscribe?.(() => {
      index.invalidate();
      if (!document.hidden && !leaving) { syncSession(); scheduleStore(); }
    });
    return () => { pageHide(); clearInterval(timer); document.removeEventListener('scroll',scrolled,true);
      offSessions?.();
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
      const index = createSessionIndex(ctx);
      const closeDetails = () => { try {
        if (!ctx.sidebarRight.isExpanded()) return;
        ctx.sidebarRight.toggleExpanded();
        document.documentElement.setAttribute('data-dsha-back-handled','true');
      } catch {} };
      document.addEventListener('dsha-close-details',closeDetails);
      const stopReading = installReadingPosition(ctx, index);
      const scan = () => {
        if (!db || !alive || document.hidden) return;
        const shells = index.inputs();
        for (const [id,entry] of entries) if (shells.get(id) !== entry.shell) { entry.active = false; entry.off?.(); entries.delete(id); }
        for (const [id,shell] of shells) {
          if (entries.has(id)) continue;
          const entry = {shell,off:null,active:true}; entries.set(id,entry);
          watchDraft(db,ctx.conversation,id,shell,() => alive && entry.active).then(off => { if (!entry.active) off(); else entry.off = off; });
        }
      };
      openDatabase().then(database => { if (!alive) database.close(); else { db = database; scan(); } }).catch(() => {
        if (!warned) { warned = true; residentInputs(ctx).values().next().value?.notify?.('error','图片草稿存储不可用，退出前请保留原图。'); }
      });
      let scanFrame = 0;
      const changed = () => {
        index.invalidate();
        if (!alive || document.hidden || scanFrame) return;
        scanFrame = requestAnimationFrame(() => { scanFrame = 0; scan(); });
      };
      const offSessions = ctx.sessions.list.subscribe?.(changed);
      document.addEventListener('visibilitychange', changed);
      // 防御尚未发布的绑定变化；后台不枚举，前台至多十秒兜底一次。
      const timer = setInterval(() => { if (!document.hidden) changed(); },10000);
      return () => { alive = false; clearInterval(timer); if (scanFrame) cancelAnimationFrame(scanFrame);
        offSessions?.(); document.removeEventListener('visibilitychange',changed);
        for (const entry of entries.values()) { entry.active = false; entry.off?.(); }
        document.documentElement.removeAttribute('data-dsha-integration');
        db?.close(); stopReading(); document.removeEventListener('dsha-close-details',closeDetails); };
    },'dsha-browser-state');
  }
  return {inject:['conversation','sessions','layout','sidebarRight','uiWorkspace'],apply,watchDraft,usable,writeDraft,residentInputs,createSessionIndex,installReadingPosition};
}});
