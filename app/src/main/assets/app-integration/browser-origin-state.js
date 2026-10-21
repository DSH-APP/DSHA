/* DSHA_ORIGIN_RESTORE_V1: explicit candidates, no credentials, no source deletion. */
(function (scope) {
  'use strict';
  const LIMIT = 256 * 1024 * 1024, MAX_KEYS = 4096, MAX_TEXT = 8 * 1024 * 1024;
  const REV = 'dsha.images.revision:', DELETED = 'dsha.images.deleted:';
  const WIDTH = 'dsh.conversation.contentWidth';
  const req = value => new Promise((resolve, reject) => { value.onsuccess = () => resolve(value.result); value.onerror = () => reject(value.error); });
  const complete = tx => new Promise((resolve, reject) => { tx.oncomplete = resolve; tx.onerror = tx.onabort = () => reject(tx.error || Error('RESTORE_TRANSACTION')); });
  function keyAllowed(key) {
    return typeof key === 'string' && key !== WIDTH && key.length <= 560 && (key === 'dsha.reading-position'
      || /^dsh\.(?:conversation\.|sidebar-right\.v1\.)[^\x00-\x1f]+$/.test(key));
  }
  function draftValid(value) {
    // Official rc2 parseStoredDraft accepts legacy strings and semantic snapshots.
    if (typeof value === 'string') return true;
    if (!value || typeof value !== 'object' || Array.isArray(value)
        || Object.keys(value).some(k => !['text','references','command'].includes(k))
        || typeof value.text !== 'string' || !Array.isArray(value.references) || value.references.length > MAX_KEYS) return false;
    let end = 0;
    if (value.command !== undefined) {
      const command = value.command;
      if (!command || typeof command !== 'object' || Array.isArray(command)
          || Object.keys(command).some(k => k !== 'token') || typeof command.token !== 'string') return false;
      const spelling = command.token.trimEnd();
      if (!/^\/\S/u.test(spelling) || !value.text.startsWith(spelling)) return false;
      end = spelling.length;
    }
    for (const reference of value.references) {
      if (!reference || typeof reference !== 'object' || Array.isArray(reference)
          || Object.keys(reference).some(k => !['source','ref','offset','length','label','appearance','clipboardText','invalid'].includes(k))
          || !['source','ref','label','clipboardText'].every(k => typeof reference[k] === 'string')
          || !Number.isSafeInteger(reference.offset) || reference.offset < end
          || !Number.isSafeInteger(reference.length) || reference.length <= 0 || reference.length !== reference.clipboardText.length
          || (reference.appearance !== undefined && !['session','file','folder'].includes(reference.appearance))
          || (reference.invalid !== undefined && typeof reference.invalid !== 'boolean')) return false;
      end = reference.offset + reference.length;
      if (end > value.text.length || value.text.slice(reference.offset,end) !== reference.clipboardText) return false;
    }
    return true;
  }
  function draftEmpty(value) {
    return value === '' || (draftValid(value) && typeof value === 'object'
      && value.text === '' && value.references.length === 0 && value.command === undefined);
  }
  function textValid(key, raw) {
    if (!keyAllowed(key) || typeof raw !== 'string' || raw.length > 2 * 1024 * 1024) return false;
    try {
      const value = JSON.parse(raw);
      if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
      if (key.startsWith('dsh.conversation.')) return draftValid(value.draft)
        && Object.keys(value).every(k => ['draft','view','viewRequest'].includes(k))
        && (value.view === null || typeof value.view === 'string')
        && (value.viewRequest === null || (value.viewRequest && !Array.isArray(value.viewRequest)
          && typeof value.viewRequest.view === 'string' && Object.keys(value.viewRequest).every(k => ['view','focus'].includes(k))
          && (value.viewRequest.focus === undefined || typeof value.viewRequest.focus === 'boolean')));
      if (key === 'dsha.reading-position') return typeof value.id === 'string' && typeof value.url === 'string'
        && value.url.startsWith('/') && !/[?#]/.test(value.url) && Array.isArray(value.positions) && value.positions.length <= 8;
      const id = key.slice('dsh.sidebar-right.v1.'.length), surface = value.bySession?.[id], layout = surface?.layout;
      if (!layout || !Number.isSafeInteger(surface.minted) || surface.minted < 0 || !layout.nodes || !layout.tabs
          || !Array.isArray(layout.floats) || typeof layout.rootId !== 'string' || typeof layout.activePaneId !== 'string'
          || !['push','fullscreen'].includes(layout.mode) || typeof layout.expanded !== 'boolean') return false;
      const seen = new Set(), used = new Set(), pending = [[layout.rootId,'dock'],...layout.floats.map(id => [id,'float'])];
      for (const [id, node] of [...Object.entries(layout.nodes),...Object.entries(layout.tabs)])
        if (!node || node.id !== id || !/^(pane|float|split|tab)[1-9]\d*$/.test(id) || Number(id.replace(/^[a-z]+/,'')) > surface.minted) return false;
      while (pending.length) {
        const [id,host] = pending.pop(), node = layout.nodes[id];
        if (!node || seen.has(id) || seen.size > MAX_KEYS) return false;
        seen.add(id);
        if (node.kind === 'split') {
          if (host !== 'dock' || id !== layout.rootId || node.axis !== 'row' || !Array.isArray(node.children)
              || node.children.length !== 2 || !Array.isArray(node.sizes) || node.sizes.length !== 2
              || node.sizes.some(n => !Number.isFinite(n) || n <= 0) || Math.abs(node.sizes[0]+node.sizes[1]-1) > 1e-9) return false;
          pending.push(...node.children.map(id => [id,host]));
        } else {
          if (node.kind !== 'pane' || node.host !== host || !Array.isArray(node.tabs)
              || (host === 'float' && (!node.rect || node.tabs.length !== 1))
              || (host === 'dock' && node.rect !== undefined)
              || (node.tabs.length ? !node.tabs.includes(node.activeTabId) : node.activeTabId !== undefined)) return false;
          for (const tab of node.tabs) { if (used.has(tab) || !layout.tabs[tab]) return false; used.add(tab); }
        }
      }
      return seen.size === Object.keys(layout.nodes).length && used.size === Object.keys(layout.tabs).length
        && layout.nodes[layout.activePaneId]?.kind === 'pane';
    } catch (_) { return false; }
  }
  function knownImage(record) {
    return record && typeof record.id === 'string' && record.id.length > 0 && record.id.length <= 512
      && typeof record.revision === 'string' && record.revision.length > 0 && record.revision.length <= 160
      && Object.keys(record).every(key => ['id','revision','owner','files','bytes'].includes(key))
      && Number.isSafeInteger(record.bytes) && record.bytes >= 0
      && (record.owner === undefined || record.owner === 'DSHA_IMAGE_DRAFT_V1') && Array.isArray(record.files)
      && record.files.length <= 20 && record.files.every(f => f?.blob instanceof Blob && typeof f.name === 'string'
        && typeof f.type === 'string' && /^image\//.test(f.type) && Number.isFinite(f.lastModified)
        && Object.keys(f).every(key => ['blob','name','type','lastModified'].includes(key)))
      && record.files.reduce((n,f) => n+f.blob.size,0) === record.bytes && record.bytes <= LIMIT;
  }
  async function database(indexed, name, create) {
    const opening = indexed.open(name,1);
    opening.onupgradeneeded = () => { if (create) opening.result.createObjectStore(create,{keyPath:'id'}); else opening.transaction.abort(); };
    return req(opening);
  }
  async function capture(storage, indexed) {
    if (storage.length > MAX_KEYS) throw Error('RESTORE_KEY_LIMIT');
    const rows = [], unknown = []; let textBytes = 0;
    for (let i=0; i<storage.length; i++) {
      const key = storage.key(i); if (!keyAllowed(key)) continue;
      const value = storage.getItem(key); textBytes += value?.length || 0;
      if (textBytes > MAX_TEXT) throw Error('RESTORE_TEXT_LIMIT');
      rows.push({key,value,valid:textValid(key,value)});
    }
    let db, records = [];
    try { db = await database(indexed,'dsha-image-drafts',null); records = await req(db.transaction('drafts','readonly').objectStore('drafts').getAll(undefined,513)); }
    catch (error) { if (error?.name !== 'AbortError') throw error; }
    finally { db?.close(); }
    if (records.length > 512) throw Error('RESTORE_RECORD_LIMIT');
    const images = [], parts = []; let bytes = 0;
    for (const record of records) {
      const revision = storage.getItem(REV+record.id);
      let deleted; try { deleted = JSON.parse(storage.getItem(DELETED+record.id) || 'null'); } catch (_) {}
      if (!knownImage(record) || revision !== record.revision) { unknown.push(String(record.id || '').slice(0,512)); continue; }
      if (deleted?.owner === 'DSHA_IMAGE_DRAFT_V1' && deleted.id === record.id && deleted.revision === revision) continue;
      bytes += record.bytes; if (bytes > LIMIT) throw Error('RESTORE_IMAGE_LIMIT');
      const files = record.files.map(file => { const part = parts.length; parts.push(file.blob);
        return {part,name:file.name,type:file.type,lastModified:file.lastModified,bytes:file.blob.size}; });
      images.push({id:record.id,revision,bytes:record.bytes,owner:record.owner,files});
    }
    // localStorage cannot share an IDB transaction; require both witnesses again before publishing.
    for (const row of rows) if (storage.getItem(row.key) !== row.value) throw Error('RESTORE_SOURCE_CHANGED');
    for (const image of images) if (storage.getItem(REV+image.id) !== image.revision) throw Error('RESTORE_SOURCE_CHANGED');
    return {manifest:{version:1,source:location.origin,rows,images,unknown,parts:parts.map(b => b.size)},parts};
  }
  async function restore(manifest, blobs, storage, indexed, guard = async () => {}, verify = async () => true) {
    if (manifest.version !== 1 || manifest.rows.length > MAX_KEYS || manifest.images.length > 512 || blobs.length !== manifest.parts.length) throw Error('RESTORE_MANIFEST');
    await guard();
    for (let i=0;i<blobs.length;i++) {
      if (!(blobs[i] instanceof Blob) || blobs[i].size!==manifest.parts[i] || !await verify(blobs[i],i)) throw Error('RESTORE_SOURCE_DIGEST');
    }
    const candidates = await database(indexed,'dsha-origin-recovery','candidates');
    const journalId = canonical(manifest);
    const candidateId = await digest(journalId);
    const candidate = {id:candidateId,manifest,source:manifest.source,rows:manifest.rows,images:manifest.images.map(record => ({...record,
      files:record.files.map(file => ({...file,blob:blobs[file.part]}))})),at:Date.now()};
    let tx;
    try {
      const previous=await req(candidates.transaction('candidates','readonly').objectStore('candidates').get(candidateId));
      if (!previous) {
        tx = candidates.transaction('candidates','readwrite'); const saved = complete(tx); tx.objectStore('candidates').add(candidate); await saved;
      }
      // Metadata AND Blob readback are verified. Existing candidates are never overwritten.
      const read=await req(candidates.transaction('candidates','readonly').objectStore('candidates').get(candidateId));
      if (!read || canonical(read.manifest)!==journalId || read.images?.length!==manifest.images.length) throw Error('RESTORE_CANDIDATE_READBACK');
      for (let n=0;n<read.images.length;n++) {
        const image=read.images[n], expected=manifest.images[n];
        if (image.id!==expected.id || image.revision!==expected.revision || image.files?.length!==expected.files.length) throw Error('RESTORE_CANDIDATE_READBACK');
        for (let i=0;i<image.files.length;i++) {
          const file=image.files[i], wanted=expected.files[i];
          if (file.name!==wanted.name || file.type!==wanted.type || file.lastModified!==wanted.lastModified || file.part!==wanted.part
              || !(file.blob instanceof Blob) || file.blob.size!==wanted.bytes || !await verify(file.blob,wanted.part)) throw Error('RESTORE_CANDIDATE_BLOB_READBACK');
        }
      }
    } finally { candidates.close(); }
    let applied = 0, conflicts = 0;
    for (const row of manifest.rows) {
      await guard();
      if (row.key === WIDTH) continue; // Earlier retained candidates may contain this global preference.
      if (!textValid(row.key,row.value)) { conflicts++; continue; }
      const before = storage.getItem(row.key);
      let empty = false, next = row.value;
      if (row.key.startsWith('dsh.conversation.') && before !== null && textValid(row.key,before)) {
        const target = JSON.parse(before), source = JSON.parse(row.value);
        empty = draftEmpty(target.draft);
        if (empty) {
          // Filling a blank draft does not replace a newer target View selection/request.
          next = JSON.stringify({...target,draft:source.draft});
          if (target.view!==source.view || JSON.stringify(target.viewRequest)!==JSON.stringify(source.viewRequest)) conflicts++;
        }
      }
      if (before !== null && before !== row.value && !empty) { conflicts++; continue; }
      if (storage.getItem(row.key) !== before) throw Error('RESTORE_TARGET_CHANGED');
      if (before !== next) { storage.setItem(row.key,next); if (storage.getItem(row.key) !== next) throw Error('RESTORE_READBACK'); applied++; }
    }
    const db = await database(indexed,'dsha-image-drafts','drafts');
    try {
      for (const image of candidate.images) {
        await guard();
        const record = {...image,files:image.files.map(({blob,name,type,lastModified}) => ({blob,name,type,lastModified}))};
        if (!knownImage(record)) throw Error('RESTORE_IMAGE_RECORD');
        const before = await req(db.transaction('drafts','readonly').objectStore('drafts').get(record.id));
        const revision = storage.getItem(REV+record.id);
        const deletion = storage.getItem(DELETED+record.id);
        if (deletion !== null) { conflicts++; continue; }
        if (revision !== null && revision !== record.revision) { conflicts++; continue; }
        // Another live page's leases are never copied or revoked by restoration.
        let held = false;
        for (let i=0;i<storage.length;i++) {
          const key=storage.key(i); if (!key?.startsWith('dsha.images.lease:')) continue;
          try { const lease=JSON.parse(storage.getItem(key)); if (lease?.id===record.id) held=true; } catch (_) { held=true; }
        }
        if (held || storage.getItem(REV+record.id)!==revision) { conflicts++; continue; }
        let added = false;
        if (before) {
          if (!knownImage(before) || before.revision!==record.revision || before.files.length!==record.files.length
              || before.files.some((f,i)=>f.name!==record.files[i].name||f.type!==record.files[i].type||f.lastModified!==record.files[i].lastModified)) { conflicts++; continue; }
          let equal=true;
          for (let i=0;i<before.files.length;i++) if (!await verify(before.files[i].blob,image.files[i].part)) equal=false;
          if (!equal) { conflicts++; continue; }
        } else {
          await guard();
          if (storage.getItem(REV+record.id)!==revision || storage.getItem(DELETED+record.id)!==deletion || imageHeld(storage,record.id)) { conflicts++; continue; }
          tx = db.transaction('drafts','readwrite'); const done = complete(tx); tx.objectStore('drafts').add(record);
          try { await done; } catch (error) { if (error?.name==='ConstraintError') { conflicts++; continue; } throw error; }
          added = true;
        }
        const read = await req(db.transaction('drafts','readonly').objectStore('drafts').get(record.id));
        if (!knownImage(read) || read.revision !== record.revision) throw Error('RESTORE_IMAGE_READBACK');
        for (let i=0;i<read.files.length;i++) if (!await verify(read.files[i].blob,image.files[i].part)) throw Error('RESTORE_IMAGE_DIGEST');
        await guard();
        if (storage.getItem(REV+record.id)!==revision || storage.getItem(DELETED+record.id)!==deletion || imageHeld(storage,record.id)) { conflicts++; continue; }
        storage.setItem(REV+record.id,record.revision);
        if (storage.getItem(REV+record.id) !== record.revision) throw Error('RESTORE_REVISION_READBACK');
        if (added || revision!==record.revision) applied++;
      }
    } finally { db.close(); }
    return {applied,conflicts,unknown:manifest.unknown?.length || 0,candidate:candidateId};
  }
  function canonical(value) {
    if (Array.isArray(value)) return '['+value.map(canonical).join(',')+']';
    if (value && typeof value==='object') return '{'+Object.keys(value).filter(key=>value[key]!==undefined).sort().map(key=>JSON.stringify(key)+':'+canonical(value[key])).join(',')+'}';
    return JSON.stringify(value);
  }
  function imageHeld(storage,id) {
    for (let i=0;i<storage.length;i++) {
      const key=storage.key(i); if (!key?.startsWith('dsha.images.lease:')) continue;
      try { if (JSON.parse(storage.getItem(key))?.id===id) return true; } catch (_) { return true; }
    }
    return false;
  }
  async function digest(text) { const bytes = await crypto.subtle.digest('SHA-256',new TextEncoder().encode(text)); return Array.from(new Uint8Array(bytes),b => b.toString(16).padStart(2,'0')).join(''); }
  async function run(config) {
    const status = document.getElementById('status');
    const text = (zh,en) => config.language === 'en' ? en : zh;
    const title=document.querySelector('h1'); if (title) title.textContent=text('恢复旧端口草稿','Restore previous-port drafts');
    const call = async (path, options={}) => {
      const response = await fetch(config.transport+path,{...options,credentials:'omit',cache:'no-store',headers:{...options.headers,'X-DSHA-Restore':config.secret}});
      if (!response.ok) throw Error('RESTORE_HTTP_'+response.status); return response;
    };
    try {
      if (config.mode === 'capture') {
        const snapshot = await capture(localStorage,indexedDB);
        await call('/manifest',{method:'POST',body:JSON.stringify(snapshot.manifest)});
        for (let i=0;i<snapshot.parts.length;i++) { status.textContent=text('保存图片候选 ','Saving image candidates ')+(i+1)+'/'+snapshot.parts.length; await call('/part/'+i,{method:'POST',body:snapshot.parts[i]}); }
        await call('/captured',{method:'POST',body:'{}'}); status.textContent=text('旧数据候选已保留，正在恢复…','Previous data was retained. Restoring…');
      } else {
        const manifestText = await (await call('/manifest')).text(), identity = await (await call('/snapshot')).json();
        if (await digest(manifestText)!==identity.snapshot) throw Error('RESTORE_SOURCE_METADATA_CHANGED');
        const manifest = JSON.parse(manifestText), blobs = [];
        for (let i=0;i<manifest.parts.length;i++) blobs.push(await (await call('/part/'+i)).blob());
        const verify = async (blob,index) => { try { await call('/verify/'+index,{method:'POST',body:blob}); return true; } catch (error) { if (error.message==='RESTORE_HTTP_409') return false; throw error; } };
        const result = await restore(manifest,blobs,localStorage,indexedDB,() => call('/lease'),verify);
        status.textContent=text('恢复结果：写入 ','Restore result: written ')+result.applied+text('，冲突 ',', conflicts ')+result.conflicts+text('，未知 ',', unknown ')+result.unknown+text('。候选和旧端口数据已保留。','. Candidates and previous-port data were retained.');
        await call('/finished',{method:'POST',body:JSON.stringify({...result,snapshot:identity.snapshot})});
      }
    } catch (error) { status.textContent=text('恢复未完成：','Restore incomplete: ')+error.message+text('。旧端口数据和已保存候选均保留，可稍后重试。','. Previous data and saved candidates were retained. Retry later.'); }
  }
  scope.DshaOriginState = {keyAllowed,textValid,knownImage,capture,restore,run};
  if (typeof document !== 'undefined') window.addEventListener('dsha-origin-restore-start',event => {
    if (scope.__dshaOriginRestoreStarted) return;
    let config; try { config=JSON.parse(event.detail); } catch (_) { return; }
    if (!config || !/^[a-f0-9]{64}$/.test(config.secret) || !/^http:\/\/127\.0\.0\.1:\d{1,5}$/.test(config.transport) || !['capture','restore'].includes(config.mode)) return;
    scope.__dshaOriginRestoreStarted=true; run(config);
  });
})(globalThis);
