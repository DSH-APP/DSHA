window.__ModuleLoader__.load({id:'dsh-app-integration', factory: () => {
  const LIMIT = 256 * 1024 * 1024;
  const prefix = 'dsha.images.revision:';
  const DRAFT_OWNER = 'DSHA_IMAGE_DRAFT_V1';
  const LEASE_PREFIX = 'dsha.images.lease:';
  const DELETED_PREFIX = 'dsha.images.deleted:';
  const MAX_RECORDS = 512, MAX_PROOF_KEYS = 4096, GC_RECORDS = 32;
  let serial = 0;
  const unique = () => Date.now().toString(36) + '-' + Math.random().toString(36).slice(2) + '-' + (++serial);
  const PAGE_ID = unique(), LEASE_HEARTBEAT_MS = 30000, LEASE_STALE_MS = 120000;
  const ownLeases = new Set(), recoveryWaiters = new Map();
  let leaseTimer = 0, leaseWatching = false, pagePaused = false, recoveryBanner;
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
  function draftText(zh,en) {
    const lang = typeof document !== 'undefined' ? document.documentElement?.lang : '';
    const selected = window.__DSHA_LANGUAGE__ || lang || (typeof navigator !== 'undefined' ? navigator.language : 'zh');
    return /^zh(?:[-_]|$)/i.test(selected) ? zh : en;
  }
  function draftFailure(error) {
    const code = error?.message, name = error?.name;
    if (code === 'DRAFT_OTHER_PAGE_REFERENCE') return {kind:'other-page',text:draftText(
      '另一页正在编辑这份图片草稿，请关闭该页后重试；退出前请保留原图。',
      'Another page is editing this image draft. Close that page to retry, and keep the originals before leaving.')};
    if (code === 'DRAFT_LEGACY_REFERENCE') return {kind:'legacy-reference',recover:true,text:draftText(
      '旧页面的草稿占用记录需要恢复。请保留原图并关闭其他 DSH 页面，再点“恢复草稿暂存”。',
      'Old draft references need recovery. Keep the originals, close other DSH pages, then select Recover draft saving.')};
    if (code === 'DRAFT_REFERENCE_PROOF_UNKNOWN') return {kind:'unknown-reference',recover:true,text:draftText(
      '本机草稿占用记录无法识别。请保留原图并关闭其他 DSH 页面，再点“恢复草稿暂存”。',
      'Local draft references could not be identified. Keep the originals, close other DSH pages, then select Recover draft saving.')};
    if (code === 'DRAFT_REFERENCE_PROOF_UNAVAILABLE' || name === 'SecurityError') return {kind:'storage-access',text:draftText(
      '无法核对本机草稿引用，请检查浏览器的存储权限，保留原图后重新打开页面。',
      'Local draft references could not be checked. Check browser storage permissions, keep the originals, and reopen this page.')};
    if (name === 'QuotaExceededError') return {kind:'quota',text:draftText(
      '本机草稿存储空间不足，请保留原图并清理设备空间后重试。',
      'Local draft storage is full. Keep the originals, free device storage, and retry.')};
    if (code === 'DRAFT_IMAGE_LIMIT') return {kind:'image-limit',text:draftText(
      '单份图片草稿最多 20 张、合计 256 MiB。请减少图片后重试，并保留原图。',
      'One image draft can hold at most 20 images totaling 256 MiB. Reduce the images and keep the originals.')};
    if (['DRAFT_CAPACITY_LIMIT','DRAFT_RECORD_LIMIT'].includes(code)) return {kind:'capacity',text:draftText(
      '图片草稿库已达到容量上限，请发送或移除不需要的图片草稿后重试，并保留原图。',
      'The image draft store is at capacity. Send or remove unneeded image drafts, then retry. Keep the originals.')};
    if (['DRAFT_CAPACITY_UNKNOWN','DRAFT_UNKNOWN_CURRENT_RECORD','DRAFT_RECORD_INVALID'].includes(code)) return {kind:'unknown-record',text:draftText(
      '部分图片草稿无法安全识别。请保留原图并导出重要数据后反馈问题，已有草稿未被清理。',
      'Some image drafts could not be safely identified. Keep the originals, export important data, and report the issue. Existing drafts were retained.')};
    return {kind:'database',text:draftText(
      '图片草稿写入失败，请保留原图后重新打开页面重试。',
      'The image draft could not be saved. Keep the originals, then reopen this page to retry.')};
  }
  function readLease(value) {
    let row; try { row = JSON.parse(value); } catch { return null; }
    if (!row || row.owner !== DRAFT_OWNER || typeof row.id !== 'string' || !row.id || row.id.length > 512
        || !['active','pending'].includes(row.state)
        || Object.keys(row).some(key => !['owner','id','state','page','at'].includes(key))
        || (row.at !== undefined && (!Number.isSafeInteger(row.at) || row.at < 1))
        || (row.page !== undefined && (typeof row.page !== 'string' || !row.page || row.page.length > 160 || row.at === undefined))) return null;
    return row;
  }
  function leaseRows(storage) {
    const length = storage.length;
    if (!Number.isSafeInteger(length) || length < 0 || length > MAX_PROOF_KEYS || typeof storage.key !== 'function')
      throw new Error('DRAFT_REFERENCE_PROOF_UNAVAILABLE');
    const keys = new Set();
    for (let i = 0; i < length; i++) {
      const key = storage.key(i);
      if (typeof key !== 'string' || keys.has(key)) throw new Error('DRAFT_REFERENCE_PROOF_UNAVAILABLE');
      keys.add(key);
    }
    return Array.from(keys).filter(key => key.startsWith(LEASE_PREFIX)).map(key => {
      const value = storage.getItem(key);
      return {key,value,row:readLease(value)};
    }).filter(entry => entry.value !== null);
  }
  const staleLease = (row,now = Date.now()) => typeof row?.page === 'string' && now - row.at > LEASE_STALE_MS;
  function ownedLease(storage,key,value) {
    for (const entry of ownLeases) if (entry.storage === storage && entry.key === key && entry.owns(value)) return true;
    return false;
  }
  function wakeLeases() {
    if (pagePaused) return;
    for (const entry of ownLeases) entry.wake?.();
  }
  function heartbeatLeases() {
    if (pagePaused) return;
    for (const entry of ownLeases) { try { entry.refresh(); } catch (error) { entry.failed?.(error); } }
    wakeLeases();
  }
  const leaseStorageChanged = event => { if (event.key?.startsWith(LEASE_PREFIX) && event.newValue === null) wakeLeases(); };
  const leasePageHide = () => {
    pagePaused = true;
    // 真实离页及时释放；BFCache 恢复时重新登记。进行中的提交仍由 pending lease 保护。
    for (const entry of ownLeases) if (entry.state === 'active') entry.suspend();
    if (leaseTimer) { clearInterval(leaseTimer); leaseTimer = 0; }
  };
  const leasePageShow = () => { pagePaused = false; heartbeatLeases(); maintainLeaseWatch(); };
  function maintainLeaseWatch() {
    if (ownLeases.size && !leaseWatching) {
      leaseWatching = true;
      window.addEventListener?.('storage',leaseStorageChanged);
      window.addEventListener?.('pagehide',leasePageHide);
      window.addEventListener?.('pageshow',leasePageShow);
      if (typeof document !== 'undefined') document.addEventListener?.('visibilitychange',heartbeatLeases);
    }
    if (ownLeases.size && !pagePaused && !leaseTimer) leaseTimer = setInterval(heartbeatLeases,LEASE_HEARTBEAT_MS);
    if (!ownLeases.size) {
      if (leaseTimer) { clearInterval(leaseTimer); leaseTimer = 0; }
      if (leaseWatching) {
        leaseWatching = false;
        window.removeEventListener?.('storage',leaseStorageChanged);
        window.removeEventListener?.('pagehide',leasePageHide);
        window.removeEventListener?.('pageshow',leasePageShow);
        if (typeof document !== 'undefined') document.removeEventListener?.('visibilitychange',heartbeatLeases);
      }
    }
  }
  function lease(storage,id,state,wake,failed) {
    const key = LEASE_PREFIX + unique(); let value = null, closed = false;
    const entry = {key,storage,state,wake,failed,
      owns(current) { return !closed && value !== null && value === current; },
      refresh() {
        if (closed || pagePaused) return;
        const current = storage.getItem(key);
        if (current !== null && current !== value) throw new Error('DRAFT_REFERENCE_PROOF_UNKNOWN');
        const next = JSON.stringify({owner:DRAFT_OWNER,id,state,page:PAGE_ID,at:Date.now()});
        storage.setItem(key,next); value = next;
      },
      suspend() { try { if (value !== null && storage.getItem(key) === value) storage.removeItem(key); } catch {} value = null; },
      release() { if (!closed) { closed = true; entry.suspend(); ownLeases.delete(entry); maintainLeaseWatch(); } }
    };
    entry.refresh(); ownLeases.add(entry); maintainLeaseWatch(); return entry;
  }
  function protectedDrafts(storage, ownLease, pendingLease) {
    const ids = new Set(), others = new Set(), legacy = new Set(), rows = leaseRows(storage);
    if (rows.some(entry => !entry.row)) throw new Error('DRAFT_REFERENCE_PROOF_UNKNOWN');
    for (const {key,value,row} of rows) {
      const own = key === ownLease?.key || key === pendingLease?.key || ownedLease(storage,key,value);
      // 不同页面可以同时编辑同一 session；只回收有心跳格式且确实过期的引用。
      // 无页身份的旧格式（包括现场热修的 at）不能证明页面已死，留给显式恢复。
      if (!own && staleLease(row) && storage.getItem(key) === value) { storage.removeItem(key); continue; }
      ids.add(row.id);
      if (!own) { others.add(row.id); if (row.page === undefined) legacy.add(row.id); }
    }
    return {ids,others,legacy};
  }
  function requireDraftAccess(proof,id) {
    if (proof.others.has(id)) throw new Error(proof.legacy.has(id) ? 'DRAFT_LEGACY_REFERENCE' : 'DRAFT_OTHER_PAGE_REFERENCE');
  }
  function recoveryCandidates(storage) {
    const rows = leaseRows(storage), candidates = [];
    for (const entry of rows) {
      if (ownedLease(storage,entry.key,entry.value)) continue;
      if (entry.row?.page !== undefined && !staleLease(entry.row)) throw new Error('DRAFT_OTHER_PAGE_REFERENCE');
      candidates.push({key:entry.key,value:entry.value});
    }
    return candidates;
  }
  function recoverDraftLeases(storage,snapshot) {
    const current = recoveryCandidates(storage);
    // 用户确认以后重新核对全部引用。确认期间出现的新页/新值不属于本次授权。
    if (current.length !== snapshot.length || current.some(entry => !snapshot.some(old => old.key === entry.key && old.value === entry.value)))
      throw new Error('DRAFT_REFERENCE_PROOF_UNAVAILABLE');
    for (const entry of current) if (storage.getItem(entry.key) !== entry.value) throw new Error('DRAFT_REFERENCE_PROOF_UNAVAILABLE');
    for (const entry of current) if (storage.getItem(entry.key) === entry.value) storage.removeItem(entry.key);
    return current.length;
  }
  function removeRecovery(waiter) {
    recoveryWaiters.delete(waiter);
    if (!recoveryWaiters.size) { recoveryBanner?.remove(); recoveryBanner = undefined; }
  }
  function offerRecovery(waiter,storage,shell,retry) {
    recoveryWaiters.set(waiter,{storage,shell,retry});
    if (recoveryBanner || typeof document === 'undefined' || !document.body?.appendChild) return;
    const banner = document.createElement('aside'), text = document.createElement('span'), button = document.createElement('button');
    banner.setAttribute('data-dsha-draft-recovery',''); banner.setAttribute('role','status');
    banner.style.cssText = 'position:fixed;z-index:10000;top:env(safe-area-inset-top,0px);left:12px;right:12px;display:flex;align-items:center;gap:12px;max-width:640px;margin:8px auto;padding:10px 12px;border:1px solid var(--dsw-alias-border-l2,GrayText);border-radius:10px;background:var(--dsw-alias-bg-module-platform,Canvas);color:var(--dsw-alias-label-primary,CanvasText);font:inherit;box-shadow:0 2px 8px #0002;';
    text.textContent = draftText('图片草稿暂存需要恢复。','Image draft saving needs recovery.'); text.style.flex = '1';
    button.type = 'button'; button.textContent = draftText('恢复草稿暂存','Recover draft saving');
    button.style.cssText = 'flex:none;max-width:50%;padding:7px 10px;border:1px solid currentColor;border-radius:7px;background:transparent;color:inherit;font:inherit;cursor:pointer;';
    button.onclick = async () => {
      const waiting = Array.from(recoveryWaiters.values()), stores = Array.from(new Set(waiting.map(entry => entry.storage)));
      let snapshots;
      try {
        snapshots = stores.map(store => ({store,rows:recoveryCandidates(store)}));
        if (!window.confirm(draftText(
          '请先保留当前原图，并关闭所有其他 DSH 页面，再恢复草稿暂存。仅移除旧页面的占用记录，已存图片会保留。是否继续？',
          'Keep the original images and close every other DSH page before recovering draft saving. Only old page references will be removed; saved images will remain. Continue?'))) return;
        // 原生确认期间其他 renderer 的 storage 通知可能排队，先让其进入本页再重新核对。
        await new Promise(resolve => setTimeout(resolve,0));
        for (const {store,rows} of snapshots) recoverDraftLeases(store,rows);
        button.disabled = true;
        await Promise.all(waiting.map(entry => entry.retry()));
      } catch (error) { waiting[0]?.shell.notify?.('error',draftFailure(error).text); }
      finally { button.disabled = false; }
    };
    banner.append(text,button); document.body.appendChild(banner); recoveryBanner = banner;
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
          requireDraftAccess(protectedIds,record.id);
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
    const warned = new Set(), waiter = {};
    let activeLease, previousImages, dirty = false, writing = 0, change = 0;
    const images = () => conversation.resolveDraftAttachments(attachmentIds(shell)).filter(a => a.kind === 'image');
    const warn = error => {
      if (disposed || !alive()) return;
      const failure = draftFailure(error);
      if (!warned.has(failure.kind)) { warned.add(failure.kind); shell.notify?.('error',failure.text); }
      if (failure.recover) offerRecovery(waiter,storage,shell,() => { dirty = true; return save(); });
    };
    const save = () => {
      let pendingLease; dirty = true;
      try {
        if (disposed || !alive() || pagePaused) return Promise.resolve(false);
        activeLease ??= lease(storage,id,'active',retry,warn);
        activeLease.refresh();
        const attachments = images();
        requireDraftAccess(protectedDrafts(storage,activeLease),id);
        pendingLease = lease(storage,id,'pending');
        const revision = unique();
        const savingChange = change;
        const files = attachments.map(a => ({blob:a.file,name:a.file.name,type:a.file.type,lastModified:a.file.lastModified}));
        const bytes = files.reduce((sum,f) => sum + f.blob.size,0);
        if (files.length > 20 || bytes > LIMIT) throw new Error('DRAFT_IMAGE_LIMIT');
        // 同步写入修订号，避免进程在 IndexedDB 提交前退出时复活已发送/删除的图片。
        storage.setItem(key,revision);
        writing++;
        return writeDraft(db,{id,revision,files,bytes},() => storage.getItem(key),{storage,lease:activeLease,pendingLease})
          .then(() => {
            if (savingChange === change && storage.getItem(key) === revision) { dirty = false; warned.clear(); removeRecovery(waiter); }
            return true;
          }).catch(error => { warn(error); return false; })
          .finally(() => { writing--; pendingLease.release(); });
      } catch (error) { pendingLease?.release(); warn(error); return Promise.resolve(false); }
    };
    const retry = () => { if (dirty && !loading && !writing && !disposed && alive()) save(); };
    const off = shell.state.subscribe(() => {
      const next = JSON.stringify(attachmentIds(shell));
      if (next === previous) return;
      previous = next;
      try {
        const nextImages = JSON.stringify(images().map(a => a.id));
        // 普通文件交给上游附件模块，不把其变化误报为图片草稿保存失败。
        if (nextImages === previousImages) return;
        previousImages = nextImages; changed = true; change++;
        if (!loading) save();
      } catch (error) { warn(error); }
    });
    let maySave = false;
    try {
      previousImages = JSON.stringify(images().map(a => a.id));
      activeLease = lease(storage,id,'active',retry,warn);
      const revision = storage.getItem(key);
      const record = await request(db.transaction('drafts').objectStore('drafts').get(id));
      if (!changed && alive() && storage.getItem(key) === revision && !deletedRevision(storage,record ?? {id,revision:''})
          && usable(record,revision) && attachmentIds(shell).length === 0) {
        const files = record.files.map(f => new File([f.blob],f.name,{type:f.type,lastModified:f.lastModified}));
        const images = conversation.createDrafts(id,files);
        const accepted = shell.actions.addAttachments(images.map(image => image.id));
        if (accepted === false) for (const image of images) conversation.releaseDraftAttachment(image.id);
      }
      maySave = changed || images().length > 0;
    } catch (error) { warn(error); maySave = changed; }
    loading = false;
    if (alive() && maySave) save();
    else if (!alive()) { off(); activeLease?.release(); removeRecovery(waiter); disposed = true; }
    return () => { if (!disposed) { disposed = true; off(); activeLease?.release(); removeRecovery(waiter); } };
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
  return {inject:['conversation','sessions','layout','sidebarRight','uiWorkspace'],apply,watchDraft,usable,knownDraft,writeDraft,lease,protectedDrafts,recoveryCandidates,recoverDraftLeases,draftFailure,installDraftDeletionProof,residentInputs,createSessionIndex,installReadingPosition,installStreamResume};
}});
