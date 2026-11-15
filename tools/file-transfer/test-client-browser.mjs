// Real locked rc3 React + mobile bundle + owned attachment component.
// Synthetic file metadata/image bytes only; no account, device, or source file writes.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { browserFixture } from '../rc1-browser-fixture.mjs';

const fixture = await browserFixture();
const page = fixture.page; page.setDefaultTimeout(4000);
const recipe = name => JSON.parse(fs.readFileSync('app/src/main/assets/' + name, 'utf8'));
try {
  const source = fs.readFileSync('app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js', 'utf8');
  await page.addScriptTag({content: source.replace('var __cache = {};', 'window.mobileRequire=__localRequire; var __cache = {};')});
  // 0.2.2-rc.1: the attachment button only spreads upstream context-menu trigger props;
  // DSHA's gallery item lives in the upstream file-actions image menu.
  await fixture.load('dsh-client-ui-attachment', [], 'module.exports.audit = { MessageImage };');
  await fixture.load('dsh-client-ui-file-actions', [recipe('file-image-actions-patch.json')], 'module.exports.audit = { registerFileMenus };');
  await fixture.load('dsh-client-ui-deliverables', [recipe('file-deliverables-patch.json')], 'module.exports.audit = { Deliverables };');
  await page.evaluate(() => {
    document.documentElement.lang = 'zh-CN';
    window.calls = []; window.tasks = []; window.prepares = []; window.savedImages = []; window.defer = false;
    window.shareBytes = 32 * 1024 * 1024; window.released = [];
    window.DSHA = {
      downloadArtifact: async (artifact, destination) => { tasks.push({artifact,destination}); return {status:'queued',taskId:'task-'+tasks.length}; },
      openDownloads: async () => { window.recordsOpened=(window.recordsOpened||0)+1; return {status:'opened'}; },
      saveImage: async file => { savedImages.push({name:file.name,type:file.type,bytes:Array.from(new Uint8Array(await file.arrayBuffer()))}); return {status:'queued',taskId:'image'}; },
    };
    const actualFetch = window.fetch;
    const metadata = body => ({id:'a'.repeat(48),name:body.path.split('/').at(-1),mime:'application/octet-stream',
      bytes:body.path.endsWith('card.bin') ? shareBytes : 4_000_000_000,sha256:'b'.repeat(64),version:'v1'});
    window.fetch = async (url, options) => {
      if (url === '/api/dsha/files/release') { released.push(JSON.parse(options.body)); return new Response('{}'); }
      if (url !== '/api/dsha/files/prepare') return actualFetch(url, options);
      const body = JSON.parse(options.body); calls.push({url,body});
      const response = () => {
        const value = new Response(JSON.stringify(metadata(body)), {status:200,headers:{'content-type':'application/json'}});
        value.blob = () => { throw Error('Persistent files must not use Blob'); };
        value.arrayBuffer = () => { throw Error('Persistent files must not read source bytes'); };
        return value;
      };
      // Deliberately ignore abort to model a late HTTP completion after switch.
      if (window.defer) return new Promise(resolve => prepares.push(() => resolve(response())));
      return response();
    };
    const React = auditModules.react.default || auditModules.react;
    const ReactDOM = auditModules['react-dom'].default || auditModules['react-dom'];
    const controls = mobileRequire('./components/file-share-controls.js');
    window.disposeFiles = controls.installNativeFileActions();
    window.root = ReactDOM.createRoot(document.getElementById('root'));
    window.renderFiles = sessionId => root.render(React.createElement('section', {'data-files-state':'tree'},
      React.createElement('header', null, React.createElement(controls.FilesRowShare, {sessionId})),
      React.createElement('ul', {'data-files-body':true},
        React.createElement('li', {'data-files-entry':'directory','data-files-path':'/work/folder'},
          React.createElement('button',{onClick:()=>window.folderOpened=(window.folderOpened||0)+1},'folder')),
        React.createElement('li', {'data-files-entry':'file','data-files-path':'/work/report.bin'},
          React.createElement('button',{onClick:()=>window.previewOpened=(window.previewOpened||0)+1},'report.bin'))),
      React.createElement('div', {'data-test-card':true}, React.createElement(controls.FileDeliveryActions,{sessionId,absolutePath:'/work/card.bin'})),
      React.createElement('div', {'data-textpreview-state':'text'},
        React.createElement(controls.FilePreviewShareButton,{sessionId,absolutePath:'/work/original.png'}),
        React.createElement('div', {'data-image-preview':true},React.createElement('img',{alt:'preview'})))));
    renderFiles('session-A');
  });
  await page.locator('[data-dsha-row-actions]').waitFor();
  assert.equal(await page.locator('[data-files-entry="directory"] [data-dsha-row-actions]').count(), 0);
  await page.getByRole('button',{name:'folder',exact:true}).click();
  assert.equal(await page.evaluate(()=>folderOpened),1,'Folder remains in the file list');
  await page.getByRole('button',{name:'report.bin',exact:true}).click();
  assert.equal(await page.evaluate(()=>previewOpened),1,'File body still opens preview');
  await page.locator('[data-dsha-row-action="downloads"]').click();
  await page.waitForFunction(()=>tasks.length===1);
  assert.deepEqual(await page.evaluate(()=>({body:calls[0].body,destination:tasks[0].destination,bytes:tasks[0].artifact.bytes})),
    {body:{sessionId:'session-A',path:'/work/report.bin'},destination:'downloads',bytes:4_000_000_000});
  await page.locator('[data-dsha-row-action="more"]').click();
  await page.getByRole('button',{name:'另存为…',exact:true}).click();
  await page.waitForFunction(()=>tasks.length===2);
  assert.equal(await page.evaluate(()=>tasks[1].destination),'saveas');
  assert.match(await page.locator('[data-mobile-nav="file-share-toast"]').textContent(), /另存为/);
  await page.locator('[data-test-card] button[aria-label="更多文件操作"]').click();
  await page.getByRole('button',{name:'分享',exact:true}).click();
  await page.waitForFunction(()=>tasks.length===3);
  assert.deepEqual(await page.evaluate(()=>({path:calls[2].body.path,destination:tasks[2].destination})),{path:'/work/card.bin',destination:'share'});
  const shareCopy = await page.locator('[data-mobile-nav="file-share-toast"]').textContent();
  assert.match(shareCopy, /正在准备分享/); assert.doesNotMatch(shareCopy, /已加入下载|已分享|发送成功/);
  await page.evaluate(()=>{window.shareBytes=32*1024*1024+1;});
  await page.locator('[data-test-card] button[aria-label="更多文件操作"]').click();
  await page.getByRole('button',{name:'分享',exact:true}).click();
  await page.waitForFunction(()=>released.length===1);
  await page.waitForFunction(()=>!document.querySelector('[data-test-card] button').disabled);
  assert.equal(await page.evaluate(()=>tasks.length),3,'Oversized share never becomes a download');
  assert.match(await page.locator('[data-mobile-nav="file-share-toast"]').textContent(), /32 MiB.*下载或另存为/);
  await page.evaluate(()=>{window.shareBytes=32*1024*1024;});
  await page.locator('[data-dsha-preview-actions]').getByRole('button',{name:'保存到相册',exact:true}).click();
  await page.waitForFunction(()=>tasks.length===4);
  assert.equal(await page.evaluate(()=>tasks[3].destination),'gallery');
  await page.locator('[data-image-preview] img').dispatchEvent('contextmenu');
  assert.equal(await page.getByRole('dialog').getByRole('button',{name:'保存到相册',exact:true}).isVisible(),true);
  await page.keyboard.press('Escape');
  const unowned = await page.evaluate(() => {
    const image=document.createElement('img');document.body.appendChild(image);
    image.dispatchEvent(new MouseEvent('contextmenu',{bubbles:true,cancelable:true}));image.remove();
    return document.querySelector('[data-dsha-file-sheet]') === null;
  });
  assert.equal(unowned,true,'Unrelated images are not intercepted');
  await page.getByRole('button',{name:'下载记录',exact:true}).click();
  assert.equal(await page.evaluate(()=>recordsOpened),1);

  // Same component, different Session, pending old prepare: old completion must
  // neither enqueue a stale task nor leave the new Session permanently busy.
  await page.evaluate(()=>{window.defer=true;});
  await page.locator('[data-test-card] button[aria-label="下载"]').click();
  await page.waitForFunction(()=>prepares.length===1);
  await page.evaluate(()=>renderFiles('session-B'));
  await page.waitForFunction(()=>!document.querySelector('[data-test-card] button').disabled);

  await page.locator('[data-test-card] button[aria-label="下载"]').click();
  await page.waitForFunction(()=>prepares.length===2);
  await page.evaluate(()=>prepares[0]());
  await page.waitForTimeout(40);
  assert.equal(await page.locator('[data-test-card] button[aria-label="下载"]').isDisabled(),true,'Old finally cannot clear current flight');
  assert.equal(await page.evaluate(()=>tasks.length),4,'Aborted old metadata cannot enqueue');
  await page.evaluate(()=>prepares[1]());
  await page.waitForFunction(()=>tasks.length===5);
  assert.equal(await page.evaluate(()=>calls.at(-1).body.sessionId),'session-B');
  await page.waitForFunction(()=>!document.querySelector('[data-test-card] button').disabled);

  // The exact patched host supplies path/session to its real slot, and only
  // the restricted native capability suppresses the desktop unavailable copy.
  await page.evaluate(() => {
    const React=auditModules.react.default||auditModules.react;
    const Deliverables=auditExports['@deepseek-ai/dsh-client-ui-deliverables'].audit.Deliverables;
    const controls=mobileRequire('./components/file-share-controls.js');
    window.renderDeliverables = native => {
      const saved=window.DSHA;
      if(!native) delete window.DSHA;
      root.render(React.createElement(Deliverables,{matched:{changes:null,presented:[{path:'/work/declared.txt',seq:1,index:0}]},
        sessionId:'declared-session',t:key=>key,openFile:path=>window.declaredPreview=path,
        // 0.2.2-rc.1: file cards also spread upstream context-menu trigger props.
        useContextMenu:()=>({getTriggerProps:(target,handlers={})=>handlers}),
        useShowCodeDiff:pick=>pick(false),useSessions:pick=>pick({byId:{'declared-session':{cwd:'/work'}}}),
        usePresentedOpen:pick=>pick({}),usePresentedHost:pick=>pick({name:'fixture',available:false,fileManager:null}),
        useChangesSummary:pick=>pick({}),renderSlot:(name,props)=>React.createElement(controls.FileDeliveryActions,props)}));
      return saved;
    };
    window.savedBridge=renderDeliverables(false);
  });
  await page.getByText('presented.unavailable',{exact:true}).waitFor();
  assert.equal(await page.locator('#root [data-dsha-file-actions]').count(),0);
  await page.evaluate(()=>{window.DSHA=savedBridge;renderDeliverables(true);});
  await page.waitForFunction(()=>!Array.from(document.querySelectorAll('span')).some(node=>node.textContent==='presented.unavailable'));
  await page.locator('[data-presented-file] button[aria-label="下载"]').click();
  await page.waitForFunction(()=>prepares.length===3);
  assert.deepEqual(await page.evaluate(()=>calls.at(-1).body),{sessionId:'declared-session',path:'/work/declared.txt'});
  await page.evaluate(()=>prepares[2]());
  await page.waitForFunction(()=>tasks.length===6);
  // Actual attachment MessageImage uses its original authorized Blob URL.
  const original = Array.from(Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aVZsAAAAASUVORK5CYII=','base64'));
  await page.evaluate(bytes=>{
    const React = auditModules.react.default || auditModules.react;
    const MessageImage = auditExports['@deepseek-ai/dsh-client-ui-attachment'].audit.MessageImage;
    // Real upstream image-menu resolver with the DSHA recipe; only the menu host is a fixture.
    const menus = [];
    auditExports['@deepseek-ai/dsh-client-ui-file-actions'].audit.registerFileMenus({
      effect: fn => fn(), contextMenus: {register: menu => (menus.push(menu), () => {}), notify: () => {}},
      remote: {workspaceFiles: {}, session: {}}
    }, {save: async () => ({status:'requested'}), copyImage: async () => {}, canSaveAs: true}, key => key);
    window.imageMenu = menus.find(menu => menu.target === 'image');
    window.menuRows = [];
    const getTriggerProps = target => target === undefined ? {} : {onContextMenu: event => {
      event.preventDefault(); window.menuRows = imageMenu.resolve(target.value);
    }};
    window.originalImageUrl=URL.createObjectURL(new Blob([new Uint8Array(bytes)],{type:'image/png'}));
    root.render(React.createElement(MessageImage, {image:{preview:{url:originalImageUrl,name:'original.png'}},load:()=>Promise.resolve(originalImageUrl),variant:'single',getTriggerProps,
      // 0.2.2-rc.1 DraggableImage prepares a desktop drag source on pointerdown.
      fileDrag:{prepare:async()=>({release(){}}),start(){},release(){}},
      labels:{image:'image',open:'open',openNamed:name=>'open '+name,loading:'loading',loadFailed:'failed',lightbox:{close:'close'}}}));
  },original);
  const imageButton = page.getByRole('button',{name:'open original.png',exact:true});
  await imageButton.waitFor();
  // Android WebView long press is a contextmenu event on the upstream trigger.
  await imageButton.dispatchEvent('contextmenu');
  assert.deepEqual(await page.evaluate(()=>menuRows.map(row=>row.id)),['dsha.saveToGallery','copy','download','saveAs']);
  assert.equal(await page.getByRole('dialog').count(),0,'Long press does not also open preview');
  await page.evaluate(()=>menuRows[0].run());
  await page.getByRole('dialog').getByRole('button',{name:'保存到相册',exact:true}).click();
  await page.waitForFunction(()=>savedImages.length===1);
  assert.deepEqual(await page.evaluate(()=>savedImages[0]),{name:'original.png',type:'image/png',bytes:original});
  const aboveHistoricalLimit = await page.evaluate(async () => {
    window.DSHA.saveImage = async file => { window.largeImageAccepted=file.size;return {status:'queued',taskId:'large-image'}; };
    window.largeImageUrl=URL.createObjectURL(new Blob([new Uint8Array(33*1024*1024)],{type:'image/png'}));
    return window.DSHAFileTransfer.imageMenu(largeImageUrl,'large-original.png');
  });
  assert.equal(aboveHistoricalLimit,true);
  await page.getByRole('dialog').getByRole('button',{name:'保存到相册',exact:true}).click();
  await page.waitForFunction(()=>window.largeImageAccepted===33*1024*1024);
  await page.evaluate(()=>URL.revokeObjectURL(largeImageUrl));
  // Ordinary browsers (no native bridge) and non-Blob sources keep only the upstream rows.
  assert.deepEqual(await page.evaluate(()=>imageMenu.resolve({url:'https://example.invalid/a.png',name:'a.png'}).map(row=>row.id)),['copy','download','saveAs']);
  assert.deepEqual(await page.evaluate(()=>{const saved=window.DSHA.saveImage;delete window.DSHA.saveImage;try{return imageMenu.resolve({url:originalImageUrl,name:'x.png'}).map(row=>row.id);}finally{window.DSHA.saveImage=saved;}}),['copy','download','saveAs']);
  // The lightbox receives the same upstream trigger props.
  await imageButton.click();
  await page.getByRole('dialog').waitFor();
  await page.keyboard.press('Escape');
  await page.evaluate(()=>{root.unmount();disposeFiles();URL.revokeObjectURL(originalImageUrl);});
  assert.equal(await page.locator('[data-dsha-file-sheet]').count(),0);
  assert.deepEqual(fixture.errors,[]);
  console.log('PASS native file UI: metadata-only 4GB download, saveas/share/gallery, preview, directories, records, stale session, upstream image menu gallery item with original bytes');
} finally { await fixture.close(); }
