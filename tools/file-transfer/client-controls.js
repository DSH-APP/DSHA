// DSHA_FILE_ACTIONS_V1. Embedded inside the fixed mobile controls module.
// This code never reads persistent workspace file bytes into a browser Blob.
function nativeFiles() {
    return typeof window.DSHA?.downloadArtifact === 'function';
}
function fileCopy() {
    return require('./components/file-share-locale.js').nativeFileCopy();
}
// Native controls do not receive every host locale prop. Track the effective
// document language so mounted previews and decorated file rows relabel in place.
function useFileCopy() {
    const [language, setLanguage] = (0, react_1.useState)(() => document.documentElement.lang);
    (0, react_1.useEffect)(() => {
        const changed = () => setLanguage(document.documentElement.lang);
        const observer = new MutationObserver(changed);
        observer.observe(document.documentElement, { attributes: true, attributeFilter: ['lang'] });
        changed();
        return () => observer.disconnect();
    }, []);
    return require('./components/file-share-locale.js').nativeFileCopy(language);
}
function imagePath(path) { return /\.(?:avif|bmp|gif|heic|heif|ico|jpe?g|png|svg|webp)$/i.test(path); }
function checkedArtifact(value) {
    if (!value || typeof value.id !== 'string' || !value.id || typeof value.name !== 'string'
        || typeof value.mime !== 'string' || !Number.isSafeInteger(value.bytes) || value.bytes < 0
        || typeof value.sha256 !== 'string' || !/^[a-f0-9]{64}$/i.test(value.sha256)
        || typeof value.version !== 'string' || !value.version)
        throw new Error('Invalid prepared file metadata');
    return value;
}
async function prepareNativeFile(sessionId, path, destination, signal) {
    if (!nativeFiles() || typeof sessionId !== 'string' || !sessionId || typeof path !== 'string' || !path)
        throw new Error('File transfer is unavailable');
    const response = await fetch('/api/dsha/files/prepare', { method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ sessionId, path }), signal });
    if (!response.ok) throw new Error('Unable to prepare file (' + response.status + ')');
    const artifact = checkedArtifact(await response.json());
    if (signal.aborted) throw new DOMException('Aborted', 'AbortError');
    if (destination === 'share' && artifact.bytes > 32 * 1024 * 1024) {
        // No native admission was attempted. Retire only this unused descriptor.
        fetch('/api/dsha/files/release', { method: 'POST', credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ id: artifact.id }) }).catch(() => {});
        throw new Error(fileCopy().shareLimit);
    }
    const result = await window.DSHA.downloadArtifact(artifact, destination);
    if (result?.status !== 'queued' || typeof result.taskId !== 'string' || !result.taskId)
        throw new Error('The download task was not accepted');
    return result;
}
let activeFileSheet = null;
function showFileSheet(name, actions) {
    activeFileSheet?.();
    const previous = document.activeElement;
    const backdrop = document.createElement('div');
    backdrop.dataset.dshaFileSheet = '';
    const panel = document.createElement('div');
    panel.setAttribute('role', 'dialog'); panel.setAttribute('aria-modal', 'true');
    panel.setAttribute('aria-label', name);
    const title = document.createElement('div'); title.textContent = name; panel.appendChild(title);
    let closed = false;
    const close = () => {
        if (closed) return; closed = true;
        backdrop.remove(); document.removeEventListener('keydown', key);
        if (activeFileSheet === close) activeFileSheet = null;
        if (previous?.isConnected) previous.focus?.({ preventScroll: true });
    };
    const key = event => {
        if (event.key === 'Escape') { event.preventDefault(); close(); }
        if (event.key === 'Tab') {
            const buttons = Array.from(panel.querySelectorAll('button'));
            const index = buttons.indexOf(document.activeElement);
            if ((event.shiftKey && index <= 0) || (!event.shiftKey && index === buttons.length - 1)) {
                event.preventDefault(); buttons[event.shiftKey ? buttons.length - 1 : 0]?.focus();
            }
        }
    };
    for (const action of [...actions, { label: fileCopy().close, run: () => {} }]) {
        const button = document.createElement('button'); button.type = 'button'; button.textContent = action.label;
        button.addEventListener('click', () => { close(); action.run(); }); panel.appendChild(button);
    }
    backdrop.addEventListener('click', event => { if (event.target === backdrop) close(); });
    backdrop.appendChild(panel); document.body.appendChild(backdrop);
    document.addEventListener('keydown', key); activeFileSheet = close;
    panel.querySelector('button')?.focus();
    return close;
}
function useNativeTransfer(sessionId) {
    const [busy, setBusy] = (0, react_1.useState)(false);
    const flight = (0, react_1.useRef)(null);
    const lifetime = (0, react_1.useRef)(null);
    (0, react_1.useEffect)(() => {
        const identity = { sessionId }; lifetime.current = identity;
        flight.current?.abort(); flight.current = null; setBusy(false);
        return () => {
            if (lifetime.current === identity) { lifetime.current = null; flight.current?.abort(); flight.current = null; activeFileSheet?.(); }
        };
    }, [sessionId]);
    const transfer = (path, destination) => {
        const identity = lifetime.current;
        if (!identity || identity.sessionId !== sessionId || flight.current) return;
        const controller = new AbortController(); flight.current = controller; setBusy(true);
        prepareNativeFile(sessionId, path, destination, controller.signal).then(() => {
            if (!controller.signal.aborted && lifetime.current === identity) showFallbackNotice(
                destination === 'share' ? fileCopy().sharing : destination === 'saveas' ? fileCopy().saving : fileCopy().queued);
        }).catch(error => {
            if (!controller.signal.aborted && lifetime.current === identity && error.name !== 'AbortError') showFallbackNotice(fileCopy().failed + error.message);
        }).finally(() => {
            if (lifetime.current === identity && flight.current === controller) { flight.current = null; setBusy(false); }
        });
    };
    return { busy, transfer };
}
function nativeMenu(path, transfer, image = imagePath(path)) {
    const copy = fileCopy();
    return showFileSheet((0, file_share_core_ts_1.fileNameOf)(path), [
        { label: copy.download + ' · Download/DSHA', run: () => transfer(path, 'downloads') },
        { label: copy.saveas, run: () => transfer(path, 'saveas') },
        { label: copy.share, run: () => transfer(path, 'share') },
        ...(image ? [{ label: copy.gallery, run: () => transfer(path, 'gallery') }] : []),
    ]);
}
function NativeFileButtons({ path, transfer, busy }) {
    const copy = useFileCopy();
    return (0, jsx_runtime_1.jsxs)('span', { 'data-dsha-file-actions': true, children: [
        (0, jsx_runtime_1.jsx)('button', { type: 'button', disabled: busy, 'aria-busy': busy,
            'aria-label': copy.download, title: copy.download + ' · Download/DSHA',
            onClick: event => { event.stopPropagation(); transfer(path, 'downloads'); }, children: (0, jsx_runtime_1.jsx)(DownloadGlyph, {}) }),
        (0, jsx_runtime_1.jsx)('button', { type: 'button', disabled: busy, 'aria-label': copy.more,
            onClick: event => { event.stopPropagation(); nativeMenu(path, transfer); }, children: '⋯' }),
    ] });
}
function FileDeliveryActions({ absolutePath, sessionId }) {
    return nativeFiles() && typeof absolutePath === 'string'
        ? (0, jsx_runtime_1.jsx)(NativeFileActions, { absolutePath, sessionId }) : null;
}
function NativeFileActions({ absolutePath, sessionId }) {
    const { busy, transfer } = useNativeTransfer(sessionId);
    return (0, jsx_runtime_1.jsx)(NativeFileButtons, { path: absolutePath, transfer, busy });
}
function FilePreviewShareButton(props) {
    return (0, jsx_runtime_1.jsx)(nativeFiles() ? NativePreviewActions : LegacyFilePreviewShareButton, props);
}
function NativePreviewActions({ absolutePath, sessionId }) {
    const copy = useFileCopy();
    const { busy, transfer } = useNativeTransfer(sessionId);
    const anchor = (0, react_1.useRef)(null), latest = (0, react_1.useRef)(null);
    latest.current = { transfer, busy, path: absolutePath };
    (0, react_1.useEffect)(() => {
        // Owned preview slot + its stable image body only. Never scan arbitrary images.
        const root = anchor.current?.closest('[data-textpreview-state]');
        if (!root) return;
        const open = event => {
            if (!event.target?.closest?.('[data-image-preview] img') || latest.current.busy) return;
            event.preventDefault(); event.stopPropagation();
            nativeMenu(latest.current.path, latest.current.transfer, true);
        };
        root.addEventListener('contextmenu', open);
        return () => { root.removeEventListener('contextmenu', open); activeFileSheet?.(); };
    }, [sessionId, absolutePath]);
    return (0, jsx_runtime_1.jsxs)('span', { ref: anchor, 'data-dsha-preview-actions': true, children: [
        (0, jsx_runtime_1.jsx)(NativeFileButtons, { path: absolutePath, transfer, busy }),
        imagePath(absolutePath) && (0, jsx_runtime_1.jsx)('button', { type: 'button', disabled: busy,
            onClick: () => transfer(absolutePath, 'gallery'), children: copy.gallery }),
    ] });
}
function FilesRowShare(props) {
    return (0, jsx_runtime_1.jsx)(nativeFiles() ? NativeFilesRows : LegacyFilesRowShare, props);
}
function NativeFilesRows({ sessionId }) {
    const copy = useFileCopy();
    const anchor = (0, react_1.useRef)(null);
    const { busy, transfer } = useNativeTransfer(sessionId);
    const latest = (0, react_1.useRef)(null); latest.current = { busy, transfer };
    const decorateRef = (0, react_1.useRef)(null);
    (0, react_1.useEffect)(() => {
        const body = anchor.current?.closest('[data-files-state="tree"]')?.querySelector('[data-files-body]');
        if (!body) return;
        const click = event => {
            const button = event.target?.closest?.('[data-dsha-row-action]');
            if (!button || !body.contains(button) || latest.current.busy) return;
            const row = button.parentElement?.parentElement;
            if (row?.getAttribute('data-files-entry') !== 'file') return;
            const path = row.getAttribute('data-files-path'); if (!path) return;
            event.preventDefault(); event.stopPropagation();
            if (button.dataset.dshaRowAction === 'downloads') latest.current.transfer(path, 'downloads');
            else nativeMenu(path, latest.current.transfer);
        };
        const decorate = () => {
            for (const stale of body.querySelectorAll('[data-dsha-row-actions]')) {
                if (stale.parentElement?.getAttribute('data-files-entry') !== 'file') stale.remove();
            }
            for (const row of body.querySelectorAll('li[data-files-entry="file"]')) {
                let controls = row.querySelector(':scope > [data-dsha-row-actions]');
                if (!controls) {
                    controls = document.createElement('span'); controls.dataset.dshaRowActions = '';
                    for (const action of ['downloads', 'more']) {
                        const button = document.createElement('button'); button.type = 'button'; button.dataset.dshaRowAction = action;
                        if (action === 'downloads') button.innerHTML = DOWNLOAD_SVG; else button.textContent = '⋯';
                        controls.appendChild(button);
                    }
                    row.appendChild(controls);
                }
                for (const button of controls.querySelectorAll('button')) {
                    button.disabled = latest.current.busy;
                    button.setAttribute('aria-label', button.dataset.dshaRowAction === 'downloads' ? fileCopy().download : fileCopy().more);
                }
            }
        };
        let raf = 0;
        const observer = new MutationObserver(() => { if (!raf) raf = requestAnimationFrame(() => { raf = 0; decorate(); }); });
        observer.observe(body, { subtree: true, childList: true, attributes: true, attributeFilter: ['data-files-entry', 'data-files-path'] });
        body.addEventListener('click', click); decorateRef.current = decorate; decorate();
        return () => { observer.disconnect(); if (raf) cancelAnimationFrame(raf); body.removeEventListener('click', click);
            for (const controls of body.querySelectorAll('[data-dsha-row-actions]')) controls.remove(); decorateRef.current = null; activeFileSheet?.(); };
    }, [sessionId]);
    (0, react_1.useEffect)(() => { decorateRef.current?.(); }, [busy, copy]);
    return (0, jsx_runtime_1.jsx)('button', { ref: anchor, type: 'button', 'data-dsha-downloads': true,
        onClick: () => window.DSHA.openDownloads().catch(error => showFallbackNotice(fileCopy().failed + error.message)), children: copy.records });
}
function installNativeFileActions() {
    if (!nativeFiles()) return () => {};
    const style = document.createElement('style'); style.dataset.dshaFileActions = '';
    style.textContent = `
[data-dsha-file-actions],[data-dsha-preview-actions],[data-dsha-row-actions]{display:inline-flex;align-items:center;gap:2px;flex:none;position:relative;z-index:2}
[data-dsha-file-actions] button,[data-dsha-preview-actions]>button,[data-dsha-row-actions] button,[data-dsha-downloads]{font:inherit;color:inherit;background:transparent;border:0;border-radius:8px;min-width:36px;min-height:36px;padding:6px;cursor:pointer}
[data-dsha-file-actions] button:hover,[data-dsha-row-actions] button:hover{background:var(--dsw-alias-interactive-bg-hover)}
[data-files-body] li[data-files-entry="file"]:has(>[data-dsha-row-actions]){display:flex;align-items:center}
[data-files-body] li[data-files-entry="file"]:has(>[data-dsha-row-actions])>button:first-child{flex:1;min-width:0}
[data-dsha-file-sheet]{position:fixed;inset:0;z-index:1600;background:#0007;display:flex;align-items:flex-end;justify-content:center;padding:16px;box-sizing:border-box}
[data-dsha-file-sheet]>div{background:var(--dsw-alias-bg-base,#fff);color:var(--dsw-alias-label-primary,#222);width:min(100%,440px);border-radius:18px;padding:12px;max-height:80dvh;overflow:auto;box-shadow:0 8px 40px #0005;padding-bottom:max(12px,env(safe-area-inset-bottom, 0px))}
[data-dsha-file-sheet]>div>div{padding:8px 12px;overflow-wrap:anywhere;font-weight:600}
[data-dsha-file-sheet] button{display:block;text-align:start;width:100%;font:inherit;color:inherit;background:transparent;border:0;border-radius:8px;padding:12px;min-height:44px}
[data-dsha-file-sheet] button:hover{background:var(--dsw-alias-interactive-bg-hover,#8882)}
[data-dsha-image-save]{position:fixed;bottom:calc(18px + env(safe-area-inset-bottom,0px));left:50%;transform:translateX(-50%);z-index:1510;border:0;border-radius:20px;padding:10px 20px;background:#fff;color:#222;font:inherit}
`;
    document.head.appendChild(style);
    const imageLifetime = new AbortController();
    let hold = null, consumed = null, imageFlight = false;
    const imageMenu = (url, name) => {
        if (typeof window.DSHA?.saveImage !== 'function' || typeof url !== 'string' || !url.startsWith('blob:' + location.origin + '/')) return false;
        showFileSheet(name || 'image', [{ label: fileCopy().gallery, run: async () => {
            if (imageFlight || imageLifetime.signal.aborted) return; imageFlight = true;
            try {
                const response = await fetch(url, { signal: imageLifetime.signal });
                if (!response.ok) throw new Error('Original image is unavailable');
                const mime = response.headers.get('content-type')?.split(';')[0] || '';
                const maximum = 5_000_000_000;
                // DSHA_IMAGE_BLOB_LIFETIME_V1: same-origin Blob URLs have an immutable,
                // known byte length. Keep their native backing storage instead of
                // copying every byte into JS chunk arrays and then another Blob.
                const header = response.headers.get('content-length');
                const length = header === null ? NaN : Number(header);
                if (!mime.startsWith('image/') || !Number.isSafeInteger(length) || length < 0 || length > maximum || !response.body) {
                    await response.body?.cancel();
                    throw new Error('Original image is unsupported or exceeds 5 GB');
                }
                const blob = await response.blob();
                imageLifetime.signal.throwIfAborted();
                if (blob.size !== length || blob.size > maximum || blob.type.split(';')[0] !== mime)
                    throw new Error('Original image metadata changed');
                const result = await window.DSHA.saveImage(new File([blob], name || 'image', { type: blob.type }));
                if (result?.status !== 'queued' || !result.taskId) throw new Error('The image task was not accepted');
                if (!imageLifetime.signal.aborted) showFallbackNotice(fileCopy().queued);
            } catch (error) { if (!imageLifetime.signal.aborted && error.name !== 'AbortError') showFallbackNotice(fileCopy().failed + error.message); }
            finally { imageFlight = false; }
        } }]); return true;
    };
    const cancelHold = () => { if (hold) clearTimeout(hold.timer); hold = null; };
    const face = {
        imageMenu,
        imageDown(event, url, name) {
            cancelHold(); consumed = null;
            if (event.pointerType === 'mouse' || event.isPrimary === false) return;
            const node = event.currentTarget, x = event.clientX, y = event.clientY;
            hold = { x, y, timer: setTimeout(() => { hold = null; if (node.isConnected && imageMenu(url, name)) consumed = { node, expires: performance.now() + 1000 }; }, 550) };
        },
        imageMove(event) { if (hold && Math.hypot(event.clientX - hold.x, event.clientY - hold.y) > 8) cancelHold(); },
        imageEnd: cancelHold,
        imageClick(event) { cancelHold(); const prior = consumed; consumed = null;
            if (prior?.node === event.currentTarget && performance.now() <= prior.expires) { event.preventDefault(); event.stopPropagation(); return true; } return false; },
    };
    window.DSHAFileTransfer = face;
    return () => { imageLifetime.abort(); cancelHold(); activeFileSheet?.(); style.remove(); if (window.DSHAFileTransfer === face) delete window.DSHAFileTransfer; };
}
