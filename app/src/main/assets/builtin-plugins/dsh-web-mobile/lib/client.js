window.__ModuleLoader__.load({ id: "dsh-web-mobile", factory: (require) => {
var __modules = {};
__modules["effects/gesture-guard.js"] = function (require, module, exports) {
"use strict";
/**
 * Gesture-consumption contract between the sidebar swipe layer and every
 * other document-level listener that would otherwise treat the release as a
 * plain tap (the overlay's drawer-close click/pointerup handlers and the
 * FAB / backdrop element listeners).
 *
 * Two independent signals, because they answer questions at different times:
 * - the axis-lock flag (`markStrokeLocked` at tryLock, i.e. during
 *   pointermove) tells a host handler running EARLIER in the same release
 *   event's capture phase that this pointerup is a swipe release, not a tap;
 * - the consume marks (`markGestureConsumed` at the gesture layer's own
 *   pointerup, after classification) cover the events that come AFTER the
 *   release.
 *
 * When the gesture layer classifies a stroke as a real swipe it calls
 * `markGestureConsumed(target, windowMs, upTo)`; any later listener that
 * calls `consumeIfGestured(event)` on the same stroke returns true and bails
 * out, so a swipe can never toggle the drawer twice or navigate a session
 * row — including the synthetic click the browser dispatches after the
 * stroke (its target is the release point or an ancestor of it, which is why
 * the mark walks the ancestor chain up to `upTo`).
 *
 * Non-gesture taps leave both signals clear, so the host's own close / tap /
 * nav-arm logic keeps working untouched.
 */
Object.defineProperty(exports, "__esModule", { value: true });
exports.markStrokeLocked = markStrokeLocked;
exports.clearStrokeLocked = clearStrokeLocked;
exports.isStrokeLocked = isStrokeLocked;
exports.markGestureConsumed = markGestureConsumed;
exports.consumeIfGestured = consumeIfGestured;
exports.isGestureConsumed = isGestureConsumed;
/** Marked targets with their expiry timestamp (monotonic performance.now). */
const consumed = new Map();
/**
 * True while the live stroke is axis-locked horizontal. Unlike the consume
 * marks (written at the gesture layer's OWN pointerup, after
 * classification), this flag is written at tryLock time — during
 * pointermove, strictly before any pointerup can fire — so a host handler
 * registered earlier in the capture phase can consult it on the same
 * release event without losing the race (audit S0/S1, 2026-08-27): while
 * the flag is up, the pointerup it is seeing is a swipe release, never a
 * tap, classified or not.
 */
let strokeLocked = false;
/** Flag the live stroke as axis-locked horizontal (called by tryLock). */
function markStrokeLocked() {
    strokeLocked = true;
}
/** Clear the axis-lock flag (called by reset and on a new pointer epoch). */
function clearStrokeLocked() {
    strokeLocked = false;
}
/** True while a stroke is axis-locked horizontal (host handlers yield). */
function isStrokeLocked() {
    return strokeLocked;
}
/**
 * True when the value looks like a DOM node that can carry an ancestor
 * chain. Feature-detected (no `instanceof Element`) so the guard stays
 * importable and testable in non-DOM environments (node:test).
 */
function isElementLike(value) {
    return (typeof value === 'object' &&
        value !== null &&
        'parentElement' in value &&
        value.parentElement !== undefined);
}
/**
 * Register that the stroke ending on `target` is a gesture. The mark covers
 * `target` itself and every ancestor up to and including `upTo` (when given
 * and present in the chain), so a follow-up synthetic click — whose target
 * is usually an ancestor of the release point — is reported as consumed
 * too. Multiple marks accumulate independently and expire after `windowMs`.
 */
function markGestureConsumed(target, windowMs, upTo) {
    const until = performance.now() + windowMs;
    if (!isElementLike(target)) {
        consumed.set(target, until);
        return;
    }
    let el = target;
    while (el !== null) {
        consumed.set(el, until);
        if (el === upTo)
            break;
        el = isElementLike(el.parentElement) ? el.parentElement : null;
    }
}
/**
 * True when the event belongs to a stroke already marked as a gesture.
 * Matches the event target itself or any of its ancestors. Stale marks are
 * dropped lazily.
 */
function consumeIfGestured(event) {
    const now = performance.now();
    const target = event.target;
    if (!isElementLike(target)) {
        for (const [t, until] of consumed) {
            if (until <= now)
                consumed.delete(t);
        }
        return false;
    }
    let el = target;
    while (el !== null) {
        const until = consumed.get(el);
        if (until !== undefined) {
            if (until <= now) {
                consumed.delete(el);
            }
            else {
                return true;
            }
        }
        el = isElementLike(el.parentElement) ? el.parentElement : null;
    }
    return false;
}
/**
 * Test-only probe: true when a gesture mark is still live for the given
 * element (tests/sidebar-swipe.test.ts asserts expiry with it; production
 * code never calls it — the swipe layer gates on its own consumedEl
 * instead). Returns false for stale marks.
 */
function isGestureConsumed(target) {
    const until = consumed.get(target);
    if (until === undefined)
        return false;
    if (until <= performance.now()) {
        consumed.delete(target);
        return false;
    }
    return true;
}
};
__modules["core/reconciler-core.js"] = function (require, module, exports) {
"use strict";
// reconciler-core.ts — DOM-free reconciler engine shared by every mobile DOM
// reconciler task. Deliberately has ZERO import statements:
//  - the custom client bundler cannot resolve `../` requires from
//    src/client/effects, and a file without imports has nothing to resolve;
//  - node:test imports it directly (Node's native type stripping) without a
//    DOM or DSH runtime, so registration / dirty routing / coalescing /
//    error-isolation can be covered by plain unit tests.
//
// The browser half (phone-chrome.ts) is a thin adapter: it owns the
// MutationObserver and requestAnimationFrame scheduler, feeds mutation keys
// into `note()`, and delegates task lifecycle to `register()` /
// `activate()` / `deactivate()`. `scopes` are opaque dirty keys — the core
// never interprets them (an attribute name like 'data-sidebar-collapsed' or
// the tree sentinel '*').
Object.defineProperty(exports, "__esModule", { value: true });
exports.createReconcilerCore = createReconcilerCore;
function createReconcilerCore(options) {
    const onError = options.onError ??
        ((taskName, error, phase) => {
            console.error(`[dsh-web-mobile] reconciler task ${taskName}${phase === 'dispose' ? ' dispose' : ''} failed`, error);
        });
    const registered = new Set();
    let active = null;
    let dirty = new Set();
    let forceAll = false;
    let pending = null;
    const runEnsure = (task) => {
        try {
            task.ensure();
        }
        catch (error) {
            onError(task.name, error, 'ensure');
        }
    };
    const runDispose = (task) => {
        try {
            task.dispose();
        }
        catch (error) {
            onError(task.name, error, 'dispose');
        }
    };
    const flush = () => {
        if (pending !== null) {
            pending();
            pending = null;
        }
        if (active === null) {
            dirty.clear();
            forceAll = false;
            return;
        }
        if (forceAll) {
            for (const task of active)
                runEnsure(task);
        }
        else if (dirty.size > 0) {
            for (const task of active) {
                const scopes = task.scopes;
                if (scopes === undefined || scopes.some((key) => dirty.has(key)))
                    runEnsure(task);
            }
        }
        dirty.clear();
        forceAll = false;
    };
    const schedule = () => {
        if (pending !== null)
            return;
        pending = options.requestFrame(() => {
            pending = null;
            flush();
        });
    };
    const register = (task) => {
        registered.add(task);
        if (active !== null) {
            active.add(task);
            runEnsure(task);
        }
        return () => {
            registered.delete(task);
            if (active !== null) {
                active.delete(task);
                runDispose(task);
            }
        };
    };
    const activate = () => {
        if (active !== null)
            return;
        active = new Set(registered);
        forceAll = true;
        flush();
    };
    const deactivate = () => {
        if (pending !== null) {
            pending();
            pending = null;
        }
        dirty.clear();
        forceAll = false;
        if (active !== null) {
            const snapshot = active;
            active = null;
            for (const task of snapshot)
                runDispose(task);
        }
    };
    return {
        get size() {
            return registered.size;
        },
        register,
        activate,
        deactivate,
        note: (keys) => {
            for (const key of keys)
                dirty.add(key);
            schedule();
        },
        flush,
    };
}
};
__modules["effects/aionui-compat.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.installAionuiCompat = installAionuiCompat;
exports.createPreviewCloseTask = createPreviewCloseTask;
exports.createSheetRiseTask = createSheetRiseTask;
const phone_chrome_ts_1 = require("./effects/phone-chrome.js");
/** dsh-web-ui 兼容：explorer / preview 列的显隐标记与升起动画（同域同机制，合并一处）。 */
function installAionuiCompat(ctx) {
    (0, phone_chrome_ts_1.installMobileEffect)(ctx, 'dsh-web-mobile: aionui explorer close marker', () => {
        const onChevronClick = (event) => {
            const target = event.target;
            if (target === null || !target.closest('.aionui-collapse-chevron'))
                return;
            (0, phone_chrome_ts_1.getFrame)()?.removeAttribute('data-aionui-explorer-open');
        };
        document.addEventListener('click', onChevronClick, true);
        return () => document.removeEventListener('click', onChevronClick, true);
    });
    (0, phone_chrome_ts_1.installMobileEffect)(ctx, 'dsh-web-mobile: preview sheet open marker', () => {
        const closePreview = () => {
            (0, phone_chrome_ts_1.getFrame)()?.removeAttribute('data-aionui-preview-open');
            (0, phone_chrome_ts_1.getFrame)()?.removeAttribute('data-mobile-preview-full');
        };
        // Temporarily spoof platform/userAgent/appVersion to Win32 desktop to
        // bypass the suite's Android check. The spoof is global, so it must be
        // restore-safe: one in-flight timer, re-entrancy guarded, and always
        // restored on effect disposal (narrow→wide / plugin reload).
        const DESKTOP_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';
        const DESKTOP_APPVERSION = '5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';
        let restoreTimer = null;
        let spoofed = false;
        let originalPlatform = navigator.platform;
        let originalUserAgent = navigator.userAgent;
        let originalAppVersion = navigator.appVersion;
        const restoreNavigator = () => {
            if (restoreTimer !== null) {
                window.clearTimeout(restoreTimer);
                restoreTimer = null;
            }
            if (!spoofed)
                return;
            spoofed = false;
            Object.defineProperty(navigator, 'platform', { value: originalPlatform, configurable: true });
            Object.defineProperty(navigator, 'userAgent', { value: originalUserAgent, configurable: true });
            Object.defineProperty(navigator, 'appVersion', { value: originalAppVersion, configurable: true });
        };
        const spoofDesktop = () => {
            if (!spoofed) {
                originalPlatform = navigator.platform;
                originalUserAgent = navigator.userAgent;
                originalAppVersion = navigator.appVersion;
                Object.defineProperty(navigator, 'platform', { value: 'Win32', configurable: true });
                Object.defineProperty(navigator, 'userAgent', { value: DESKTOP_UA, configurable: true });
                Object.defineProperty(navigator, 'appVersion', { value: DESKTOP_APPVERSION, configurable: true });
                spoofed = true;
            }
            if (restoreTimer !== null)
                window.clearTimeout(restoreTimer);
            restoreTimer = window.setTimeout(restoreNavigator, 1000);
        };
        const onTap = (event) => {
            const target = event.target;
            if (target === null)
                return;
            const row = target.closest('[data-aionui-explorer-col] [class*="_treeRow"]');
            if (row === null)
                return;
            if (row.querySelector('[class*="_treeArrow"]:not([class*="_treeArrowEmpty"])') !== null)
                return;
            spoofDesktop();
            (0, phone_chrome_ts_1.getFrame)()?.setAttribute('data-aionui-preview-open', '');
        };
        const onCollapse = (event) => {
            const target = event.target;
            if (target === null)
                return;
            if (target.closest('[data-aionui-preview-col] [class*="_panelCollapse"]') !== null) {
                closePreview();
            }
        };
        document.addEventListener('click', onTap, true);
        document.addEventListener('click', onCollapse, true);
        return () => {
            restoreNavigator();
            document.removeEventListener('click', onTap, true);
            document.removeEventListener('click', onCollapse, true);
        };
    });
}
function createPreviewCloseTask() {
    return {
        name: 'preview-close-sync',
        // Only acts when the suite hides the col via inline style. Deliberately
        // NOT scoped to data-aionui-preview-open: our own open marker is set
        // before the suite necessarily flips its inline visibility, so waking on
        // that marker would read the still-hidden style as a "suite close" and
        // immediately undo the file-row tap.
        scopes: ['style'],
        ensure: () => {
            const pv = document.querySelector('[data-aionui-preview-col]');
            if (pv === null)
                return;
            if (pv.style.visibility === 'hidden') {
                (0, phone_chrome_ts_1.getFrame)()?.removeAttribute('data-aionui-preview-open');
                (0, phone_chrome_ts_1.getFrame)()?.removeAttribute('data-mobile-preview-full');
            }
        },
        dispose: () => { },
    };
}
function createSheetRiseTask() {
    const cols = ['[data-aionui-explorer-col]', '[data-aionui-preview-col]'];
    const seen = new Map();
    const play = (el) => {
        el.animate([
            { opacity: 0, transform: 'translateY(28px)' },
            { opacity: 1, transform: 'none' },
        ], { duration: 280, easing: 'cubic-bezier(.16, 1, .3, 1)', fill: 'backwards' });
    };
    return {
        name: 'sheet-rise-replay',
        // The flush runs on the next frame, by which time React has rendered the
        // opened col, so the frame markers / inline style / class changes are
        // reliable triggers — no '*'.
        scopes: [
            'style',
            'class',
            'data-aionui-explorer-open',
            'data-aionui-preview-open',
            'data-mobile-preview-full',
        ],
        ensure: () => {
            for (const sel of cols) {
                const el = document.querySelector(sel);
                if (el === null)
                    continue;
                const visible = getComputedStyle(el).visibility === 'visible';
                const prev = seen.get(sel) ?? false;
                if (visible && !prev)
                    play(el);
                seen.set(sel, visible);
            }
        },
        dispose: () => {
            seen.clear();
        },
    };
}
};
__modules["effects/stats-line.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.statsAnchorAlive = statsAnchorAlive;
exports.createStatsLineTask = createStatsLineTask;
// The official conversation status row (turns / steps / LLM time / TTFT /
// cache) has a hashed class, so the stylesheet cannot target it directly.
// Mark the exact row on narrow screens by text: a [class*=_root] that
// carries the metrics text and no composer input (textarea or the
// data-composer-input Lexical node; the composer card also ends in
// _root and can mention turns in its model line). The CSS then lays the
// marked row out as ONE horizontally scrolling line with every metric
// reachable.
// Fast-path predicate: is the previously marked strip still alive in place?
// Re-verifying one anchor per flush is O(1); the full-tree hunt in mark()
// grows with the conversation and runs on every streaming token.
function statsAnchorAlive(el) {
    if (el === null || !el.isConnected)
        return false;
    if (el.closest('[data-phase]') === null)
        return false;
    return el.closest('[class*="_composerStack"]') !== null;
}
function createStatsLineTask() {
    // The composer root renders the TPS readout ("TPS 89.4 tok/s") as its
    // own row BELOW the status strip; fold it into the strip so every
    // metric scrolls together. The suite re-renders its own tree, so this
    // must be idempotent and re-run on every mutation. Where the readout
    // came from is recorded so disposal can put it back — on a
    // narrow→wide transition the desktop layout must be the official one
    // again, and `[data-mobile-nav="stats"]` is not covered by the
    // desktop hide rules.
    let tpsOrigin = null;
    const moveTps = (stats) => {
        if ([...stats.children].some((c) => /^TPS\s+\d/.test((c.textContent ?? '').trim())))
            return;
        const stack = stats.closest('[class*="_composerStack"]');
        if (stack === null)
            return;
        for (const el of stack.querySelectorAll('div')) {
            const text = (el.textContent ?? '').trim();
            if (!/^TPS\s+\d/.test(text))
                continue;
            if (el.children.length > 0)
                continue;
            // The composer stack can be rebuilt by React between mutations:
            // refresh the origin every time we actually move the TPS readout, so
            // disposal returns it where it currently belongs.
            if (el.parentElement !== null) {
                tpsOrigin = { parent: el.parentElement, next: el.nextSibling };
            }
            stats.appendChild(el);
            return;
        }
    };
    const mark = () => {
        // Fast path: the marked strip usually survives React rebuilds between
        // tokens; re-verifying the anchor is O(1) while the full-tree hunt below
        // grows with the conversation. moveTps still re-runs so a rebuilt TPS
        // readout is re-folded.
        const anchor = document.querySelector('[data-mobile-nav="stats"]');
        if (anchor !== null && statsAnchorAlive(anchor)) {
            moveTps(anchor);
            return;
        }
        // Stale marker on a node that left the composer stack/phase context:
        // drop it so the slow path can re-anchor cleanly.
        anchor?.removeAttribute('data-mobile-nav');
        for (const root of document.querySelectorAll('[data-phase] [class*="_root"]')) {
            // The status row lives inside the composer stack; message-area
            // blocks can also mention turns/steps and must be skipped.
            if (root.closest('[class*="_composerStack"]') === null)
                continue;
            // The todo plan strip also lives in the composer stack and its root
            // ends in _root. Its items may legitimately contain "步"/"steps" in
            // their text, so never mistake it (or any interactive dock panel)
            // for the stats strip.
            if (root.matches('[data-testid="todo-panel"]'))
                continue;
            if (root.querySelector('button') !== null)
                continue;
            const text = root.textContent ?? '';
            if (!/(turns|steps|\bLLM\b|轮|步)/.test(text))
                continue;
            // Composer card must never be mistaken for the status strip; exclude
            // its input region across both composer DOMs (textarea / Lexical
            // contentEditable marked data-composer-input).
            if (root.querySelector('textarea, [data-composer-input]') !== null)
                continue;
            root.setAttribute('data-mobile-nav', 'stats');
            moveTps(root);
            return;
        }
    };
    // Scope decision: the TPS readout updates are childList/characterData text
    // mutations inside the composer stack, so this task can only wake on the
    // tree key. A subtree-scoped observer would need one observer per
    // container, which the single full-tree observer design intentionally
    // avoids; the expensive composer-stack scan stays the cost of re-anchoring
    // markers that React rebuilds every token.
    return {
        name: 'stats-line',
        scopes: ['*'],
        ensure: mark,
        dispose: () => {
            // Hand the official layout back: return the TPS readout to its own
            // row, then drop the marker that drives the one-line strip.
            if (tpsOrigin !== null && tpsOrigin.parent.isConnected) {
                // Find the TPS readout only inside the marked stats strip we moved
                // it into — a global text search could pick up a different element.
                for (const stats of document.querySelectorAll('[data-mobile-nav="stats"]')) {
                    const tps = [...stats.querySelectorAll('div')].find((el) => el.children.length === 0 && /^TPS\s+\d/.test((el.textContent ?? '').trim()));
                    if (tps !== undefined) {
                        tpsOrigin.parent.insertBefore(tps, tpsOrigin.next);
                        break;
                    }
                }
            }
            for (const el of document.querySelectorAll('[data-mobile-nav="stats"]')) {
                el.removeAttribute('data-mobile-nav');
            }
            tpsOrigin = null;
        },
    };
}
};
__modules["effects/preview-fullscreen.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.createPreviewFullscreenTask = createPreviewFullscreenTask;
const phone_chrome_ts_1 = require("./effects/phone-chrome.js");
function createPreviewFullscreenTask(t) {
    let button = null;
    const syncLabel = (target) => {
        const full = (0, phone_chrome_ts_1.getFrame)()?.hasAttribute('data-mobile-preview-full') ?? false;
        const label = t(full ? 'previewExitFullscreen' : 'previewFullscreen');
        if (target.getAttribute('aria-label') === label)
            return;
        target.setAttribute('aria-label', label);
        target.title = label;
    };
    const onClick = () => {
        (0, phone_chrome_ts_1.getFrame)()?.toggleAttribute('data-mobile-preview-full');
        if (button !== null)
            syncLabel(button);
    };
    return {
        name: 'preview-fullscreen-toggle',
        scopes: ['data-aionui-preview-open', 'data-mobile-preview-full'],
        ensure: () => {
            const col = document.querySelector('[data-aionui-preview-col]');
            if (col === null)
                return;
            if (button === null) {
                button = document.createElement('button');
                button.type = 'button';
                button.dataset.mobileNav = 'preview-full-toggle';
                button.innerHTML = [
                    '<svg class="dsh-web-mobile-full-in" viewBox="0 0 16 16" fill="none" aria-hidden="true">',
                    '<path d="M6 2H2v4M10 2h4v4M6 14H2v-4M10 14h4-4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>',
                    '</svg>',
                    '<svg class="dsh-web-mobile-full-out" viewBox="0 0 16 16" fill="none" aria-hidden="true">',
                    '<path d="M6 2v4H2M10 2v4h4M6 14v-4H2M10 14v-4h4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>',
                    '</svg>',
                ].join('');
                button.addEventListener('click', onClick);
            }
            syncLabel(button);
            if (button.parentElement !== col)
                col.appendChild(button);
        },
        dispose: () => {
            button?.remove();
            button = null;
        },
    };
}
};
__modules["effects/git-chip-reparent.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.createGitChipTask = createGitChipTask;
function createGitChipTask() {
    return {
        name: 'git-chip-reparent',
        scopes: ['*'],
        ensure: () => {
            const chip = document.querySelector('[data-slot="conversation.input.dock"] [data-gitgraph-chip-anchor]');
            if (chip === null)
                return;
            const card = document.querySelector('[data-composer-input], textarea')?.closest('[class*="_card"]');
            if (card == null)
                return;
            if (chip.parentElement !== card)
                card.insertBefore(chip, card.firstChild);
        },
        dispose: () => {
            const chip = document.querySelector('[data-slot="conversation.input.dock"] [data-gitgraph-chip-anchor]');
            const dock = document.querySelector('[data-slot="conversation.input.dock"]');
            if (chip !== null && dock !== null && chip.parentElement !== dock)
                dock.appendChild(chip);
        },
    };
}
};
__modules["effects/settings-toolbar-reparent.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.createSettingsToolbarTask = createSettingsToolbarTask;
function createSettingsToolbarTask() {
    let origin = null;
    return {
        name: 'settings-toolbar-reparent',
        scopes: ['*'],
        ensure: () => {
            const dialog = document.querySelector('[aria-modal="true"]');
            if (dialog === null)
                return;
            const nav = dialog.querySelector(':scope > [class*="_nav"]');
            const header = dialog.querySelector('[class*="_header"]:not([class*="_headerActions"])');
            if (nav === null || header === null)
                return;
            if (header.parentElement === nav)
                return;
            // The dialog DOM can be rebuilt by React between mutations: refresh
            // the origin every time we actually move the header, so disposal
            // restores it where it currently belongs, not where it was first seen.
            if (header.parentElement !== null) {
                origin = { parent: header.parentElement, next: header.nextSibling };
            }
            nav.appendChild(header);
        },
        dispose: () => {
            if (origin === null)
                return;
            const header = document.querySelector('[aria-modal="true"] [class*="_header"]:not([class*="_headerActions"])');
            if (header !== null && origin.parent.isConnected) {
                origin.parent.insertBefore(header, origin.next);
            }
            origin = null;
        },
    };
}
};
__modules["effects/overlay-backdrop-fab.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.fadeOverlayOut = fadeOverlayOut;
exports.createOverlayTask = createOverlayTask;
const phone_chrome_ts_1 = require("./effects/phone-chrome.js");
/** Fade the CURRENT backdrop out (pointer-events off + opacity 0). Called by
 * the gesture layer when a close commit starts animating, so the dimming
 * fades WITH the drawer's slide-out instead of vanishing after it. The
 * element itself is removed later by the task's normal remove path (the
 * marker flip schedules it). */
function fadeOverlayOut() {
    fadeHook?.();
}
let fadeHook = null;
/** Removal is deferred by one fade so the dimming eases out instead of
 * snapping (user request 2026-08-29 「背景黑色遮罩进行渐变动画」; the fade-IN
 * already existed as a mount animation). */
const BACKDROP_FADE_MS = 200;
function createOverlayTask(t, toggleSidebar) {
    let backdrop = null;
    let fab = null;
    let backdropRemoveTimer = null;
    /** True while the backdrop carries our inline faded state. Guards the
     * restore branch: while a late close commit is animating, the marker is
     * STILL open, so a plain open-branch restore would undo the pre-fade. */
    let faded = false;
    const drawerOpen = () => {
        const frame = (0, phone_chrome_ts_1.getFrame)();
        return frame !== null && !frame.hasAttribute('data-sidebar-collapsed');
    };
    const heroPhase = () => document.querySelector('[data-phase="active"]') === null;
    fadeHook = () => {
        if (backdrop === null)
            return;
        faded = true;
        backdrop.style.pointerEvents = 'none';
        backdrop.style.opacity = '0';
    };
    return {
        name: 'overlay-backdrop-fab',
        scopes: ['*', 'data-sidebar-collapsed', 'data-phase'],
        ensure: () => {
            const frame = (0, phone_chrome_ts_1.getFrame)();
            if (frame === null)
                return;
            if (drawerOpen()) {
                if (backdrop === null) {
                    backdrop = document.createElement('div');
                    backdrop.dataset.mobileNav = 'backdrop';
                    backdrop.setAttribute('role', 'button');
                    backdrop.setAttribute('aria-label', t('backdrop'));
                    backdrop.addEventListener('click', toggleSidebar);
                    frame.appendChild(backdrop);
                    faded = false;
                }
                else if (faded && backdropRemoveTimer !== null) {
                    // Quick close→reopen inside the fade window: cancel the pending
                    // removal and let the CSS transition ease the dimming back in.
                    window.clearTimeout(backdropRemoveTimer);
                    backdropRemoveTimer = null;
                    faded = false;
                    backdrop.style.removeProperty('pointer-events');
                    backdrop.style.removeProperty('opacity');
                }
            }
            else if (backdrop !== null) {
                backdrop.style.pointerEvents = 'none';
                backdrop.style.opacity = '0';
                faded = true;
                if (backdropRemoveTimer === null) {
                    backdropRemoveTimer = window.setTimeout(() => {
                        backdropRemoveTimer = null;
                        backdrop?.remove();
                        backdrop = null;
                    }, BACKDROP_FADE_MS + 60);
                }
            }
            if (heroPhase() && !drawerOpen() && fab === null) {
                fab = document.createElement('button');
                fab.type = 'button';
                fab.dataset.mobileNav = 'fab';
                fab.setAttribute('aria-label', t('open'));
                fab.title = t('open');
                fab.innerHTML =
                    '<svg viewBox="0 0 16 16" fill="none" aria-hidden="true" width="18" height="18">' +
                        '<path fill-rule="evenodd" clip-rule="evenodd" d="M9.67272 0.522841C10.8339 0.522841 11.76 0.522714 12.4963 0.602493C13.2453 0.683657 13.8789 0.854248 14.4264 1.25197C14.7504 1.48739 15.0355 1.77247 15.2709 2.0965C15.6686 2.64394 15.8392 3.27758 15.9204 4.02655C16.0002 4.7629 16 5.68895 16 6.85014V9.14986C16 10.3111 16.0002 11.2371 15.9204 11.9735C15.8392 12.7224 15.6686 13.3561 15.2709 13.9035C15.0355 14.2275 14.7504 14.5126 14.4264 14.748C13.8789 15.1458 13.2453 15.3163 12.4963 15.3975C11.76 15.4773 10.8339 15.4772 9.67272 15.4772H6.3273C5.16611 15.4772 4.24006 15.4773 3.50371 15.3975C2.75474 15.3163 2.1211 15.1458 1.57366 14.748C1.24963 14.5126 0.964549 14.2275 0.729131 13.9035C0.331407 13.3561 0.160817 12.7224 0.0796529 11.9735C-0.000126137 11.2371 1.25338e-09 10.3111 1.25338e-09 9.14986V6.85014C1.25329e-09 5.68895 -0.000126137 4.7629 0.0796529 4.02655C0.160817 3.27758 0.331407 2.64394 0.729131 2.0965C0.964549 1.77247 1.24963 1.48739 1.57366 1.25197C2.1211 0.854248 2.75474 0.683657 3.50371 0.602493C4.24006 0.522714 5.16611 0.522841 6.3273 0.522841H9.67272ZM5.54303 1.88715V14.1118C5.78636 14.1128 6.04709 14.1169 6.3273 14.1169H9.67272C10.8639 14.1169 11.7032 14.1164 12.3493 14.0465C12.9824 13.9779 13.3497 13.8494 13.6268 13.6482C13.8354 13.4966 14.0195 13.3125 14.1711 13.1039C14.3723 12.8268 14.5007 12.4595 14.5693 11.8264C14.6393 11.1803 14.6398 10.341 14.6398 9.14986V6.85014C14.6398 5.65896 14.6393 4.81967 14.5693 4.1736C14.5007 3.54048 14.3723 3.17318 14.1711 2.89609C14.0195 2.68747 13.8354 2.50337 13.6268 2.35179C13.3497 2.1506 12.9824 2.02212 12.3493 1.95353C11.7032 1.88358 10.8639 1.88307 9.67272 1.88307H6.3273C6.04709 1.88307 5.78636 1.8862 5.54303 1.88715ZM4.1828 1.91166C3.99125 1.9216 3.8148 1.93577 3.65076 1.95353C3.01764 2.02212 2.65034 2.1506 2.37325 2.35179C2.16463 2.50337 1.98052 2.68747 1.82895 2.89609C1.62776 3.17318 1.49928 3.54048 1.43069 4.1736C1.36074 4.81967 1.36023 5.65896 1.36023 6.85014V9.14986C1.36023 10.341 1.36074 11.1803 1.43069 11.8264C1.49928 12.4595 1.62776 12.8268 1.82895 13.1039C1.98052 13.3125 2.16463 13.4966 2.37325 13.6482C2.65034 13.8494 3.01764 13.9779 3.65076 14.0465C4.29683 14.1164 5.13612 14.1169 6.3273 14.1169H9.67272C10.8639 14.1169 11.7032 14.1164 12.3493 14.0465C12.9824 13.9779 13.3497 13.8494 13.6268 13.6482C13.8354 13.4966 14.0195 13.3125 14.1711 13.1039C14.3723 12.8268 14.5007 12.4595 14.5693 11.8264C14.6393 11.1803 14.6398 10.341 14.6398 9.14986V6.85014C14.6398 5.65896 14.6393 4.81967 14.5693 4.1736C14.5007 3.54048 14.3723 3.17318 14.1711 2.89609C14.0195 2.68747 13.8354 2.50337 13.6268 2.35179C13.3497 2.1506 12.9824 2.02212 12.3493 1.95353C11.7032 1.88358 10.8639 1.88307 9.67272 1.88307H6.3273C5.13612 1.88307 4.29683 1.88358 3.65076 1.95353C3.47672 1.97129 3.30027 1.98546 3.10872 1.9954L4.1828 1.91166Z" fill="currentColor"/>' +
                        '</svg>';
                fab.addEventListener('click', toggleSidebar);
                frame.appendChild(fab);
            }
            else if ((!heroPhase() || drawerOpen()) && fab !== null) {
                fab.remove();
                fab = null;
            }
        },
        dispose: () => {
            if (backdropRemoveTimer !== null) {
                window.clearTimeout(backdropRemoveTimer);
                backdropRemoveTimer = null;
            }
            fadeHook = null;
            backdrop?.remove();
            backdrop = null;
            fab?.remove();
            fab = null;
        },
    };
}
};
__modules["effects/file-viewer-compat.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.createFileViewerMarkerTask = createFileViewerMarkerTask;
const phone_chrome_ts_1 = require("./effects/phone-chrome.js");
/**
 * dsh-file-viewer open marker. The plugin renders a
 * <section class="dsfv-panel" data-conversation-composer-overlay> as a
 * conversation.view tab next to "对话"/"轨迹" (its own idempotent <style>
 * uses the stable `dsfv-` prefix — no CSS Modules). Detection keys on the
 * panel's own `.dsfv-panel` class, NOT on `data-conversation-composer-overlay`:
 * that attribute is the host conversation.view system's generic overlay flag
 * shared by every view tab (the built-in trajectory tab sets it too), so it
 * would false-positive the marker whenever any overlay is open. The marker
 * only reflects the file-viewer panel existing and serves the compat.css
 * mobile layout rules. Gesture takeover is a separate signal —
 * sidebar-swipe.ts reads the generic overlay attribute directly.
 * Idempotent like the other frame markers; dispose clears it when the tab
 * unmounts or on deactivation.
 * Scoped to the mobile branch because the reconciler only runs when the
 * mobile breakpoint is active (registerReconcileTasks installs it there).
 * (Port of community fork fix 2ff7976, re-scoped per design
 * 2026-09-06-conversation-overlay-takeover-design.md §4.1.)
 */
function createFileViewerMarkerTask() {
    return {
        name: 'file-viewer-open-marker',
        scopes: ['*'],
        ensure: () => {
            const frame = (0, phone_chrome_ts_1.getFrame)();
            if (frame === null)
                return;
            const active = document.querySelector('.dsfv-panel') !== null;
            if (active) {
                frame.setAttribute('data-file-viewer-open', '');
            }
            else if (frame.hasAttribute('data-file-viewer-open')) {
                frame.removeAttribute('data-file-viewer-open');
            }
        },
        dispose: () => {
            (0, phone_chrome_ts_1.getFrame)()?.removeAttribute('data-file-viewer-open');
        },
    };
}
};
__modules["effects/phone-chrome.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.DESKTOP_QUERY = exports.MOBILE_QUERY = void 0;
exports.installMobileEffect = installMobileEffect;
exports.findFrame = findFrame;
exports.getFrame = getFrame;
exports.installFrameController = installFrameController;
exports.installReconciler = installReconciler;
exports.addReconcilerTask = addReconcilerTask;
exports.detectIosWebKit = detectIosWebKit;
exports.installPhoneChrome = installPhoneChrome;
exports.installOverlayInteractions = installOverlayInteractions;
exports.registerReconcileTasks = registerReconcileTasks;
const gesture_guard_ts_1 = require("./effects/gesture-guard.js");
const reconciler_core_ts_1 = require("./core/reconciler-core.js");
const aionui_compat_ts_1 = require("./effects/aionui-compat.js");
const stats_line_ts_1 = require("./effects/stats-line.js");
const preview_fullscreen_ts_1 = require("./effects/preview-fullscreen.js");
const git_chip_reparent_ts_1 = require("./effects/git-chip-reparent.js");
const settings_toolbar_reparent_ts_1 = require("./effects/settings-toolbar-reparent.js");
const overlay_backdrop_fab_ts_1 = require("./effects/overlay-backdrop-fab.js");
const file_viewer_compat_ts_1 = require("./effects/file-viewer-compat.js");
// The custom client bundler cannot resolve `../` requires from src/client/effects,
// so this mirrors the namespace id from src/client/locales.ts. Keep in sync.
const NS = 'mobileNav';
/** Same width bound as the shell's SIDEBAR_AUTO_COLLAPSE (viewport < 1024),
 *  ANDed with a touch-primary pointer guard. Width alone cannot tell a phone
 *  from a desktop window: split views and OS display scaling push a PC's CSS
 *  viewport below 1024px too, and the whole mobile shell (drawer, header
 *  Files button, gestures) would mount there. (pointer: coarse) keeps the
 *  adaptation on touch-primary devices — phones, tablets, DSHA — while any
 *  mouse-driven window stays desktop at every width. Headless probes have no
 *  pointer at all: arm the mobile branch with Emulation.setTouchEmulation-
 *  Enabled before asserting mobile UI. */
exports.MOBILE_QUERY = '(max-width: 1023px) and (pointer: coarse)';
/** Informational wide-bound for the debug badge. The authoritative desktop
 *  guard is the CSS hide block in misc.css.ts — the exact complement of
 *  MOBILE_QUERY — because slot-rendered controls exist at every width. */
exports.DESKTOP_QUERY = '(min-width: 1024px)';
/**
 * Re-arm a mobile-only DOM effect on every width change. Replaces the
 * repeated matchMedia + change-listener scaffold so all breakpoint strings
 * live in one place.
 */
function installMobileEffect(ctx, label, install) {
    ctx.effect(() => {
        const narrow = window.matchMedia(exports.MOBILE_QUERY);
        let cleanup;
        const arm = () => {
            cleanup?.();
            cleanup = narrow.matches ? install(narrow) : undefined;
        };
        arm();
        narrow.addEventListener('change', arm);
        return () => {
            narrow.removeEventListener('change', arm);
            cleanup?.();
        };
    }, label);
}
/** The AppFrame element: direct parent of the shell overlay layer. */
function findFrame() {
    return document.querySelector('[data-shell-overlay]')?.parentElement ?? null;
}
/** Resolve the plugin-owned frame marker, falling back to the raw shell frame. */
function getFrame() {
    return document.querySelector('[data-mobile-nav="frame"]') ?? findFrame();
}
/**
 * Frame marker controller: owns `data-mobile-nav="frame"` and every plugin
 * marker that can survive on the shell-owned frame. Installed once at apply
 * time so effects no longer each need to find/set/clear the frame. Returns a
 * disposer that unregisters the task and resets the installed flag, so a
 * same-environment plugin reload can rebuild the reconciler from scratch.
 */
function installFrameController() {
    if (frameControllerInstalled)
        return () => { };
    frameControllerInstalled = true;
    let frame = null;
    const removeTask = addReconcilerTask({
        name: 'frame-marker',
        scopes: ['*'],
        ensure: () => {
            frame = findFrame();
            if (frame !== null && !frame.hasAttribute('data-mobile-nav')) {
                frame.setAttribute('data-mobile-nav', 'frame');
            }
        },
        dispose: () => {
            if (frame !== null) {
                frame.removeAttribute('data-mobile-nav');
                frame.removeAttribute('data-mobile-preview-full');
                frame.removeAttribute('data-aionui-explorer-open');
                frame.removeAttribute('data-aionui-preview-open');
            }
            frame = null;
        },
    });
    return () => {
        removeTask();
        frameControllerInstalled = false;
    };
}
let frameControllerInstalled = false;
let reconcileTasksRegistered = false;
let reconcilerInstalled = false;
// The DOM-free core owns the task registry, dirty-key routing, and coalesced
// flush scheduling; this module is the thin browser adapter that feeds it
// MutationObserver records and drives its lifecycle from the mobile effect.
const core = (0, reconciler_core_ts_1.createReconcilerCore)({
    requestFrame: (flush) => {
        let id = 0;
        const run = () => {
            id = 0;
            flush();
        };
        id = requestAnimationFrame(run);
        return () => {
            if (id !== 0)
                cancelAnimationFrame(id);
        };
    },
});
/**
 * One full-tree MutationObserver for every mobile DOM reconciler. Tasks can be
 * registered from React or plain effects; they only run while the mobile
 * breakpoint is active and are re-armed automatically on width changes.
 */
function installReconciler(ctx) {
    if (reconcilerInstalled)
        return () => { };
    reconcilerInstalled = true;
    installMobileEffect(ctx, 'dsh-web-mobile: DOM reconciler', () => {
        // Coalesce every mutation burst (typing, animations, per-token TPS
        // re-renders) into one dirty-key pass per animation frame. Each task
        // declares scopes so only intersecting tasks run on a given flush.
        const observer = new MutationObserver((records) => {
            const keys = new Set();
            for (const record of records) {
                keys.add(record.type === 'attributes' && record.attributeName !== null ? record.attributeName : '*');
            }
            core.note(keys);
        });
        observer.observe(document.documentElement, {
            childList: true,
            subtree: true,
            attributes: true,
            attributeFilter: [
                'style',
                'class',
                'data-phase',
                'data-sidebar-collapsed',
                'data-aionui-explorer-open',
                'data-aionui-preview-open',
                'data-mobile-preview-full',
            ],
        });
        core.activate();
        return () => {
            observer.disconnect();
            core.deactivate();
        };
    });
    return () => {
        reconcilerInstalled = false;
    };
}
/** Register a reconciler task. The returned disposer removes it immediately. */
function addReconcilerTask(task) {
    return core.register(task);
}
/**
 * Whether the page runs on iOS / iPadOS WebKit, where focusing a text field
 * whose computed font-size is below 16px zooms the whole visual viewport
 * (#45). Every other engine ignores field font-size, so the 16px floor in
 * misc.css.ts is gated on this marker instead of applying to every phone —
 * Android would only get bigger search boxes for no benefit.
 *
 * Pure and injectable so the decision table is unit-testable:
 * - The feature probe is the reliable signal: `font: -apple-system-body` is
 *   Safari-only and `-webkit-touch-callout` is an iOS property, so the pair
 *   is true on iOS WebKit (including Chrome / Edge / Opera on iOS, which are
 *   WebKit and zoom identically) and false on Chromium (measured) and on
 *   macOS Safari.
 * - The UA fallback covers engines whose CSS.supports is missing or which
 *   parse the probe differently: iPhone / iPad / iPod UAs, plus iPadOS 13+
 *   which reports a Macintosh UA and is told apart by its touch points.
 */
function detectIosWebKit(nav, supports) {
    if (supports !== null) {
        try {
            if (supports('(font: -apple-system-body) and (-webkit-touch-callout: none)'))
                return true;
        }
        catch {
            // A UA that rejects the condition string falls through to the UA test.
        }
    }
    const ua = nav.userAgent;
    if (/iP(hone|ad|od)/.test(ua))
        return true;
    return /Macintosh/.test(ua) && nav.maxTouchPoints > 1;
}
/** Marker the iOS-only zoom-guard CSS is scoped to (html element). */
const IOS_MARKER = 'data-mobile-nav-ios';
/**
 * Viewport content the plugin owns while the mobile branch is armed.
 * Deliberately zoom-free: iOS 10+ ignores maximum-scale/user-scalable for
 * user pinch but other engines honor them, so writing them would only take
 * zoom away from Android/DSHA; the iOS focus-zoom fix is the >=16px field
 * floor (data-mobile-nav-ios), not a zoom ban (#45).
 */
const VIEWPORT_CONTENT = 'width=device-width, initial-scale=1, viewport-fit=cover';
const findViewportMeta = () => document.querySelector('meta[name="viewport"]');
/**
 * Phone chrome: KEEP the system status bar (no fullscreen) and make it
 * blend into the page. On narrow screens:
 * - The viewport meta is OWNED by the plugin while armed:
 *   width=device-width, initial-scale=1, viewport-fit=cover, re-asserted on
 *   every host rewrite, node replacement, or late injection, so
 *   env(safe-area-inset-top) stays the real status-bar / notch height
 *   instead of silently going stale when the host touches the meta. No zoom
 *   tokens here: iOS 10+ ignores them for user pinch but other engines
 *   honor them, and the focus-zoom fix is the >=16px field floor (#45), not
 *   a zoom ban. Dispose restores the host's own content as observed at arm
 *   time.
 * - A theme-color meta tracks the shell background (the official theme is
 *   toggled by body[data-ds-dark-theme], which flips --dsw-alias-bg-base):
 *   Android then paints the status bar / URL bar with the page's own base
 *   color, so the status bar reads as part of the UI instead of a foreign
 *   strip. The drawer paints the same strip on iOS / notch displays.
 * - documentElement carries data-mobile-nav-ios on iOS WebKit so the
 *   stylesheet can hold every text field at >=16px and Safari never
 *   focus-zooms the viewport (#45). Double-tap zoom is off through
 *   touch-action; pinch zoom stays available on purpose — it is the only way
 *   back out of a zoom the browser applied on its own.
 */
function installPhoneChrome(ctx) {
    installMobileEffect(ctx, 'dsh-web-mobile: status bar theme + viewport + zoom guard', () => {
        const themeMeta = document.createElement('meta');
        themeMeta.name = 'theme-color';
        const bodyBg = () => getComputedStyle(document.body).backgroundColor;
        const root = document.documentElement;
        let originalViewport = null;
        let observedMeta = null;
        // Our own write retriggers the observers; the equality check in
        // assertViewport turns that pass into a no-op. `applying` guards the
        // write itself against re-entrant observer callbacks on exotic engines.
        let applying = false;
        // The plugin owns the meta while armed, so a host rewrite, a node
        // replacement, or a meta that arrives after this effect arms cannot
        // silently drop viewport-fit=cover and shift every surface under the
        // notch. Both observers funnel into the same assertion;
        // attachMetaObserver re-binds to the current node so a replacement keeps
        // being watched.
        const assertViewport = () => {
            const viewport = findViewportMeta();
            if (viewport === null)
                return;
            if (originalViewport === null)
                originalViewport = viewport.content;
            if (applying || viewport.content === VIEWPORT_CONTENT)
                return;
            applying = true;
            viewport.content = VIEWPORT_CONTENT;
            applying = false;
        };
        const metaObserver = new MutationObserver(assertViewport);
        const attachMetaObserver = () => {
            const viewport = findViewportMeta();
            if (viewport === observedMeta)
                return;
            if (observedMeta !== null)
                metaObserver.disconnect();
            observedMeta = viewport;
            if (viewport !== null) {
                metaObserver.observe(viewport, { attributes: true, attributeFilter: ['content'] });
            }
        };
        const headObserver = new MutationObserver(() => {
            attachMetaObserver();
            assertViewport();
        });
        headObserver.observe(document.head, { childList: true });
        attachMetaObserver();
        assertViewport();
        const observer = new MutationObserver(() => {
            themeMeta.content = bodyBg();
        });
        observer.observe(document.body, { attributes: true, attributeFilter: ['data-ds-dark-theme'] });
        const cssSupports = typeof CSS !== 'undefined' && typeof CSS.supports === 'function'
            ? (condition) => CSS.supports(condition)
            : null;
        if (detectIosWebKit(navigator, cssSupports))
            root.setAttribute(IOS_MARKER, '');
        themeMeta.content = bodyBg();
        if (themeMeta.parentElement === null)
            document.head.appendChild(themeMeta);
        return () => {
            metaObserver.disconnect();
            headObserver.disconnect();
            observer.disconnect();
            const viewport = findViewportMeta();
            // Hand the meta back only if it still holds OUR content; a host value
            // written while we were armed wins on dispose.
            if (viewport !== null && originalViewport !== null && viewport.content === VIEWPORT_CONTENT) {
                viewport.content = originalViewport;
            }
            themeMeta.remove();
            root.removeAttribute(IOS_MARKER);
        };
    });
}
/**
 * Drawer close interactions that are plain event listeners, not DOM
 * reconciliation:
 * - Escape closes the drawer (yielding to any open modal dialog, which owns
 *   its own Escape handling).
 * - Tapping a navigation target inside the drawer (session row, task board /
 *   ssh takeover entries, search results) closes the drawer so the content
 *   it opened gets the whole screen. Session-row action buttons (kebab) are
 *   excluded — they open a menu that must survive the tap.
 */
function installOverlayInteractions(ctx) {
    installMobileEffect(ctx, 'dsh-web-mobile: drawer close (Escape + navigate)', () => {
        const toggleSidebar = () => ctx.layout.toggleSidebar();
        const drawerOpen = () => {
            const frame = getFrame();
            return frame !== null && !frame.hasAttribute('data-sidebar-collapsed');
        };
        const onKeyDown = (event) => {
            if (event.key !== 'Escape')
                return;
            if (document.querySelector('[aria-modal="true"]') !== null)
                return;
            if (drawerOpen())
                toggleSidebar();
        };
        // Capture phase: run before the shell or a plugin processes the click,
        // so takeover panels never render under the open drawer.
        const drawerRoot = () => document.querySelector('[data-mobile-nav="frame"] > :first-child');
        const shouldCloseOnTapInsideDrawer = (target) => {
            if (document.querySelector('[aria-modal="true"]') !== null)
                return false;
            if (!drawerOpen())
                return false;
            if (!(target instanceof Element))
                return false;
            const drawer = drawerRoot();
            if (drawer === null || !drawer.contains(target))
                return false;
            if (target.closest('[class*="sessionRow"] button') !== null)
                return false;
            return target.closest('button[data-dsh-taskboard-entry], button[data-dsh-ssh-entry], [class*="newSession"], [class*="sessionRow"], [class*="searchResultRow"], [class*="searchResultWorkspace"]') !== null;
        };
        // Touch path for session/search rows: never close the drawer from pointer
        // events. Closing at pointerup (or deferring the close) races the browser's
        // synthesized click; some iOS shells suppress that click entirely, so the
        // row's onClick never runs. Instead arm the drawer to close on the *fact*
        // of navigation: when the selected row's title changes, React has already
        // opened the conversation, so the drawer can close safely.
        let lastTouchNavAt = 0;
        let navSignatureAtArm = '';
        let navObserver = null;
        let navTimer = null;
        const selectedRowSignature = () => {
            const selected = drawerRoot()?.querySelector('[role="treeitem"][aria-selected="true"]');
            const title = selected?.querySelector('[class*="_title"]');
            return title?.textContent?.trim() ?? null;
        };
        const disarmNav = () => {
            navObserver?.disconnect();
            navObserver = null;
            if (navTimer !== null)
                window.clearTimeout(navTimer);
            navTimer = null;
            navSignatureAtArm = '';
        };
        const armNav = () => {
            disarmNav();
            navSignatureAtArm = selectedRowSignature() ?? '';
            const root = drawerRoot();
            if (root === null)
                return;
            navObserver = new MutationObserver(() => {
                if (!drawerOpen()) {
                    disarmNav();
                    return;
                }
                const signature = selectedRowSignature();
                if (signature !== null && signature !== navSignatureAtArm) {
                    disarmNav();
                    toggleSidebar();
                }
            });
            navObserver.observe(root, {
                childList: true,
                subtree: true,
                attributes: true,
                attributeFilter: ['aria-selected'],
            });
            navTimer = window.setTimeout(disarmNav, 2000);
        };
        const onDrawerClick = (event) => {
            // A classified swipe already toggled the drawer; never let its
            // synthetic tap also close it / navigate a row (gesture-guard).
            // isStrokeLocked: a stroke axis-locked mid-swipe (audit S0) — the
            // consume marks do not exist until the gesture layer's own pointerup,
            // which runs AFTER this handler on the same release event.
            if ((0, gesture_guard_ts_1.isStrokeLocked)() || (0, gesture_guard_ts_1.consumeIfGestured)(event))
                return;
            // A touch row-tap owns the close (pointerup or the navigation observer);
            // let the row's click reach React without toggling the drawer twice.
            if (performance.now() - lastTouchNavAt < 500)
                return;
            if (shouldCloseOnTapInsideDrawer(event.target))
                toggleSidebar();
        };
        const onDrawerPointerUp = (event) => {
            // A classified swipe must not arm the nav observer or toggle again
            // (gesture-guard): the drawer already toggled, and the row under the
            // stroke was never a tap. isStrokeLocked covers the release event of
            // a stroke locked mid-swipe but not yet classified — this handler
            // runs before the gesture layer's own pointerup (audit S0/S1: without
            // it the host toggled first and the gesture toggled back, net zero).
            if ((0, gesture_guard_ts_1.isStrokeLocked)() || (0, gesture_guard_ts_1.consumeIfGestured)(event))
                return;
            if (event.pointerType !== 'touch' && event.pointerType !== 'pen')
                return;
            const target = event.target;
            if (!(target instanceof Element))
                return;
            if (!shouldCloseOnTapInsideDrawer(target))
                return;
            const row = target.closest('[role="treeitem"]');
            if (row !== null) {
                lastTouchNavAt = performance.now();
                if (row.getAttribute('aria-selected') === 'true') {
                    // Already-selected row will not navigate; closing immediately is safe.
                    toggleSidebar();
                }
                else {
                    // Unselected row: let navigation land, then close via the observer.
                    armNav();
                }
                return;
            }
            // Non-row nav targets (newSession / taskboard / ssh / search rows that
            // are not treeitems): the pointerup close path is still correct.
            toggleSidebar();
        };
        document.addEventListener('keydown', onKeyDown, true);
        document.addEventListener('click', onDrawerClick, true);
        document.addEventListener('pointerup', onDrawerPointerUp, true);
        return () => {
            disarmNav();
            document.removeEventListener('keydown', onKeyDown, true);
            document.removeEventListener('click', onDrawerClick, true);
            document.removeEventListener('pointerup', onDrawerPointerUp, true);
        };
    });
}
/**
 * Register the shared DOM reconciler tasks. Returns a disposer that
 * unregisters every task and resets the flag, so a same-environment plugin
 * reload can rebuild the reconciler from scratch.
 */
function registerReconcileTasks(ctx) {
    if (reconcileTasksRegistered)
        return () => { };
    reconcileTasksRegistered = true;
    const t = ctx.locale.bind(NS);
    const removeTasks = [
        addReconcilerTask((0, preview_fullscreen_ts_1.createPreviewFullscreenTask)(t)),
        addReconcilerTask((0, git_chip_reparent_ts_1.createGitChipTask)()),
        addReconcilerTask((0, settings_toolbar_reparent_ts_1.createSettingsToolbarTask)()),
        addReconcilerTask((0, aionui_compat_ts_1.createPreviewCloseTask)()),
        addReconcilerTask((0, aionui_compat_ts_1.createSheetRiseTask)()),
        addReconcilerTask((0, stats_line_ts_1.createStatsLineTask)()),
        addReconcilerTask((0, overlay_backdrop_fab_ts_1.createOverlayTask)(t, () => ctx.layout.toggleSidebar())),
        addReconcilerTask((0, file_viewer_compat_ts_1.createFileViewerMarkerTask)()),
    ];
    return () => {
        for (const remove of removeTasks)
            remove();
        reconcileTasksRegistered = false;
    };
}
};
__modules["components/MobileNavToggle.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.MobileNavToggle = MobileNavToggle;
const jsx_runtime_1 = require("react/jsx-runtime");
const dsh_client_ui_primitives_1 = require("@deepseek-ai/dsh-client-ui-primitives");
const phone_chrome_ts_1 = require("./effects/phone-chrome.js");
/**
 * Mobile-only icon buttons next to the session title:
 * - toggle: opens the directory drawer on narrow screens.
 * - files: toggles the dsh-web-ui explorer sheet directly — one tap opens,
 *   a second tap closes it, no drawer round-trip. (The drawer footer keeps
 *   a Files entry for the hero/blank phases where this header does not
 *   exist.)
 * Hidden entirely on wide screens (CSS media query).
 */
function MobileNavToggle({ toggleSidebar, openFiles, t }) {
    const toggleExplorer = () => openFiles();
    return ((0, jsx_runtime_1.jsxs)(jsx_runtime_1.Fragment, { children: [(0, jsx_runtime_1.jsx)("button", { type: "button", "data-mobile-nav": "toggle", "aria-label": t('open'), title: t('open'), onClick: () => toggleSidebar(), children: (0, jsx_runtime_1.jsx)(dsh_client_ui_primitives_1.IconPanelLeftOutline16, { size: 16 }) }), (0, jsx_runtime_1.jsx)("button", { type: "button", "data-mobile-nav": "files", "aria-label": t('files'), title: t('files'), onClick: toggleExplorer, children: (0, jsx_runtime_1.jsx)(dsh_client_ui_primitives_1.IconFolderOpenOutline16, { size: 16 }) })] }));
}
};
__modules["components/MobileDrawerFooter.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.MobileDrawerFooter = MobileDrawerFooter;
const jsx_runtime_1 = require("react/jsx-runtime");
const dsh_client_ui_primitives_1 = require("@deepseek-ai/dsh-client-ui-primitives");
const phone_chrome_ts_1 = require("./effects/phone-chrome.js");
/**
 * Mobile-only drawer footer actions, relocated from the session header to the
 * drawer footer (beside Settings):
 * - Files: opens the dsh-web-ui aionui explorer as a floating bottom sheet
 *   (the explorer column is hidden on mobile until this marker is set, so
 *   the suite's own persisted-expanded state can never cover the UI on load).
 * - Session log: the official session-log-export controller, so the
 *   progress/result dialog is shared with the desktop flow.
 * Hidden entirely on wide screens (CSS media query).
 */
function MobileDrawerFooter({ useSessions, downloadSessionLog, toggleSidebar, openFiles, t }) {
    const sessionId = useSessions((state) => state.current);
    const openExplorer = () => { openFiles(); toggleSidebar(); };
    return ((0, jsx_runtime_1.jsxs)("div", { "data-mobile-nav": "drawer-actions", children: [(0, jsx_runtime_1.jsxs)("button", { type: "button", "data-mobile-nav": "explorer", "aria-label": t('files'), title: t('files'), onClick: openExplorer, children: [(0, jsx_runtime_1.jsx)(dsh_client_ui_primitives_1.IconPanelLeftOutline16, { size: 14 }), (0, jsx_runtime_1.jsx)("span", { children: t('files') })] }), (0, jsx_runtime_1.jsxs)("button", { type: "button", "data-mobile-nav": "session-log", "aria-label": t('sessionLog'), title: t('sessionLog'), disabled: sessionId === undefined, onClick: () => {
                    if (sessionId !== undefined)
                        downloadSessionLog(sessionId);
                }, children: [(0, jsx_runtime_1.jsx)(dsh_client_ui_primitives_1.IconDownloadOutline16, { size: 14 }), (0, jsx_runtime_1.jsx)("span", { children: t('sessionLog') })] })] }));
}
};
__modules["styles/base.css.js"] = function (require, module, exports) {
"use strict";
// base — split from src/client/mobile.css.ts (2026-08-16), order preserved.
// Do not reorder: styles/index.ts concatenates in this exact order.
Object.defineProperty(exports, "__esModule", { value: true });
exports.BASE_CSS = void 0;
exports.BASE_CSS = `
/* ---------- base control styles (rendered at any width, hidden where unused) ---------- */

[data-mobile-nav="toggle"],
[data-mobile-nav="files"] {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  flex: none;
  padding: 0;
  border: none;
  border-radius: 50%;
  background: transparent;
  color: var(--dsw-alias-label-secondary, inherit);
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
}
[data-mobile-nav="toggle"]:hover,
[data-mobile-nav="files"]:hover {
  background: var(--dsw-alias-interactive-bg-hover, rgba(0, 0, 0, .06));
}
[data-mobile-nav="toggle"]:focus-visible,
[data-mobile-nav="files"]:focus-visible {
  outline: 2px solid var(--dsw-alias-state-business-primary, #4f6ef7);
  outline-offset: 1px;
}

/* Drawer footer actions: the relocated Session log download plus the Files
   action that opens the dsh-web-ui explorer sheet. */
[data-mobile-nav="drawer-actions"] {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
[data-mobile-nav="session-log"],
[data-mobile-nav="explorer"] {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  height: 34px;
  padding: 0 12px;
  border: 1px solid var(--dsw-alias-border-l1, rgba(0, 0, 0, .12));
  border-radius: 12px;
  background: transparent;
  color: var(--dsw-alias-label-primary, inherit);
  font-family: inherit;
  font-size: 13px;
  line-height: 20px;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
}
[data-mobile-nav="session-log"]:hover:not(:disabled),
[data-mobile-nav="explorer"]:hover {
  background: var(--dsw-alias-interactive-bg-hover, rgba(0, 0, 0, .06));
}
[data-mobile-nav="session-log"]:disabled {
  color: var(--dsw-alias-label-dimmed, rgba(0, 0, 0, .35));
  cursor: default;
}

/* Session-delete confirm / error cards (shown as a bottom overlay, see the
   delete-dialog wrapper below). Danger-tinted card with a description and
   two actions. */
[data-mobile-nav="delete-confirm"] {
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 100%;
  box-sizing: border-box;
  padding: 8px 10px;
  border: 1px solid var(--dsw-alias-state-error-secondary, rgba(220, 38, 38, .35));
  border-radius: 12px;
  background: var(--dsw-alias-interactive-bg-hover-danger, rgba(220, 38, 38, .06));
}
[data-mobile-nav="delete-confirm-title"] {
  font-size: 13px;
  font-weight: 600;
  line-height: 18px;
  color: var(--dsw-alias-state-error-primary, #b91c1c);
}
[data-mobile-nav="delete-confirm-desc"] {
  font-size: 12px;
  line-height: 17px;
  color: var(--dsw-alias-label-secondary, inherit);
}
[data-mobile-nav="delete-confirm-actions"] {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 2px;
}
[data-mobile-nav="delete-confirm-actions"] > button {
  height: 30px;
  padding: 0 12px;
  border: 1px solid var(--dsw-alias-border-l1, rgba(0, 0, 0, .12));
  border-radius: 10px;
  background: transparent;
  color: var(--dsw-alias-label-primary, inherit);
  font-family: inherit;
  font-size: 13px;
  line-height: 20px;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
}
[data-mobile-nav="delete-confirm-yes"] {
  border-color: var(--dsw-alias-state-error-secondary, rgba(220, 38, 38, .5)) !important;
  background: var(--dsw-alias-state-error-primary, #dc2626) !important;
  color: #ffffff !important;
}
[data-mobile-nav="delete-confirm-actions"] > button:disabled {
  opacity: .55;
  cursor: default;
}
[data-mobile-nav="delete-error"] {
  width: 100%;
  font-size: 12px;
  line-height: 17px;
  color: var(--dsw-alias-state-error-primary, #b91c1c);
}

/* Bottom overlay for the delete confirm / error card: dimmed backdrop plus a
   viewport-anchored card above the drawer. [hidden] keeps the error line out
   of layout until a failure lands. */
[data-mobile-nav="delete-dialog-backdrop"] {
  position: fixed;
  inset: 0;
  z-index: 55;
  background: rgba(0, 0, 0, .45);
  animation: dsh-web-mobile-fade .2s var(--ds-ease-in-out, ease-in-out);
}
[data-mobile-nav="delete-dialog"] {
  position: fixed;
  left: 8px;
  right: 8px;
  bottom: calc(env(safe-area-inset-bottom, 0px) + 16px);
  z-index: 56;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px;
  border-radius: 14px;
  background: var(--dsw-alias-bg-base, #ffffff);
  box-shadow: 0 8px 30px rgba(0, 0, 0, .22);
  animation: dsh-web-mobile-sheet-in .22s var(--ds-ease-out, ease-in-out);
}
@media (prefers-reduced-motion: reduce) {
  [data-mobile-nav="delete-dialog-backdrop"],
  [data-mobile-nav="delete-dialog"] {
    animation: none !important;
  }
}

/* Floating fallback button (hero / blank phases without a session header).
   The top clears the camera band below the status bar; when the client has
   set viewport-fit=cover the safe-area inset moves it below the notch too. */
[data-mobile-nav="fab"] {
  position: absolute;
  top: calc(env(safe-area-inset-top, 0px) + 72px);
  left: 10px;
  z-index: 21;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  padding: 0;
  border: 1px solid var(--dsw-alias-border-l1, rgba(0, 0, 0, .12));
  border-radius: 50%;
  background: var(--dsw-alias-button-floating-fill, #ffffff);
  color: var(--dsw-alias-label-primary, inherit);
  cursor: pointer;
  box-shadow: 0 2px 12px rgba(0, 0, 0, .18);
  -webkit-tap-highlight-color: transparent;
}
[data-mobile-nav="fab"]:hover {
  background: var(--dsw-alias-button-floating-hover, rgba(0, 0, 0, .08));
}
[data-mobile-nav="fab"]:focus-visible {
  outline: 2px solid var(--dsw-alias-state-business-primary, #4f6ef7);
  outline-offset: 2px;
}

/* Dimmed backdrop under the open drawer; above every column, below the drawer. */
[data-mobile-nav="backdrop"] {
  position: absolute;
  inset: 0;
  z-index: 30;
  background: rgba(0, 0, 0, .45);
  cursor: pointer;
  animation: dsh-web-mobile-fade .2s var(--ds-ease-in-out, ease-in-out);
  /* Fade-out twin of the mount animation: the task eases the dimming away
     (inline opacity 0 + pointer-events none) and removes the element after
     the fade. Also used by the gesture layer so the backdrop fades in step
     with a close-follow commit's slide-out. */
  transition: opacity .2s var(--ds-ease-in-out, ease-in-out);
  -webkit-tap-highlight-color: transparent;
}
@keyframes dsh-web-mobile-fade {
  from { opacity: 0; }
  to { opacity: 1; }
}
@media (prefers-reduced-motion: reduce) {
  [data-mobile-nav="backdrop"] {
    animation: none !important;
    transition: none !important;
  }
}
/* Settings sheet entrance: the official dialog mounts with no animation at
   all, so it snaps in. Fade + slight rise/scale reads as a proper sheet. */
@keyframes dsh-web-mobile-sheet-in {
  from {
    opacity: 0;
    transform: translateY(14px) scale(.98);
  }
  to {
    opacity: 1;
    transform: none;
  }
}
/* Preview sheet rise: the aionui preview column opens as a bottom sheet. */
@keyframes dsh-web-mobile-sheet-up {
  from {
    opacity: 0;
    transform: translateY(28px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

`;
};
__modules["styles/layout.css.js"] = function (require, module, exports) {
"use strict";
// layout — split from src/client/mobile.css.ts (2026-08-16), order preserved.
// Self-contained: the mobile media query opens and closes in this file.
Object.defineProperty(exports, "__esModule", { value: true });
exports.LAYOUT_CSS = void 0;
exports.LAYOUT_CSS = `/* ---------- mobile-only layout (narrow viewport AND touch-primary pointer) ---------- */

@media (max-width: 1023px) and (pointer: coarse) {
  /* --- Phone chrome ---
     The system status bar stays visible (no fullscreen). Three adjustments
     make it behave:
     - touch-action: pan-y pinch-zoom kills double-tap-to-zoom (and the 300ms
       tap delay) while keeping vertical pan. Omitting pan-x (i.e. not using
       the manipulation alias) forbids HORIZONTAL pan on the root: a
       left-edge horizontal drag would otherwise be claimed by the browser as
       a pan (firing pointercancel) before the sidebar swipe layer can
       classify it. touch-action does not inherit and the behavior
       intersection stops at the first scroll container, so only touches
       landing directly on the root background are affected — inner
       horizontal scrolling of content containers is untouched. pinch-zoom is
       listed on purpose (#45): a bare pan-y also drops pinch, and then a
       zoom the browser applied by itself — iOS enlarges the viewport when a
       field under 16px takes focus — can no longer be undone by the user,
       so the app stays magnified until it is reopened or rotated. Two-finger
       zoom is also the WCAG 1.4.4 escape hatch and costs the gesture layer
       nothing: pinch is not a horizontal pan.
     - overscroll-behavior-x: none suppresses the browser's edge history
       navigation on the root scroller — Android Chrome claims a horizontal
       stroke that STARTS within its edge band (EDGE_WIDTH_DP=48dp,
       NavigationHandler.java) and navigates BACK, the exact gesture that
       opens the drawer ("页面直接返回上一页", 2026-08-29 user report). Only
       html/body count for this (Chromium issue 41483088: inner containers
       are ignored by the navigation path). iOS Safari's edge back-swipe has
       no CSS opt-out (WebKit bug 240183) — there the widened gesture start
       zone (96px, beyond every browser's edge-claim strip) is the
       mitigation.
     - With the client's viewport-fit=cover, env(safe-area-inset-top) is the
       status bar / notch height; the rules below push the app content below
       it so the status bar never covers anything. Off notched phones (or in
       a normal browser tab where the layout viewport already sits below the
       status bar) the inset is 0 and nothing shifts. */
  html,
  body {
    touch-action: pan-y pinch-zoom !important;
    overscroll-behavior-x: none !important;
  }

  /* AppFrame: the drawer takes the sidebar column out of grid flow, so the
     remaining in-flow items (center, details) land in tracks 1..2: give the
     center every pixel and keep the details track at zero. The top padding
     clears the status bar / notch for every in-flow surface (session header,
     messages, composer); the absolutely-positioned drawer is unaffected (its
     containing block is the frame's padding box, i.e. still the frame top).
     box-sizing MUST be border-box: the official frame is height:100% of a
     100%-height body, and it is content-box by default, so the safe-area
     padding is ADDED on top of the full viewport height. The frame then grows
     to 100% + inset, the document itself becomes scrollable by exactly the
     inset, and the sticky composer seat (bottom:0 of the scroll body) lands
     below the visual viewport. Symptoms on a notched phone: the whole UI can
     be swiped up, the composer lifts off the bottom leaving a blank strip,
     and the newest message sits under the composer because the host's
     at-bottom follow scrolls its own scroll body, not the document. With
     border-box the padding is taken out of the 100% height instead, so the
     frame is exactly one viewport tall and the document never scrolls. */
  [data-mobile-nav="frame"] {
    box-sizing: border-box !important;
    position: relative !important;
    grid-template-columns: minmax(0, 1fr) 0 0 !important;
    padding-top: env(safe-area-inset-top, 0px) !important;
  }

  /* The sidebar column (first grid child) becomes a left drawer. The drawer
     hugs the sidebar content exactly (the wide sidebar carries an inline
     width, ~280px): a fixed 92vw box would leave a white strip where the
     container background shows beside the content.
     Closed state: translateX(-110%) — more than -100% of the max-content
     width — guarantees the whole drawer (and its shadow, had it one) leaves
     the viewport. A mere -100% leaves a sliver on screen; -105% (as used
     before) left 14px of the drawer plus a long 32px-blur shadow gradient
     visible along the left edge of the main UI. No box-shadow at all: the
     dimmed backdrop already separates drawer from content. */
  [data-mobile-nav="frame"] > :first-child {
    position: absolute !important;
    inset: 0 auto 0 0 !important;
    width: max-content;
    max-width: 92vw;
    z-index: 40 !important;
    transform: translateX(-110%);
    transition: transform .28s var(--ds-ease-in-out, ease-in-out);
    background: var(--dsw-alias-bg-base, #ffffff);
    /* Keep the drawer's own content below the status bar / notch: the drawer
       spans the full frame height (its absolute containing block is the
       frame's padding box, so the frame's own safe-area padding does NOT
       reach it). The drawer background paints the status-bar strip, which
       the client's theme-color meta matches, so the strip reads seamless. */
    padding-top: env(safe-area-inset-top, 0px) !important;
    /* Kill the official sidebarCol right border: with the backdrop the edge
       reads cleanly, and the settings dialog (width:100% of this box) stays
       pixel-flush with the drawer. */
    border-right: none !important;
  }

  /* Expanded state (frame without data-sidebar-collapsed) slides the drawer in.
     The open state must be transform:none — NOT translateX(0): an identity
     transform still makes the drawer the containing block for fixed-position
     descendants (the settings dialog's .VOzbGW_overlay is portaled into the
     sidebar DOM). With the identity transform the wide settings sheet
     (100vw-16) overflows the 280px drawer, the dialog's focus scrolls the
     overflow:hidden drawer to scrollLeft=102, and every static child (plus the
     fixed overlay) shifts 102px off-screen. With transform:none the overlay is
     viewport-anchored: it dims the full screen and the sheet sits at left:8. */
  [data-mobile-nav="frame"]:not([data-sidebar-collapsed]) > :first-child {
    transform: none !important;
  }

  /* Drawer swipe gestures (edge swipe-in / content swipe-out, see
     docs/specs/2026-08-27-sidebar-swipe-gestures.md).
     One rule is load-bearing for the gesture layer: dropping pan-x on the
     drawer lets horizontal pointermove events reach the gesture code —
     WITHOUT it the browser treats a horizontal stroke as a pan, fires
     pointercancel and the gesture never classifies (vertical panning stays
     intact). Start-hit is decided purely by geometry on the document
     capture listener (START_ZONE_PX = 48px); there is no hotspot element
     (removed per audit C2, 2026-08-27). pinch-zoom rides along with the
     root value so a browser-applied zoom stays undoable inside the drawer
     too (#45); touch-action intersects down the ancestor chain, so a bare
     pan-y here would cancel the root's pinch permission. */
  [data-mobile-nav="frame"] > :first-child {
    touch-action: pan-y pinch-zoom !important;
  }

  /* prefers-reduced-motion: the drawer's .28s slide is motion; drop it
     (audit S2 2026-08-27 — the old reduce block only covered the settings
     sheet and its mask). Same idiom as the animation:none blocks below. */
  @media (prefers-reduced-motion: reduce) {
    [data-mobile-nav="frame"] > :first-child {
      transition: none !important;
    }
  }

  /* Drag handles are useless on touch and would float over the drawer. */
  [data-side="sidebar"],
  [data-side="details"] {
    display: none !important;
  }

  /* --- Conversation text on mobile ---
     The official message flow keeps desktop's 32px side gutters and 16px
     type. On a phone: shrink the type a notch and widen the lines by
     trimming the gutters (the sidebar drawer list keeps its size). The
     flow's scroll container holds the markdown <p> paragraphs; since
     DSH 0.1.2-rc.1 the composer is a Lexical contenteditable that also
     renders real <p> paragraphs inside its own _scroll container, so the
     composer must be excluded explicitly via
     :not(:has([data-composer-input])). */
  /* The official main scroll body reserves scrollbar-gutter for desktop
     scrollbars (8px), which shoves every column off-center on a phone.
     Classic desktop scrollbars (Edge/Chrome) also occupy ~8-17px in a
     phone-sized viewport, shifting the column further. Mobile scrolling
     is touch/wheel, so remove the scrollbar entirely on phones: the
     column is then exactly centered in every browser. */
  [data-phase] [class*="_scrollBody"] {
    scrollbar-gutter: auto !important;
    scrollbar-width: none;
  }
  [data-phase] [class*="_scrollBody"]::-webkit-scrollbar {
    display: none !important;
    width: 0;
    height: 0;
  }
  /* Message action rows (copy / run-time badges) can overflow the right
     edge on narrow screens — keep them inside the message width. */
  [data-phase] [class*="_actions"] {
    overflow: hidden;
  }
  [data-phase] [class*="_actions"] [class*="_timeEnd"] {
    flex: 0 1 auto;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap !important;
  }

  /* Message tooltip bubbles (copy / feedback labels, message-row hover
     bubbles) are redundant on touch: the icon already flips to a checkmark.
     Suppress only inside the actions row — the fork's original scope. On
     this host (0.1.1-rc.2) NO tooltip renders as a visible bubble: the copy
     label is a visuallyHidden span and no client-ui package emits
     role="tooltip". The user message bubble (gdEzaW_bubble) and the goal
     bubble (oRe1gG_bubble) live in _userStack/_row, NOT in _actions — the
     previously unscoped selector hid every user message on touch devices
     (2026-09-06 live regression). role="tooltip" stays globally suppressed:
     genuine ARIA tooltips are exactly what the sticky-residue fix targets,
     and nothing legitimate carries the role today. The actions-row arm
     re-activates by itself when a host version renders tooltip labels
     inline in the actions row (DSH 0.1.2 shape). */
  @media (hover: none), (pointer: coarse) {
    [data-phase] [role="tooltip"],
    [data-phase] [class*="_actions"] [class*="_bubble"] {
      display: none !important;
      visibility: hidden !important;
      opacity: 0 !important;
      pointer-events: none !important;
    }
  }

  [data-phase]
    [class*="_scroll"]:not([class*="_scrollBody"]):not(:has([data-composer-input])):has(p) {
    padding-left: 20px;
    padding-right: 20px;
    font-size: 15px !important;
  }
  /* The official markdown styles set an explicit 16px on paragraphs and
     list items, so the container's inherited 15px is not enough. User
     messages render their text in a div whose class carries _text_
     (16px too) — cover it as well. */
  [data-phase]
    [class*="_scroll"]:not([class*="_scrollBody"]):not(:has([data-composer-input])):has(p) p,
  [data-phase]
    [class*="_scroll"]:not([class*="_scrollBody"]):not(:has([data-composer-input])):has(p) li,
  [data-phase]
    [class*="_scroll"]:not([class*="_scrollBody"]):not(:has([data-composer-input])):has(p) [
      class*="_text_"
    ] {
    font-size: 15px !important;
  }

  /* Markdown tables: the official table uses width:max-content, so on a phone
     it hugs the content and leaves dead space beside/inside the table. Force
     the table to fill the message column and let the table wrapper handle
     overflow if a cell is genuinely too wide. */
  [data-phase] table {
    width: 100%;
    max-width: 100%;
  }
  [data-phase] th,
  [data-phase] td {
    max-width: none;
    min-width: 0;
  }

  /* Markdown images: the official rule often forces width:100%, which
     upscales small square images to the full message column. Show small
     images at their intrinsic size; large / very wide images still scale
     down to fit the column (max-width:100% keeps horizontal panoramas
     adaptive without overflowing). */
  [data-phase] [class*="_scroll"]:not([class*="_scrollBody"]) img {
    width: auto !important;
    max-width: 100% !important;
    height: auto !important;
    /* Cap square / tall images so a big sticker does not dominate the
       narrow column; landscape images stay governed by max-width only.
       The plain px line is the fallback for engines without dvh. */
    max-height: 220px !important;
    max-height: min(40dvh, 220px) !important;
  }

  /* User bubbles: the official stack is capped at min(525px, 82%), which on a
     phone leaves a large blank strip on the left and pushes the bubble high.
     On mobile let the user message fill the same full width as assistant
     messages (the bubble background then spans the whole message column). */
  [data-phase] [class*="_userStack"],
  [data-phase] [class*="_userStack"] [class*="_bubble"] {
    box-sizing: border-box;
    width: fit-content;
    max-width: 100%;
  }

  /* --- Composer bottom row on mobile ---
     The official row contains two lanes: tools (plus + permission/mode
     controls) and trailing (model + context + send). The previous rules made
     the modes lane flex:none, so its full intrinsic width collided with the
     model selector on narrow phones. Keep fixed hit targets fixed, but let
     text-bearing controls shrink and ellipsize before they paint over the
     trailing lane. */
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) {
    box-sizing: border-box;
    container-type: inline-size;
    container-name: dsh-mobile-composer;
    flex-wrap: nowrap;
    gap: 6px;
    padding-left: 6px;
    padding-right: 6px;
    /* The dropdown menu is absolutely positioned inside this row; any
       overflow: hidden here would clip it. Inner lanes keep their own
       overflow clipping, so the row itself can stay visible. */
    overflow: visible;
  }
  /* Dual-primary form (subagent view: stop + send). The four-control
     cluster [model][meter][stop][send] overflows the single-row lane the
     nowrap rule above enforces; the model pill is the only shrinkable
     item, so it collapses to zero and the fixed trio loses its auto
     margin (all hug the lane's left edge, send may even paint off-view).
     Restore the official wrap for this form only: the trailing lane
     drops to a second full-width row where the four controls always
     fit. Main-session three-control form keeps single-row layout. */
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]):has([class*="_primary"] ~ [class*="_primary"]) {
    flex-wrap: wrap;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > :first-child {
    flex: 0 1 auto;
    min-width: 0;
    gap: 6px;
    /* The permission dropdown (Menu, side: top) pops upward from inside the
       tools lane; overflow hidden here would crop it, same as the row. Text
       ellipsis is handled by the trigger label itself. */
    overflow: visible;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"] {
    flex: 1 1 auto;
    min-width: 0;
    gap: 6px;
    /* Must not clip the model dropdown; the model trigger clips its own label. */
    overflow: visible;
  }
  /* PermissionSelect / plan controls share the tools lane. Let the
     permission label use the remaining tools width, while the lower-priority
     plan slot keeps an icon-sized target instead of stealing model width. */
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > :first-child > [class*="_modes"] {
    flex: 0 1 auto;
    min-width: 0;
    max-width: none;
    gap: 4px;
    /* The permission Menu list (side: top) pops upward out of this lane;
       overflow hidden crops it. The trigger label clips its own text. */
    overflow: visible;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > :first-child > [class*="_modes"] > [class*="_trigger"] {
    flex: 1 1 auto;
    min-width: 28px;
    max-width: 100%;
    display: flex !important;
    overflow: hidden;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > :first-child > [class*="_modes"] > [class*="_trigger"] > [class*="_triggerLabel"] {
    flex: 1 1 auto;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap !important;
  }
  /* Slot wrappers such as the live plan chip are not trigger elements. Do
     not force them into an icon-sized box: their child button would overflow
     that wrapper and paint over PermissionSelect. Keep the wrapper intrinsic;
     the model lane below is the one that sacrifices width. */
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > :first-child > [class*="_modes"] > :not([class*="_trigger"]) {
    flex: 0 1 auto;
    min-width: 34px;
    max-width: max-content;
    overflow: visible;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > :first-child > [class*="_modes"] > [class*="_wrap"] > [class*="_chip"] {
    max-width: 100%;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap !important;
  }
  @container dsh-mobile-composer (max-width: 359px) {
    [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > :first-child > [class*="_modes"] > [class*="_trigger"] > [class*="_triggerLabel"] {
      display: none !important;
    }
  }
  /* Model selector: flexible and shrinkable, but never clipped.
     The root must be overflow:visible so the dropdown menu can render.
     The trigger itself clips the label text. */
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_root"]:has(> [class*="_trigger"][aria-haspopup="menu"]) {
    flex: 0 1 auto;
    min-width: 0;
    overflow: visible;
  }
  @container dsh-mobile-composer (max-width: 359px) {
    [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_root"]:has(> [class*="_trigger"][aria-haspopup="menu"]) {
      flex-basis: auto;
    }
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_root"]:has(> [class*="_trigger"][aria-haspopup="menu"]) > [class*="_trigger"] {
    display: flex !important;
    width: 100%;
    max-width: 100%;
    min-width: 0;
    overflow: hidden;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_root"]:has(> [class*="_trigger"][aria-haspopup="menu"]) > [class*="_trigger"] > [class*="_triggerLabel"] {
    flex: 1 1 auto;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap !important;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_root"]:has(> [class*="_trigger"]):not(:has(> [class*="_trigger"][aria-haspopup="menu"])) {
    flex: 0 0 auto;
  }

  /* Model switcher menu: center the dropdown on the now-shrinkable trigger,
     but never let it exceed the viewport on narrow phones. */
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_root"]:has(> [class*="_trigger"]) > [class*="_menu"] {
    left: 50% !important;
    right: auto !important;
    transform: translateX(-50%) !important;
    max-width: min(320px, calc(100vw - 16px));
    box-sizing: border-box;
  }

  /* --- Fix composer row overflow at narrow widths (320px-360px) ---
     Force every direct child of the tools and trailing lanes to shrink,
     so they can fit within the available space without causing horizontal
     overflow. The fixed-size icon buttons are exempt: officially both are
     flex:none at a fixed size (plus 28x28, send 34x34) and must stay put,
     not participate in adaptation. */
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > :first-child > :not([class*="_add"]) {
    flex-shrink: 1;
    min-width: 0;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"] > :not([class*="_primary"]) {
    flex-shrink: 1;
    min-width: 0;
  }
  /* Pin the plus button at the left edge of the tools lane: official
     flex:none 28x28, never squeezed by narrower viewports. */
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > :first-child > [class*="_add"] {
    flex: none;
  }
  /* The context meter in the trailing lane is another fixed-size icon
     control: its trigger is officially width:28px flex:none, but the root
     itself is shrinkable, so a squeezed root lets the trigger paint over
     the pinned send button. Keep the whole meter at its natural size; its
     trigger uses aria-haspopup="dialog", so the model-selector menu rules
     (keyed on "menu") still do not apply. */
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"] > [class*="_root"] {
    flex: none;
    min-width: 0;
  }
  /* ContextMeter (JObwrW_ hash family) right-cluster pinning: keep the meter
     at its official size (28x28 trigger, 14px ring -- enlarging the ring made
     it steal attention) and glue it to the send button. A small negative
     right margin trims the 6px lane gap to 2px against send. Anchor on the
     unique aria-haspopup="dialog" trigger (no other composer control uses
     it), not the hashed class, so an upstream hash bump cannot silently
     unhook us. Knob: margin-right trim (-4px). */
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"] > [class*="_root"]:has(> [class*="_trigger"][aria-haspopup="dialog"]) {
    margin-right: -4px;
  }
  /* The model pill joins the same right cluster: its margin-left:auto absorbs
     ALL trailing slack, so the adaptive void sits between the tools lane and
     the pill (visible on wide phones/tablets), while [pill][meter][send] stay
     welded together at the right edge on every width. Descendant combinator
     on purpose: the pill root sits behind a display:contents wrapper, so a
     direct-child combinator silently misses (probe-verified). Within the
     trailing lane aria-haspopup="menu" belongs to the model trigger alone. */
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"] [class*="_root"]:has(> [class*="_trigger"][aria-haspopup="menu"]) {
    margin-left: auto;
    margin-right: -4px;
  }
  /* Shrink only the trigger BOX (28 -> 24, padding zeroed) while the ring
     ink stays at its official 14px: the dead inset per side drops from 7px
     to 5px so the small ring no longer floats in its own button. 24x24 keeps
     the WCAG 2.2 minimum target size. Ring size itself is intentionally
     untouched -- enlarging it was rejected as attention-grabbing. */
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"] > [class*="_root"]:has(> [class*="_trigger"][aria-haspopup="dialog"]) > [class*="_trigger"] {
    width: 24px;
    height: 24px;
    padding: 0;
  }
  /* Slack-absorber priority in the trailing lane: model pill > meter > send.
     Exactly one element carries margin-left:auto so the adaptive void always
     sits BEFORE the welded right cluster, never inside it. The meter itself
     never had an auto before 2026-09-06: in subagent sessions the model seat
     is officially absent (the parent pins the model), and zeroing the send's
     auto on the meter's aria-haspopup="dialog" then left NOTHING to absorb
     slack -- the whole right cluster hugged the lane's left edge (user
     screenshot). Fix: when no model pill renders, the meter root becomes the
     absorber, welding [meter][send] at the right edge like the main view's
     [pill][meter][send]; the send's auto only survives when neither renders. */
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"]:not(:has([class*="_trigger"][aria-haspopup="menu"])) > [class*="_root"]:has(> [class*="_trigger"][aria-haspopup="dialog"]) {
    margin-left: auto;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"] > [class*="_primary"] {
    flex: none;
    margin-left: auto;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"]:has([class*="_trigger"][aria-haspopup="menu"], > [class*="_root"] > [class*="_trigger"][aria-haspopup="dialog"]) > [class*="_primary"] {
    margin-left: 0;
  }

  /* --- Session header on mobile ---
     Keep the host-owned metadata in one responsive row. The conversation
     title and running/subagent status keep their lanes; the mode text is the
     first to ellipsize when space runs out, while Files keeps its hit area. */
  [data-mobile-nav="frame"] [data-phase] header {
    padding-left: 16px;
    padding-right: 8px;
  }
  [data-mobile-nav="frame"] [data-phase] header > :first-child {
    display: flex !important;
    align-items: center;
    box-sizing: border-box;
    width: 100%;
    min-width: 0;
    gap: 2px;
    padding-left: 20px;
  }
  [data-mobile-nav="frame"] [data-phase] header > :first-child > :first-child {
    display: flex !important;
    align-items: center;
    flex: 1 1 auto;
    min-width: 0;
    gap: 2px;
  }
  /* The directory toggle stays at the far left of the header. */
  [data-mobile-nav="toggle"] {
    position: absolute !important;
    left: 8px !important;
    top: 12px !important;
    z-index: 2 !important;
  }
  /* Files remains in flow and is ordered as the rightmost plugin action. */
  [data-mobile-nav="files"] {
    position: static !important;
    left: auto !important;
    right: auto !important;
    top: auto !important;
    z-index: auto !important;
  }
  [data-mobile-nav="frame"] [data-phase] header [class*="_headerActions"] {
    display: flex !important;
    align-items: center;
    box-sizing: border-box;
    flex: 0 1 auto;
    min-width: 0;
    max-width: calc(100% - 32px);
    margin-left: auto;
    justify-content: flex-end;
    gap: 2px;
  }
  /* The title takes the remaining width and never paints outside it; the
     metadata lane's mode text is what shrinks first. */
  [data-mobile-nav="frame"] [data-phase] header [class*="_crumbs"] {
    flex: 1 1 0;
    min-width: 0;
    max-width: none;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap !important;
  }
  /* Mode label: preserve its icon and scale with the viewport — it yields
     space to the title and subagent status first, but can use more width on
     wider screens up to 220px before ellipsizing. */
  [data-mobile-nav="frame"] [data-phase] header [class*="_label"]:has(> svg) {
    order: 1;
    flex: 0 1 auto;
    min-width: 0;
    max-width: min(22vw, 220px);
    display: block;
    position: relative;
    box-sizing: border-box;
    padding-left: 18px;
    padding-right: 2px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap !important;
  }
  [data-mobile-nav="frame"] [data-phase] header [class*="_label"]:has(> svg) > svg {
    position: absolute !important;
    left: 0 !important;
    top: 50% !important;
    transform: translateY(-50%) !important;
  }
  /* Running/subagent controls keep their full status text and hit area; they
     do not give up width to the mode label. NOTE: the real subagent lineage
     root has class="ZKlsPq_root " — a TRAILING SPACE from the plugin's
     template-literal className — so [class*="_root"] never matches it. Use
     [class*="_root"] and exclude the switcher root ([class*="_switcherRoot"])
     so only the count/job roots get pinned (the switcher must stay shrinkable
     so its own title can ellipsize). */
  [data-mobile-nav="frame"] [data-phase] header [class*="_root"]:not([class*="_switcherRoot"]):has(> button[class*="_trigger"]) {
    order: 2;
    flex: 0 0 auto;
    min-width: max-content;
    max-width: max-content;
    white-space: nowrap !important;
    position: static;
  }
  [data-mobile-nav="frame"] [data-phase] header [class*="_root"]:not([class*="_switcherRoot"]):has(> button[class*="_trigger"]) > button,
  [data-mobile-nav="frame"] [data-phase] header [class*="_root"]:not([class*="_switcherRoot"]):has(> button[class*="_trigger"]) > button * {
    white-space: nowrap !important;
  }
  /* The lineage count's leading "/" (ZKlsPq_separator — official desktop
     chrome rendered only for a root session inside the crumbs) looks like a
     stray extra breadcrumb level on small screens; hide it. The crumbSep "/"
     between ancestry segments (subagent sessions) is a real separator and
     stays. */
  [data-mobile-nav="frame"] [data-phase] header [class*="_crumbs"] [class*="_separator"] {
    display: none !important;
  }
  [data-mobile-nav="frame"] [data-phase] header [data-mobile-nav="files"] {
    order: 3;
    flex: 0 0 28px;
    width: 28px;
  }
  /* Session log download: gone from the header row on mobile (the utilities
     seat holds only the session-log-export capsule). */
  [data-mobile-nav="frame"] [data-phase] header > :first-child > :last-child {
    display: none !important;
  }
  /* View tabs strip (official [role="tablist"] under the crumbs row).
     Desktop ships a single flex row (gap: 36) sized for the two stock tabs
     (对话/轨迹). Plugins register further views (memory / skill / todo
     panels, per-plugin settings pages), and once the count passes two the
     shrinkable buttons collapse to their min-content: CJK labels stack one
     glyph per line (staircase), latin labels break word-per-line — the
     strip eats a screenful of vertical space (#41, 8 tabs, HarmonyOS
     browser). Scroll the strip horizontally instead — the standard mobile
     tab-bar pattern — with every label kept whole (flex-shrink: 0 +
     nowrap). Affordance is the peek: the naturally cut-off tab at the right
     edge says "more this way" (unlike the settings navList, whose buttons
     nearly fit and would show no cut edge), which is why this strip scrolls
     while that one wraps. touch-action: pan-x opts the strip into
     horizontal panning — the root's pan-y intersection stops at this first
     scroll container (same mechanism as the drawer's pan-y), so the page
     never scrolls sideways. overscroll-behavior-x: contain stops a flick
     from chaining past the ends; snap keeps tabs edge-aligned after a
     fling; the scrollbar stays hidden like every native tab bar. */
  [data-mobile-nav="frame"] [data-phase] header [role="tablist"] {
    flex-wrap: nowrap;
    gap: 0 16px;
    overflow-x: auto;
    overscroll-behavior-x: contain;
    scroll-snap-type: x proximity;
    touch-action: pan-x;
    scrollbar-width: none;
  }
  [data-mobile-nav="frame"] [data-phase] header [role="tablist"]::-webkit-scrollbar {
    display: none;
  }
  [data-mobile-nav="frame"] [data-phase] header [role="tablist"] > button {
    flex-shrink: 0;
    white-space: nowrap;
    scroll-snap-align: start;
  }
  /* Header crowding on narrow phones.
     A background-job trigger in the header actions, or the subagent lineage
     count ("N 个子代理") living inside the crumbs nav, consumes the width the
     mode label would otherwise use. This squeezes the crumbs nav so hard that
     the subagent count is clipped by the nav's overflow:hidden — the text
     looks overwritten and the trigger's right edge stops being reliably
     tappable. Mode text is the lowest-priority item, so it is compressed
     first. The lineage root (dsh-client-ui-subagent) sits in the crumbs for
     BOTH running and idle descendants, so we key the guards on that root
     rather than the transient running-state dot — otherwise the count gets
     clipped again the moment agents go idle. Match roots with
     [class*="_root"] (the real class carries a trailing space; [class*="_root"]
     matches nothing). */
  @media (max-width: 440px) {
    [data-mobile-nav="frame"] [data-phase] header [class*="_crumbs"] {
      padding-right: 8px;
    }
    [data-mobile-nav="frame"] [data-phase] header [class*="_headerActions"]:has([class*="_root"]) [class*="_label"]:has(> svg),
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_crumbs"] [class*="_root"]) [class*="_label"]:has(> svg) {
      max-width: 18px;
      min-width: 18px;
      padding-left: 18px;
      padding-right: 0 !important;
    }
  }
  /* When the subagent lineage (any state) AND a background job are present
     together, even the mode icon is not enough room by itself. Keep the full
     subagent count (the reported-overwritten text) by compacting the job
     trigger to its dot/chevron, and keep mode icon-only so the crumbs nav can
     also hold a small right-hand gap — the subagent text should never sit
     flush against the mode component. */
  @media (max-width: 559px) {
    [data-mobile-nav="frame"] [data-phase] header [class*="_crumbs"] {
      padding-right: 8px;
    }
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_crumbs"] [class*="_root"]) [class*="_headerActions"] [class*="_root"]:not([class*="_switcherRoot"]):has(> button[class*="_trigger"]) [class*="_count"] {
      display: none !important;
    }
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_crumbs"] [class*="_root"]):has([class*="_headerActions"] [class*="_root"]) [class*="_label"]:has(> svg) {
      max-width: 18px;
      min-width: 18px;
      padding-left: 18px;
      padding-right: 0 !important;
    }
  }
  @media (max-width: 359px) {
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_crumbs"] [class*="_root"]):has([class*="_headerActions"] [class*="_root"]) [class*="_label"]:has(> svg) {
      display: none !important;
    }
  }

  /* --- Header popovers on mobile (dsh-client-ui-jobs / dsh-client-ui-subagent) --- */
  /* The official entries sit in the session header actions. Their popovers
     are anchored to the trigger's left edge, so clamp them to the viewport. */
  [data-mobile-nav="frame"] [data-phase] header [class*="_menu"] {
    left: 8px !important;
    right: auto !important;
    width: min(336px, calc(100vw - 16px));
    max-width: none;
    max-height: min(420px, calc(100dvh - 120px));
  }
  /* --- Settings dialog on mobile ---
     Desktop: 800px two-column flex (188px nav + content). Mobile: a
     near-full-width sheet — nav tabs wrap into rows on top, option rows
     stay horizontal (title+description left, control right). Structural
     selectors are scoped to the unique aria-modal dialog; every
     settings-specific rule is gated with
     :has(> :first-child > :last-child > button) — the settings nav tab
     list holds <button> tabs, so the transient export dialog (the same
     primitives Modal, header(title+close)+description+body) keeps its
     official centered card layout. Requires :has() support
     (Chromium 105+, 2022).

     The directory picker (dsh-client-ui-directory-picker-browse) must be
     excluded too: its footer bar holds <button> children AND its breadcrumb
     trail (role="navigation") — which the role gate relies on to exclude
     it — is REPLACED by the path input in edit mode (pencil button), so
     without the ZuhsRW exclusion clicking the pencil would suddenly match
     this sheet rule: the dialog jumps to the top of the screen, the header
     (with the path input) is hidden by the > :first-child > :first-child
     display:none rule below, and the user can no longer type a path
     (issue #12, 2026-08-16). The picker family keeps the official layout
     on mobile in every mode. */
  [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])) {
    position: absolute !important;
    left: 8px !important;
    /* Fixed top (no translateY): a transform on the panel combined with the
       panel overflowing the max-content drawer shifts the fixed overlay's
       coordinate frame, dragging the whole sidebar content off-screen. The
       safe-area inset keeps the sheet below the status bar / notch. */
    top: calc(env(safe-area-inset-top, 0px) + 12px) !important;
    width: calc(100vw - 16px);
    max-width: calc(100vw - 16px);
    /* Height follows the content (no dead space under a short page); it
       caps at 100dvh-24 (less the safe-area top) and the options area
       scrolls only then. */
    height: auto;
    max-height: min(800px, calc(100vh - 24px - env(safe-area-inset-top, 0px)));
    max-height: min(800px, calc(100dvh - 24px - env(safe-area-inset-top, 0px)));
    flex-direction: column !important;
    border-radius: 14px !important;
    animation: dsh-web-mobile-sheet-in .22s var(--ds-ease-out, ease-in-out);
  }
  /* The settings sheet's dimmed mask fades in with the panel (the mask is
     the first child of the overlay that directly contains the sheet). */
  :has(> [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"]))) > :first-child {
    animation: dsh-web-mobile-fade .18s var(--ds-ease-out, ease-in-out);
  }
  @media (prefers-reduced-motion: reduce) {
    [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])),
    :has(> [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"]))) > :first-child {
      animation: none !important;
    }
  }
  /* The export dialog (not the settings sheet) must never overflow the
     viewport: the official centered card can be wider than 390px. */
  [aria-modal="true"]:not(:has(> :first-child > :last-child > button)) {
    max-width: calc(100vw - 32px);
  }
  /* Nav bar: hide the "Settings" caption (redundant on a full-width sheet)
     and wrap the tab list so every tab is visible — a horizontal scroll cut
     the last tab ("Plugins") off with no affordance to scroll. */
  [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])) > :first-child {
    width: 100%;
    flex-direction: row !important;
    align-items: center;
    gap: 6px;
    padding: 10px 12px 8px;
  }
  [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])) > :first-child > :first-child {
    display: none !important;
  }
  /* The tab list scrolls in the space left by the toolbar: the toolbar
     (config file + close) is reparented INTO this nav row by a client
     reconciler task (settings-toolbar-reparent), so the tab list must be
     anchored by its class, NOT by :last-child (the reparented toolbar
     becomes the nav's new last child). */
  [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])) > :first-child [class*="_navList"] {
    flex: 1 1 auto;
    min-width: 0;
    flex-direction: row !important;
    flex-wrap: wrap;
    gap: 6px;
    overflow: visible;
  }
  /* Content toolbar (Open configuration file + close): grouped flush to
     the right edge, and reparented INTO the nav row on mobile by the
     settings-toolbar-reparent reconciler task, so it shares one line with
     the tabs (user feedback 2026-08-16 — the toolbar's own row left a
     full-width dead gap under the tabs). Children carry official
     auto-margins that would defeat flex-end, so neutralize them. The close
     button gets a round tappable base so it reads as its own control, not
     part of the outline button.
     Anchored structurally, not by class substring: a bare [class*="_header"]
     also matches every plugin settings card header in the options area —
     the official Plugins config cards (YyYd_a_header) and the dsh-web-ui-all
     group cards (Kwoi6G_header / bpnj3G_header / Jh0q7G_header / jmhvDG_header /
     rUBhvW_header, all sharing the upstream template text-align:left,
     gap:12px, padding:14px 16px). The old broad anchor right-aligned their
     text, gutted the padding and painted a 32px gray circle behind the
     chevron (2026-09-05 sweep: 8 bleeding headers). The toolbar has two
     structural homes, both covered below: after the reparent it is a direct
     child of the nav row ([class*="_nav"]); before the reparent runs it is
     the content column's direct child (the panel's :last-child). Card
     headers live deeper — inside the options scroll area — and match
     neither, so no per-plugin hash guards are needed. */
  [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])) > [class*="_nav"] > [class*="_header"]:not([class*="_headerActions"]),
  [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])) > :last-child > [class*="_header"]:not([class*="_headerActions"]) {
    flex: 0 0 auto;
    justify-content: flex-end;
    align-items: center;
    gap: 8px;
    padding: 0 0 0 4px;
    min-height: 40px;
  }
  [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])) > [class*="_nav"] > [class*="_header"]:not([class*="_headerActions"]) > *,
  [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])) > :last-child > [class*="_header"]:not([class*="_headerActions"]) > * {
    margin-left: 0 !important;
    margin-right: 0 !important;
  }
  [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])) > [class*="_nav"] > [class*="_header"]:not([class*="_headerActions"]) > :last-child,
  [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])) > :last-child > [class*="_header"]:not([class*="_headerActions"]) > :last-child {
    width: 32px;
    height: 32px;
    border-radius: 50% !important;
    display: inline-flex !important;
    align-items: center;
    justify-content: center;
    background: var(--dsw-alias-interactive-bg-hover, rgba(0, 0, 0, .06)) !important;
  }
  /* Appearance mode cards: the official cube row renders three tall
     vertical cards (~268px) that eat half the sheet. Turn them into a
     compact horizontal trio (icon + label inline, equal widths).
     Relies on the official cube-row class name of this version. */
  [aria-modal="true"] [class*="_cubeRow"] {
    gap: 6px;
  }
  [aria-modal="true"] [class*="_cubeRow"] > * {
    flex: 1 1 0;
    flex-direction: row !important;
    align-items: center;
    justify-content: center;
    gap: 6px;
    padding: 10px 8px;
    min-height: 0;
  }
  /* Content: the options scroll area gets bottom breathing room so the last
     row never sits flush against the sheet's rounded corner. */
  [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])) > :last-child {
    flex: 1 1 auto;
    min-height: 0;
  }
  [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])) > :last-child > :last-child {
    padding: 0 12px 24px;
  }
}
`;
};
__modules["styles/compat.css.js"] = function (require, module, exports) {
"use strict";
// compat — split from src/client/mobile.css.ts (2026-08-16), order preserved.
// Self-contained: every rule here is mobile-only and the media query opens
// and closes in this file. Concatenation order still matters for the
// cascade (compat intentionally overrides layout), just not for syntax.
Object.defineProperty(exports, "__esModule", { value: true });
exports.COMPAT_CSS = void 0;
exports.COMPAT_CSS = `@media (max-width: 1023px) and (pointer: coarse) {
  /* ---------- dsh-web-ui family compatibility ----------
     The linxin666 plugin suite extends the shell frame directly:
       - aionui-panel appends two trailing grid columns (explorer / preview)
         plus absolute drag handles to [data-dsh-frame]; its 5-track inline
         grid is already overridden above, but the handles and columns would
         still float over the main UI. On mobile the columns leave the grid
         as floating bottom sheets and keep their own visibility state —
         the suite's collapse chevron / preview tabs still work, so no
         feature is lost. The task-board / ssh plugins inject sidebar
         entries and center-column takeover panels; the entries need
         spacing and the kanban needs scrollable columns. */

  /* Touch devices: the drag handles are useless — the floating expand
     button is the opener. */
  .aionui-explorer-handle,
  .aionui-preview-handle {
    display: none !important;
  }

  /* Shared base: both columns leave the grid as floating panels. The
     explorer is gated shut by default (its own persisted expanded state
     must never cover the mobile UI on load); the header Files action opens
     it via the frame marker below, and the sheet's own collapse chevron
     clears it. Preview stays owned by the suite (hidden while no tab is
     open). The per-column rules below override the geometry. */
  [data-aionui-explorer-col],
  [data-aionui-preview-col] {
    position: fixed !important;
    z-index: 55 !important;
    background: var(--aion-bg-base, #ffffff) !important;
    border-left: none !important;
  }
  /* Explorer (file tree) bottom sheet: bottom edge aligned exactly with
     the composer card's bottom line — the card sits 36px above the
     viewport bottom (8px composer padding + the 28px stats strip below
     the card), so the sheet uses the same 36px bottom offset. */
  [data-aionui-explorer-col] {
    visibility: hidden !important;
    left: 8px !important;
    right: 8px !important;
    top: auto !important;
    bottom: 36px !important;
    width: auto !important;
    height: min(55dvh, 460px) !important;
    max-height: calc(100dvh - 44px) !important;
    border-radius: 14px !important;
    overflow: hidden !important;
    box-shadow: 0 -4px 28px rgba(0, 0, 0, .18) !important;
    animation: dsh-web-mobile-sheet-up .24s var(--ds-ease-out, ease-in-out) !important;
  }
  /* Preview (file content) bottom sheet. Gated shut by default: the suite
     persists open preview tabs in localStorage and restores them on load,
     which would pop the sheet over the fresh UI. The client only sets the
     frame marker after the user taps a file row in the explorer; the
     suite's own collapse chevron clears it via the visibility watcher. */
  [data-aionui-preview-col] {
    visibility: hidden !important;
    position: fixed !important;
    left: 8px !important;
    right: 8px !important;
    top: auto !important;
    bottom: 40px !important;
    width: auto !important;
    height: min(50dvh, 420px) !important;
    max-height: calc(100dvh - 48px) !important;
    border-radius: 14px !important;
    overflow: hidden !important;
    box-shadow: 0 -4px 28px rgba(0, 0, 0, .18) !important;
    z-index: 56 !important;
    animation: dsh-web-mobile-sheet-up .24s var(--ds-ease-out, ease-in-out) !important;
    /* Fullscreen toggle (issue #8): animate the geometry change instead of
       snapping. visibility is deliberately not listed, so opening/closing
       the sheet stays instant; the open/close keyframes own transform. */
    transition:
      left .24s var(--ds-ease-out, ease-in-out),
      right .24s var(--ds-ease-out, ease-in-out),
      top .24s var(--ds-ease-out, ease-in-out),
      bottom .24s var(--ds-ease-out, ease-in-out),
      width .24s var(--ds-ease-out, ease-in-out),
      height .24s var(--ds-ease-out, ease-in-out),
      border-radius .24s var(--ds-ease-out, ease-in-out),
      box-shadow .24s var(--ds-ease-out, ease-in-out),
      padding-top .24s var(--ds-ease-out, ease-in-out) !important;
  }
  /* User-opened preview sheet (frame marker, set on file-row tap). */
  [data-mobile-nav="frame"][data-aionui-preview-open] [data-aionui-preview-col] {
    visibility: visible !important;
  }
  /* The Files action opens the explorer sheet (frame marker). */
  [data-mobile-nav="frame"][data-aionui-explorer-open] [data-aionui-explorer-col] {
    visibility: visible !important;
  }
  /* While the preview sheet is up, the explorer sheet yields (two stacked
     bottom sheets would read as one broken overlay). Closing the preview
     via its collapse chevron / tab close clears the marker, and the
     explorer sheet returns. Same specificity as the explorer-open rule, so
     this must stay AFTER it. */
  [data-mobile-nav="frame"][data-aionui-preview-open] [data-aionui-explorer-col] {
    visibility: hidden !important;
  }
  /* The open drawer must never sit under a sheet: while the frame is in the
     narrow-expanded state both sheets yield (later in the file than the
     open marker rule, so it wins at equal specificity). The fullscreen
     toggle has its own drawer-open rule at the end of its section. */
  [data-mobile-nav="frame"]:not([data-sidebar-collapsed]) [data-aionui-explorer-col],
  [data-mobile-nav="frame"]:not([data-sidebar-collapsed]) [data-aionui-preview-col] {
    visibility: hidden !important;
    display: none !important;
  }
  /* The suite's own expand button reads the store state we bypass on
     mobile — hide it; the header Files action is the opener. */
  .aionui-floating-expand {
    display: none !important;
  }

  /* Preview sheet fullscreen toggle (issue #8): a fixed button parked in the
     sheet's titlebar row, just left of the suite's collapse chevron (24px at
     right:8px of the sheet, and the sheet spans 8px..(100vw-8px)). The top
     calc mirrors the sheet geometry above (bottom 40px + min(50dvh, 420px));
     when the frame carries "data-mobile-preview-full" the sheet goes
     fullscreen and the button moves to the viewport corner. */
  [data-mobile-nav="preview-full-toggle"] {
    position: absolute !important;
    right: 36px !important;
    top: 8px !important;
    z-index: 57 !important;
    display: none !important;
    align-items: center;
    justify-content: center;
    width: 20px;
    height: 20px;
    padding: 0;
    border: none;
    border-radius: 4px;
    background: transparent;
    color: var(--aion-text-secondary, var(--dsw-alias-label-secondary, inherit));
    cursor: pointer;
    -webkit-tap-highlight-color: transparent;
    /* Native look: same size/radius/hover language as the suite's tab-bar
       icon buttons (the 20px panelCollapse next to it). The button lives
       INSIDE the preview column, so it rides the sheet's own open
       animation and geometry transition — no curve matching needed. */
    transition: background-color .15s, top .24s var(--ds-ease-out, ease-in-out);
  }
  [data-mobile-nav="preview-full-toggle"]:hover {
    background: var(--aion-bg-3, rgba(0, 0, 0, .22));
  }
  [data-mobile-nav="preview-full-toggle"]:active {
    background: var(--aion-bg-active, rgba(0, 0, 0, .28));
  }
  [data-mobile-nav="preview-full-toggle"]:focus-visible {
    outline: 2px solid var(--dsw-alias-state-business-primary, #4f6ef7);
    outline-offset: 2px;
  }
  [data-mobile-nav="preview-full-toggle"] svg {
    width: 14px;
    height: 14px;
  }
  /* Keep the last tab (and the "+" URL-tab trigger) from sliding under the
     fullscreen toggle: reserve the right end of the preview tab row. */
  [data-aionui-preview-col] [class*="_tabScroll"] {
    padding-right: 34px !important;
  }
  /* Visible only while the preview sheet is open. Visibility itself is
     inherited from the column, so the sheet's own hide rules (collapse,
     drawer open) cover the button too. */
  [data-mobile-nav="frame"][data-aionui-preview-open] [data-aionui-preview-col] [data-mobile-nav="preview-full-toggle"] {
    display: inline-flex !important;
  }
  /* Icon swap on the frame fullscreen marker. */
  [data-mobile-nav="preview-full-toggle"] .dsh-web-mobile-full-out {
    display: none !important;
  }
  [data-mobile-nav="frame"][data-mobile-preview-full] [data-aionui-preview-col] [data-mobile-nav="preview-full-toggle"] .dsh-web-mobile-full-in {
    display: none !important;
  }
  [data-mobile-nav="frame"][data-mobile-preview-full] [data-aionui-preview-col] [data-mobile-nav="preview-full-toggle"] .dsh-web-mobile-full-out {
    display: inline !important;
  }
  /* Fullscreen preview: the sheet fills the whole viewport (notch included);
     the safe-area padding drops the titlebar row below the status bar, and
     the toggle follows the titlebar into the top corner. */
  [data-mobile-nav="frame"][data-aionui-preview-open][data-mobile-preview-full] [data-aionui-preview-col] {
    inset: 0 !important;
    left: 0 !important;
    right: 0 !important;
    top: 0 !important;
    bottom: 0 !important;
    width: 100% !important;
    height: 100dvh !important;
    max-height: none !important;
    box-sizing: border-box !important;
    padding-top: env(safe-area-inset-top, 0px) !important;
    border-radius: 0 !important;
    box-shadow: none !important;
    z-index: 57 !important;
    animation: none !important;
  }
  /* Fullscreen: the column fills the viewport, so the button follows the
     titlebar row down below the notch. */
  [data-mobile-nav="frame"][data-mobile-preview-full] [data-aionui-preview-col] [data-mobile-nav="preview-full-toggle"] {
    top: calc(env(safe-area-inset-top, 0px) + 8px) !important;
  }
  @media (prefers-reduced-motion: reduce) {
    [data-aionui-preview-col],
    [data-mobile-nav="preview-full-toggle"] {
      transition: none !important;
      animation: none !important;
    }
  }

  /* dsh-web-ui sidebar entries (task board / ssh) sit flush against each
     other — give the injected rows breathing room. */
  button[data-dsh-taskboard-entry],
  button[data-dsh-ssh-entry] {
    margin-bottom: 8px !important;
  }

  /* Task board: five kanban columns at minmax(0,1fr) crush into ~78px phone
     strips. Give every column a usable minimum and let the row scroll. */
  [data-dsh-taskboard-board] > [class*="_columns"] {
    grid-template-columns: repeat(5, minmax(240px, 1fr)) !important;
    overflow-x: auto !important;
  }
  /* The floating button must not float over a takeover panel (task board /
     ssh own the center column while active). */
  html[data-dsh-taskboard-active] [data-mobile-nav="fab"],
  html[data-dsh-ssh-active] [data-mobile-nav="fab"],
  html[data-dsh-taskboard-active] [data-mobile-nav="backdrop"],
  html[data-dsh-ssh-active] [data-mobile-nav="backdrop"] {
    display: none !important;
  }
  /* Board header: let the search field take the slack instead of squeezing
     the action buttons. */
  [data-dsh-taskboard-board] > [class*="_boardHeader"] [class*="_search"] {
    flex: 1 1 auto !important;
    min-width: 80px !important;
  }

  /* ---------- dsh-web-ui polish: plugin market search ----------
     The market tab row (Discover / Themes / Installed + the plugin search
     box) is a no-wrap flex: at 390px the tabs plus the ~218px search box
     (~475px total) overflow the ~334px sheet and the search box runs off
     the right edge of the screen (it also forces a horizontal scrollbar on
     the sheet's options area). Let the row wrap: the tabs keep the first
     line and the search box gets its own full-width second line. */

  [aria-modal="true"] [class*="_tabs"] {
    flex-wrap: wrap !important;
    row-gap: 8px !important;
  }
  [aria-modal="true"] [class*="_searchInline"] {
    flex: 1 1 100% !important;
    width: 100% !important;
    max-width: 100% !important;
  }
  /* iOS Safari auto-zooms a focused input whose computed font-size is below
     16px. dshmarket's tab search uses the shared primitive Input at 13px;
     raise only this market-owned field on mobile so focusing it keeps the
     current viewport scale. Scoped to the market root to avoid changing
     unrelated settings/search fields; pinch zoom stays available. */
  [data-dsh-market-root] [class*="tabSearch"] input,
  [data-dsh-market-root] input[class*="tabSearch"] {
    font-size: 16px !important;
  }

  /* ---------- dshmarket polish: Tasks operations popup ----------
     Upstream .opPanel is a small dropdown pinned to the right edge of its
     ~54px trigger button; on a phone it reads as stuck to the sheet edge
     instead of centered. Promote it to a fixed, viewport-centered card:
     no ancestor between the popup and the viewport carries a transform,
     so position:fixed centers against the real viewport (a plain left:50%
     would resolve against the tiny relative trigger wrapper and land even
     further right). The upstream 86vw width cap, 70vh max-height and
     internal scroll all still apply; the close button stays inside. */
  [data-mobile-nav="frame"] [aria-modal="true"] [class*="_opPanel"] {
    position: fixed !important;
    top: 50% !important;
    bottom: auto !important;
    left: 50% !important;
    right: auto !important;
    transform: translate(-50%, -50%) !important;
  }

  /* ---------- dshmarket polish: header title row ----------
     The title row (icon + title + repo link + version + optional
     "Update market" / "Update all" buttons) is a nowrap flex whose
     natural width (~450px with both update buttons) exceeds the ~334px
     sheet. Flex then crushes the flexible items below their content
     width and every label wraps word-by-word — the "text turns
     vertical" report. Trigger is state-dependent (the buttons only
     exist while plugin updates are pending), which explains the
     sometimes-horizontal/sometimes-vertical flapping. Let the row wrap
     instead: line 1 keeps icon + title + repo + version, the update
     buttons get their own full-width-feeling second line, and the title
     itself is locked to one ellipsized line no matter what follows it. */
  [data-mobile-nav="frame"] [aria-modal="true"] [class*="_titleRow"] {
    flex-wrap: wrap !important;
    row-gap: 6px !important;
  }
  [data-mobile-nav="frame"] [aria-modal="true"] [class*="_titleRow"] [class*="_title"] {
    flex: 1 1 auto !important;
    min-width: 0 !important;
    white-space: nowrap !important;
    overflow: hidden !important;
    text-overflow: ellipsis !important;
  }
  [data-mobile-nav="frame"] [aria-modal="true"] [class*="_titleRow"] button {
    white-space: nowrap !important;
  }

  /* ---------- dshmarket 1.20+ compat: keep the settings nav visible ----------
     Upstream Market.module.css hides the host dialog's nav on phones
     ([role=dialog]:has([data-dsh-market-root]) > nav { display:none } at
     max-width:560px) so the market can take over the dialog; its comment
     assumes the host keeps "its own close button in the content header".
     Our host's only close ✕ lives inside that very nav, so the market
     would leave no categories and no way back or out (dead-end UI,
     2026-08-23). Mirror upstream's exact media condition and restore the
     nav: categories row + ✕ stay above the inline market page. */
  @media (max-width: 560px) {
    [data-mobile-nav="frame"] [role="dialog"]:has([data-dsh-market-root]) > nav {
      display: flex !important;
    }
  }

  /* ---------- dsh-usage-stats polish: usage & balance panel ----------
     The panel's stats row shows three token counters side by side
     (today / month / total). The counters use tabular nowrap figures whose
     min-content width overflows the ~336px panel body on a phone: figures
     clip at the row's edges and the panel grows a horizontal scrollbar.
     Stack the three counters vertically — full-width rows, so the figures
     always fit. */

  [class*="usg_"][class*="_statsRow"] {
    flex-direction: column !important;
  }
  [class*="usg_"][class*="_stat"]:not([class*="_statsRow"]) {
    flex: 0 0 auto !important;
    width: 100% !important;
    min-width: 0 !important;
  }

  /* ---------- dsh-web-ui polish: settings sheet ----------
     The official dialog is a desktop two-column form; on a phone the
     label/control split leaves a huge dead gap and long descriptions wrap
     into tall stacks. Stack each row (text above, control full-width) and
     keep the nav tabs on ONE horizontally scrolling row. */

  /* Nav tabs: single scrolling row instead of the 3-per-row grid — seven
     categories wrap into three rows on a phone (~130px of sheet height);
     one row with a thin scrollbar keeps every tab reachable and returns
     that space to the options area (user feedback 2026-08-16). An earlier
     one-row attempt had no scroll affordance and silently cut the last
     tab off; the thin scrollbar IS the affordance. Scoped to the frame
     marker: the desktop dialog keeps its official vertical nav column. */
  [data-mobile-nav="frame"] [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])) > :first-child [class*="_navList"] {
    display: flex !important;
    flex-wrap: nowrap !important;
    overflow-x: auto !important;
    overflow-y: hidden !important;
    gap: 6px !important;
    width: 100% !important;
    scrollbar-width: thin !important;
    -webkit-overflow-scrolling: touch !important;
  }
  /* Hairline scrollbar for the tab row: the default WebKit scrollbar reads
     fat on a phone; 2px keeps the scroll affordance without the bulk. */
  [data-mobile-nav="frame"] [aria-modal="true"] [class*="_navList"]::-webkit-scrollbar {
    height: 2px !important;
  }
  [data-mobile-nav="frame"] [aria-modal="true"] [class*="_navList"]::-webkit-scrollbar-thumb {
    background: var(--dsw-alias-border-l2, rgba(0, 0, 0, .22)) !important;
    border-radius: 1px !important;
  }
  [data-mobile-nav="frame"] [aria-modal="true"] [class*="_navList"]::-webkit-scrollbar-track {
    background: transparent !important;
  }
  [data-mobile-nav="frame"] [aria-modal="true"] [class*="_navCell"] {
    flex: 0 0 auto !important;
    white-space: nowrap !important;
    padding: 6px 8px !important;
    gap: 6px !important;
    font-size: 13px !important;
    justify-content: flex-start !important;
  }
  [data-mobile-nav="frame"] [aria-modal="true"] [class*="_navCell"] svg {
    width: 14px !important;
    height: 14px !important;
    flex: none !important;
  }
  /* Content toolbar: the "Open configuration file" button is hidden on
     mobile — it is rarely needed on a phone and steals ~180px from the
     tab row's scroll area (user feedback 2026-08-16). Only the close ✕
     stays, flush right in the nav row. Desktop untouched (frame scoped). */
  [data-mobile-nav="frame"] [aria-modal="true"] [class*="_header"]:not([class*="_headerActions"]) [class*="_actions"] {
    display: none !important;
  }
  [data-mobile-nav="frame"] [aria-modal="true"] [class*="_header"]:not([class*="_headerActions"]) [class*="_actions"] [class*="_action"]:not([class*="_actions"]) {
    font-size: 13px !important;
    padding: 6px 12px !important;
    min-height: 0 !important;
  }
  /* Setting rows: text on top, control below at full width. Compound
     "_row*" families are excluded: the Models page names its whole card
     list "_rows" (plus "_rowCard/_rowHead/_rowIdentity/_rowActions"), and
     the bare-substring match used to hand the list's first/last cards a
     width:100% that - on the official content-box cards (+14px padding,
     1px border) - ran 30px past their siblings and off-screen. */
  [aria-modal="true"] [class*="_section"] [class*="_row"]:not([class*="_rows"]):not([class*="_rowCard"]):not([class*="_rowHead"]):not([class*="_rowIdentity"]):not([class*="_rowActions"]) {
    flex-direction: column !important;
    align-items: stretch !important;
    gap: 8px !important;
  }
  [aria-modal="true"] [class*="_section"] [class*="_row"]:not([class*="_rows"]):not([class*="_rowCard"]):not([class*="_rowHead"]):not([class*="_rowIdentity"]):not([class*="_rowActions"]) > :first-child {
    width: 100% !important;
    max-width: none !important;
  }
  [aria-modal="true"] [class*="_section"] [class*="_row"]:not([class*="_rows"]):not([class*="_rowCard"]):not([class*="_rowHead"]):not([class*="_rowIdentity"]):not([class*="_rowActions"]) > :last-child {
    width: 100% !important;
    max-width: none !important;
  }
  /* Appearance mode group: give the cube row a consistent bordered
     segmented look (the official borders differ per state). */
  [aria-modal="true"] [class*="_cubeRow"] > * {
    border: 1px solid var(--dsw-alias-border-l1, rgba(0, 0, 0, .12)) !important;
  }

  /* ---------- dsh-web-ui polish: explorer sheet ----------
     The aionui explorer was designed for a desktop side column: compact the
     header, search box and tree rows so a phone shows more entries, and pad
     the scroll bottom so the last row never sits flush on the edge. */

  [data-aionui-explorer-col] [class*="_tabBar"]:not([class*="_tabBarRight"]) {
    height: 36px !important;
  }
  [data-aionui-explorer-col] [class*="_tabBtn"],
  [data-aionui-explorer-col] [class*="_tabBtnActive"] {
    padding: 0 12px !important;
    font-size: 13px !important;
  }
  [data-aionui-explorer-col] [class*="_searchBox"] {
    height: 32px !important;
    font-size: 13px !important;
  }
  [data-aionui-explorer-col] [class*="_treeRow"] {
    height: 30px !important;
    font-size: 13px !important;
  }
  [data-aionui-explorer-col] [class*="_treeRow"] svg {
    width: 14px !important;
    height: 14px !important;
  }
  [data-aionui-explorer-col] [class*="_scrollArea"] {
    padding-bottom: 28px !important;
  }

  /* ---------- dsh-web-ui polish: drawer footer ----------
     The injected footer actions (Files + Session log) become two equal pill
     buttons instead of text-width capsules. */

  /* The official footerActions row also hosts the remote-web-ui entry
     row (two icon buttons); without wrapping the two groups squeeze each
     other on one line. Wrap so each group gets its own full-width row. */
  [data-mobile-nav="frame"] [class*="_footerActions"] {
    flex-wrap: wrap !important;
    gap: 6px !important;
  }
  [data-mobile-nav="drawer-actions"] {
    width: 100% !important;
  }
  [data-mobile-nav="drawer-actions"] > button {
    flex: 1 1 0 !important;
    padding: 0 8px !important;
    white-space: nowrap !important;
  }

  /* ---------- dsh-web-ui polish: floating pet ----------
     The whale-girl pet (dsh-pet) floats at the viewport corner with a
     persisted, draggable position. On phones the pet is scaled down so
     it does not dominate the screen; the plugin's own drag + persist
     still work (the position itself is left alone — the mobile default
     position is seeded via the pet API to just above the composer). */

  body > [class*="_float"]:has([class*="_sprite"][role="button"]) {
    transform: scale(.66);
    transform-origin: bottom right;
  }
  /* While a modal dialog (settings sheet / export) owns the screen the pet
     floats ABOVE it and covers the dialog content; modal semantics say the
     background is inert, so hide the pet for the modal's lifetime. */
  body:has([aria-modal="true"]) > [class*="_float"]:has([class*="_sprite"][role="button"]) {
    display: none !important;
  }

  /* ---------- dsh-web-ui polish: conversation stats line ----------
     The official session-status row (turns / steps / LLM time / TTFT /
     cache) is long. The client marks the exact row with
     [data-mobile-nav="stats"] (text-anchored, hashed classes can't be
     targeted). Layout: ONE fixed-height (28px) flex strip that scrolls
     horizontally — the full metrics stream stays reachable by swiping,
     the row never grows vertically, no ellipsis or fade, 12px gaps
     between metric groups, a 2px scrollbar as the swipe affordance. */

  [data-mobile-nav="stats"] {
    display: flex !important;
    flex-flow: row nowrap !important;
    align-items: center !important;
    width: 100% !important;
    max-width: 100% !important;
    min-width: 0 !important;
    height: 28px !important;
    min-height: 28px !important;
    max-height: 28px !important;
    box-sizing: border-box !important;
    white-space: nowrap !important;
    overflow-x: auto !important;
    overflow-y: hidden !important;
    -webkit-overflow-scrolling: touch;
    overscroll-behavior-x: contain;
    scrollbar-width: thin !important;
    scrollbar-color: var(--dsw-alias-border-l1, rgba(0, 0, 0, .28)) transparent !important;
    padding: 0 0 4px !important;
    line-height: 20px !important;
    font-size: 12px !important;
  }
  [data-mobile-nav="stats"]::-webkit-scrollbar {
    height: 2px !important;
  }
  [data-mobile-nav="stats"]::-webkit-scrollbar-thumb {
    background: var(--dsw-alias-label-tertiary, rgba(0, 0, 0, .3)) !important;
    border-radius: 2px !important;
  }
  [data-mobile-nav="stats"]::-webkit-scrollbar-track {
    background: transparent !important;
  }
  [data-mobile-nav="stats"] > * {
    display: flex !important;
    flex: 0 0 auto !important;
    flex-flow: row nowrap !important;
    align-items: center !important;
    width: max-content !important;
    min-width: max-content !important;
    max-width: none !important;
    white-space: nowrap !important;
    margin-right: 12px !important;
    padding: 0 !important;
  }
  [data-mobile-nav="stats"] > *:last-child {
    margin-right: 0 !important;
  }
  [data-mobile-nav="stats"] * {
    white-space: nowrap !important;
  }

  /* ---------- dsh-genui panel dock ----------
     The genui panel docks above the composer (conversation.input.dock,
     id genui-panel). On a phone its business-blue outline, generous chrome
     and single-line ellipsis read as an unfinished artifact: long titles
     truncate mid-word ("…default b···") with the chevron glued to the
     ellipsis, and the pill crowds the composer. Mobile treatment: neutral
     card border matching the composer, tighter chrome so the full title
     fits, chevron with breathing room. Scoped to the mobile frame marker —
     desktop keeps genui's own styling untouched. */

  [data-mobile-nav="frame"] [data-genui-panel] {
    margin: 6px 12px 4px !important;
    border-color: var(--dsw-alias-border-l1, rgba(0, 0, 0, .12)) !important;
    border-radius: 12px !important;
  }
  [data-mobile-nav="frame"] [data-genui-panel] [class*="_panelToggle"] {
    padding: 7px 12px !important;
    gap: 8px !important;
  }
  [data-mobile-nav="frame"] [data-genui-panel] [class*="_panelBadge"] {
    padding: 0 7px !important;
    border-radius: 5px !important;
    font-size: 10.5px !important;
    line-height: 1.7 !important;
  }
  [data-mobile-nav="frame"] [data-genui-panel] [class*="_panelTitle"] {
    flex: 1 1 auto !important;
    min-width: 0 !important;
    font-size: 12.5px !important;
    line-height: 1.45 !important;
  }
  [data-mobile-nav="frame"] [data-genui-panel] [class*="_panelChevron"] {
    flex: none !important;
    margin-left: 0 !important;
    padding-left: 4px !important;
  }

  /* ---------- git-graph branch chip: inside the composer card ----------
     The branch chip (conversation.input.dock) floats between the dock rows
     and the input card; on a phone it reads as a stray capsule crowding the
     composer. A client reconciler task (git-chip-reparent) reparents the
     chip INTO the composer card; these rules pin it to the card's top-left
     and give the card a dedicated chip row. The card is position: relative
     by the official stylesheet, so the absolute anchor resolves against it.
     The plugin's own sheet sets all four offsets on the anchor, so
     right/bottom must be neutralized too. Scope is the frame marker + the
     anchor attribute (NOT the dock slot — the reparenting moves the chip
     out of the dock's subtree). Desktop untouched: the frame marker only
     exists below 1024px, and the effect restores the chip to the dock when
     the viewport widens. Chip row geometry (2026-08-16, user feedback):
     48px padding left a 16px dead gap between the chip and the input line
     and made the composer read too tall; the row was tuned to 40px = chip
     (24px) at top 12px + ~4px to the textarea. The chip itself has since
     grown to 28px (git-graph chip CSS), which ate the breathing gap, so the
     row is 44px to keep the same ~4px clearance (2026-09-06). */

  [data-mobile-nav="frame"] [data-gitgraph-chip-anchor] {
    position: absolute !important;
    top: 12px !important;
    left: 12px !important;
    right: auto !important;
    bottom: auto !important;
    z-index: 1 !important;
  }
  [data-mobile-nav="frame"] [class*="_card"]:has([data-gitgraph-chip-anchor]) {
    padding-top: 44px !important;
  }
  /* Kill double-tap zoom on the chip wherever it lives (the tap-target trio
     in misc.css is scoped to the dock slot and dies once the reparent moves
     the anchor into the card). Geometry-free: touch-action only. */
  [data-mobile-nav="frame"] [data-gitgraph-chip-anchor] [data-gitgraph-chip] {
    touch-action: manipulation !important;
  }

  /* ---------- dsh-meme 表情选择卡片：右缘安全距离 ----------
     The meme picker (conversation.input.overlay, id meme-picker) is
     absolutely positioned left:0 inside the composer's overlay anchor with
     width:min(360px,90vw). That 90vw resolves against the VIEWPORT, not the
     anchor, and with the picker's own padding+border the border-box
     (377px on a 390px phone) exceeds the 356px anchor — the card's right
     edge then runs past the anchor and off the right screen edge, while the
     left edge keeps the anchor's 17px safe inset. Stretch the card to the
     anchor on both sides (left/right 0, width auto, border-box) so the
     right gap mirrors the left; cap at the card's original border-box size
     (360px content + 24px padding + 2px border) so tablets keep the
     intended card width instead of stretching. Desktop is untouched: the
     frame marker only exists below 1024px. */
  [data-mobile-nav="frame"] .meme-picker {
    left: 0 !important;
    right: 0 !important;
    width: auto !important;
    box-sizing: border-box !important;
    max-width: 386px !important;
  }

  /* dsh-meme 网格缩略图：自适应铺满卡片,保留 8px 间隙。
     dsh-meme 的 .mp-grid 是 flex-wrap + 固定 76px 的 .mp-cell(行内 style 再压到 74px):
     3 列(390px 手机)时每行右侧剩 ~78px 空白,卡片没有铺满。换成响应式 grid:
     repeat(auto-fill, minmax(64px,1fr)) 让列数随可用宽度伸缩、卡片 width:100% +
     aspect-ratio:1 随轨道自适应(方形,cover 裁切不变),gap 仍是 dsh-meme 的 8px。
     行内 width/height 用 !important 覆盖;手机端约 4 列、平板端约 5 列,均满宽。 */
  [data-mobile-nav="frame"] .meme-picker .mp-grid {
    display: grid !important;
    grid-template-columns: repeat(auto-fill, minmax(64px, 1fr)) !important;
    scrollbar-width: thin !important;
    scrollbar-color: var(--dsw-alias-label-tertiary, rgba(0, 0, 0, .3)) transparent !important;
  }
  [data-mobile-nav="frame"] .meme-picker .mp-cell {
    width: 100% !important;
    height: auto !important;
    aspect-ratio: 1 !important;
  }
  /* dsh-meme 网格右侧滚动条：默认 WebKit 滚动条在手机上看太粗,压成 4px
     细条——保留滚动指示又不占横向空间,thumb 圆角浅色、轨道透明。 */
  [data-mobile-nav="frame"] .meme-picker .mp-grid::-webkit-scrollbar {
    width: 4px !important;
  }
  [data-mobile-nav="frame"] .meme-picker .mp-grid::-webkit-scrollbar-thumb {
    background: var(--dsw-alias-label-tertiary, rgba(0, 0, 0, .3)) !important;
    border-radius: 999px !important;
  }
  [data-mobile-nav="frame"] .meme-picker .mp-grid::-webkit-scrollbar-track {
    background: transparent !important;
  }

  /* ---------- agent preset 模式选择菜单：手机端紧凑底部弹层 ----------
     The official agent-preset menu (role=menu, portal mounted on body) uses
     position:fixed + max-height:820px + bottom:12px, so on a phone it
     stretches from the trigger down to 12px above the screen bottom —
     effectively filling the screen. Turn it into a polished bottom sheet:
     cap the height, center it horizontally (the official max-width 360px
     left-anchors at left:12px, leaving 12/18px asymmetric gaps), add a
     drag-handle affordance, breathing room, and softer top radius; the
     inner viewport keeps scrolling. Scoped to the agent-preset item class
     (cubgiG_*) so other role=menu dropdowns (model/access mode) are
     untouched. Desktop ≥1024px is outside the media query, so it keeps the
     official large dropdown. */
  /* agent-preset 菜单依赖 @deepseek-ai/dsh-client-ui-agent-preset 的 CSS Module 哈希 (cubgiG_*)，升级该包时需验证此选择器是否仍有效 */
  [role="menu"]:has([class*="cubgiG_item"]) {
    top: auto !important;
    left: 50% !important;
    right: auto !important;
    bottom: 12px !important;
    transform: translateX(-50%) !important;
    width: min(100% - 24px, 360px) !important;
    max-width: 360px !important;
    max-height: min(55dvh, 440px) !important;
    padding: 30px 6px 10px !important;
    border-radius: 16px !important;
  }
  [role="menu"]:has([class*="cubgiG_item"])::before {
    content: '';
    position: absolute;
    top: 10px;
    left: 50%;
    transform: translateX(-50%);
    width: 36px;
    height: 4px;
    border-radius: 999px;
    background: var(--dsw-alias-border-l2, rgba(0, 0, 0, .22)) !important;
    pointer-events: none;
  }
  /* 菜单内部滚动条：默认 WebKit 滚动条在竖屏太粗,会占 ~15px 宽度把文字描述
     挤窄,导致描述换行/截断不自然。压成 4px 细条(与表情网格一致),文字区域
     恢复自适应宽度。 */
  [role="menu"]:has([class*="cubgiG_item"]) [class*="_viewport_"] {
    scrollbar-width: thin !important;
    scrollbar-color: var(--dsw-alias-label-tertiary, rgba(0, 0, 0, .3)) transparent !important;
  }
  [role="menu"]:has([class*="cubgiG_item"]) [class*="_viewport_"]::-webkit-scrollbar {
    width: 4px !important;
  }
  [role="menu"]:has([class*="cubgiG_item"]) [class*="_viewport_"]::-webkit-scrollbar-thumb {
    background: var(--dsw-alias-label-tertiary, rgba(0, 0, 0, .3)) !important;
    border-radius: 999px !important;
  }
  [role="menu"]:has([class*="cubgiG_item"]) [class*="_viewport_"]::-webkit-scrollbar-track {
    background: transparent !important;
  }

/* 搜索框底部间距修复 */
[aria-modal="true"] [class*="tabSearchRow"] {
  padding: 2px 4px 16px !important;
}


/* ===== 已安装列表：路径单行截断 ===== */
[class*="irow"]:not([class*="irowActions"]):not([class*="irowTrailing"]) > div > [class*="spec"] {
  white-space: nowrap !important;
  overflow: hidden !important;
  text-overflow: ellipsis !important;
  max-width: 100% !important;
  font-size: 12px !important;
}
[class*="irow"]:not([class*="irowActions"]):not([class*="irowTrailing"]) > div > [class*="nm"] {
  white-space: nowrap !important;
  overflow: hidden !important;
  text-overflow: ellipsis !important;
  max-width: 100% !important;
}
/* ===== 已安装列表：手机端纵向重排 ===== */
@media (max-width: 1023px) and (pointer: coarse) {
  [class*="irow"]:not([class*="irowActions"]):not([class*="irowTrailing"]) {
    flex-wrap: wrap !important;
    align-items: center !important;
    gap: 4px 10px !important;
  }
  [class*="irow"]:not([class*="irowActions"]):not([class*="irowTrailing"]) > div:first-child {
    flex: 1 1 100% !important;
    max-width: 100% !important;
    min-width: 0 !important;
  }
  [class*="irow"]:not([class*="irowActions"]):not([class*="irowTrailing"]) > [class*="grow"] {
    flex: 1 1 auto !important;
  }
  [class*="irow"]:not([class*="irowActions"]):not([class*="irowTrailing"]) > button {
    flex: 0 0 auto !important;
  }
  [class*="irow"]:not([class*="irowActions"]):not([class*="irowTrailing"]) > button[class*="switch"] {
    order: 3 !important;
  }
  [class*="irow"]:not([class*="irowActions"]):not([class*="irowTrailing"]) > button:not([class*="switch"]) {
    order: 2 !important;
  }
  [class*="irow"]:not([class*="irowActions"]):not([class*="irowTrailing"]) > [class*="owner"] {
    order: 1 !important;
  }
  [class*="irow"]:not([class*="irowActions"]):not([class*="irowTrailing"]) > [class*="grow"] {
    order: 0 !important;
  }
}
/* ===== 市场卡片图片容器：横向滚动 ===== */
[data-mobile-nav="frame"] [class*="cardShots"] {
  display: flex !important;
  flex-wrap: nowrap !important;
  overflow-x: auto !important;
  -webkit-overflow-scrolling: touch !important;
  scrollbar-width: thin !important;
  min-width: 0 !important;
  width: 100% !important;
  max-width: 100% !important;
  gap: 8px !important;
  padding: 4px 0 !important;
}
[data-mobile-nav="frame"] [class*="cardShots"] > [class*="cardShot"] {
  flex: 0 0 min(100%, 420px) !important;
  width: min(100%, 420px) !important;
  max-width: 100% !important;
  height: auto !important;
  display: block !important;
  object-fit: contain !important;
}
[data-mobile-nav="frame"] [class*="cardShots"]::-webkit-scrollbar {
  height: 4px !important;
}
[data-mobile-nav="frame"] [class*="cardShots"]::-webkit-scrollbar-thumb {
  background: var(--ds-border-color, #ccc) !important;
  border-radius: 4px !important;
}

  /* ---------- dsh-file-viewer (conversation.view tab「文件查看器」) ----------
     The plugin ships NO responsive CSS: its min-width:0 flex panels overflow
     on a phone — the titlebar caps the path at 520px beside a 5-button action
     row, and CSV/code headers row-stick inside content scrollers. It renders
     inline into the conversation view region (stable 'dsfv-*' prefix, injected
     <style>), not a modal sheet, so the fixes here are: stop the PANEL from
     scrolling horizontally (leave horizontal scrolling inside the content
     scrollers), compress the titlebar/statusbar, enlarge touch targets, and
     scope everything under [data-file-viewer-open] so only the active
     file-viewer tab is affected. The marker is owned by the
     file-viewer-open-marker reconciler task; nothing leaks to desktop because
     this whole block lives inside the mobile media query.
     (Port of community fork fix 2ff7976.) */

  [data-mobile-nav="frame"][data-file-viewer-open] .dsfv-panel {
    min-width: 0 !important;
    max-width: 100% !important;
    overflow-x: hidden !important;
  }
  /* Titlebar: single compact row; path truncates, secondary meta hides on
     narrow, the 5-button action row wraps to two rows of tall targets. */
  [data-mobile-nav="frame"][data-file-viewer-open] .dsfv-titlebar {
    gap: 4px !important;
    padding: 6px 8px !important;
    flex-wrap: nowrap !important;
    min-width: 0 !important;
  }
  [data-mobile-nav="frame"][data-file-viewer-open] .dsfv-titlebar-path {
    min-width: 0 !important;
    padding-right: 4px !important;
  }
  [data-mobile-nav="frame"][data-file-viewer-open] .dsfv-path {
    font-size: 13px !important;
    min-width: 0 !important;
    max-width: 220px !important;
  }
  [data-mobile-nav="frame"][data-file-viewer-open] .dsfv-titlebar-actions {
    flex-wrap: wrap !important;
    gap: 4px !important;
    justify-content: flex-end !important;
    margin-left: auto !important;
  }
  [data-mobile-nav="frame"][data-file-viewer-open] .dsfv-toolbar-btn {
    min-height: 34px !important;
    padding: 0 10px !important;
    font-size: 13px !important;
  }
  [data-mobile-nav="frame"][data-file-viewer-open] .dsfv-icon-btn,
  [data-mobile-nav="frame"][data-file-viewer-open] .dsfv-back-btn {
    min-height: 34px !important;
    min-width: 34px !important;
  }
  @media (max-width: 480px) {
    [data-mobile-nav="frame"][data-file-viewer-open] .dsfv-meta {
      display: none !important;
    }
  }
  /* Status bar: wrap, safe-area bottom padding, compact. */
  [data-mobile-nav="frame"][data-file-viewer-open] .dsfv-statusbar {
    flex-wrap: wrap !important;
    gap: 4px 10px !important;
    padding: 4px 8px calc(4px + env(safe-area-inset-bottom, 0px)) !important;
    font-size: 12px !important;
  }
  /* Content scrollers must own horizontal scrolling; the flex columns and the
     renderer stack must not let content push the panel wide. */
  [data-mobile-nav="frame"][data-file-viewer-open] .dsfv-renderer,
  [data-mobile-nav="frame"][data-file-viewer-open] .dsfv-renderer-stack,
  [data-mobile-nav="frame"][data-file-viewer-open] .dsfv-scroll,
  [data-mobile-nav="frame"][data-file-viewer-open] .dsfv-csv-scroll,
  [data-mobile-nav="frame"][data-file-viewer-open] .dsfv-code-body {
    min-width: 0 !important;
    max-width: 100% !important;
  }
  [data-mobile-nav="frame"][data-file-viewer-open] .dsfv-scroll,
  [data-mobile-nav="frame"][data-file-viewer-open] .dsfv-csv-scroll {
    overflow-x: auto !important;
    -webkit-overflow-scrolling: touch !important;
  }
  /* Browser / subtoolbar rows wrap; file rows get touch-friendly height. */
  [data-mobile-nav="frame"][data-file-viewer-open] .dsfv-browser-nav,
  [data-mobile-nav="frame"][data-file-viewer-open] .dsfv-subtoolbar {
    flex-wrap: wrap !important;
    gap: 6px !important;
    padding: 4px 8px !important;
  }
  [data-mobile-nav="frame"][data-file-viewer-open] .dsfv-file-row {
    min-height: 44px !important;
    padding: 8px 10px !important;
  }
  [data-mobile-nav="frame"][data-file-viewer-open] .dsfv-file-list [class*="name"] {
    min-width: 0 !important;
  }
  /* Produced-file chips render in the conversation tail, outside the viewer
     tab — keep tappable but not scoped to the marker. */
  [data-mobile-nav="frame"] .dsfv-produced-chip,
  [data-mobile-nav="frame"] .dsfv-produced-folder {
    min-height: 40px !important;
    padding: 0 12px !important;
  }
  @media (prefers-reduced-motion: reduce) {
    [data-mobile-nav="frame"][data-file-viewer-open] [class*="dsfv-"] {
      transition: none !important;
      animation: none !important;
    }
  }
}

`;
};
__modules["styles/misc.css.js"] = function (require, module, exports) {
"use strict";
// misc — split from src/client/mobile.css.ts (2026-08-16), order preserved.
// Self-contained: each section (composer / tablet / desktop) carries its own
// media query.
Object.defineProperty(exports, "__esModule", { value: true });
exports.MISC_CSS = void 0;
exports.MISC_CSS = `@media (max-width: 1023px) and (pointer: coarse) {
  /* ---------- hero composer on mobile ----------
     The official hero card carries a 2-line textarea plus a tall tool row,
     which reads oversized on a phone. Tighten the empty-state rhythm: keep
     the official centered hero, shrink the textarea line box, slim the card
     padding and the tool row, and close the gap under the headline. */

  [data-phase="hero"] [class*="_card"]:has(textarea, [data-composer-input]) {
    gap: 8px !important;
  }
  /* Cards carrying the reparented git branch chip must keep compat.css's
     44px chip clearance: that rule sets padding-top: 44px on any card that
     contains the absolutely-positioned chip anchor (top 12px + 28px chip —
     the chip grew 24→28px, so the clearance grew 40→44px to keep the same
     ~4px breathing gap). This compact override used to stomp it back to 6px
     with the same specificity (this sheet loads after compat), so on the
     hero empty state the chip painted over the input line (2026-09-06).
     Excluding chip-bearing cards restores the clearance; the textarea
     collapse below still applies to them. */
  [data-phase="hero"] [class*="_card"]:has(textarea, [data-composer-input]):not(:has([data-gitgraph-chip-anchor])) {
    padding-top: 6px !important;
  }
  /* The official composer autosizes the textarea and writes an inline
     height (2 lines on the hero empty state) on the textarea's scroll/grow
     wrappers. :placeholder-shown lets us collapse the EMPTY state to one
     line with !important; as soon as the user types, the pseudo-class no
     longer matches and the autosizer's inline height takes over again — so
     multi-line growth keeps working. */
  [data-phase="hero"] textarea:placeholder-shown {
    height: 28px !important;
  }
  [data-phase="hero"] [class*="_card"]:has(textarea:placeholder-shown) > [class*="_scroll"],
  [data-phase="hero"] [class*="_card"]:has(textarea:placeholder-shown) [class*="_grow"] {
    height: 28px !important;
  }
  /* DSH 0.1.2: the input is a Lexical contentEditable (no textarea) and the
     empty state is signalled by the separate [data-composer-placeholder]
     node, so mirror the one-line collapse for that shape. */
  [data-phase="hero"] [class*="_card"]:has([data-composer-placeholder]) [data-composer-input] {
    height: 28px !important;
  }
  [data-phase="hero"] [class*="_card"]:has([data-composer-placeholder]) > [class*="_scroll"],
  [data-phase="hero"] [class*="_card"]:has([data-composer-placeholder]) [class*="_grow"] {
    height: 28px !important;
  }
  [data-phase="hero"] [class*="_card"]:has(textarea, [data-composer-input]) > [class*="_row"] {
    padding-top: 2px !important;
  }
  [data-phase="hero"] [class*="_headline"] {
    line-height: 1.15 !important;
    margin-bottom: 0 !important;
  }
  [data-phase="hero"] [class*="_stack"] {
    gap: 0 !important;
  }

  /* ---------- composer dock: swap git branch chip with the todo card ----------
     The git-graph branch chip (conversation.input.dock, order 100) floats
     alone at the bottom-left above the input card, with a dead zone to its
     right; the full-width todo card (order 0) sits above it. Swap them so
     the chip reads as the stack's top row and the todo card fills the row
     above the composer. The dock container itself is display:contents
     (inline style) — its children are direct flex items of the composer
     stack, so order on the children is what reorders them. Only the chip
     needs an order change: -1 puts it before the todo card (order 0) and
     before the input card (order 0, later in DOM). The todo card must KEEP
     its order 0 — raising it past the input card's order 0 would drop it
     below the composer entirely (2026-08-16 regression, fixed). The queue
     strip (order 20) keeps hugging the input card. Desktop untouched (this
     block lives inside the max-width: 1023px media query). */
  [data-slot="conversation.input.dock"] [data-gitgraph-chip-anchor] {
    order: -1 !important;
  }
  /* Mobile tap target + feedback for the branch chip (git-graph, 24px
     desktop spec). Two real-world problems: ① the chip is tiny and sits
     right above the expandable todo card — mis-taps land on the todo card;
     ② opening the popover waits for the host's /git/branches round-trip
     (~700ms on device) with zero feedback, so users tap again and toggle
     the popover closed. Enlarge the target, kill double-tap zoom delay,
     and give an instant pressed state so a tap reads as registered. */
  [data-slot="conversation.input.dock"] [data-gitgraph-chip-anchor] [data-gitgraph-chip] {
    touch-action: manipulation !important;
    min-height: 34px !important;
    padding: 0 12px !important;
    font-size: 13px !important;
  }
  [data-slot="conversation.input.dock"] [data-gitgraph-chip-anchor] [data-gitgraph-chip]:active {
    transform: scale(.96) !important;
    transition: transform .12s !important;
  }

  /* ---------- ask question composer (ask_user_question): kill iOS Safari
      input-focus auto-zoom ----------
      Safari on iPhone enlarges the whole viewport when a focused <input> /
      <textarea> computes font-size < 16px, and only reverts on blur. The ask
      dialog is a modal composer takeover, so taps outside never blur the
      field and the magnification persists until the field loses focus
      (e.g. the dialog is dismissed). The ask
      composer's custom-answer <input> (.customInput) and optionless free-form
      <textarea> (.customTextarea) both ship at 14px (ui-user-questions
      QuestionComposer.module.css). Raise them to 16px on mobile so Safari
      sees a >=16px field and skips the zoom entirely. Scoped to the ask
      composer's stable [data-question-key] root (AGENTS.md: scope hashed-class
      selectors to the owning region, prefer stable data-* markers); the
      class-name suffix match follows the plugin's established harness
      CSS-module convention (verified against the live app: generated names
      end with the original local name, e.g. uV2eYG_input / qDHVXG_searchInput). */
  [data-question-key] [class*="_customInput"],
  [data-question-key] [class*="_customTextarea"] {
    font-size: 16px !important;
  }

  /* ---------- dsh-file-viewer inputs: kill iOS Safari auto-zoom ----------
     Same rule as the ask composer above: the file viewer's search / jump-to-
     line / pdf-page fields ship at 13-14px, which Safari auto-magnifies on
     focus inside a panel that does not blur on tap-away. Raise them to 16px
     on mobile so Safari skips the zoom. Scoped to the frame marker; the
     viewer itself is scoped by its stable dsfv prefix.
     (Port of community fork fix 2ff7976.) */
  [data-mobile-nav="frame"] [class*="dsfv-search-input"],
  [data-mobile-nav="frame"] [class*="dsfv-jump-input"],
  [data-mobile-nav="frame"] [class*="dsfv-page-input"] {
    font-size: 16px !important;
  }

  /* ---------- iOS WebKit: hold every text field at >=16px so Safari never
      focus-zooms the viewport (#45) ----------
      Report (iPhone 15 Pro Max): the page magnifies as soon as a field takes
      focus, sometimes also when switching sessions (the host composer mounts
      with autoFocus), and it stays magnified until the app is closed and
      reopened or rotated landscape->portrait.
      Mechanism: iOS Safari enlarges the visual viewport whenever a focused
      input / textarea computes below 16px, and it only zooms back out on
      blur — a chat shell keeps the composer focused, so the zoom has no
      moment to revert; before this fix the root touch-action also withheld
      pinch-zoom, so the user could not pull it back out either (see
      layout.css.ts). maximum-scale=1 in the viewport meta is NOT the fix:
      iOS 10+ ignores it for user pinch zoom while other engines honor it, so
      writing it would only take zoom away from Android. Raising the fields is
      the fix that stays inside the standard.
      Gated on html[data-mobile-nav-ios] (phone-chrome.ts detectIosWebKit)
      because only WebKit on iOS zooms on focus: Android and desktop keep the
      compact 13px search boxes they were designed with. The floor covers
      every text-entry field on the page, including the ones portalled
      outside the frame (settings dialogs, the market sheet, third-party
      panels) — a phone can reach all of them. Button-like and widget inputs
      are excluded (nothing to type, no keyboard), and select is left alone on
      purpose: it would break the composer's 28px access-mode control, and a
      native picker overlays the screen instead of leaving a zoomed page
      behind. The composer's mirror / backdrop layers ride along with the
      textarea: they measure the autosize height and paint the highlight, so
      all three must share one font-size or the caret drifts off the text
      (they inherit 16px from the host card today — the rule locks that in on
      hosts whose composer ships smaller).
      The contenteditable branch is the forward-looking one: dsh
      0.1.2-rc.1 replaces the composer textarea with a Lexical
      contenteditable whose card reads font-size:
      var(--dsh-content-font-size, 14px), i.e. 14px by default — squarely in
      the zoom-triggering range. Match the attribute rather than the value
      "true" (Lexical writes "true", other hosts use plaintext-only or the
      bare attribute) and exclude contenteditable="false", which Lexical puts
      on decorator nodes inside the editor. */
  html[data-mobile-nav-ios] textarea,
  html[data-mobile-nav-ios] [contenteditable]:not([contenteditable="false"]),
  html[data-mobile-nav-ios] [data-input-mirror],
  html[data-mobile-nav-ios] [data-input-backdrop],
  html[data-mobile-nav-ios] input:not([type="button"]):not([type="checkbox"]):not([type="color"]):not([type="file"]):not([type="hidden"]):not([type="image"]):not([type="radio"]):not([type="range"]):not([type="reset"]):not([type="submit"]) {
    font-size: 16px !important;
  }

  /* ---------- drawer session tree: skip off-screen rendering ----------
     The drawer mounts ~389 nodes at once (the open gesture early-commits
     the host state while the drawer is still off-screen), and during
     streaming every token commit re-lays-out tree rows that are not even
     visible. content-visibility: auto lets the engine skip layout and
     paint of the session tree while it is outside the viewport (the arm
     moment of the open gesture) and of off-screen rows when the drawer is
     open on a long conversation. contain-intrinsic-size keeps the scroll
     geometry stable while rows are skipped. Scoped to the drawer tree via
     the frame marker + first child so the explorer sheet tree (a different
     subtree) is not affected. Measured with CDP Tracing on an empty
     conversation at 1x CPU (2026-08-29): biggest script task 104 -> 66ms,
     max rAF gap 167 -> 33ms; the benefit scales with conversation length.
     Desktop untouched (this block lives inside the max-width: 1023px
     media query). */
  [data-mobile-nav="frame"] > :first-child [role="tree"] {
    content-visibility: auto;
    contain-intrinsic-size: auto 600px;
  }
}

/* ---------- tablet / wide mobile: keep sheets from becoming full-width ----------
   Below 768px the near-full-width sheets are the right call for a phone.
   On wider but still sub-desktop viewports (foldables, tablet portrait,
   desktop-mode tall windows) the same full-bleed sheet leaves content
   clustered at the left edge with a large dead zone on the right. Cap and
   center the modal sheets and the aionui bottom sheets instead. */
@media (min-width: 768px) and (max-width: 1023px) and (pointer: coarse) {
  /* All modal dialogs: centered, never edge-to-edge. The settings sheet has
     a higher-specificity full-width rule above, so repeat its selector here
     to win; the generic export/other-modal rule is covered by the second
     selector. */
  [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])),
  [aria-modal="true"]:not(:has(> :first-child > :last-child > button)) {
    left: 0 !important;
    right: 0 !important;
    margin-left: auto !important;
    margin-right: auto !important;
    width: min(calc(100vw - 32px), 720px) !important;
    max-width: min(calc(100vw - 32px), 720px) !important;
  }

  /* The dsh-web-ui explorer / preview bottom sheets: same treatment — keep
     the mobile bottom-sheet behavior, but stop them spanning the full width. */
  [data-aionui-explorer-col],
  [data-aionui-preview-col] {
    left: 0 !important;
    right: 0 !important;
    width: min(calc(100vw - 32px), 720px) !important;
    margin-left: auto !important;
    margin-right: auto !important;
  }

  /* Settings sections (e.g. Agent presets) often carry a desktop max-width
     (720px) that leaves a dead strip on the right once the sheet is capped to
     the same width; let them fill the sheet body instead. */
  [aria-modal="true"] [class*="_section"] {
    width: 100% !important;
    max-width: none !important;
  }
}

/* ---------- desktop / non-touch: the mobile controls must never appear ----------
   Exact complement of the mobile query "(max-width: 1023px) and (pointer:
   coarse)" as a comma list (NOT A or NOT B): any viewport ≥1024px, plus any
   narrow viewport whose primary pointer is a mouse (fine) or absent (none).
   The pointer terms are what keep the header Files button off narrow desktop
   windows — the slot renders the buttons at every width, so before this the
   only guard was the width term (2026-08-30 PC leak: split windows and OS
   display scaling dropped the CSS viewport below 1024px and armed the whole
   mobile shell on desktop). */

@media (min-width: 1024px), (pointer: fine), (pointer: none) {
  [data-mobile-nav="toggle"],
  [data-mobile-nav="files"],
  [data-mobile-nav="fab"],
  [data-mobile-nav="backdrop"],
  [data-mobile-nav="session-log"],
  [data-mobile-nav="explorer"],
  [data-mobile-nav="preview-full-toggle"],
  [data-mobile-nav="drawer-actions"],
  [data-mobile-nav="session-delete"],
  [data-mobile-nav="delete-dialog-backdrop"],
  [data-mobile-nav="delete-dialog"] {
    display: none !important;
  }
}
`;
};
__modules["styles/index.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.MOBILE_CSS = void 0;
const base_css_ts_1 = require("./styles/base.css.js");
const layout_css_ts_1 = require("./styles/layout.css.js");
const compat_css_ts_1 = require("./styles/compat.css.js");
const misc_css_ts_1 = require("./styles/misc.css.js");
/**
 * All mobile styles, concatenated in the exact order of the original
 * single-file stylesheet (base → layout → compat → misc, where misc keeps
 * composer → tablet → desktop). Injected as ONE <style data-plugin> tag —
 * do not reorder.
 */
exports.MOBILE_CSS = [base_css_ts_1.BASE_CSS, layout_css_ts_1.LAYOUT_CSS, compat_css_ts_1.COMPAT_CSS, misc_css_ts_1.MISC_CSS].join('\n');
};
__modules["effects/sidebar-swipe.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.startZonePxFor = startZonePxFor;
exports.classifySwipe = classifySwipe;
exports.slidingVelocity = slidingVelocity;
exports.hitTestStart = hitTestStart;
exports.followTranslate = followTranslate;
exports.followOpenTransform = followOpenTransform;
exports.findHorizontalScroller = findHorizontalScroller;
exports.selectionOwnsStroke = selectionOwnsStroke;
exports.installSidebarSwipe = installSidebarSwipe;
const phone_chrome_ts_1 = require("./effects/phone-chrome.js");
const gesture_guard_ts_1 = require("./effects/gesture-guard.js");
const overlay_backdrop_fab_ts_1 = require("./effects/overlay-backdrop-fab.js");
/**
 * Sidebar drawer swipe gestures (B 档 hybrid follow, per the 2026-08-29
 * controlled upgrade of docs/specs/2026-08-27-sidebar-swipe-gestures.md).
 *
 * Three gestures:
 * - edge swipe-in: the pointer goes down within the start zone (45% of the
 *   left edge) and the drawer is closed → the host state is flipped AT
 *   AXIS-LOCK (early commit) while the drawer is pinned in its closed slot,
 *   so the REAL open subtree mounts off-screen and then follows the finger
 *   out of the slot (see startFollow for why the flip has to come first);
 * - content swipe-toward-slot: the pointer goes down inside the open drawer
 *   and drags LEFT (LTR) → the drawer FOLLOWS the finger (inline translateX,
 *   transition:none) and releases into the host's transition;
 * - content swipe-out (legacy): drag RIGHT inside the open drawer → no
 *   follow (A 档 semantics preserved verbatim), release classifies.
 *
 * The release decision is UNCHANGED from A 档: classifySwipe (distance ratio
 * OR recent-window velocity) — after a follow stroke, dx IS the followed
 * position, so the same function decides complete vs spring-back. The commit
 * is still just `ctx.layout.toggleSidebar()`. The follow mechanics ride the
 * host transition instead of fighting it: during the stroke the drawer gets
 * inline `transition: none` + translateX; on release the inline styles are
 * dropped and the commit retargets the host transition IN THE SAME TASK (no
 * paint in between), so the drawer animates from the finger position to the
 * final state with zero custom animation code.
 *
 * Review constraints honored (spec 2026-08-27 second review): the backdrop
 * stays binary (appears at commit — never opacity-followed, 缺陷 2); a modal
 * rising mid-stroke reverts the drawer every move event (缺陷 1's per-frame
 * guard); the OPEN final state must end with transform:none (the containing
 * block invariant for fixed descendants) — a transitionend-free cleanup pair
 * (inline clear + host value) guarantees it because the host open rule is
 * transform:none. No gesture-layer DOM, no setPointerCapture. Zero transform
 * writes remain true for the LEGACY rightward-close path.
 *
 * Coexistence with the host's overlay interactions (document capture click /
 * pointerup) is two-layered via gesture-guard.ts: (1) tryLock publishes an
 * axis-lock flag the instant the stroke locks horizontal — during
 * pointermove, strictly before any pointerup — and the host handlers yield
 * on it first, because they are registered EARLIER and the post-release
 * consume marks do not exist yet on the stroke's own release event (audit
 * S0: the host toggled first and the gesture toggled back, net zero);
 * (2) a classified swipe additionally marks its target chain consumed so
 * the synthetic click after the stroke can never toggle twice or navigate
 * a row.
 */
/**
 * Start-zone width as a FRACTION of the viewport width: the pointer counts
 * as "from the left edge" anywhere inside the left (RTL: right) strip this
 * wide. Fifth tuning pass (2026-08-29, user preference "识别区再扩宽到约占
 * 总宽的 45%"): the fixed 96px strip still missed landings beyond it, and
 * the user wants the sloppy, anywhere-in-the-left-half feel of native apps.
 * History of the constant: 24px (hotspot era) → 48px (third pass, fixed
 * "识别成对话内容滚动") → 96px (fourth pass — at that point the zone also
 * finally cleared Chrome Android's EDGE_WIDTH_DP=48dp history-navigation
 * trigger strip, whose strokes the browser claims and pointercancels; the
 * browser gesture itself is suppressed by the root overscroll-behavior-x:
 * none rule in layout.css.ts) → 0.45×viewport (fifth pass, this value).
 * Safety at this width: the release classification (0.16×w travel OR
 * 0.45px/ms velocity) still gates the commit, so widening cannot open on a
 * tap; vertical strokes reset at axis lock (≤8px of prevented movement) and
 * hand scrolling back; strokes beginning inside genuinely horizontally
 * scrollable containers are excluded from the zone entirely — see
 * findHorizontalScroller (at 45% the stats line / message code blocks sit
 * well inside the strip, so that guard is load-bearing).
 */
const START_ZONE_RATIO = 0.45;
/**
 * The zone in pixels for a given viewport width (pure, exported for the
 * decision-table tests). Rounded so the probe boundary assertions stay
 * integral (390px → Math.round(175.5) = 176).
 */
function startZonePxFor(viewportWidthPx, ratio = START_ZONE_RATIO) {
    return Math.round(viewportWidthPx * ratio);
}
/**
 * Axis-lock threshold: once the stroke's dominant axis has moved this far,
 * the axis is decided. Horizontal-dominant (|dx| > |dy|) locks the stroke
 * to X (a swipe); vertical-dominant abandons it to native scrolling.
 * Replaces the old 4px slop + 1.5× direction-bias pair — a 1.5× bias
 * rejected natural ~45° diagonal swipes (the other half of the
 * "识别成滚动" report). MUI uses a 3px uncertainty threshold; 8px is a
 * comfortable margin against tap jitter while still deciding in the first
 * ~16ms of movement.
 */
const LOCK_PX = 8;
/** Distance thresholds as a fraction of the viewport width.
 *  Second tuning pass (2026-08-27, "识别成滚动" feedback): 0.16 open = ~62px
 *  on a 390px phone, 0.13 close = ~51px. Keep the open threshold above the
 *  close threshold so an accidental reverse swipe cannot re-open. */
const OPEN_DISTANCE_RATIO = 0.16;
const CLOSE_DISTANCE_RATIO = 0.13;
/** Velocity window: most-recent-60ms instantaneous speed (end-segment slope). */
const VELOCITY_WINDOW_MS = 60;
/** px/ms speed thresholds for open / close (MUI uses 0.45). */
const OPEN_VELOCITY = 0.45;
const CLOSE_VELOCITY = 0.45;
/** Covers the .28s CSS transition; prevents reverse-gesture double-toggles. */
const COOLDOWN_MS = 350;
/** How long a consumed gesture mark stays live (covers the synthetic click).
 * Short by design: browsers dispatch the synthetic click within tens of ms,
 * while iOS shells suppress it entirely — a long window with no delivery
 * would let the marks swallow the user's next genuine tap (dead-tap bug).
 * When upTo is absent from the release chain (edge swipe-in releases over
 * the main content) the mark walk reaches the document root, so this short
 * window is also the bound on how long any tap can be suppressed. */
const CONSUME_WINDOW_MS = 300;
/**
 * Rightward travel (from the stroke start) that arms the OPEN follow, i.e.
 * flips the host state early so the real drawer subtree mounts. Slightly
 * above LOCK_PX so an 8px horizontal twitch inside the wide start zone does
 * not mount-and-unmount 389 nodes; small enough that the dead zone before
 * the drawer's edge appears is imperceptible.
 */
/** The open follow arms at the AXIS LOCK itself: tryLock already demanded
 * 8px of horizontal-dominant travel, so no extra twitch margin is needed —
 * every pixel between lock and arm was dead drag (user report 2026-08-29
 * 「右滑的过程中最开始有真空期,有一段卡的地方」). The release verdict still
 * decides the outcome, so arming early cannot commit a false open. */
const OPEN_FOLLOW_ARM_PX = 8;
/**
 * The host's closed-slot offset as a PERCENTAGE of the drawer's own width
 * (`transform: translateX(-110%)` — the 10% overshoot hides the drawer's
 * shadow). Percentages are load-bearing for the open follow: the element
 * width changes mid-stroke when React swaps the collapsed rail for the real
 * drawer, and a percentage re-resolves against the current width while a
 * cached px value would not.
 */
const CLOSED_SLOT_PCT = 110;
/** Duration of the self-run terminal close animation. Matches the host's
 * .28s drawer transition so the handoff feels identical. */
const COMMIT_ANIM_MS = 280;
/** Percentage baseline of the OPEN-direction follow. The host's closed slot
 * is -110%, but following from -110% hides the first 28px of travel (the
 * 10% overshoot of the 280px drawer): the drawer stayed invisible until
 * ~dx=28 — user report 「刚开始会卡一下，之后才会拖出来」(measured: first
 * paint at dx=12 was left=-296, edge reached the viewport only at dx=28).
 * 101% keeps a small hidden margin (subpixel safety, would-be sliver at
 * exactly -100%) so the drawer edge answers the finger right after the
 * axis lock: at the 8px arm the edge is already ~5px on-screen (-102% left
 * only 2.4px and read as a vacuum; -110% hid the first 28px entirely).
 * The closed slot itself is only ever needed at TERMINAL states,
 * where CLOSED_SLOT_PCT is used verbatim. */
const OPEN_FOLLOW_BASE_PCT = 101;
/** Pointer id we are tracking (multi-touch is ignored). */
let trackingPointer = 0;
/** True once the stroke is axis-locked (direction bias passed). */
let tracking = false;
/** Stroke samples (x + timestamp) for the recent-window velocity. */
let samples = [];
/** Stroke origin (for the direction-bias check). */
let startX = 0;
let startY = 0;
/** Drawer visibility at lock time. */
let lockDrawerOpen = false;
/** Expiry of the post-release cooldown (performance.now()). */
let cooldownUntil = 0;
/** Element whose stroke was marked consumed (null = no live mark). */
let consumedEl = null;
/** B 档 follow state — one cache per stroke, set once at lock time so the
 * per-move writes never read layout (the spec review's rAF-contention
 * constraint). followDrawer stays bound for the whole stroke so a
 * released-then-re-engaged stroke (direction wobble) reuses the cache. */
let followDrawer = null;
let followEngaged = false;
let strokeClosedTx = 0;
let strokeRtl = false;
/** True while an OPEN stroke has early-committed the host state (the drawer
 * subtree is mounted but pinned in its slot, following the finger). The
 * release must then either keep it open or toggle it back. */
let openFollowArmed = false;
/** True once an open stroke has decided NOT to arm the follow (aborted arm:
 * a modal/takeover veto, a missing drawer) so it never retries mid-stroke. */
let openFollowRefused = false;
/**
 * Pure decision: what does this stroke do, given the drawer state?
 * `dx`/`dy` are raw pointer deltas (RTL mirrors X through `rtl`), `velX` is
 * the raw recent-window X velocity. The stroke must be locked horizontal
 * (|dx| > |dy| and past the lock slop) and direction-consistent; then
 * distance OR velocity wins, with the drawer-state-specific threshold.
 */
function classifySwipe(t, m, rtl) {
    // RTL mirrors the X axis: a rightward stroke (positive dx in LTR) is
    // leftward in RTL. Normalize to the logical direction before judging.
    const dx = rtl ? -m.dx : m.dx;
    if (Math.abs(dx) <= t.lockPx)
        return 'none';
    if (Math.abs(dx) <= Math.abs(m.dy))
        return 'none';
    if (t.drawerOpen) {
        // BOTH horizontal directions close (2026-08-29 sixth round, user report
        // 「根本没法左滑关闭」). Leftward is the natural "push it back into its
        // slot" gesture — and the only one the follow animation actually paints
        // (followTranslate's close branch follows leftward), so refusing it made
        // the drawer track the finger and then spring back, i.e. the animation
        // promised a close the classifier would not honor. Rightward stays
        // accepted verbatim: four tuning rounds of muscle memory ride on it and
        // failure scenarios B0/B1/B2 assert it. Nothing else competes for a
        // horizontal stroke while the drawer is open, so accepting both costs no
        // ambiguity.
        const travel = Math.abs(dx);
        if (travel / t.viewportWidthPx >= t.closeDistanceRatio)
            return 'close';
        const velX = rtl ? -m.velX : m.velX;
        // A fling only counts when it agrees with the stroke's own direction
        // (same contradiction guard the open branch applies).
        if (velX > 0 !== dx > 0)
            return 'none';
        return Math.abs(velX) >= t.closeVelocity ? 'close' : 'none';
    }
    if (dx <= 0)
        return 'none';
    if (dx / t.viewportWidthPx >= t.openDistanceRatio)
        return 'open';
    const velX = rtl ? -m.velX : m.velX;
    return velX >= t.openVelocity ? 'open' : 'none';
}
/**
 * Recent-window instantaneous velocity (px/ms) from the tail of the last
 * `windowMs` milliseconds of samples, up to `now`. Sliding X per ms between
 * the LAST TWO in-window samples — the end-of-stroke slope — so a long slow
 * drag then a quick flick reports the flick, not the drag average. Samples
 * older than the window are ignored. Fewer than two in-window samples → 0.
 */
function slidingVelocity(samples, windowMs, now) {
    const cutoff = now - windowMs;
    const inWindow = samples.filter((s) => s.t >= cutoff);
    if (inWindow.length < 2)
        return 0;
    const a = inWindow[inWindow.length - 2];
    const b = inWindow[inWindow.length - 1];
    const dt = b.t - a.t;
    if (dt <= 0)
        return 0;
    return (b.x - a.x) / dt;
}
/**
 * Geometric start-hit test: the pointer went down in the left edge start
 * zone (when the drawer is closed) or inside the drawer content area (when
 * open). Pure and viewport-relative so it is unit-testable; the runtime
 * variant additionally checks the drawer geometry via the DOM.
 */
function hitTestStart(clientX, viewportWidthPx, rtl, t) {
    const edge = rtl ? viewportWidthPx - clientX : clientX;
    return edge >= 0 && edge <= t.startZonePx;
}
/**
 * Pure follow mapping (B 档): the translateX (px) to paint for a stroke
 * sample, or null when THIS sample has no follow. `closedTx` is the signed
 * closed-slot translateX (negative LTR, positive RTL — the drawer slides
 * off the anchored edge); `dx` is the RAW pointer delta; normalization
 * mirrors classifySwipe (`d = rtl ? -dx : dx`, rightward-logical positive =
 * toward open).
 *
 * Decision table (C3 hybrid, 2026-08-29 user decision):
 * - close stroke (drawer open): LEFTWARD-logical travel drags the drawer
 *   toward its closed slot, clamped at the slot; rightward-logical → null
 *   (the legacy A 档 close owns that direction — no follow, momentum-honest);
 * - open stroke (drawer closed): NOT used at runtime — the open direction
 *   follows through `followOpenTransform` instead, because its baseline has
 *   to stay a percentage across the subtree swap (see that function). The px
 *   mapping is kept pure and tested as the reference semantics;
 * - a zero closed slot (degenerate host without a closed transform) yields
 *   a constant 0 — the follow degrades to a no-op instead of inventing
 *   travel.
 */
function followTranslate(closedTx, dx, rtl, drawerOpen) {
    const dir = closedTx <= 0 ? -1 : 1;
    const slot = Math.abs(closedTx);
    const d = rtl ? -dx : dx;
    if (drawerOpen) {
        if (d >= 0)
            return null;
        // + 0 normalizes -0 (dir=-1 times a clamped 0) so strict equality in the
        // decision table and in probe comparisons sees a plain zero.
        return dir * Math.min(slot, -d) + 0;
    }
    if (d <= 0)
        return null;
    return dir * (slot - Math.min(slot, d)) + 0;
}
/**
 * Pure follow mapping for the OPEN direction (B 档, 2026-08-29 second pass).
 * Returns the CSS transform to paint for a stroke that has already
 * early-committed the host state, or null when this sample has no follow
 * (leftward-logical travel, i.e. pulled back past the stroke origin).
 *
 * The baseline is the host's own PERCENTAGE slot (`translateX(-110%)`), kept
 * symbolic on purpose: at arm time the element is still the ~206px collapsed
 * rail and a frame later React has swapped in the ~280px drawer. A px
 * baseline captured before the swap would leave the wider drawer 74px
 * off-position (its slot is -308px, not -227px); `-110%` re-resolves against
 * the element's current width on every frame, so the same declaration is
 * correct across the mount. `min()`/`max()` clamp the open end so overshoot
 * cannot drag the drawer past its resting position.
 */
function followOpenTransform(travelPx, rtl) {
    const t = rtl ? -travelPx : travelPx;
    if (t <= 0)
        return null;
    return rtl
        ? `translateX(max(0px, calc(${OPEN_FOLLOW_BASE_PCT}% - ${t}px)))`
        : `translateX(min(0px, calc(-${OPEN_FOLLOW_BASE_PCT}% + ${t}px)))`;
}
/**
 * Pure walk: the innermost element of the chain (self included) that is a
 * GENUINELY horizontally scrollable container — overflow-x auto/scroll AND
 * content actually overflowing (scrollWidth > clientWidth + 1; the +1
 * absorbs subpixel rounding). A stroke beginning inside one belongs to that
 * scroller: the browser claims the horizontal pan (pointercancel on real
 * devices) and the release classification must neither compete with it nor
 * preventDefault it away — prevention is what would break the strip's native
 * scrolling near the left edge once the start zone grew to 45% of the
 * viewport (the stats
 * line spans the full width; message code blocks are overflow-x:auto too).
 * CDP failure scenario C1 pins this contract. overflow-x:hidden/clip never
 * match: clipped content cannot pan, so a horizontal stroke there stays free
 * for the gesture layer.
 */
function findHorizontalScroller(node) {
    let cur = node;
    while (cur !== null) {
        if ((cur.overflowX === 'auto' || cur.overflowX === 'scroll') &&
            cur.scrollWidth > cur.clientWidth + 1) {
            return cur;
        }
        cur = cur.parent;
    }
    return null;
}
/** The open drawer element: first child of the plugin frame. */
function findDrawer() {
    const frame = (0, phone_chrome_ts_1.getFrame)();
    return frame !== null && frame.firstElementChild instanceof HTMLElement
        ? frame.firstElementChild
        : null;
}
/** True when the drawer is currently open (per the collapsed marker). */
function drawerOpen() {
    const frame = (0, phone_chrome_ts_1.getFrame)();
    return frame !== null && !frame.hasAttribute('data-sidebar-collapsed');
}
/**
 * Map the real DOM ancestor chain (target first, root last) onto the plain
 * SwipeChainNode shape findHorizontalScroller walks. Bounded by the document
 * depth (~15 nodes in this app) and run once per pointerdown, so the
 * getComputedStyle calls are not a per-frame cost.
 */
function chainFrom(target) {
    let node = null;
    let el = target;
    while (el !== null) {
        node = {
            parent: node,
            scrollWidth: el.scrollWidth,
            clientWidth: el.clientWidth,
            overflowX: getComputedStyle(el).overflowX,
        };
        el = el.parentElement;
    }
    return node;
}
/** Whether a modal dialog owns the screen (gestures must yield to it). */
function modalOpen() {
    return document.querySelector('[aria-modal="true"]') !== null;
}
/** True when a full-screen takeover (taskboard / ssh) owns the frame, or any
 *  host conversation.view overlay (trajectory tab, dsh-file-viewer, or a
 *  future third-party view — they all set the generic
 *  `data-conversation-composer-overlay` attribute on their root) is open.
 *  In both cases the drawer edge-swipe gestures yield so horizontal content
 *  scrolling (kanban columns, trajectory tables, CSV/code panes) wins the
 *  left-edge start zone; the FAB still opens the drawer. This reads the
 *  generic overlay attribute directly — deliberately decoupled from the
 *  file-viewer marker, which keys on `.dsfv-panel` only (design
 *  2026-09-06-conversation-overlay-takeover-design.md §4.2). */
function takeoverActive() {
    return (document.documentElement.hasAttribute('data-dsh-taskboard-active') ||
        document.documentElement.hasAttribute('data-dsh-ssh-active') ||
        document.querySelector('[data-conversation-composer-overlay]') !== null);
}
/**
 * Whether a live, non-collapsed text selection owns the pointer stroke.
 * A selection-handle drag (and a long-press selection that appears between
 * pointerdown and the axis lock) is horizontally dominant and geometrically
 * indistinguishable from a drawer swipe — the browser must keep it (#43,
 * iPad WebKit). Feature-detected so the node:test suite can import the
 * predicate without a DOM.
 *
 * TWO selection models must be read, because they are disjoint:
 * - the DOCUMENT selection (window.getSelection) covers message-flow text
 *   and contenteditable hosts;
 * - a selection inside a text control lives on the ELEMENT as
 *   selectionStart/selectionEnd and is INVISIBLE to window.getSelection —
 *   measured on the composer during a hijacked stroke (#44, real iPad):
 *   taStart=0 taEnd=20 while the document selection reported isCollapsed.
 *   Reading only the document selection let the swipe layer arm, lock, and
 *   collapse the composer selection the user was extending.
 * document.activeElement is the right anchor for the element model: a handle
 * drag keeps focus inside the control, and it also covers strokes whose
 * points land outside the control's own box.
 */
function selectionOwnsStroke() {
    if (typeof window === 'undefined')
        return false;
    const sel = window.getSelection();
    if (sel !== null && !sel.isCollapsed)
        return true;
    if (typeof document === 'undefined')
        return false;
    const el = document.activeElement;
    if (el === null)
        return false;
    const tag = el.tagName;
    if (tag !== 'TEXTAREA' && tag !== 'INPUT')
        return false;
    // Input types without a text selection (checkbox, number, email, …) report
    // null here — measured in Chromium — and older WebKit/Gecko throw
    // InvalidStateError instead. Both mean "no text selection is being
    // dragged", never "the control owns this stroke", so neither may be
    // allowed to escape from a pointer handler.
    try {
        const { selectionStart: start, selectionEnd: end } = el;
        return typeof start === 'number' && typeof end === 'number' && start !== end;
    }
    catch {
        return false;
    }
}
/** Whether the swipe layer is on cooldown (animation in flight). */
function onCooldown() {
    return performance.now() < cooldownUntil;
}
/**
 * Cache the follow geometry for a freshly locked stroke. Runs ONCE per
 * stroke (one getComputedStyle, plus one getBoundingClientRect only for the
 * cold-start fallback); the per-move path afterwards is write-only.
 *
 * CLOSE strokes follow from a px baseline read here. OPEN strokes cannot:
 * the host renders TWO different subtrees in the same sidebar column —
 * collapsed it is a ~206px rail holding only Task Board / SSH / Files /
 * Session log (79 nodes, ZERO `role=treeitem`), open it is the ~280px drawer
 * with the session tree and footer (389 nodes, 15 treeitems). Dragging the
 * closed column would only reveal the rail (measured 2026-08-29, the user's
 * "完全不同的 UI、没有真实会话、位置全乱" report). The open direction therefore
 * commits FIRST and follows AFTER (armOpenFollow), which is also why its
 * baseline must stay a percentage rather than a px value cached here.
 */
function startFollow() {
    // Unbind first: followDrawer survives across strokes (endStroke releases
    // the styles AFTER reset(), so reset must not clear it). Without this an
    // open stroke would inherit the binding left by the previous close-follow
    // and start following after all — exactly what the probe assertion
    // swipe.open-stroke-no-follow catches.
    followDrawer = null;
    followEngaged = false;
    openFollowArmed = false;
    openFollowRefused = false;
    // strokeRtl is read by the OPEN branch of applyFollow BEFORE it arms, so it
    // must be refreshed for every locked stroke — not only the close branch —
    // or an open stroke would inherit the previous stroke's reading direction.
    strokeRtl = frameRtl();
    const drawer = findDrawer();
    if (drawer === null)
        return;
    // A closed stroke binds nothing here: the OPEN direction early-commits and
    // binds inside armOpenFollow, using a percentage baseline (the element's
    // width changes when React swaps the rail for the real drawer).
    if (!lockDrawerOpen)
        return;
    followDrawer = drawer;
    // The slot is 110% of the element's OWN width (the host's closed rule is
    // translateX(-110%), the extra 10% covering any shadow).
    //
    // Measuring the OPEN drawer is load-bearing (2026-08-29 seventh round,
    // user report 「左滑的时候会卡一下…会突然有出现半开不开的样子」 →
    // 「UI 会停在我最终滑动的地方，之后消失」). The previous baseline was a
    // slot observed on the CLOSED host, i.e. on the ~206px nav rail
    // (~-226.7px) — but the drawer being dragged is ~280px and parks at
    // ~-308px. followTranslate clamps at the slot, so the drag froze 81px
    // short of the edge: the drawer stopped under a still-moving finger
    // (「半开不开」), and the release then had to travel that remainder,
    // reading as a stall followed by a disappearance.
    //
    // Width is stable for the duration of a close stroke (no subtree swap
    // until the release commits), so a px baseline is safe here — unlike the
    // open direction, which must stay percentage-based because React swaps the
    // rail for the real drawer mid-stroke.
    const slot = (drawer.getBoundingClientRect().width * CLOSED_SLOT_PCT) / 100;
    strokeClosedTx = strokeRtl ? slot : -slot;
}
/**
 * Arm the OPEN follow: pin the drawer in its closed slot with an important
 * inline pair, THEN flip the host state in the same task. React mounts the
 * real ~280px drawer subtree while our inline transform holds it off-screen,
 * so the next move samples slide the genuine drawer — session tree and all —
 * out of the slot under the finger. Ordering matters: pin before the flip,
 * or the host's open rule (`transform: none`) paints the drawer at rest for
 * one frame and the user sees it snap into place before the follow starts.
 *
 * The backdrop and the FAB swap at the flip, which is the documented binary
 * behavior (spec review 缺陷 2: no opacity-following backdrop).
 */
/** True while the drawer subtree layout+paint is deliberately deferred by
 * the arm-time content-visibility split (see armOpenFollow). */
let cvDeferred = false;
/** Re-materialize the drawer contents after the mount-frame split. */
function revealDrawerContent() {
    if (!cvDeferred)
        return;
    cvDeferred = false;
    followDrawer?.style.removeProperty('content-visibility');
    const el = findDrawer();
    if (el !== null && el !== followDrawer)
        el.style.removeProperty('content-visibility');
}
function armOpenFollow(ctx) {
    if (openFollowArmed || openFollowRefused)
        return;
    const drawer = findDrawer();
    if (drawer === null || modalOpen() || takeoverActive()) {
        openFollowRefused = true;
        return;
    }
    followDrawer = drawer;
    followEngaged = true;
    drawer.style.setProperty('transition', 'none', 'important');
    const pinned = followOpenTransform(0.0001, strokeRtl);
    drawer.style.setProperty('transform', pinned ?? `translateX(-${CLOSED_SLOT_PCT}%)`, 'important');
    // Split the mount cost (2026-08-29, user report 「滑动不会立刻生效，而是卡
    // 那么零点几秒」): the toggle below synchronously mounts the 389-node
    // drawer subtree, and reconcile + style + layout + paint all land in ONE
    // long task — measured 308ms at 4x CPU throttle, a quarter-second of
    // frozen screen on a phone. content-visibility:hidden (set BEFORE the
    // flip, on the column that survives the subtree swap) makes the mount
    // frame skip subtree layout+paint — the panel BOX still paints and the
    // compositor keeps following the finger — and the contents materialize
    // two frames later via revealDrawerContent(), where the motion masks the
    // second (smaller) block. Ignored by browsers without support (no-op).
    drawer.style.setProperty('content-visibility', 'hidden', 'important');
    cvDeferred = true;
    openFollowArmed = true;
    ctx.layout.toggleSidebar();
    requestAnimationFrame(() => {
        requestAnimationFrame(revealDrawerContent);
    });
}
/**
 * Paint this move sample's follow position. Null mapping (legacy direction
 * or pulled back past the stroke origin) releases the inline styles so the
 * host transition is live again — the drawer springs to wherever the host
 * state puts it and the classification still owns the release. Re-engaging
 * after a null sample rewrites both inline properties, which also
 * self-heals anything that restored them mid-stroke (React re-render).
 *
 * Both properties MUST be written with `important` priority. The open state
 * is styled by our own `transform: none !important` (layout.css.ts — the
 * containing-block rule for the settings overlay), which outranks a plain
 * inline declaration: a normal `style.transform = ...` leaves the computed
 * transform at `none` and the drawer never moves. That is exactly how the
 * first follow implementation shipped invisible while every inline-string
 * assertion passed (2026-08-29) — assert COMPUTED transform, never
 * `element.style.transform`.
 */
function applyFollow(ctx, dx) {
    if (!tracking)
        return;
    if (!lockDrawerOpen) {
        // OPEN direction: arm past the twitch threshold, then follow with the
        // percentage baseline (the element's width changes across the mount).
        const travel = strokeRtl ? -dx : dx;
        if (!openFollowArmed) {
            if (travel < OPEN_FOLLOW_ARM_PX)
                return;
            armOpenFollow(ctx);
            if (!openFollowArmed)
                return;
        }
        const value = followOpenTransform(dx, strokeRtl);
        if (value === null) {
            // Pulled back past the origin: hold the drawer parked in its slot
            // rather than releasing (releasing would let the host animate it open
            // behind the finger). The release still classifies and may revert.
            followDrawer?.style.setProperty('transform', `translateX(-${CLOSED_SLOT_PCT}%)`, 'important');
            return;
        }
        followDrawer?.style.setProperty('transform', value, 'important');
        return;
    }
    if (followDrawer === null)
        return;
    const tx = followTranslate(strokeClosedTx, dx, strokeRtl, lockDrawerOpen);
    if (tx === null) {
        // Pulled back past the origin. Hold the drawer at rest instead of
        // releasing the inline pair: releasing would restore the host's .28s
        // transition mid-stroke, so a direction wobble would animate the drawer
        // and then jump when the finger crosses back — the same reason the open
        // branch pins instead of releasing.
        followEngaged = true;
        followDrawer.style.setProperty('transition', 'none', 'important');
        followDrawer.style.setProperty('transform', 'translateX(0px)', 'important');
        return;
    }
    followEngaged = true;
    followDrawer.style.setProperty('transition', 'none', 'important');
    followDrawer.style.setProperty('transform', `translateX(${tx}px)`, 'important');
}
/**
 * Drop the inline follow styles. The host stylesheet retakes control: with
 * the transition restored, clearing the transform animates the drawer from
 * the finger position to whatever the CURRENT host state says. Called on
 * every end-stroke branch (revert: this IS the spring-back; commit: the
 * same-task retarget below overrides the initial leg before any paint).
 */
function releaseFollowStyles() {
    const el = followDrawer;
    if (!followEngaged || el === null)
        return;
    followEngaged = false;
    el.style.removeProperty('transition');
    el.style.removeProperty('transform');
}
/** A close commit that is still animating to the closed slot before the host
 * state flips. The flip MUST wait: the sidebar column renders two mutually
 * exclusive subtrees (280px drawer when open, 206px nav rail when closed),
 * and React swaps them some ~200ms after the marker flips — measured
 * mid-animation at t≈200ms of a 280ms transition (width 280→206, tx jumped
 * -207.6→-181.9 as -110% re-resolved against the narrower rail). Flipping
 * first therefore replaces the drawer's content and retargets its transition
 * IN FLIGHT — user report 「最后抽屉样式突然消失,不是自然的动画收起」.
 * Late commit: animate the inline transform to the slot, flip only when the
 * drawer is already off-screen, then drop the inline pair. */
let pendingCommit = null;
function finishPendingCommit() {
    const pending = pendingCommit;
    if (pending === null)
        return;
    pendingCommit = null;
    window.clearTimeout(pending.timer);
    // The element may already be unmounted (React swaps the subtree at the
    // flip); stripping inline from a detached node is a harmless no-op.
    pending.el.style.removeProperty('transition');
    pending.el.style.removeProperty('transform');
    // If the host already closed while our animation ran (e.g. a genuine
    // backdrop tap inside the 280ms window), the flip already happened and a
    // blind toggle would RE-OPEN the drawer — skip it.
    const frame = (0, phone_chrome_ts_1.getFrame)();
    if (frame !== null && !frame.hasAttribute('data-sidebar-collapsed')) {
        pending.ctx.layout.toggleSidebar();
    }
}
/** Animate `el` to `targetTx` with our own transition, flip the host when it
 * lands. One-shot: a second call settles the previous commit first. */
function commitWithAnimation(ctx, el, targetTx) {
    finishPendingCommit();
    el.style.setProperty('transition', `transform ${COMMIT_ANIM_MS}ms ease-in-out`, 'important');
    // Flush the before-change style so the transition provably starts from the
    // current (finger) position instead of risking a coalesced recalc that
    // would jump straight to the target.
    void el.getBoundingClientRect();
    el.style.setProperty('transform', targetTx, 'important');
    // Fade the dimming in step with the slide-out: the marker flips only when
    // the drawer lands, so without this the screen would go drawer-then-dark
    // (backdrop snapping away ~260ms AFTER the drawer already left).
    (0, overlay_backdrop_fab_ts_1.fadeOverlayOut)();
    cooldownUntil = performance.now() + COOLDOWN_MS;
    pendingCommit = {
        el,
        ctx,
        timer: window.setTimeout(finishPendingCommit, COMMIT_ANIM_MS + 40),
    };
}
/** Terminal close commit: animate the drawer into the closed slot, then flip
 * the host. The slot must be the host's REAL closed rule (-110%), because
 * after the flip the closed host paints exactly this value — dropping the
 * inline pair must be a no-op, not a jump. */
function commitFollowClose(ctx) {
    const el = followDrawer;
    followDrawer = null;
    followEngaged = false;
    if (el === null) {
        // No follow binding (defensive): fall back to the immediate flip.
        releaseFollowStyles();
        ctx.layout.toggleSidebar();
        cooldownUntil = performance.now() + COOLDOWN_MS;
        return;
    }
    const target = strokeRtl
        ? `translateX(${CLOSED_SLOT_PCT}%)`
        : `translateX(-${CLOSED_SLOT_PCT}%)`;
    commitWithAnimation(ctx, el, target);
}
/** Cancel paths: styles back to the host, pointer state to idle. An armed
 * open follow has already flipped the host state, so a cancel must also
 * toggle it back — release the inline pair first so the host transition
 * animates home from the finger position within the same task. */
function abortStroke(ctx, immediate = false) {
    if (pendingCommit !== null) {
        // A terminal commit is animating: this stroke already ended. Only a
        // teardown (dispose) must settle it synchronously; otherwise let the
        // timer land the flip.
        if (immediate)
            finishPendingCommit();
        return;
    }
    const wasArmed = openFollowArmed;
    openFollowArmed = false;
    openFollowRefused = false;
    revealDrawerContent();
    if (wasArmed && ctx !== null && followDrawer !== null && !immediate) {
        // Armed open stroke aborted mid-follow: the host is already open, and
        // flipping now would swap the subtree mid-motion — same artifact as the
        // close release. Animate back into the slot, then flip.
        reset();
        commitFollowClose(ctx);
        return;
    }
    releaseFollowStyles();
    reset();
    if (wasArmed && ctx !== null) {
        ctx.layout.toggleSidebar();
        cooldownUntil = performance.now() + COOLDOWN_MS;
    }
}
/** Start a stroke; returns true when it may be tracked. */
function beginStroke(event, rtl, viewportWidthPx) {
    if (onCooldown())
        return false;
    if (modalOpen())
        return false;
    if (takeoverActive())
        return false;
    // A live text selection owns the stroke (a selection-handle drag is
    // horizontally dominant and geometrically identical to a swipe — #43,
    // iPad WebKit): yield before any geometric test. This also blocks
    // swipe-open while a stale selection is alive; one tap collapses the
    // selection everywhere, and backdrop tap-to-close is unaffected (a tap
    // never reaches tryLock).
    if (selectionOwnsStroke())
        return false;
    if (!(event.target instanceof Element))
        return false;
    // A stroke beginning inside a genuinely horizontally scrollable container
    // belongs to that scroller (the stats line, a message code block, any
    // carousel): yield it so its native horizontal pan survives — and so the
    // wide 45%-of-viewport start zone cannot turn a strip scroll into a
    // drawer open (failure scenario C1). Applies to both branches: inside the
    // drawer the same "scroller owns horizontal" semantics should hold.
    if (findHorizontalScroller(chainFrom(event.target)) !== null)
        return false;
    const open = drawerOpen();
    if (open) {
        // Close strokes may start ANYWHERE over the frame (2026-08-29 sixth
        // round, user report 「希望打开抽屉之后以外的部分可以进行左滑」). The
        // previous gate required the start point inside the drawer's own
        // geometry and explicitly rejected the backdrop, so the ~28% of the
        // screen beside the drawer swallowed every swipe — combined with the
        // leftward verdict being refused, closing felt impossible. Nothing else
        // owns a horizontal stroke while the drawer is open (the conversation is
        // behind the backdrop), so the whole frame is fair game.
        //
        // Tap-to-close on the backdrop is unaffected: a tap never reaches
        // tryLock, so endStroke returns on !wasTracking without writing a
        // consume mark, and the document-capture click handler passes backdrop /
        // FAB clicks through unconditionally anyway.
        const frame = (0, phone_chrome_ts_1.getFrame)();
        if (frame === null)
            return false;
        const rect = frame.getBoundingClientRect();
        if (event.clientX < rect.left || event.clientX > rect.right)
            return false;
        if (event.clientY < rect.top || event.clientY > rect.bottom)
            return false;
        // A session-row action menu (kebab) owns its own tap.
        if (event.target.closest('[class*="sessionRow"] button') !== null)
            return false;
    }
    else if (!hitTestStart(event.clientX, viewportWidthPx, rtl, { startZonePx: startZonePxFor(viewportWidthPx) })) {
        return false;
    }
    trackingPointer = event.pointerId;
    tracking = false;
    startX = event.clientX;
    startY = event.clientY;
    samples = [{ t: event.timeStamp, x: event.clientX }];
    return true;
}
/**
 * Axis-lock the stroke once its dominant axis has moved LOCK_PX. Horizontal
 * dominance (|dx| > |dy|) locks to X and is tracked; vertical dominance
 * abandons the stroke back to native scrolling (browser takes over, no
 * further preventDefault). Once locked the axis never re-decides — matching
 * MUI's UNCERTAINTY_THRESHOLD semantics.
 */
function tryLock(event) {
    const dx = event.clientX - startX;
    const dy = event.clientY - startY;
    if (Math.max(Math.abs(dx), Math.abs(dy)) < LOCK_PX)
        return false;
    if (Math.abs(dx) <= Math.abs(dy)) {
        // Vertical-dominant: hand the touch back to scrolling.
        reset();
        return false;
    }
    tracking = true;
    lockDrawerOpen = drawerOpen();
    // Publish the lock to the host handlers (see gesture-guard.ts): they run
    // EARLIER in this release event's capture phase, before endStroke writes
    // any consume mark — the flag is their only ordering-proof yield signal
    // (audit S0/S1).
    (0, gesture_guard_ts_1.markStrokeLocked)();
    startFollow();
    return true;
}
/** Append a sample and prune the window. */
function pushSample(event) {
    samples.push({ t: event.timeStamp, x: event.clientX });
    const cutoff = event.timeStamp - VELOCITY_WINDOW_MS;
    let i = 0;
    while (i < samples.length - 1 && samples[i].t < cutoff)
        i += 1;
    if (i > 0)
        samples = samples.slice(i);
}
/**
 * Release the stroke: classify, then either commit or spring back.
 *
 * B 档 ordering is load-bearing: the verdict is computed FIRST (the follow
 * position IS dx, so classifySwipe decides complete-vs-revert exactly as in
 * A 档), then the inline follow styles are dropped — restoring the host
 * transition and clearing the transform starts an animation toward the
 * drawer's CURRENT host state — and only then does the commit flip the host
 * state, retargeting that transition within the SAME task. No paint happens
 * between the two, so the user sees one continuous motion from the finger
 * position into the final state; a reverted stroke simply animates home.
 *
 * An ARMED OPEN follow inverts the commit: the host state was already
 * flipped at arm time, so a positive verdict must NOT toggle again (that
 * would close the drawer the user just pulled out) and a negative verdict
 * must toggle BACK. Either way the inline release comes first, so the host
 * transition animates from the finger position to whichever state wins.
 */
function endStroke(ctx, event, rtl, viewportWidthPx) {
    const wasTracking = tracking;
    const armedOpen = openFollowArmed;
    openFollowArmed = false;
    openFollowRefused = false;
    // Velocity must be computed before reset() clears the samples.
    const vel = slidingVelocity(samples, VELOCITY_WINDOW_MS, event.timeStamp);
    // Distance is measured from the stroke START (not the axis-lock point):
    // the slop is an activation gate, not travel that should consume the
    // user's swipe distance. Measuring from the lock point made the effective
    // travel = slop + threshold (e.g. 4px + 78px), so a 78px threshold
    // actually needed ~82px+ of finger travel — the "feels like half the
    // screen" complaint. From the start, a 78px threshold is a 78px swipe.
    const dx = event.clientX - startX;
    const dy = event.clientY - startY;
    reset();
    if (!wasTracking) {
        // A stroke that armed the follow is by definition locked, so this branch
        // cannot leave the host state flipped — but keep the invariant explicit.
        if (armedOpen) {
            commitFollowClose(ctx);
        }
        return;
    }
    const modal = modalOpen();
    // An armed open follow has already flipped the marker, so classifySwipe
    // must still be asked the question the USER answered: it was a closed
    // drawer when the stroke began (lockDrawerOpen), which is what the stored
    // flag holds — never re-read drawerOpen() here.
    const verdict = modal || (!armedOpen && onCooldown())
        ? 'none'
        : classifySwipe({
            openDistanceRatio: OPEN_DISTANCE_RATIO,
            closeDistanceRatio: CLOSE_DISTANCE_RATIO,
            velocityWindowMs: VELOCITY_WINDOW_MS,
            openVelocity: OPEN_VELOCITY,
            closeVelocity: CLOSE_VELOCITY,
            lockPx: LOCK_PX,
            cooldownMs: COOLDOWN_MS,
            startZonePx: startZonePxFor(viewportWidthPx),
            viewportWidthPx,
            drawerOpen: lockDrawerOpen,
        }, { dx, dy, velX: vel }, rtl);
    // The mount-frame split must never survive into a terminal state: reveal
    // the contents (no-op unless armed this stroke) before any release or
    // commit animation.
    revealDrawerContent();
    // Terminal styles, per verdict. CLOSE commits are LATE: animate the inline
    // transform into the closed slot and flip the host only when the drawer is
    // already off-screen (commitFollowClose → commitWithAnimation) — flipping
    // first swaps the sidebar subtree mid-animation (measured: width 280→206
    // at t≈200ms of the 280ms transition, tx jumped backward). OPEN verdicts
    // and the revert/modal/cooldown paths keep the plain release: the host
    // stays in its current state, so its own transition finishes the motion
    // and no subtree swap can be in flight. Every path either releases or
    // hands the inline pair to the pending commit — it can never leak.
    if (armedOpen) {
        // The host is already open (early commit). Keep it on 'open', otherwise
        // animate back into the slot and flip closed.
        if (verdict === 'open') {
            releaseFollowStyles();
            cooldownUntil = performance.now() + COOLDOWN_MS;
        }
        else {
            commitFollowClose(ctx);
        }
        if (event.target instanceof Element)
            markStrokeConsumed(event.target);
        return;
    }
    if (!(event.target instanceof Element))
        return;
    if (verdict === 'close') {
        // Mark the stroke consumed so the tap's synthetic click cannot
        // double-toggle or navigate a row. The mark walks the ancestor chain up
        // to the DRAWER (not the frame): the synthetic click always lands on the
        // stroke's own start target (left-edge start zone / drawer content), never
        // on the backdrop — but the backdrop is a frame child, so marking up to
        // the frame would make the host treat a genuine backdrop tap within the
        // 300ms window as consumed and swallow the close (the "tap twice to close"
        // bug). Marking stays IMMEDIATE even though the flip is late: the mark
        // snapshots the chain now, and the synthetic click arrives within ~10ms.
        markStrokeConsumed(event.target);
        commitFollowClose(ctx);
        return;
    }
    releaseFollowStyles();
    if (verdict === 'open') {
        // Unreachable for a tracked stroke (an unarmed stroke is by definition
        // drawer-open at start), but keep the host-service commit symmetric.
        markStrokeConsumed(event.target);
        ctx.layout.toggleSidebar();
        cooldownUntil = performance.now() + COOLDOWN_MS;
    }
}
/**
 * Mark the released stroke so its synthetic click cannot re-toggle the drawer
 * or activate a row.
 *
 * The mark walks the ancestor chain up to the DRAWER when the stroke started
 * inside it: the backdrop is a frame child, so stopping at the frame would
 * make the host treat a genuine backdrop tap within the window as consumed
 * and swallow the close (the "tap twice to close" bug). A stroke that started
 * OUTSIDE the drawer (the left-edge start zone, or — since closing accepts
 * the whole frame — the backdrop itself) has no drawer in its chain, so the
 * walk would otherwise run all the way to the document root and briefly
 * shadow every tap on the page; the frame is the tightest correct stop for
 * those, and it is what must be marked anyway, because a backdrop-started
 * close stroke needs its own overlay click consumed.
 */
function markStrokeConsumed(target) {
    const drawer = findDrawer();
    const upTo = drawer !== null && drawer.contains(target) ? drawer : (0, phone_chrome_ts_1.getFrame)() ?? null;
    (0, gesture_guard_ts_1.markGestureConsumed)(target, CONSUME_WINDOW_MS, upTo);
    consumedEl = target;
}
/** Forget stroke state (called on cancel / visibility change / blur). */
function reset() {
    trackingPointer = 0;
    tracking = false;
    samples = [];
    (0, gesture_guard_ts_1.clearStrokeLocked)();
}
/** The logical reading direction of the frame (RTL support). */
function frameRtl() {
    const frame = (0, phone_chrome_ts_1.getFrame)();
    return frame !== null && getComputedStyle(frame).direction === 'rtl';
}
/** Install the gesture layer for the current mobile breakpoint. */
function installSidebarSwipe(ctx) {
    (0, phone_chrome_ts_1.installMobileEffect)(ctx, 'dsh-web-mobile: sidebar swipe gestures', () => {
        const viewportWidth = () => window.innerWidth || document.documentElement.clientWidth || 0;
        const onPointerDown = (event) => {
            // A new pointer starts a new interaction epoch: drop the previous
            // stroke's click gate. When the browser never delivers the synthetic
            // click (iOS shells suppress it after a swipe), this — together with
            // the short CONSUME_WINDOW_MS — keeps the next genuine tap alive
            // instead of eating it at the document-capture click handler.
            consumedEl = null;
            (0, gesture_guard_ts_1.clearStrokeLocked)(); // belt-and-suspenders: a lost stroke must not leak its lock into this epoch
            if (event.pointerType !== 'touch' && event.pointerType !== 'pen')
                return;
            // A second finger means the browser owns this interaction (pinch zoom),
            // and a two-finger drag is never a drawer swipe. Merely ignoring the
            // extra pointer would keep the stroke alive — and with it the
            // touchmove preventDefault below, which cancels the native pinch. On
            // iOS that pinch is the only way back out of a zoom, so fighting it
            // recreates exactly the trap #45 reported. Hand the whole interaction
            // back instead.
            if (trackingPointer !== 0 && trackingPointer !== event.pointerId) {
                abortStroke(ctx);
                return;
            }
            beginStroke(event, frameRtl(), viewportWidth());
        };
        const onPointerMove = (event) => {
            if (event.pointerId !== trackingPointer)
                return;
            // A modal may rise mid-stroke (e.g. an a11y trap opening) — spec review
            // 缺陷 1's guard, now per-MOVE because B 档 paints a transform the
            // modal must not inherit: abandon and spring the drawer back.
            if (modalOpen() || takeoverActive()) {
                abortStroke(ctx);
                return;
            }
            if (!tracking) {
                // A long-press selection can appear AFTER pointerdown but BEFORE the
                // axis lock (#43 second timing window): abandon the stroke and hand
                // the touch back so the handles become draggable (reset() also lifts
                // the touchmove preventDefault). Once locked the gesture stays
                // committed — a selection never appears mid-swipe.
                if (selectionOwnsStroke()) {
                    reset();
                    return;
                }
                if (tryLock(event)) {
                    pushSample(event);
                    applyFollow(ctx, event.clientX - startX);
                }
            }
            else {
                pushSample(event);
                applyFollow(ctx, event.clientX - startX);
            }
        };
        const onPointerUp = (event) => {
            if (event.pointerId !== trackingPointer)
                return;
            endStroke(ctx, event, frameRtl(), viewportWidth());
        };
        const onPointerCancel = (event) => {
            if (event.pointerId !== trackingPointer)
                return;
            abortStroke(ctx);
        };
        // The browser may synthesize a click a few ms after the stroke's
        // pointerup. The host overlay handlers and the FAB / backdrop element
        // listeners would treat it as a tap; swallow it at document capture so
        // a swipe can never toggle twice or navigate a row. Non-gesture taps
        // (no live mark) pass through untouched.
        //
        // A click whose target is (or is inside) the backdrop or the FAB is
        // NEVER a gesture's synthetic click: the stroke start is always the
        // left-edge start zone or the drawer content, never the backdrop (outside
        // the drawer, on the right) or the FAB. The mark chain can reach them
        // in degenerate hit-test cases (e.g. a stroke starting on a point where
        // the empty drawer does not register as the event target), and
        // swallowing that click would break the "tap the backdrop to close"
        // path — the "tap twice to close" bug. Let those clicks through.
        const onClick = (event) => {
            if (consumedEl === null)
                return;
            if (!(event.target instanceof Element))
                return;
            // A genuine backdrop / FAB tap is always let through: their own click
            // listeners toggle the drawer, and a consume mark that walked to the
            // document root would otherwise swallow it ("tap twice to close").
            // The one exception is a click on the overlay element that STARTED the
            // just-committed stroke — since close strokes may begin anywhere over
            // the frame, the backdrop can now be the stroke's own start target,
            // and letting its synthetic click through would re-toggle the drawer
            // straight back open.
            const overlay = event.target.closest('[data-mobile-nav="backdrop"], [data-mobile-nav="fab"]');
            if (overlay !== null && !overlay.contains(consumedEl))
                return;
            if (!(0, gesture_guard_ts_1.consumeIfGestured)(event))
                return;
            event.stopPropagation();
            event.preventDefault();
            consumedEl = null;
        };
        const onVisibility = () => {
            if (document.hidden)
                abortStroke(ctx);
        };
        // Edge-touch priority (iOS UIScreenEdgePanGestureRecognizer semantics):
        // a stroke that began inside the left-edge start zone must never be
        // claimed by native scrolling. touch-action: pan-y already forbids the
        // browser from panning it horizontally; this preventDefault (passive:
        // false) additionally stops the vertical-scroll claim, so the pointer
        // event stream reaches the gesture layer intact on browsers where the
        // scroller wins the race (iOS Safari in particular — headless cannot
        // reproduce that behavior). Vertical-dominant strokes abandon the
        // gesture (reset() clears trackingPointer), so scrolling resumes for
        // touches that were never swipes. Strokes starting inside a genuinely
        // horizontally scrollable container never reach this state at all
        // (beginStroke rejects them via findHorizontalScroller), so their
        // native horizontal pan is never prevented.
        //
        // Multi-touch is the one case that must never be prevented: two fingers
        // on the screen mean a pinch, and preventDefault on those touchmoves
        // cancels the browser's zoom gesture. The pointerdown guard above
        // already abandons the stroke when a second finger lands; this is the
        // belt-and-braces path for engines that hand the gesture to the
        // compositor without delivering a second pointerdown (#46 real-device
        // report: pinch-out zoomed but pinch-in would not zoom back).
        const onTouchMove = (event) => {
            if (trackingPointer === 0)
                return;
            if (event.touches.length > 1) {
                abortStroke(ctx);
                return;
            }
            event.preventDefault();
        };
        document.addEventListener('pointerdown', onPointerDown, true);
        document.addEventListener('pointermove', onPointerMove, true);
        document.addEventListener('pointerup', onPointerUp, true);
        document.addEventListener('pointercancel', onPointerCancel, true);
        document.addEventListener('click', onClick, true);
        document.addEventListener('touchmove', onTouchMove, { capture: true, passive: false });
        const onBlur = () => abortStroke(ctx);
        document.addEventListener('visibilitychange', onVisibility);
        window.addEventListener('blur', onBlur);
        return () => {
            document.removeEventListener('pointerdown', onPointerDown, true);
            document.removeEventListener('pointermove', onPointerMove, true);
            document.removeEventListener('pointerup', onPointerUp, true);
            document.removeEventListener('pointercancel', onPointerCancel, true);
            document.removeEventListener('click', onClick, true);
            document.removeEventListener('touchmove', onTouchMove, { capture: true });
            document.removeEventListener('visibilitychange', onVisibility);
            window.removeEventListener('blur', onBlur);
            abortStroke(ctx, true);
        };
    });
}
};
__modules["effects/subagent-chip-touch.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.installSubagentChipTouch = installSubagentChipTouch;
const phone_chrome_ts_1 = require("./effects/phone-chrome.js");
/**
 * Touch support for the lineage-count chip ("N 个子代理") that
 * `dsh-client-ui-subagent` renders in the session header.
 *
 * Upstream history (both observed live on the served bundle):
 *
 * 1. The original count-variant trigger shipped without an onClick handler
 *    (`onClick: openTitle === void 0 ? void 0 : …`) and drove its card purely
 *    through onMouseEnter/onMouseLeave hover timers — enter arms a 150 ms
 *    open timer, leave arms a 120 ms close timer, and each cancels the
 *    other. On touch devices every tap makes the browser synthesize paired
 *    mouseenter/mouseleave from its tracked mouse position, which usually
 *    differs from the tap point: taps did nothing, or the card popped back
 *    open ~200 ms after an outside close (the “点了没反应 / 自弹回” era,
 *    hash ZKlsPq).
 *
 * 2. 0.1.0-rc.6 (hash h8S2Va) removed the hover timers and gave the trigger
 *    a native `onClick: () => changeOpen(!open)`. A phone tap now crosses
 *    TWO toggle sources: the browser fires pointerup first (this shim
 *    dispatches the synthetic ArrowDown there, capture phase — BEFORE the
 *    click), which opens the card through the component's own keyboard
 *    path, and then the tap's click reaches the native onClick, which
 *    toggles the card right back shut. The two toggles cancel each other:
 *    the panel flashes open for a frame and is gone (「闪退」), and the
 *    chip reads as unresponsive.
 *
 * Fix strategy, scoped to touch pointers (mouse users keep native hover):
 * 1. Toggle the card ourselves along the component's own keyboard path —
 *    ArrowDown keydown on the trigger opens (+focus first row), Escape
 *    closes (both verified against the live component in both upstream
 *    versions). React delivers dispatched KeyboardEvents to onKeyDown like
 *    any bubbling event.
 * 2. Swallow the tap's own follow-up click on the trigger we just toggled,
 *    so a native onClick (era 2) can never cancel the keyboard-path
 *    toggle. On the hover-only build the click never toggled anything, so
 *    swallowing it is a no-op — one deterministic toggle per tap across
 *    both upstreams.
 * 3. For a short window after every touch pointer activity, swallow trusted
 *    synthesized mouseover/out/enter/leave events targeting the lineage
 *    root or its menu, so era-1 hover timers can neither cancel our toggle
 *    nor resurrect a just-closed card (no-op on rc.6, which has no hover
 *    timers at all).
 */
/** Count-variant trigger only: the switcher variant has its own onClick. */
const CHIP_TRIGGER_SELECTOR = '[data-mobile-nav="frame"] button[class*="_trigger"][aria-haspopup="tree"][aria-expanded]:not([class*="_switcherTrigger"])';
/**
 * Lineage root plus its menu. NOTE: `ZKlsPq` (hover-only era) and `h8S2Va`
 * (0.1.0-rc.6) are the dsh-client-ui-subagent CSS-module hashes — audit
 * these selectors when the package upgrades.
 */
const HOVER_SUBTREE_SELECTOR = '[class*="ZKlsPq_root"], [class*="ZKlsPq_menu"], [class*="h8S2Va_root"], [class*="h8S2Va_menu"]';
/** How long after touch activity synthesized hover events stay suppressed. */
const SWALLOW_WINDOW_MS = 800;
/**
 * How long the tap's follow-up click stays suppressed on the trigger we
 * toggled through the keyboard path. A touch click lands a few ms after its
 * pointerup; 1 s is a generous upper bound that still expires before the
 * user's next deliberate tap.
 */
const CLICK_GRACE_MS = 1000;
const SWALLOWED_TYPES = ['mouseover', 'mouseout', 'mouseenter', 'mouseleave'];
function installSubagentChipTouch(ctx) {
    (0, phone_chrome_ts_1.installMobileEffect)(ctx, 'dsh-web-mobile: lineage chip touch toggle', () => {
        if (typeof PointerEvent === 'undefined')
            return undefined;
        let swallowUntil = 0;
        const armSwallowWindow = () => {
            swallowUntil = Date.now() + SWALLOW_WINDOW_MS;
        };
        // The trigger whose tap we just toggled through the keyboard path, and
        // how long that tap's follow-up click must be suppressed on it.
        let toggledTrigger = null;
        let toggledUntil = 0;
        const onPointerUp = (event) => {
            if (event.pointerType !== 'touch' && event.pointerType !== 'pen')
                return;
            armSwallowWindow();
            const target = event.target;
            if (!(target instanceof Element))
                return;
            const trigger = target.closest(CHIP_TRIGGER_SELECTOR);
            if (trigger === null)
                return;
            const open = trigger.getAttribute('aria-expanded') === 'true';
            // The component's own keyboard path: navigate() treats ArrowDown as
            // open (+focus first row) and Escape as close-with-focus-restore.
            trigger.dispatchEvent(new KeyboardEvent('keydown', {
                key: open ? 'Escape' : 'ArrowDown',
                bubbles: true,
                cancelable: true,
            }));
            toggledTrigger = trigger;
            toggledUntil = Date.now() + CLICK_GRACE_MS;
        };
        /**
         * The tap's own click must not re-toggle the trigger: on 0.1.0-rc.6 the
         * trigger carries a native onClick (changeOpen(!open)) that would cancel
         * the keyboard-path toggle fired on pointerup — the flash-and-close
         * race. stopPropagation() at document capture blocks the click from
         * reaching the container-level React delegation (so the trigger's
         * onClick never runs) while letting other document listeners observe it.
         * Identity-checked, so taps on menu rows or anywhere else pass through
         * untouched.
         */
        const onClick = (event) => {
            if (toggledTrigger === null)
                return;
            if (Date.now() >= toggledUntil) {
                toggledTrigger = null;
                return;
            }
            const target = event.target;
            if (!(target instanceof Element))
                return;
            if (target.closest(CHIP_TRIGGER_SELECTOR) !== toggledTrigger)
                return;
            toggledTrigger = null;
            event.stopPropagation();
        };
        const onAnyPointerActivity = (event) => {
            if (event.pointerType !== 'touch' && event.pointerType !== 'pen')
                return;
            armSwallowWindow();
            void event;
        };
        const swallowSyntheticHover = (event) => {
            if (Date.now() >= swallowUntil)
                return;
            if (!event.isTrusted)
                return;
            const target = event.target;
            if (!(target instanceof Element))
                return;
            if (target.closest(HOVER_SUBTREE_SELECTOR) === null)
                return;
            event.stopImmediatePropagation();
        };
        document.addEventListener('pointerdown', onAnyPointerActivity, true);
        document.addEventListener('pointerup', onPointerUp, true);
        document.addEventListener('click', onClick, true);
        for (const type of SWALLOWED_TYPES) {
            document.addEventListener(type, swallowSyntheticHover, true);
        }
        return () => {
            document.removeEventListener('pointerdown', onAnyPointerActivity, true);
            document.removeEventListener('pointerup', onPointerUp, true);
            document.removeEventListener('click', onClick, true);
            for (const type of SWALLOWED_TYPES) {
                document.removeEventListener(type, swallowSyntheticHover, true);
            }
        };
    });
}
};
__modules["effects/session-menu.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.installSessionMenuDelete = installSessionMenuDelete;
const phone_chrome_ts_1 = require("./effects/phone-chrome.js");
// Mirrored from src/client/locales.ts: the custom client bundler cannot
// resolve `../` requires from effects/. Keep in sync.
const NS = 'mobileNav';
/** The ui-workspace dictionary namespace the host session menu labels come from. */
const WORKSPACE_NS = 'workspace';
/** Marker on the injected menu item (idempotence across React re-renders). */
const DELETE_ITEM_MARKER = 'data-mobile-nav="session-delete"';
/** Danger accent read from the theme, with a fixed fallback. */
const DANGER_COLOR = 'var(--dsw-alias-state-error-primary, #b91c1c)';
/** 16px outline trash glyph (IconTrashOutline16 path), currentColor-filled. */
const TRASH_SVG = '<svg width="16" height="16" viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg">'
    + '<path d="M14.4782 4.84067L14.2138 10.1152C14.1102 12.1872 14.067 13.0115 13.3866 13.9607C13.1044 14.3546 12.7498 14.6912 12.3424 14.9535C11.8239 15.2872 11.2415 15.4316 10.5585 15.4998C9.88727 15.5668 9.04946 15.5656 7.99998 15.5656C6.95051 15.5656 6.1127 15.5668 5.44142 15.4998C4.75851 15.4316 4.17602 15.2872 3.65753 14.9535C3.25012 14.6912 2.89559 14.3546 2.61332 13.9607C1.93296 13.0115 1.88979 12.1872 1.78619 10.1152L1.52179 4.84067L2.89006 4.77277L3.15343 10.0463C3.26221 12.2218 3.32452 12.6015 3.72646 13.1624C3.90825 13.4161 4.13686 13.6334 4.39927 13.8023C4.66204 13.9714 5.00263 14.0792 5.57825 14.1367C6.16562 14.1953 6.92298 14.1963 7.99998 14.1963C9.07699 14.1963 9.83434 14.1953 10.4217 14.1367C10.9973 14.0792 11.3379 14.1367 11.6007 13.8023C11.8631 13.6334 12.0917 13.4161 12.2735 13.1624C12.6755 12.6015 12.7378 12.2218 12.8465 10.0463L13.1099 4.77277L14.4782 4.84067ZM5.43011 6.22849H6.7994V11.3909H5.43011V6.22849ZM9.20056 6.22849H10.5699V11.3909H9.20056V6.22849ZM8.53597 0.434431C9.17976 0.434431 9.6522 0.426926 10.0966 0.571258C10.2357 0.616451 10.3717 0.672554 10.502 0.738948C10.9182 0.951107 11.2464 1.29099 11.7015 1.74612L12.4978 2.54136H15.3742V3.91169H0.625732V2.54136H3.50218L4.29845 1.74612C4.75358 1.29099 5.08174 0.951107 5.49801 0.738948C5.62831 0.672554 5.76425 0.616451 5.90334 0.571258C6.34776 0.426926 6.82021 0.434431 7.46399 0.434431H8.53597ZM7.46399 1.80476C6.73208 1.80476 6.51641 1.81187 6.32617 1.87369C6.25545 1.89667 6.18668 1.92533 6.12041 1.95907C5.96398 2.03878 5.82348 2.16253 5.44142 2.54136H10.5585C10.1765 2.16253 10.036 2.03878 9.87955 1.95907C9.81329 1.92533 9.74452 1.89667 9.6738 1.87369C9.48356 1.81187 9.26789 1.80476 8.53597 1.80476H7.46399Z" fill="currentColor" /></svg>';
/** Escape text destined for innerHTML (session titles are user content). */
function escapeHtml(value) {
    return value
        .replaceAll('&', '&amp;')
        .replaceAll('<', '&lt;')
        .replaceAll('>', '&gt;')
        .replaceAll('"', '&quot;');
}
/**
 * Install the mobile session-delete menu machinery. Mobile-only: the whole
 * effect arms under the ≤1023px breakpoint and is a complete no-op on
 * desktop. Returns a disposer (via installMobileEffect) that removes every
 * listener, observer, injected node, and the confirm dialog.
 * @param ctx - client root context.
 */
function installSessionMenuDelete(ctx) {
    (0, phone_chrome_ts_1.installMobileEffect)(ctx, 'dsh-web-mobile: session-menu delete', () => {
        const navT = ctx.locale.bind(NS);
        // Host workspace-browser dictionary for menu-signature detection. Bound
        // lazily so a later-registered dictionary is picked up; the general
        // overload accepts the raw namespace id.
        const wsT = (key, params) => ctx.locale.bind(WORKSPACE_NS)(key, params);
        let anchor = null;
        let injectRaf = 0;
        let dialogHost = null;
        let closeDialogOnKey = null;
        /** Resolve one session id for a row: title match, group position tiebreak. */
        const resolveSessionId = (row, title) => {
            const sessions = ctx.sessions.list.getSnapshot();
            const workspaces = ctx.workspaces.list.getSnapshot();
            const archived = new Set(workspaces.archivedSessionIds);
            const candidates = sessions.ids.filter(id => {
                const summary = sessions.byId[id];
                return summary !== undefined && !summary.blank && summary.displayTitle === title && !archived.has(id);
            });
            if (candidates.length === 1)
                return candidates[0];
            if (candidates.length === 0)
                return undefined;
            // Duplicate titles: the row's position among its group's same-title
            // rows maps 1:1 onto the same-title ids of that group's account.
            const group = row.closest('[class*="_groupSection"]');
            if (group === null)
                return undefined;
            const headerTitle = group
                .querySelector(':scope > [class*="_projectRow"] [class*="_title"]')
                ?.textContent?.trim();
            const owned = new Set(workspaces.items.flatMap(workspace => workspace.sessionIds));
            const workspace = headerTitle === undefined
                ? undefined
                : workspaces.items.find(candidate => candidate.title === headerTitle);
            const workspaceIds = workspace === undefined ? [] : workspace.sessionIds;
            const groupIds = workspace === undefined
                ? sessions.ids.filter(id => !owned.has(id) && !archived.has(id) && sessions.byId[id] !== undefined)
                : workspaceIds.filter(id => !archived.has(id) && sessions.byId[id] !== undefined);
            const sameTitleGroupIds = groupIds.filter(id => sessions.byId[id]?.displayTitle === title);
            const rows = [...group.querySelectorAll(':scope > [class*="_sessionRow"]')];
            const rowIndex = rows.indexOf(row);
            const sameTitleBefore = rowIndex === -1
                ? 0
                : rows.slice(0, rowIndex).filter(candidate => candidate.querySelector('[class*="_title"]')?.textContent?.trim() === title).length;
            return sameTitleGroupIds[sameTitleBefore];
        };
        /** Whether a menu list is the host's per-session row menu. */
        const isSessionMenu = (menu) => {
            const labels = [...menu.querySelectorAll('[role="menuitem"] [class*="_itemLabel"]')]
                .map(element => element.textContent?.trim() ?? '');
            const rename = wsT('rename');
            const fork = wsT('menu.fork');
            const archive = wsT('menu.archiveSession');
            return labels.length === 3 && labels.includes(rename) && labels.includes(fork) && labels.includes(archive);
        };
        const closeDialog = () => {
            if (closeDialogOnKey !== null) {
                document.removeEventListener('keydown', closeDialogOnKey, true);
                closeDialogOnKey = null;
            }
            if (dialogHost !== null) {
                dialogHost.backdrop.remove();
                dialogHost.card.remove();
                dialogHost = null;
            }
        };
        /** Show the delete confirmation as a bottom card over the frame. */
        const showDeleteDialog = (sessionId, title) => {
            closeDialog();
            const frame = (0, phone_chrome_ts_1.getFrame)() ?? document.body;
            const backdrop = document.createElement('div');
            backdrop.dataset.mobileNav = 'delete-dialog-backdrop';
            const card = document.createElement('div');
            card.dataset.mobileNav = 'delete-dialog';
            card.setAttribute('role', 'dialog');
            card.setAttribute('aria-modal', 'true');
            card.innerHTML = `
        <div data-mobile-nav="delete-confirm-title">${escapeHtml(navT('deleteConfirmTitle'))}</div>
        <div data-mobile-nav="delete-confirm-desc">${escapeHtml(navT('deleteConfirmDesc', { title }))}</div>
        <div data-mobile-nav="delete-confirm-actions">
          <button type="button" data-mobile-nav="delete-confirm-no">${escapeHtml(navT('deleteConfirmNo'))}</button>
          <button type="button" data-mobile-nav="delete-confirm-yes">${escapeHtml(navT('deleteConfirmYes'))}</button>
        </div>
        <div data-mobile-nav="delete-error" role="alert" hidden></div>`;
            const noButton = card.querySelector('[data-mobile-nav="delete-confirm-no"]');
            const yesButton = card.querySelector('[data-mobile-nav="delete-confirm-yes"]');
            const errorLine = card.querySelector('[data-mobile-nav="delete-error"]');
            noButton?.addEventListener('click', closeDialog);
            backdrop.addEventListener('click', closeDialog);
            const onKey = (event) => {
                if (event.key === 'Escape')
                    closeDialog();
            };
            document.addEventListener('keydown', onKey, true);
            closeDialogOnKey = onKey;
            const resetButtons = () => {
                if (yesButton !== null) {
                    yesButton.disabled = false;
                    yesButton.textContent = navT('deleteConfirmYes');
                }
                if (noButton !== null)
                    noButton.disabled = false;
            };
            const fail = (message) => {
                if (errorLine !== null) {
                    errorLine.textContent = message;
                    errorLine.hidden = false;
                }
                resetButtons();
            };
            const mapError = (payload, reason) => {
                const code = payload?.error?.code;
                if (code === 'session-not-found')
                    return navT('deleteErrorNotFound');
                if (code === 'session-busy')
                    return navT('deleteErrorBusy');
                const message = payload?.error?.message ?? (reason instanceof Error ? reason.message : String(reason));
                return navT('deleteErrorGeneric', { message });
            };
            yesButton?.addEventListener('click', async () => {
                yesButton.disabled = true;
                if (noButton !== null)
                    noButton.disabled = true;
                yesButton.textContent = navT('deletePending');
                if (errorLine !== null)
                    errorLine.hidden = true;
                const wasCurrent = ctx.sessions.list.getSnapshot().current === sessionId;
                try {
                    const response = await fetch('/api/mobile-nav.session.delete', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ sessionId }),
                    });
                    const payload = await response.json().catch(() => null);
                    if (!response.ok || payload === null || payload.ok !== true) {
                        fail(mapError(payload, new Error(`HTTP ${response.status}`)));
                        return;
                    }
                }
                catch (reason) {
                    fail(mapError(null, reason));
                    return;
                }
                closeDialog();
                if (wasCurrent)
                    ctx.sessions.clear();
                // Repull the baseline so the deleted row disappears. Must be called AS
                // A METHOD on ctx.sessions: refresh() reads `this.manager`, and an
                // extracted reference would throw "this is undefined" — the failure
                // mode that left deleted cold sessions lingering as ghost rows.
                const sessions = ctx.sessions;
                await sessions.refresh?.();
                if (wasCurrent)
                    ctx.layout.toggleSidebar();
            });
            frame.appendChild(backdrop);
            frame.appendChild(card);
            dialogHost = { backdrop, card };
        };
        /** Show a non-destructive error card (session could not be resolved). */
        const showError = (message) => {
            closeDialog();
            const frame = (0, phone_chrome_ts_1.getFrame)() ?? document.body;
            const backdrop = document.createElement('div');
            backdrop.dataset.mobileNav = 'delete-dialog-backdrop';
            const card = document.createElement('div');
            card.dataset.mobileNav = 'delete-dialog';
            card.setAttribute('role', 'dialog');
            card.setAttribute('aria-modal', 'true');
            card.innerHTML = `
        <div data-mobile-nav="delete-confirm-title">${escapeHtml(navT('deleteSession'))}</div>
        <div data-mobile-nav="delete-error" role="alert">${escapeHtml(message)}</div>
        <div data-mobile-nav="delete-confirm-actions">
          <button type="button" data-mobile-nav="delete-confirm-no">${escapeHtml(navT('deleteConfirmNo'))}</button>
        </div>`;
            card.querySelector('[data-mobile-nav="delete-confirm-no"]')?.addEventListener('click', closeDialog);
            backdrop.addEventListener('click', closeDialog);
            const onKey = (event) => {
                if (event.key === 'Escape')
                    closeDialog();
            };
            document.addEventListener('keydown', onKey, true);
            closeDialogOnKey = onKey;
            frame.appendChild(backdrop);
            frame.appendChild(card);
            dialogHost = { backdrop, card };
        };
        /** Inject the delete item into one open session menu (idempotent). */
        const injectInto = (menu) => {
            if (menu.querySelector(`[${DELETE_ITEM_MARKER}]`) !== null)
                return;
            const template = menu.querySelector('[role="menuitem"]');
            const wrap = template?.parentElement;
            const viewport = menu.querySelector('[class*="_viewport"]');
            if (template === null || wrap === null || wrap === undefined || viewport === null)
                return;
            const clone = wrap.cloneNode(true);
            const button = clone.querySelector('[role="menuitem"]');
            if (button === null)
                return;
            const icon = button.querySelector('[class*="_itemIcon"]');
            if (icon !== null) {
                icon.innerHTML = TRASH_SVG;
                icon.style.color = DANGER_COLOR;
            }
            const label = button.querySelector('[class*="_itemLabel"]');
            if (label !== null) {
                label.textContent = navT('deleteSession');
                label.style.color = DANGER_COLOR;
            }
            button.setAttribute('data-mobile-nav', 'session-delete');
            button.addEventListener('click', (event) => {
                event.preventDefault();
                event.stopPropagation();
                const captured = anchor;
                // Close the host menu by toggling its anchor (React-owned state).
                captured?.button.click();
                try {
                    if (captured === null || captured === undefined) {
                        showError(navT('deleteErrorResolve'));
                        return;
                    }
                    const sessionId = resolveSessionId(captured.row, captured.title);
                    if (sessionId === undefined) {
                        showError(navT('deleteErrorResolve'));
                        return;
                    }
                    showDeleteDialog(sessionId, captured.title);
                }
                catch (reason) {
                    // Never fail silently: surface internal resolution errors instead of
                    // leaving the tap with no visible result.
                    console.error('[dsh-web-mobile] session delete failed:', reason);
                    showError(navT('deleteErrorGeneric', {
                        message: reason instanceof Error ? reason.message : String(reason),
                    }));
                }
            });
            viewport.appendChild(clone);
        };
        /** Inject into every open session menu. */
        const injectAll = () => {
            for (const menu of document.querySelectorAll('[role="menu"]')) {
                if (isSessionMenu(menu))
                    injectInto(menu);
            }
        };
        const scheduleInject = () => {
            if (injectRaf !== 0)
                return;
            injectRaf = requestAnimationFrame(() => {
                injectRaf = 0;
                injectAll();
            });
        };
        // Capture the ⋯ button click before React handles it, so the row/title
        // are known when the portaled menu appears. The host renders the anchor
        // button WITHOUT `aria-haspopup` (Menu renders `{anchor}` verbatim), so
        // the row's single button IS the ⋯ anchor — no attribute to match on.
        const onDocumentClick = (event) => {
            const target = event.target;
            if (target === null)
                return;
            const row = target.closest('[class*="_sessionRow"]');
            if (row === null)
                return;
            const button = row.querySelector('button');
            if (button === null)
                return;
            const title = row.querySelector('[class*="_title"]')?.textContent?.trim() ?? '';
            anchor = { button, row, title };
            scheduleInject();
        };
        document.addEventListener('click', onDocumentClick, true);
        // Re-inject whenever a menu list mounts/updates (React recreates the list
        // on every open, so the injected node must follow).
        const observer = new MutationObserver((records) => {
            for (const record of records) {
                if (record.type !== 'childList')
                    continue;
                const target = record.target;
                if (target === document.body) {
                    scheduleInject();
                    break;
                }
                if (target instanceof HTMLElement
                    && (target.matches('[role="menu"]') || target.closest('[role="menu"]') !== null)) {
                    scheduleInject();
                    break;
                }
            }
        });
        observer.observe(document.body, { childList: true, subtree: true });
        injectAll();
        return () => {
            document.removeEventListener('click', onDocumentClick, true);
            observer.disconnect();
            if (injectRaf !== 0)
                cancelAnimationFrame(injectRaf);
            closeDialog();
            anchor = null;
        };
    });
}
};
__modules["effects/composer-keyboard-guard.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.installComposerKeyboardGuard = installComposerKeyboardGuard;
const phone_chrome_ts_1 = require("./effects/phone-chrome.js");
/**
 * iOS keyboard guard for the composer's fixed control cluster.
 *
 * Upstream `dsh-client-ui-conversation` (0.1.2-rc.1) hangs the same
 * `keepFocus` handler on the composer row's three buttons (send, stop, the
 * `+` commands trigger):
 *
 *   const keepFocus = (e) => {
 *     e.preventDefault()
 *     editor?.getRootElement()?.focus({ preventScroll: true })
 *   }
 *
 * `onMouseDown` keeps the caret in the editor across button clicks on
 * desktop. On iOS WebKit the same handler runs inside the tap's synthesized
 * mousedown, and that programmatic `focus()` call re-raises the on-screen
 * keyboard whenever it had closed (scroll-to-dismiss, keyboard dismissal,
 * PWA relaunch) while logical focus never left the contenteditable. The
 * user taps Send on a collapsed keyboard and the keyboard springs back up
 * over the running conversation — the message still sends, but the screen
 * is now half keyboard.
 *
 * Fix strategy, scoped to iOS WebKit (the engine that re-raises keyboards
 * from a programmatic focus; Android/desktop behavior is untouched):
 *
 * In the capture phase of every mousedown whose target sits inside the
 * composer card but is NOT the editing surface itself, temporarily install
 * an own no-op `focus` property on the `[data-composer-input]` element.
 * React's `keepFocus` then calls the shadow instead of the prototype
 * method, the keyboard stays down, and the shadow is removed on the next
 * macrotask so nothing outlives the tap:
 *
 *   capture mousedown → shadow focus → (bubbling) keepFocus → click →
 *   macrotask restore
 *
 * Why shadowing instead of intercepting the event: `keepFocus`'s own
 * `preventDefault()` must keep running (it stops the tap from blurring
 * the editor), and the editor's own tap-to-type path must never be
 * touched — only the button-initiated programmatic focus is undesirable
 * on iOS. A capture-phase `stopPropagation` would break both.
 *
 * DOM contract (verified against 0.1.2-rc.1 dsh-client-ui-conversation):
 * - `[data-composer-card]` — the composer card root (InputBar).
 * - `[data-composer-input]` — the Lexical contenteditable surface.
 * - The buttons carry hashed `_primary`/`_add` classes and no stable
 *   data marker, so the card boundary (not the buttons) is the anchor.
 * Audit both markers when the conversation package upgrades.
 */
/** The composer card root that owns the fixed control cluster. */
const COMPOSER_CARD_SELECTOR = '[data-composer-card]';
/** The Lexical editing surface (the only element allowed to raise the keyboard). */
const COMPOSER_INPUT_SELECTOR = '[data-composer-input]';
/** Re-arm marker kept on the editor element while its focus is shadowed. */
const SHADOW_MARKER = 'data-mobile-nav-focus-shadow';
function installComposerKeyboardGuard(ctx) {
    (0, phone_chrome_ts_1.installMobileEffect)(ctx, 'dsh-web-mobile: composer keyboard guard', () => {
        // Only iOS/iPadOS WebKit re-raises the dismissed keyboard on a
        // programmatic focus; other engines get no listener at all.
        if (!(0, phone_chrome_ts_1.detectIosWebKit)(navigator, typeof CSS !== 'undefined' && typeof CSS.supports === 'function' ? CSS.supports.bind(CSS) : null)) {
            return undefined;
        }
        const restore = () => {
            const el = document.querySelector(`[${SHADOW_MARKER}]`);
            if (el === null)
                return;
            el.removeAttribute(SHADOW_MARKER);
            const shadowed = el;
            if (Object.prototype.hasOwnProperty.call(el, 'focus'))
                delete shadowed.focus;
        };
        const onMouseDown = (event) => {
            const target = event.target;
            if (!(target instanceof Element))
                return;
            if (typeof target.closest !== 'function')
                return;
            const card = target.closest(COMPOSER_CARD_SELECTOR);
            if (card === null)
                return;
            const editor = card.querySelector(COMPOSER_INPUT_SELECTOR);
            if (editor === null || target.closest(COMPOSER_INPUT_SELECTOR) !== null)
                return;
            // A button-area tap: shadow focus for the remainder of this dispatch.
            restore();
            editor.setAttribute(SHADOW_MARKER, '');
            Object.defineProperty(editor, 'focus', {
                configurable: true,
                writable: true,
                value: function swallowedFocus() {
                    /* keepFocus called; keep the dismissed keyboard dismissed */
                },
            });
            setTimeout(restore, 0);
        };
        document.addEventListener('mousedown', onMouseDown, true);
        return () => {
            document.removeEventListener('mousedown', onMouseDown, true);
            restore();
        };
    });
}
};
__modules["core/raf-scheduler.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.createRafScheduler = createRafScheduler;
function createRafScheduler(raf, caf) {
    let pending = 0;
    let queued = false;
    return {
        schedule(fn) {
            if (queued)
                return;
            queued = true;
            pending = raf(() => {
                queued = false;
                fn();
            });
        },
        cancel() {
            if (!queued)
                return;
            caf(pending);
            queued = false;
        },
    };
}
};
__modules["debug.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.installDebugBadge = installDebugBadge;
const phone_chrome_ts_1 = require("./effects/phone-chrome.js");
/**
 * Debug badge — ?mobile-nav-debug=1
 * Renders a live state overlay (URL, viewport, media queries, shell chrome,
 * aionui columns, genui cards, captured errors) so a phone-side repro can be
 * diagnosed without guessing. No-op unless the query param is present.
 */
function installDebugBadge(ctx) {
    ctx.effect(() => {
        if (!new URLSearchParams(location.search).has('mobile-nav-debug'))
            return () => { };
        const errors = [];
        const onError = (event) => errors.push(`ERR ${event.message.slice(0, 120)}`);
        const onRejection = (event) => errors.push(`REJ ${String(event.reason).slice(0, 120)}`);
        window.addEventListener('error', onError);
        window.addEventListener('unhandledrejection', onRejection);
        const badge = document.createElement('div');
        badge.style.cssText = [
            'position:fixed', 'top:40px', 'right:6px', 'z-index:2147483000',
            'background:rgba(0,0,0,.82)', 'color:#fff', 'font:11px/1.5 ui-monospace,monospace',
            'padding:8px 10px', 'border-radius:8px', 'max-width:94vw', 'max-height:70vh',
            'overflow:auto', 'white-space:pre-wrap', 'pointer-events:none',
        ].join(';');
        const read = () => {
            const q = (sel) => !!document.querySelector(sel);
            const vis = (sel) => {
                const el = document.querySelector(sel);
                return el === null ? 'absent' : getComputedStyle(el).visibility;
            };
            const frame = document.querySelector('[data-mobile-nav="frame"]');
            return [
                `build 20260906 (overlay takeover re-scope)`,
                `URL ${location.pathname}${location.search}`,
                `W ${innerWidth} x ${innerHeight} dpr ${devicePixelRatio}`,
                `mq≤1023 ${matchMedia(phone_chrome_ts_1.MOBILE_QUERY).matches}  mq≥1024 ${matchMedia(phone_chrome_ts_1.DESKTOP_QUERY).matches}`,
                `css ${q('style[data-plugin-css*="mobile"]')}  frame ${!!frame}`,
                `previewCol ${vis('[data-aionui-preview-col]')}  explorerCol ${vis('[data-aionui-explorer-col]')}`,
                `previewOpen ${frame?.hasAttribute('data-aionui-preview-open') ?? '?'}  explorerOpen ${frame?.hasAttribute('data-aionui-explorer-open') ?? '?'}  previewFull ${frame?.hasAttribute('data-mobile-preview-full') ?? '?'}`,
                `header ${vis('[data-phase] header')}  composer ${q('textarea, [data-composer-input]')}`,
                `genui cards ${document.querySelectorAll('[data-genui]').length}  panel ${q('[data-genui-panel]')}`,
                `phase ${document.querySelector('[data-phase]')?.getAttribute('data-phase') ?? '?'}`,
                `errs ${errors.slice(-5).join(' | ') || 'none'}`,
            ].join('\n');
        };
        const paint = () => { badge.textContent = read(); };
        paint();
        // Never re-enter on the badge's own textContent mutations: paint() writes
        // into a body subtree, so a naive full-tree observer would feed its own
        // output back into paint() forever and starve the page (observed as a hard
        // freeze with ?mobile-nav-debug=1).
        const observer = new MutationObserver((records) => {
            for (const record of records) {
                if (record.target === badge || badge.contains(record.target))
                    continue;
                paint();
                return;
            }
        });
        observer.observe(document.body, { childList: true, subtree: true, attributes: true });
        const timer = setInterval(paint, 1500);
        document.body.appendChild(badge);
        return () => {
            window.removeEventListener('error', onError);
            window.removeEventListener('unhandledrejection', onRejection);
            observer.disconnect();
            clearInterval(timer);
            badge.remove();
        };
    }, 'dsh-web-mobile: debug badge');
}
};
__modules["i18n/locales.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.en = exports.zh = exports.NS = void 0;
/** `mobileNav` namespace dictionaries: drawer controls. */
exports.NS = 'mobileNav';
/** Simplified Chinese dictionary (the key-set source of truth). */
exports.zh = {
    'open': '打开目录',
    'close': '收起目录',
    'backdrop': '点击关闭目录',
    'sessionLog': '导出会话日志',
    'files': '文件浏览',
    'previewFullscreen': '全屏预览',
    'previewExitFullscreen': '退出全屏',
    'deleteSession': '删除会话',
    'deleteConfirmTitle': '删除会话？',
    'deleteConfirmDesc': '将删除「{title}」的完整会话记录，此操作不可恢复。',
    'deleteConfirmYes': '删除',
    'deleteConfirmNo': '取消',
    'deletePending': '正在删除…',
    'deleteErrorBusy': '该会话正在运行且无法停止，请稍后重试。',
    'deleteErrorNotFound': '会话不存在或已被删除。',
    'deleteErrorResolve': '无法确定要删除的会话，请重试。',
    'deleteErrorGeneric': '删除失败：{message}',
};
/** English dictionary, key-identical to the Chinese source of truth. */
exports.en = {
    'open': 'Open directory',
    'close': 'Close directory',
    'backdrop': 'Click to close directory',
    'sessionLog': 'Session log',
    'files': 'Files',
    'previewFullscreen': 'Fullscreen preview',
    'previewExitFullscreen': 'Exit fullscreen',
    'deleteSession': 'Delete session',
    'deleteConfirmTitle': 'Delete session?',
    'deleteConfirmDesc': 'The complete log of “{title}” will be permanently removed. This cannot be undone.',
    'deleteConfirmYes': 'Delete',
    'deleteConfirmNo': 'Cancel',
    'deletePending': 'Deleting…',
    'deleteErrorBusy': 'This session is running and could not be stopped. Try again later.',
    'deleteErrorNotFound': 'The session does not exist or was already deleted.',
    'deleteErrorResolve': 'Could not identify the session to delete. Please try again.',
    'deleteErrorGeneric': 'Delete failed: {message}',
};
};
__modules["index.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.inject = void 0;
exports.apply = apply;
const MobileNavToggle_tsx_1 = require("./components/MobileNavToggle.js");
const MobileDrawerFooter_tsx_1 = require("./components/MobileDrawerFooter.js");
const index_ts_1 = require("./styles/index.js");
const phone_chrome_ts_1 = require("./effects/phone-chrome.js");
const sidebar_swipe_ts_1 = require("./effects/sidebar-swipe.js");
const subagent_chip_touch_ts_1 = require("./effects/subagent-chip-touch.js");
const session_menu_ts_1 = require("./effects/session-menu.js");
const composer_keyboard_guard_ts_1 = require("./effects/composer-keyboard-guard.js");
const aionui_compat_ts_1 = require("./effects/aionui-compat.js");
const raf_scheduler_ts_1 = require("./core/raf-scheduler.js");
const debug_ts_1 = require("./debug.js");
const locales_ts_1 = require("./i18n/locales.js");
/** Required services (cordis fiber inject — the loader passes all module exports as an object plugin). */
exports.inject = ['slots', 'layout', 'locale', 'sessionLogDownload', 'sessions', 'workspaces', 'sidebarRight'];
/**
 * Mobile-adaptive shell, browser half: injects the mobile stylesheet, then
 * contributes the directory toggle to the session header and the backdrop +
 * floating button to the shell overlay.
 * @param ctx - client root context.
 */
function apply(ctx) {
    ctx.effect(() => ctx.locale.register(locales_ts_1.NS, { zh: locales_ts_1.zh, en: locales_ts_1.en }), 'dsh-web-mobile: dictionaries');
    ctx.effect(() => {
        const tag = document.createElement('style');
        tag.dataset.plugin = 'dsh-web-mobile';
        tag.dataset.pluginCss = 'dsh-web-mobile/mobile.css';
        tag.textContent = index_ts_1.MOBILE_CSS;
        // DSHA：0.1.5 的文件/预览属于右侧面板，在手机上覆盖会话而不压缩输入区。
        tag.textContent += `
@media (max-width: 1023px) and (pointer: coarse) {
  [data-mobile-nav="frame"] [data-rightbar-col] {
    position: fixed !important; inset: 0 !important; width: 100vw !important;
    height: 100% !important; z-index: 35; pointer-events: none;
  }
  [data-mobile-nav="frame"] [data-sidebar-right-panel] {
    width: 100% !important; max-width: 100vw !important;
    box-sizing: border-box; padding-top: env(safe-area-inset-top, 0px);
    padding-bottom: env(safe-area-inset-bottom, 0px);
  }
  [data-sidebar-right-panel][data-sidebar-right-open] { pointer-events: auto; }
  /* 标签关闭按钮由上游按 20px 居中定位，不能套用普通工具按钮的最小高度。 */
  [data-sidebar-right-panel] button:not([data-dockkit-tab-close]) { min-height: 32px; }
}`;

        document.head.appendChild(tag);
        // Keep this stylesheet last in <head> so its overrides win over the
        // host UI's own styles (some host rules also use !important).
        setTimeout(() => {
            if (tag.isConnected)
                document.head.appendChild(tag);
        }, 0);
        return () => {
            tag.remove();
        };
    }, 'dsh-web-mobile: styles');
    // Hard-fix the installed-plugins list text layout: the host market UI
    // injects its own CSS after this plugin's stylesheet, so CSS overrides can
    // be beaten. Inline !important styles win over every external rule. Keep
    // the selector on outer rows only; irowActions/irowTrailing are nested
    // flex containers and must retain the market's own action geometry.
    ctx.effect(() => {
        const mq = window.matchMedia(phone_chrome_ts_1.MOBILE_QUERY);
        const rowSelector = '[class*="irow"]:not([class*="irowActions"]):not([class*="irowTrailing"])';
        const set = (el, props) => {
            for (const [key, value] of Object.entries(props)) {
                el.style.setProperty(key, value, 'important');
            }
        };
        const unset = (el, props) => {
            for (const key of props)
                el.style.removeProperty(key);
        };
        const rowProps = ['flex-wrap', 'align-items', 'gap'];
        const firstProps = ['flex', 'max-width', 'min-width'];
        const textProps = ['white-space', 'overflow', 'text-overflow', 'max-width'];
        const clear = () => {
            document.querySelectorAll(rowSelector).forEach((row) => {
                unset(row, rowProps);
                const first = row.children[0];
                if (first)
                    unset(first, firstProps);
                row.querySelectorAll(':scope > button, :scope > [class*="owner"], :scope > [class*="grow"]').forEach((el) => {
                    unset(el, ['order']);
                });
                const spec = row.querySelector('[class*="spec"]');
                const nm = row.querySelector('[class*="nm"]');
                if (spec)
                    unset(spec, textProps);
                if (nm)
                    unset(nm, textProps);
            });
        };
        const apply = () => {
            // The market rows only exist while the market UI is mounted (inside a
            // settings dialog). Skip the full-document class-substring scan on every
            // streamed mutation frame with no dialog open; dshmarket keeps the
            // data-dsh-market-root marker (1.20.x), [role="dialog"] covers the
            // settings dialog generically so a marker change degrades to cost, not
            // to a silently dead effect.
            if (document.querySelector('[data-dsh-market-root], [role="dialog"]') === null)
                return;
            document.querySelectorAll(rowSelector).forEach((row) => {
                set(row, {
                    'flex-wrap': 'wrap',
                    'align-items': 'center',
                    'gap': '4px 10px',
                });
                const first = row.children[0];
                if (first) {
                    set(first, {
                        'flex': '1 1 100%',
                        'max-width': '100%',
                        'min-width': '0',
                    });
                }
                const spec = row.querySelector('[class*="spec"]');
                const nm = row.querySelector('[class*="nm"]');
                if (spec) {
                    set(spec, {
                        'white-space': 'nowrap',
                        'overflow': 'hidden',
                        'text-overflow': 'ellipsis',
                        'max-width': '100%',
                    });
                }
                if (nm) {
                    set(nm, {
                        'white-space': 'nowrap',
                        'overflow': 'hidden',
                        'text-overflow': 'ellipsis',
                        'max-width': '100%',
                    });
                }
            });
        };
        const arm = () => {
            clear();
            if (mq.matches)
                apply();
        };
        arm();
        // Streaming floods this observer with document-wide childList batches;
        // coalesce to one apply per frame and re-check the breakpoint at flush
        // time so a queued callback never writes mobile styles on desktop.
        const scheduler = (0, raf_scheduler_ts_1.createRafScheduler)((cb) => window.requestAnimationFrame(cb), (id) => window.cancelAnimationFrame(id));
        const mo = new MutationObserver(() => {
            if (mq.matches)
                scheduler.schedule(() => { if (mq.matches)
                    apply(); });
        });
        mo.observe(document.documentElement, { childList: true, subtree: true });
        mq.addEventListener('change', arm);
        return () => {
            scheduler.cancel();
            mo.disconnect();
            mq.removeEventListener('change', arm);
            clear();
        };
    }, 'dsh-web-mobile: installed-list-inline-styles');
    // Shared mobile infrastructure: frame marker ownership and the single
    // full-tree reconciler. Installed inside one effect so a plugin reload in
    // the same JS environment tears the whole reconciler down and rebuilds it.
    ctx.effect(() => {
        const stops = [
            (0, phone_chrome_ts_1.installFrameController)(),
            (0, phone_chrome_ts_1.installReconciler)(ctx),
            (0, phone_chrome_ts_1.registerReconcileTasks)(ctx),
        ];
        return () => {
            for (const stop of stops)
                stop();
        };
    }, 'dsh-web-mobile: reconciler infrastructure');
    // Drawer close interactions: Escape and navigation taps inside the drawer.
    (0, phone_chrome_ts_1.installOverlayInteractions)(ctx);
    // Session deletion, injected into each session row's ⋯ menu (beside
    // rename / fork / archive) with a confirm dialog. Mobile-only.
    (0, session_menu_ts_1.installSessionMenuDelete)(ctx);
    // Sidebar swipe gestures: edge swipe-in opens the drawer, content swipe-out
    // closes it (release-classified, zero inline transforms — A 档).
    (0, sidebar_swipe_ts_1.installSidebarSwipe)(ctx);
    // Lineage-count chip: reliable open/close on touch pointers (upstream is
    // hover-timer driven and has no onClick on the count variant).
    (0, subagent_chip_touch_ts_1.installSubagentChipTouch)(ctx);
    // iOS: tapping the composer's send/stop/+ buttons must not re-raise the
    // dismissed keyboard (upstream keepFocus focuses the editor on mousedown).
    (0, composer_keyboard_guard_ts_1.installComposerKeyboardGuard)(ctx);
    (0, phone_chrome_ts_1.installPhoneChrome)(ctx);
    (0, aionui_compat_ts_1.installAionuiCompat)(ctx);
    // Debug badge (?mobile-nav-debug=1): live state overlay for phone-side
    // repros. No-op without the query param (docs: README, AGENTS.md).
    (0, debug_ts_1.installDebugBadge)(ctx);
    ctx.slots.inject('conversation.session.header.actions', () => ctx.slots.register({
        name: 'conversation.session.header.actions',
        id: 'mobile-nav-toggle',
        order: 10,
        locale: locales_ts_1.NS,
        inject: () => ({
            toggleSidebar: () => ctx.layout.toggleSidebar(),
            openFiles: () => ctx.sidebarRight.openTab('files'),
        }),
    }, MobileNavToggle_tsx_1.MobileNavToggle));
    // Session log download, relocated from the session header to the drawer
    // footer on mobile (the header capsule is hidden by CSS); the drawer
    // footer also hosts the Files action that opens the dsh-web-ui explorer
    // sheet.
    //
    // Footer stacking relies on the list-slot sort by (priority, order):
    // dsh-remote-web-ui leaves it unset (default 0, its two icon buttons stay
    // on top) and dsh-usage-stats uses 10. Order 5 keeps the Files + Session
    // log pills directly under the icon row with the usage/balance badge
    // below them — instead of a tie at 10 where registration order could
    // wedge the badge between the icons and the pills.
    ctx.slots.inject('sidebar.footer.action', () => ctx.slots.register({
        name: 'sidebar.footer.action',
        id: 'mobile-nav-session-log',
        order: 5,
        locale: locales_ts_1.NS,
        inject: () => ({
            // The component's internal id is a plain string (slot runtime typing);
            // the host-generation brand boundary lives here and only here, hence
            // the double assertion (string and Branded<'SessionId'> do not overlap).
            downloadSessionLog: (sessionId) => ctx.sessionLogDownload.download(sessionId),
            toggleSidebar: () => ctx.layout.toggleSidebar(),
            openFiles: () => ctx.sidebarRight.openTab('files'),
        }),
    }, MobileDrawerFooter_tsx_1.MobileDrawerFooter));
}
};
var __cache = {};
function __localRequire(id) {
  if (id.charCodeAt(0) !== 46) return require(id);
  id = id.slice(2);
  var cached = __cache[id];
  if (cached) return cached.exports;
  var module = { exports: {} };
  __cache[id] = module;
  __modules[id](__localRequire, module, module.exports);
  return module.exports;
}
var module = { exports: {} };
__modules["index.js"](__localRequire, module, module.exports);
return module.exports; } });
