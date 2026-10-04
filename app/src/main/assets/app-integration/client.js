window.__ModuleLoader__.load({id:'dsh-app-integration', factory: () => {
  const LIMIT = 256 * 1024 * 1024;
  const prefix = 'dsha.images.revision:';
  const DRAFT_OWNER = 'DSHA_IMAGE_DRAFT_V1';
  const LEASE_PREFIX = 'dsha.images.lease:';
  const DELETED_PREFIX = 'dsha.images.deleted:';
  const MAX_RECORDS = 512, MAX_PROOF_KEYS = 4096, GC_RECORDS = 32;
  let serial = 0;
  const unique = () => Date.now().toString(36) + '-' + Math.random().toString(36).slice(2) + '-' + (++serial);
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
    return knownDraft(record) && record.revision === revision;
  }
  function knownDraft(record) {
    if (!record || typeof record.id !== 'string' || !record.id || record.id.length > 512
        || typeof record.revision !== 'string' || !record.revision || record.revision.length > 160
        || (record.owner !== undefined && record.owner !== DRAFT_OWNER)
        || Object.keys(record).some(key => !['id','revision','files','bytes','owner'].includes(key))
        || !Array.isArray(record.files) || record.files.length > 20) return false;
    let bytes = 0;
    for (const file of record.files) {
      if (!file || !(file.blob instanceof Blob) || typeof file.name !== 'string' || typeof file.type !== 'string'
          || !/^image\//.test(file.type) || !Number.isFinite(file.lastModified)
          || Object.keys(file).some(key => !['blob','name','type','lastModified'].includes(key))) return false;
      bytes += file.blob.size;
    }
    return Number.isSafeInteger(bytes) && bytes <= LIMIT && record.bytes === bytes;
  }
  function lease(storage, id, state) {
    const key = LEASE_PREFIX + unique(), value = JSON.stringify({owner:DRAFT_OWNER,id,state});
    storage.setItem(key,value);
    return {key,release() { try { if (storage.getItem(key) === value) storage.removeItem(key); } catch {} }};
  }
  function protectedDrafts(storage, ownLease, pendingLease) {
    const ids = new Set(), others = new Set();
    if (!Number.isSafeInteger(storage.length) || storage.length > MAX_PROOF_KEYS || typeof storage.key !== 'function')
      throw new Error('DRAFT_REFERENCE_PROOF_UNAVAILABLE');
    for (let i = 0; i < storage.length; i++) {
      const key = storage.key(i); if (typeof key !== 'string' || !key.startsWith(LEASE_PREFIX)) continue;
      const value = JSON.parse(storage.getItem(key));
      if (!value || value.owner !== DRAFT_OWNER || typeof value.id !== 'string' || !value.id
          || !['active','pending'].includes(value.state)) throw new Error('DRAFT_REFERENCE_PROOF_UNKNOWN');
      ids.add(value.id); if (key !== ownLease?.key && key !== pendingLease?.key) others.add(value.id);
    }
    return {ids,others};
  }
  function deletedRevision(storage, record) {
    const text = storage.getItem(DELETED_PREFIX + record.id);
    if (text === null) return false;
    let proof; try { proof = JSON.parse(text); } catch { return false; }
    return proof?.owner === DRAFT_OWNER && proof.id === record.id && proof.revision === record.revision
      && proof.kind === 'mobile-nav.session.delete';
  }
  function writeDraft(db, record, currentRevision, options = {}) {
    const storage = options.storage ?? localStorage, budget = options.limit ?? LIMIT;
    return new Promise((resolve,reject) => {
      const tx = db.transaction('drafts','readwrite'), store = tx.objectStore('drafts');
      let failure;
      tx.oncomplete = () => failure ? reject(failure) : resolve();
      tx.onerror = () => reject(tx.error); tx.onabort = () => reject(tx.error || failure || new Error('图片草稿存储已取消'));
      const all = store.getAll(undefined,MAX_RECORDS+1);
      all.onerror = () => { failure = all.error || new Error('DRAFT_READ_FAILED'); tx.abort(); };
      all.onsuccess = () => {
        try {
          if (currentRevision() !== record.revision) return;
          if (!knownDraft(record) || !Number.isSafeInteger(budget) || budget < 1 || budget > LIMIT)
            throw new Error('DRAFT_RECORD_INVALID');
          if (all.result.length > MAX_RECORDS) throw new Error('DRAFT_RECORD_LIMIT');
          const protectedIds = protectedDrafts(storage, options.lease, options.pendingLease);
          if (protectedIds.others.has(record.id)) throw new Error('DRAFT_OTHER_PAGE_REFERENCE');
          // Compute every proof before queuing any mutation. Missing revisions,
          // read failures, unknown rows and unclosed leases never imply orphans.
          let bytes = 0, unknown = false, reclaimed = 0;
          const garbage = [];
          for (const old of all.result) {
            if (!knownDraft(old)) { unknown = true; continue; }
            const revision = storage.getItem(prefix + old.id);
            const obsolete = typeof revision === 'string' && revision !== old.revision;
            if (old.id !== record.id && !protectedIds.ids.has(old.id)
                && (obsolete || deletedRevision(storage,old)) && garbage.length < GC_RECORDS
                && reclaimed + old.bytes <= LIMIT) { garbage.push(old.id); reclaimed += old.bytes; continue; }
            if (old.id !== record.id) bytes += old.bytes;
          }
          const previous = all.result.find(row => row.id === record.id);
          if (previous !== undefined && !knownDraft(previous)) throw new Error('DRAFT_UNKNOWN_CURRENT_RECORD');
          // Safe garbage collection can commit even when capacity prevents this
          // new save, allowing bounded batches to make progress on later writes.
          for (const id of garbage) store.delete(id);
          if (!record.files.length) { store.delete(record.id); return; }
          const count = all.result.length - garbage.length + (previous === undefined && record.files.length ? 1 : 0);
          if (unknown || bytes + record.bytes > budget || count > MAX_RECORDS) {
            failure = new Error(unknown ? 'DRAFT_CAPACITY_UNKNOWN' : 'DRAFT_CAPACITY_LIMIT'); return;
          }
          store.put({...record,owner:DRAFT_OWNER});
        } catch (error) { failure = error; tx.abort(); }
      };
    });
  }
  async function watchDraft(db, conversation, id, shell, alive, storage = localStorage) {
    let loading = true, changed = false, previous = JSON.stringify(attachmentIds(shell)), disposed = false;
    const key = prefix + id;
    let activeLease;
    let warned = false;
    const warn = () => { if (!warned) { warned = true; shell.notify?.('error','图片草稿未能保存到本机，退出前请保留原图并检查存储空间。'); } };
    const save = () => {
      let pendingLease;
      try {
        const attachments = conversation.resolveDraftAttachments(attachmentIds(shell)).filter(a => a.kind === 'image');
        if (protectedDrafts(storage,activeLease).others.has(id)) throw new Error('DRAFT_OTHER_PAGE_REFERENCE');
        pendingLease = lease(storage,id,'pending');
        const revision = unique();
        // 同步写入修订号，避免进程在 IndexedDB 提交前退出时复活已发送/删除的图片。
        storage.setItem(key,revision);
        const files = attachments.map(a => ({blob:a.file,name:a.file.name,type:a.file.type,lastModified:a.file.lastModified}));
        const bytes = files.reduce((sum,f) => sum + f.blob.size,0);
        if (files.length > 20 || bytes > LIMIT) throw new Error('图片草稿太大');
        writeDraft(db,{id,revision,files,bytes},() => storage.getItem(key),{storage,lease:activeLease,pendingLease})
          .catch(warn).finally(() => pendingLease.release());
      } catch { pendingLease?.release(); warn(); }
    };
    const off = shell.state.subscribe(() => {
      const next = JSON.stringify(attachmentIds(shell));
      if (next === previous) return;
      previous = next; changed = true;
      if (!loading) save();
    });
    let maySave = false;
    try {
      activeLease = lease(storage,id,'active');
      const revision = storage.getItem(key);
      const record = await request(db.transaction('drafts').objectStore('drafts').get(id));
      if (!changed && alive() && storage.getItem(key) === revision && !deletedRevision(storage,record ?? {id,revision:''})
          && usable(record,revision) && attachmentIds(shell).length === 0) {
        const files = record.files.map(f => new File([f.blob],f.name,{type:f.type,lastModified:f.lastModified}));
        const images = conversation.createDrafts(id,files);
        const accepted = shell.actions.addAttachments(images.map(image => image.id));
        if (accepted === false) for (const image of images) conversation.releaseDraftAttachment(image.id);
      }
      maySave = changed || attachmentIds(shell).length > 0;
    } catch { warn(); }
    loading = false;
    if (alive() && maySave) save();
    else if (!alive()) { off(); activeLease?.release(); disposed = true; }
    return () => { if (!disposed) { disposed = true; off(); activeLease?.release(); } };
  }
  function installDraftDeletionProof(storage = localStorage) {
    const original = window.fetch;
    if (typeof original !== 'function') return () => {};
    const wrapped = function(input,init) {
      let observed;
      try {
        const url = new URL(typeof input === 'string' ? input : input.url,location.href);
        const method = String(init?.method ?? input?.method ?? 'GET').toUpperCase();
        if (url.origin === location.origin && url.pathname === '/api/mobile-nav.session.delete'
            && method === 'POST' && typeof init?.body === 'string' && init.body.length <= 2048) {
          const id = JSON.parse(init.body).sessionId, revision = storage.getItem(prefix + id);
          if (typeof id === 'string' && id && id.length <= 512 && typeof revision === 'string' && revision)
            observed = {id,revision};
        }
      } catch {}
      const result = Reflect.apply(original,this,arguments);
      if (observed) Promise.resolve(result).then(async response => {
        if (response.status !== 200) return;
        const copy = response.clone(); let text = '';
        if (copy.body?.getReader) {
          const reader = copy.body.getReader(), chunks = []; let bytes = 0, timer;
          const deadline = new Promise(resolve => { timer = setTimeout(() => resolve(null),5000); });
          try { for (;;) { const part = await Promise.race([reader.read(),deadline]);
            if (part === null) { reader.cancel().catch(() => {}); return; } if (part.done) break;
            bytes += part.value.byteLength; if (bytes > 16384) { reader.cancel().catch(() => {}); return; } chunks.push(part.value); }
            const body = new Uint8Array(bytes);let offset=0;for(const chunk of chunks){body.set(chunk,offset);offset+=chunk.byteLength;}
            text = new TextDecoder().decode(body);
          } finally { clearTimeout(timer); reader.releaseLock(); }
        } else {
          const length = Number(copy.headers.get('content-length'));
          if (!Number.isSafeInteger(length) || length < 1 || length > 16384) return;
          text = await copy.text(); if (text.length > 16384) return;
        }
        const body = JSON.parse(text);
        if (body.ok === true && body.deleted === observed.id && storage.getItem(prefix + observed.id) === observed.revision)
          storage.setItem(DELETED_PREFIX + observed.id,JSON.stringify({owner:DRAFT_OWNER,kind:'mobile-nav.session.delete',...observed}));
      }).catch(() => {});
      return result;
    };
    window.fetch = wrapped;
    return () => { if (window.fetch === wrapped) window.fetch = original; };
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
      if (previous && typeof previous.url === 'string') {
        // Reading identity already includes the session id; query credentials are never needed.
        previous.url = previous.url.split('?')[0].split('#')[0];
        const sanitized = JSON.stringify(previous);
        if (sanitized !== previousRaw) localStorage.setItem(storageKey,sanitized);
        previousRaw = sanitized;
      }
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
        const value = JSON.stringify({id,url:location.pathname,positions});
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
        if (previous.id !== id || previous.url !== location.pathname) return;
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
  function installStreamResume(ctx) {
    // Android can resume a document whose carrier still claims OPEN after its
    // background transport died. Use the official Connection reset so Remote
    // journal/snapshot consumers obtain a fresh baseline, never replay a prompt.
    let alive = true, needsResume = document.hidden, timer = 0, lastReset = 0;
    const schedule = (native = false) => {
      if (!alive || document.hidden || timer || (!native && !needsResume)) return;
      if (!needsResume && lastReset && Date.now() - lastReset < 250) return;
      timer = setTimeout(() => {
        timer = 0;
        if (!alive || document.hidden) return;
        needsResume = false; lastReset = Date.now();
        ctx.connection.reconnect();
      }, 50);
    };
    const changed = () => {
      if (document.hidden) {
        needsResume = true;
        if (timer) { clearTimeout(timer); timer = 0; }
      } else schedule();
    };
    const resumed = () => {
      if (!alive) return;
      // Activity may resume before Chromium publishes visibility. Retain the
      // native request even when the preceding hidden callback was lost.
      if (document.hidden) { needsResume = true; return; }
      schedule(true);
    };
    document.addEventListener('visibilitychange', changed);
    window.addEventListener('dsha-browser-resume', resumed);
    window.addEventListener('pageshow', resumed);
    return () => {
      alive = false;
      if (timer) clearTimeout(timer);
      document.removeEventListener('visibilitychange', changed);
      window.removeEventListener('dsha-browser-resume', resumed);
      window.removeEventListener('pageshow', resumed);
    };
  }
  function apply(ctx) {
    ctx.inject(['connection'], scoped => scoped.effect(() => installStreamResume(scoped), 'dsha-browser-stream-resume'));
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
      const stopDraftDeletion = installDraftDeletionProof();
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
        db?.close(); stopReading(); stopDraftDeletion(); document.removeEventListener('dsha-close-details',closeDetails); };
    },'dsha-browser-state');
  }
  return {inject:['conversation','sessions','layout','sidebarRight','uiWorkspace'],apply,watchDraft,usable,knownDraft,writeDraft,installDraftDeletionProof,residentInputs,createSessionIndex,installReadingPosition,installStreamResume};
}});
