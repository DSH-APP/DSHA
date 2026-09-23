window.__ModuleLoader__.load({ id: "dsh-web-mobile", factory: (require) => {
var __modules = {};
__modules["core/icon-compat.js"] = function (require, module, exports) {
"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
Object.defineProperty(exports, "__esModule", { value: true });
exports.IconFolderOpen = exports.IconPanelLeft = exports.IconDownload = exports.IconPaperclip = void 0;
const primitives = __importStar(require("@deepseek-ai/dsh-client-ui-primitives"));
/** 命名缺失时的兜底：渲染成空，绝不抛错。 */
const missingIcon = () => null;
/** 按候选名字顺序在宿主模块里寻找图标组件。 */
const pickIcon = (names) => {
    const table = primitives;
    for (const name of names) {
        const found = table[name];
        if (found !== undefined)
            return found;
    }
    return missingIcon;
};
/** 输入区文件入口（回形针）。 */
exports.IconPaperclip = pickIcon(['IconPaperclipOutlineRegular', 'IconPaperclipOutline16']);
/** 抽屉页脚的会话日志导出。 */
exports.IconDownload = pickIcon(['IconDownloadOutlineRegular', 'IconDownloadOutline16']);
/** 会话头部的目录抽屉开关。 */
exports.IconPanelLeft = pickIcon(['IconPanelLeftOutlineRegular', 'IconPanelLeftOutline16']);
/** 会话头部的 Files/右侧栏入口。 */
exports.IconFolderOpen = pickIcon(['IconFolderOpenOutlineRegular', 'IconFolderOpenOutline16']);
};
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
__modules["effects/session-row-fiber.js"] = function (require, module, exports) {
"use strict";
// session-row-fiber.ts — DOM-free core behind the drawer session-row tap
// fallback (#49 / PR #49): on some WebKit/iOS builds a tap on a session row
// never produces a `click`, so the row's own React onClick never runs and
// navigation silently does nothing. The runtime half resolves the tapped
// element's fiber with `reactFiberOf`, walks it with `findSessionIdInFiber`,
// and hands the id to the host. This file is the PURE half, so the walk is
// unit-testable with no DOM, no renderer and no DSH runtime.
//
// Deliberately has ZERO import statements, like reconciler-core.ts: node:test
// loads it directly through Node's native type stripping, and the client bundle
// has nothing to resolve. Nothing here touches `document` or `window`.
//
// Two measured facts from the live 0.1.5-rc.2 host shaped the walk:
//  - the row ITEM fiber is 3 hops above the tapped element and carries the
//    session id at `props.node.id`;
//  - hop 32 is a `ScopeProvider` ancestor whose `props.scope` is the literal
//    string 'session-maybe'. Anything trusting the SHAPE of an id
//    (/^session[-_]/) would hand that string to `ctx.sessions.open(id)`, and
//    that contract fails loud on unknown ids. So a value counts only when the
//    caller's `isKnownId` accepts it — there is no shape-based fallback, and a
//    "plausible looking id" must never be guessed.
Object.defineProperty(exports, "__esModule", { value: true });
exports.FIBER_WALK_LIMIT = void 0;
exports.findSessionIdInFiber = findSessionIdInFiber;
exports.reactFiberOf = reactFiberOf;
exports.isTapWithinSlop = isTapWithinSlop;
/**
 * Hop budget for one walk. The measured row item fiber is 3 hops up and the
 * whole chain is well under this; the bound exists so an unexpectedly deep or
 * malformed chain cannot spin.
 */
exports.FIBER_WALK_LIMIT = 60;
/** Props keys holding an OBJECT with an `.id` (row items, session records). */
const OBJECT_KEYS = ['node', 'session', 'summary', 'result'];
/** Props keys holding the id STRING directly. */
const ID_KEYS = ['sessionId', 'id'];
/** React stamps a fiber on a node under a per-renderer random suffix. */
const FIBER_KEY_PREFIXES = ['__reactFiber$', '__reactInternalInstance$'];
/** `value.id` when `value` is an object carrying a string id, else null. */
function objectIdOf(value) {
    if (typeof value !== 'object' || value === null)
        return null;
    const id = value.id;
    return typeof id === 'string' ? id : null;
}
/** The first id this hop's props offer that `isKnownId` accepts. */
function acceptedIdInProps(props, isKnownId) {
    if (props === null || props === undefined)
        return null;
    for (const key of OBJECT_KEYS) {
        const candidate = objectIdOf(props[key]);
        if (candidate !== null && isKnownId(candidate))
            return candidate;
    }
    for (const key of ID_KEYS) {
        const candidate = props[key];
        if (typeof candidate === 'string' && isKnownId(candidate))
            return candidate;
    }
    return null;
}
/**
 * Walk from `fiber` towards the root (`.return`) and return the session id of
 * the NEAREST hop offering one the caller knows. A hop whose candidate is
 * rejected does not stop the walk, so an outer row item fiber still wins over
 * an inner fiber carrying an unrelated or stale id. Returns null when nothing
 * within `limit` hops (the starting fiber counts as the first) is accepted.
 */
function findSessionIdInFiber(fiber, isKnownId, limit = exports.FIBER_WALK_LIMIT) {
    let hop = fiber;
    for (let walked = 0; walked < limit && hop !== null && hop !== undefined; walked += 1) {
        const found = acceptedIdInProps(hop.memoizedProps, isKnownId);
        if (found !== null)
            return found;
        hop = hop.return;
    }
    return null;
}
/**
 * The React fiber a DOM node (or any renderer-stamped object) carries: React
 * assigns it under `__reactFiber$<rendererKey>` and keeps the legacy
 * `__reactInternalInstance$<rendererKey>` alias. First stamp wins; null when
 * the value is not a stamped object.
 */
function reactFiberOf(instance) {
    if (instance === null || instance === undefined)
        return null;
    if (typeof instance !== 'object')
        return null;
    const record = instance;
    for (const prefix of FIBER_KEY_PREFIXES) {
        for (const key of Object.keys(record)) {
            if (!key.startsWith(prefix))
                continue;
            const fiber = record[key];
            if (typeof fiber === 'object' && fiber !== null)
                return fiber;
        }
    }
    return null;
}
/**
 * Whether a pointer release still counts as a tap: it stayed within `slopPx` on
 * BOTH axes (max-norm, not Euclidean). The drawer list scrolls vertically, so a
 * 60px vertical drift must not navigate.
 */
function isTapWithinSlop(from, to, slopPx) {
    return Math.abs(to.x - from.x) <= slopPx && Math.abs(to.y - from.y) <= slopPx;
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
__modules["core/sessions-compat.js"] = function (require, module, exports) {
"use strict";
// Sessions service shape drifted in 0.1.6-alpha.2 (audit doc §10.1): a2
// removed `ISessions.open`/`clear` and `SessionListState.current` /
// `currentAddress`, moving selection to per-session `retainedBy` counters
// (upstream reads it as
// `Object.values(byId).find(s => (s.retainedBy.mainView ?? 0) > 0)?.id`).
// These helpers let call sites stay compile-green against rc.2 typings while
// degrading explicitly on an a2 host instead of throwing or silently dying.
Object.defineProperty(exports, "__esModule", { value: true });
exports.currentSessionIdOf = currentSessionIdOf;
exports.sessionsCanClear = sessionsCanClear;
exports.sessionsCanOpen = sessionsCanOpen;
/** The current session id: rc.2's `current` field when present, else the a2
 *  main-view-retained session. Undefined when the shape matches neither. */
function currentSessionIdOf(list) {
    if (typeof list !== 'object' || list === null)
        return undefined;
    const snapshot = list;
    if (typeof snapshot.current === 'string')
        return snapshot.current;
    for (const key in snapshot.byId) {
        const summary = snapshot.byId[key];
        // for-in guarantees the key exists, not the value — an explicitly
        // undefined property still reaches the guard below.
        if (summary === undefined)
            continue;
        const mainView = summary.retainedBy?.mainView;
        if (typeof mainView === 'number' && mainView > 0 && typeof summary.id === 'string')
            return summary.id;
    }
    return undefined;
}
/** a2 removed `clear()` (selection lifecycle moved to the retain model). */
function sessionsCanClear(sessions) {
    return typeof sessions?.clear === 'function';
}
/** a2 removed `open()`; callers must degrade (armNav fallback in
 *  phone-chrome) instead of throwing inside the capture pointerup listener. */
function sessionsCanOpen(sessions) {
    return typeof sessions?.open === 'function';
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
    // The strip must stay inside the composer stack, but it need not be a
    // DIRECT child of it: 0.1.5 nests the status row (bOPqQW_root) under the
    // composer card wrapper (uV2eYG_root), so the marked element sits one level
    // deeper than on rc.2 hosts. Ancestry is still the right test — only the
    // marker's own subtree position changed, not its container relationship.
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
    // 2026-09-23（店主最终确认）：**环要、百分比数字不要** —— 把这块挪进输入框行
    // 的右簇（模型/麦克风旁），再由 CSS 用 font-size:0 只留环、隐掉 "45%" 文本。
    // 它原本独占统计行右侧 63px + 12px 间距；挪走后统计条拿满整宽 326px，
    // 「轮次·步数·tok/s」+「tok 总量·缓存命中」约 316px 完整放下，不滚动也不省略。
    // 与 moveTps 同款：幂等 + 记录原位，dispose（宽屏档）时放回官方布局。
    let ringOrigin = null;
    const moveRing = (stats) => {
        const holder = stats.parentElement;
        const dock = holder === null ? null : holder.parentElement;
        if (dock === null)
            return;
        const ring = [...dock.children].find((child) => !child.contains(stats) && /\d\s*%/.test(child.textContent ?? ''));
        if (ring === undefined)
            return;
        const row = document.querySelector('[data-composer-card] [class*="_row"] [class*="_trailing"]');
        if (row === null)
            return;
        if (ring.parentElement === row)
            return;
        if (ring.parentElement !== null) {
            ringOrigin = { parent: ring.parentElement, next: ring.nextSibling };
        }
        ring.setAttribute('data-mobile-nav', 'stats-ring');
        row.insertBefore(ring, row.querySelector(':scope > [class*="_primary"]'));
    };
    const mark = () => {
        // Fast path: the marked strip usually survives React rebuilds between
        // tokens; re-verifying the anchor is O(1) while the full-tree hunt below
        // grows with the conversation. moveTps still re-runs so a rebuilt TPS
        // readout is re-folded.
        const anchor = document.querySelector('[data-mobile-nav="stats"]');
        if (anchor !== null && statsAnchorAlive(anchor)) {
            moveTps(anchor);
            moveRing(anchor);
            return;
        }
        // Stale marker on a node that left the composer stack/phase context:
        // drop it so the slow path can re-anchor cleanly.
        anchor?.removeAttribute('data-mobile-nav');
        // Scope decision: the status row is a DESCENDANT of the composer stack,
        // not necessarily its child. On rc.2 hosts it is a direct child (its own
        // `_root`); on 0.1.5 the composer card wrapper (uV2eYG_root) sits between
        // the stack and the row (bOPqQW_root), so requiring a direct child made
        // the hunt permanently miss and the strip was never marked (measured: row
        // present at 16,814 carrying "8 turns 582 steps · 103 tok/s" while
        // [data-mobile-nav="stats"] was absent). Body blocks outside the stack are
        // still skipped by the containment test below.
        const stack = document.querySelector('[class*="_composerStack"]');
        if (stack === null)
            return;
        for (const root of stack.querySelectorAll('[class*="_root"]')) {
            // The status row lives inside the composer stack. The query is already
            // scoped to the stack, so every candidate is inside it by construction —
            // message-area blocks that mention turns/steps never enter this loop. (A
            // `stack.contains(root)` guard stood here and its comment claimed to skip
            // those blocks; it was unreachable.)
            // The todo plan strip also lives in the composer stack and its root
            // ends in _root. Its items may legitimately contain "步"/"steps" in
            // their text, so never mistake it (or any interactive dock panel)
            // for the stats strip.
            if (root.matches('[data-testid="todo-panel"]'))
                continue;
            // Dock panels are skipped by never marking a candidate whose buttons are
            // actionable controls. 0.1.5 renders the status row ITSELF as two
            // popover buttons (bOPqQW_pill, aria-haspopup="dialog"), so an
            // "any button" guard excluded the one row this task exists to mark
            // (measured: bOPqQW_root rejected solely by hasButton, marker count 0).
            // Every popover button counts as a status widget: the composer's real
            // controls (model bar, context meter) carry no metrics text and are
            // filtered by the text test above, and a panel with an actionable button
            // still fails here.
            const buttons = root.querySelectorAll('button');
            if (buttons.length > 0 && ![...buttons].every((button) => button.getAttribute('aria-haspopup') !== null))
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
            moveRing(root);
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
            // 上下文环放回统计行旁边，宽屏档恢复官方布局。
            if (ringOrigin !== null && ringOrigin.parent.isConnected) {
                for (const ring of document.querySelectorAll('[data-mobile-nav="stats-ring"]')) {
                    ringOrigin.parent.insertBefore(ring, ringOrigin.next);
                    ring.removeAttribute('data-mobile-nav');
                }
            }
            ringOrigin = null;
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
/** The FAB's second face: an arrow, shown while a sidebar panel owns the main
 * area (see the panelViewOpen note in the task body). */
const FAB_BACK_ICON = '<svg viewBox="0 0 16 16" fill="none" aria-hidden="true" width="18" height="18">' +
    '<path d="M9.8 3.4 5.2 8l4.6 4.6" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/>' +
    '</svg>';
const FAB_DRAWER_ICON = '<svg viewBox="0 0 16 16" fill="none" aria-hidden="true" width="18" height="18">' +
    '<path fill-rule="evenodd" clip-rule="evenodd" d="M9.67272 0.522841C10.8339 0.522841 11.76 0.522714 12.4963 0.602493C13.2453 0.683657 13.8789 0.854248 14.4264 1.25197C14.7504 1.48739 15.0355 1.77247 15.2709 2.0965C15.6686 2.64394 15.8392 3.27758 15.9204 4.02655C16.0002 4.7629 16 5.68895 16 6.85014V9.14986C16 10.3111 16.0002 11.2371 15.9204 11.9735C15.8392 12.7224 15.6686 13.3561 15.2709 13.9035C15.0355 14.2275 14.7504 14.5126 14.4264 14.748C13.8789 15.1458 13.2453 15.3163 12.4963 15.3975C11.76 15.4773 10.8339 15.4772 9.67272 15.4772H6.3273C5.16611 15.4772 4.24006 15.4773 3.50371 15.3975C2.75474 15.3163 2.1211 15.1458 1.57366 14.748C1.24963 14.5126 0.964549 14.2275 0.729131 13.9035C0.331407 13.3561 0.160817 12.7224 0.0796529 11.9735C-0.000126137 11.2371 1.25338e-09 10.3111 1.25338e-09 9.14986V6.85014C1.25329e-09 5.68895 -0.000126137 4.7629 0.0796529 4.02655C0.160817 3.27758 0.331407 2.64394 0.729131 2.0965C0.964549 1.77247 1.24963 1.48739 1.57366 1.25197C2.1211 0.854248 2.75474 0.683657 3.50371 0.602493C4.24006 0.522714 5.16611 0.522841 6.3273 0.522841H9.67272ZM5.54303 1.88715V14.1118C5.78636 14.1128 6.04709 14.1169 6.3273 14.1169H9.67272C10.8639 14.1169 11.7032 14.1164 12.3493 14.0465C12.9824 13.9779 13.3497 13.8494 13.6268 13.6482C13.8354 13.4966 14.0195 13.3125 14.1711 13.1039C14.3723 12.8268 14.5007 12.4595 14.5693 11.8264C14.6393 11.1803 14.6398 10.341 14.6398 9.14986V6.85014C14.6398 5.65896 14.6393 4.81967 14.5693 4.1736C14.5007 3.54048 14.3723 3.17318 14.1711 2.89609C14.0195 2.68747 13.8354 2.50337 13.6268 2.35179C13.3497 2.1506 12.9824 2.02212 12.3493 1.95353C11.7032 1.88358 10.8639 1.88307 9.67272 1.88307H6.3273C6.04709 1.88307 5.78636 1.8862 5.54303 1.88715ZM4.1828 1.91166C3.99125 1.9216 3.8148 1.93577 3.65076 1.95353C3.01764 2.02212 2.65034 2.1506 2.37325 2.35179C2.16463 2.50337 1.98052 2.68747 1.82895 2.89609C1.62776 3.17318 1.49928 3.54048 1.43069 4.1736C1.36074 4.81967 1.36023 5.65896 1.36023 6.85014V9.14986C1.36023 10.341 1.36074 11.1803 1.43069 11.8264C1.49928 12.4595 1.62776 12.8268 1.82895 13.1039C1.98052 13.3125 2.16463 13.4966 2.37325 13.6482C2.65034 13.8494 3.01764 13.9779 3.65076 14.0465C4.29683 14.1164 5.13612 14.1169 6.3273 14.1169H9.67272C10.8639 14.1169 11.7032 14.1164 12.3493 14.0465C12.9824 13.9779 13.3497 13.8494 13.6268 13.6482C13.8354 13.4966 14.0195 13.3125 14.1711 13.1039C14.3723 12.8268 14.5007 12.4595 14.5693 11.8264C14.6393 11.1803 14.6398 10.341 14.6398 9.14986V6.85014C14.6398 5.65896 14.6393 4.81967 14.5693 4.1736C14.5007 3.54048 14.3723 3.17318 14.1711 2.89609C14.0195 2.68747 13.8354 2.50337 13.6268 2.35179C13.3497 2.1506 12.9824 2.02212 12.3493 1.95353C11.7032 1.88358 10.8639 1.88307 9.67272 1.88307H6.3273C5.13612 1.88307 4.29683 1.88358 3.65076 1.95353C3.47672 1.97129 3.30027 1.98546 3.10872 1.9954L4.1828 1.91166Z" fill="currentColor"/>' +
    '</svg>';
/**
 * @param t - `mobileNav` dictionary.
 * @param toggleSidebar - opens/closes the drawer.
 * @param panelExit - the sidebar-panel exit face (panel-exit.ts). The FAB is the
 *   screen's only control while a panel owns the main area — the header toggle
 *   does not render there — so it doubles as 「返回会话」. Null on a host that
 *   cannot select panels (rc.6), where it stays a plain drawer button.
 */
function createOverlayTask(t, toggleSidebar, panelExit) {
    let backdrop = null;
    let fab = null;
    let backdropRemoveTimer = null;
    /** True while the backdrop carries our inline faded state. Guards the
     * restore branch: while a late close commit is animating, the marker is
     * STILL open, so a plain open-branch restore would undo the pre-fade. */
    let faded = false;
    const drawerOpen = () => {
        const frame = (0, phone_chrome_ts_1.getFrame)();
        if (frame === null)
            return false;
        // The user's call (2026-09-13): our drawer is the one users get, even on
        // hosts that ship their own overlay drawer. The host's version measures
        // 321px wide with z-index:1100 and, notably, NO full-screen backdrop at all
        // (measured: the conversation stays hit-testable beside it), which is the
        // behaviour the phone owner rejected as unusable. So the legacy column
        // rules stay armed and this backdrop keeps being created.
        return !frame.hasAttribute('data-sidebar-collapsed');
    };
    const heroPhase = () => document.querySelector('[data-phase="active"]') === null;
    /**
     * The FAB has two faces. While a sidebar panel owns the main area it reads as
     * 「返回会话」 and leaves the panel; everywhere else it opens the drawer.
     *
     * It matters because the FAB is the ONLY control on screen there: the panel
     * replaces the conversation, so the header (and with it the drawer toggle)
     * does not render, and a panel's own page head carries no way back either.
     * Same button, same corner — only its meaning follows the view.
     */
    const onFabClick = (event) => {
        if (panelExit !== null && panelExit.panelOpen()) {
            event.preventDefault();
            event.stopPropagation();
            panelExit.exit();
            return;
        }
        toggleSidebar();
    };
    /** Icon and accessible name follow the view so the button never reads as a
     * mystery control. Idempotent: ensure() runs on every mutation burst. */
    const syncFab = () => {
        if (fab === null)
            return;
        const exiting = panelExit !== null && panelExit.panelOpen();
        const mode = exiting ? 'exit-panel' : 'open-drawer';
        if (fab.dataset.mobileNavFabMode === mode)
            return;
        fab.dataset.mobileNavFabMode = mode;
        const label = t(exiting ? 'backToConversation' : 'open');
        fab.setAttribute('aria-label', label);
        fab.title = label;
        fab.innerHTML = exiting ? FAB_BACK_ICON : FAB_DRAWER_ICON;
    };
    return {
        name: 'overlay-backdrop-fab',
        scopes: ['*', 'data-sidebar-collapsed', 'data-phase'],
        ensure: () => {
            // Re-armed on every ensure, not once in the factory: core.deactivate()
            // runs dispose() on every MOBILE_QUERY flip (reconciler-core.ts), and
            // reactivation only re-runs ensure() - a hook assigned in the factory
            // body stayed null from the first breakpoint flip onwards, silently
            // killing the backdrop fade.
            fadeHook = () => {
                if (backdrop === null)
                    return;
                faded = true;
                backdrop.style.pointerEvents = 'none';
                backdrop.style.opacity = '0';
            };
            const frame = (0, phone_chrome_ts_1.getFrame)();
            if (frame === null)
                return;
            if (drawerOpen()) {
                if (backdrop === null) {
                    backdrop = document.createElement('div');
                    backdrop.dataset.mobileNav = 'backdrop';
                    backdrop.setAttribute('role', 'button');
                    backdrop.setAttribute('aria-label', t('backdrop'));
                    // No element-level click listener: phone-chrome's capture-phase click
                    // handler owns the backdrop tap. The third-party mobile shim stops
                    // click propagation at the frame for anything outside the drawer, so
                    // a listener down here never sees the tap (2026-09-14).
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
                fab.addEventListener('click', onFabClick);
                frame.appendChild(fab);
            }
            else if ((!heroPhase() || drawerOpen()) && fab !== null) {
                fab.remove();
                fab = null;
            }
            syncFab();
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
__modules["effects/sidebar-swipe.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.startZonePxFor = startZonePxFor;
exports.classifySwipe = classifySwipe;
exports.classifyFilesSwipe = classifyFilesSwipe;
exports.slidingVelocity = slidingVelocity;
exports.hitTestStart = hitTestStart;
exports.filesZoneHit = filesZoneHit;
exports.openStateStartMode = openStateStartMode;
exports.followTranslate = followTranslate;
exports.followOpenTransform = followOpenTransform;
exports.findHorizontalScroller = findHorizontalScroller;
exports.selectionOwnsStroke = selectionOwnsStroke;
exports.closeDrawerAnimated = closeDrawerAnimated;
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
 * wide. The zone STAYS at 45% (2026-09-11 user decision): a brief seventh
 * pass shrank it to 0.25 for the draggable-widget conflict and was rolled
 * back the same day — the user keeps the "anywhere in the left half" feel
 * and the conflict is handled by yield signals instead (the
 * data-mobile-nav-dragging cooperation mark + the floating-widget positional
 * heuristic, see dragMarkYields/findFloatingWidget).
 * History of the constant: 24px (hotspot era) → 48px (third pass, fixed
 * "识别成对话内容滚动") → 96px (fourth pass — at that point the zone also
 * finally cleared Chrome Android's EDGE_WIDTH_DP=48dp history-navigation
 * trigger strip, whose strokes the browser claims and pointercancels; the
 * browser gesture itself is suppressed by the root overscroll-behavior-x:
 * none rule in layout.css.ts) → 0.45×viewport (fifth pass; brief 0.25
 * experiment rolled back) — this value.
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
/**
 * Files-panel (host right sidebar) gesture constants — the right-edge mirror
 * of the drawer layer (spec 2026-09-13-files-swipe-gesture-design.md).
 *
 * FILES_ZONE_RATIO mirrors the drawer's START_ZONE_RATIO: a narrow strip
 * cannot be used because Chrome Android's history-nav edge strip (~48dp)
 * pointercancels strokes starting inside it — the same reason the drawer
 * zone grew to 45% of the viewport.
 */
const FILES_ZONE_RATIO = 0.45;
/**
 * Distance threshold as a fraction of the viewport width for BOTH files
 * directions (≈62px on a 390px phone). The drawer's separate close ratio
 * (0.13) exists because the close stroke follows into its slot; files
 * strokes have no follow, so one ratio serves both directions.
 */
const FILES_DISTANCE_RATIO = 0.16;
/** px/ms velocity threshold for both files directions (drawer parity). */
const FILES_VELOCITY = 0.45;
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
 * Which gesture family owns the current stroke: 'drawer' = the sidebar
 * gestures (every pre-existing behavior, untouched); 'files' = the
 * right-edge files-panel gesture (no follow painting, host-panel commit).
 * Written by beginStroke only; a fresh beginStroke always routes it.
 */
let strokeMode = 'drawer';
/** Files-panel visibility at lock time (the mirror of lockDrawerOpen). */
let lockFilesOpen = false;
/**
 * The files-panel toggle injected at install (openFilesPanel: it toggles by
 * the host's own control state, so open and close share one function).
 * Module-level because endStroke is a module-level function; the default is
 * a no-op so the node:test suite can import the module without a DOM.
 */
let filesToggleFn = () => false;
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
 * Pure decision for the FILES gesture (right-edge zone), the mirror twin of
 * classifySwipe. RTL mirrors the X axis exactly like classifySwipe. The
 * verdict space extends the drawer's with `files` (the files-panel commit:
 * open the panel on a leftward stroke when everything is closed, close it on
 * a rightward stroke when it is open):
 * - leftward-logical strokes only ever mean "open the panel" and fire ONLY
 *   when panel and drawer are BOTH closed — the panel would mount under the
 *   open drawer (z-1100) and be invisible, so the stroke is 'none' (the
 *   2026-09-13 narrowing: a leftward stroke NEVER collapses anything);
 * - rightward-logical strokes close the VISIBLE TOP: drawer open → 'close'
 *   (the animated commitFollowClose path, gated on the drawer's own
 *   distance/velocity thresholds so both families judge a stroke alike);
 *   else panel open → 'files'; else 'none'.
 */
function classifyFilesSwipe(t, m, rtl) {
    const dx = rtl ? -m.dx : m.dx;
    if (Math.abs(dx) <= t.lockPx)
        return 'none';
    if (Math.abs(dx) <= Math.abs(m.dy))
        return 'none';
    const velX = rtl ? -m.velX : m.velX;
    if (dx < 0) {
        if (t.panelOpen || t.drawerOpen)
            return 'none';
        if (-dx / t.viewportWidthPx >= t.distanceRatio)
            return 'files';
        // A fling only counts when it agrees with the stroke's own direction
        // (the same contradiction guard classifySwipe applies).
        if (velX > 0 !== dx > 0)
            return 'none';
        return -velX >= t.velocity ? 'files' : 'none';
    }
    if (t.drawerOpen) {
        // Same gates as the drawer family's close (classifySwipe), including its
        // CLOSE_DISTANCE_RATIO: this cell IS the drawer-close commit path, so the
        // two families must judge one physical stroke alike. Without the gate the
        // files zone's 45% reaches ~66px into the open drawer column at 390px,
        // where a thumb resting on a row drifts ~8px sideways while scrolling —
        // that closed the drawer AND consumed the tap. The leftward cell beside the
        // drawer is already 'none', so this is what makes the families agree.
        const closeRatio = t.drawerCloseDistanceRatio ?? t.distanceRatio;
        if (dx / t.viewportWidthPx >= closeRatio)
            return 'close';
        if (velX <= 0)
            return 'none';
        return velX >= t.velocity ? 'close' : 'none';
    }
    if (t.panelOpen) {
        if (dx / t.viewportWidthPx >= t.distanceRatio)
            return 'files';
        if (velX > 0 !== dx > 0)
            return 'none';
        return velX >= t.velocity ? 'files' : 'none';
    }
    return 'none';
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
 * Geometric start-hit test for the FILES gesture: the pointer went down in
 * the RIGHT edge zone (RTL: LEFT) — the exact mirror of hitTestStart. Pure
 * and viewport-relative.
 */
function filesZoneHit(clientX, viewportWidthPx, rtl, zonePx) {
    const edge = rtl ? clientX : viewportWidthPx - clientX;
    return edge >= 0 && edge <= zonePx;
}
/** Which gesture family owns a stroke that begins while the drawer is OPEN.
 * Inside the drawer body the drawer family always wins (owner's rule
 * 2026-09-17: the drawer's own surface must answer a leftward drag, whether or
 * not the viewport-ratio files zone overlaps it — at 390px that zone starts at
 * x=214, inside the 280px drawer); outside the body the right zone keeps its
 * files routing and its deliberate leftward 'none' verdict (2026-09-13
 * narrowing). */
function openStateStartMode(insideDrawer, inFilesZone) {
    return insideDrawer || !inFilesZone ? 'drawer' : 'files';
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
 * True when the host's right sidebar files panel is currently VISIBLE (any
 * form: fullscreen on phones, docked on tablets). Measured on 0.1.5: the
 * panel element is persistent — the closed fullscreen form stays in the DOM
 * at `visibility: hidden` with its rect pushed to x=viewport-width — so
 * presence alone is not the panel-state read. Detect the open state by the
 * three ways the host can hide it (visibility / display / pushed-off rect);
 * a host without this panel keeps returning false and the explorer fallback
 * applies.
 */
function filesPanelOpen() {
    const panel = document.querySelector('[data-sidebar-right-panel]');
    if (panel === null)
        return false;
    const cs = getComputedStyle(panel);
    if (cs.visibility === 'hidden' || cs.display === 'none')
        return false;
    return panel.getBoundingClientRect().left < window.innerWidth;
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
 * Draggable-element yield mark (2026-09-11, 桌宠拖动冲突 D 方案的 C 侧):
 * a dragging component (desktop pet, floating ball, drag-to-reorder, …)
 * marks itself with `data-mobile-nav-dragging` while its drag is live — on
 * the element the pointer is holding (or any ancestor), or on
 * documentElement/body as a global mark when the dragged node moves around
 * or the dragger prefers not to touch the node tree. The gesture layer
 * reads the mark at pointerdown AND at every axis-lock attempt before the
 * stroke locks: a mark present at either point yields the whole stroke (no
 * drawer arm, no touchmove preventDefault) because the two layers would
 * otherwise both answer the same pointer stream — the exact bug the probe
 * reproduces (draggable-conflict-probe pet.t1: a 56px floating ball dragged
 * rightward inside the fifth-pass 45% zone opened the drawer mid-drag).
 * Semantics mirror selectionOwnsStroke: the mark must be up by the first
 * few move events (a pointerdown handler is the natural place); once the
 * stroke axis-locks the gesture stays committed — a mark appearing
 * mid-locked-stroke does not unwind an already-armed open follow.
 */
function dragMarkYields(event) {
    if (document.documentElement.hasAttribute('data-mobile-nav-dragging'))
        return true;
    if (document.body.hasAttribute('data-mobile-nav-dragging'))
        return true;
    return (event.target instanceof Element &&
        event.target.closest('[data-mobile-nav-dragging]') !== null);
}
/** Upper bound (px) of the "small floating widget" positional heuristic.
 * The real-world reference is dsh-pet's floating ball (kz2Bea_float,
 * position:fixed, measured 148x160 on the live profile page) — 160 would
 * sit exactly on that widget's edge; 200 leaves headroom for sibling
 * plugin widgets while a full-screen overlay (backdrop, sheets, dialogs)
 * still cannot pass. */
const FLOATING_WIDGET_MAX_PX = 200;
/**
 * Floating-widget positional yield (2026-09-11, 悬浮窗拖动冲突 B 侧): plugins
 * ship draggable floating widgets (desktop-pet / floating-ball / draggable
 * panel shapes) that carry NO standard "draggable" DOM mark, yet the user
 * presses the widget itself — so the stroke's start target sits inside that
 * widget's layer. Draggable widgets almost always live in a SMALL
 * freely-positioned layer (position: fixed | absolute, own box ≤ 160px)
 * hovering above the page, so walk the ancestor chain from the event target:
 * the first small positioned ancestor counts as a floating widget and the
 * stroke yields (no arm, no touchmove preventDefault). Pairs with
 * dragMarkYields (cooperation mark) which needs no shape guessing.
 * Excluded: anything inside our own frame subtree — the FAB / backdrop /
 * drawer content carry their own gesture semantics and must never be
 * misread as floating widgets (the closed-state FAB sits in the start zone).
 * ponytail: no DOM-standard draggable signal exists; shape ≈ draggable is an
 * approximation with a known ceiling — a STATIC small positioned element
 * (e.g. a message badge) also yields, costing a stroke start under a
 * ≤160px dot; a REAL widget that misses (bigger layer, static positioning)
 * upgrades via the data-mobile-nav-dragging mark or by raising the cap.
 */
function findFloatingWidget(target) {
    if (target.closest('[data-mobile-nav="frame"]') !== null)
        return null;
    let el = target;
    while (el !== null) {
        if (el instanceof HTMLElement) {
            const cs = getComputedStyle(el);
            if ((cs.position === 'fixed' || cs.position === 'absolute') &&
                el.offsetWidth <= FLOATING_WIDGET_MAX_PX &&
                el.offsetHeight <= FLOATING_WIDGET_MAX_PX) {
                return el;
            }
        }
        el = el.parentElement;
    }
    return null;
}
function floatingWidgetYields(event) {
    return (event.target instanceof Element &&
        findFloatingWidget(event.target) !== null);
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
    if (!tracking || strokeMode !== 'drawer')
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
/** Animate an OPEN drawer into its closed slot and flip the host state once it
 * has landed. Every non-gesture closer (backdrop tap, Escape, navigation taps)
 * routes through this, so a click close animates exactly like a swipe close:
 * the host swaps the pane's subtree AND drops its surface (transparent,
 * borderless, content display:none) at the marker flip, so a plain CSS
 * transition would slide out an invisible shell - the same reason the gesture
 * close uses a late commit (eighth round, 2026-08-29). The OPEN direction needs
 * none of this: the host keeps the pane's visuals until the marker flips, so
 * its own transform transition plays (spec 2026-08-27, A 档).
 * Returns false when the caller must fall back to a plain toggleSidebar(): the
 * drawer is already closed (that call would OPEN it) or the user asked for
 * reduced motion, where the spec degrades the animation instead of adding one. */
function closeDrawerAnimated(ctx) {
    if (!drawerOpen())
        return false;
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches)
        return false;
    const drawer = findDrawer();
    if (drawer === null)
        return false;
    const target = frameRtl()
        ? `translateX(${CLOSED_SLOT_PCT}%)`
        : `translateX(-${CLOSED_SLOT_PCT}%)`;
    commitWithAnimation(ctx, drawer, target);
    return true;
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
    // A live draggable (data-mobile-nav-dragging, see dragMarkYields) owns the
    // stroke: yield before any geometric test so the drawer cannot arm for a
    // drag that starts inside the start zone.
    if (dragMarkYields(event))
        return false;
    // Plugin-shipped draggable floating widgets (pet / floating-ball shapes
    // without any cooperation mark) yield the same way, via the positional
    // heuristic — the user pressed the widget itself.
    if (floatingWidgetYields(event))
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
    const filesZonePx = startZonePxFor(viewportWidthPx, FILES_ZONE_RATIO);
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
        // 2026-09-13 narrowing (user decision): the RIGHT zone beside the drawer
        // now belongs to the files gesture. Its leftward stroke must NOT close
        // the drawer (the files panel would mount UNDER the drawer — z-1100 —
        // and be invisible); its rightward stroke keeps the animated close via
        // classifyFilesSwipe's 'close' verdict. The left zone / drawer content
        // keeps every pre-existing drawer behavior. Drawer-mode follow painting
        // only ever maps LEFTWARD (followTranslate's close branch), which is
        // exactly the direction the narrowing removes from this zone — no close
        // follow is lost by the routing (probe swipe.close-follow-reaches-slot
        // was relocated into the drawer family accordingly).
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
        //
        // 2026-09-17 (owner: the judgment zone must end at the drawer's right
        // edge): inside the drawer BODY the drawer family always wins. The files
        // zone is viewport-relative (0.45 from the right edge = x >= 214 at 390px)
        // and overlaps the 280px drawer by 66px, so that sliver of the drawer's own
        // surface used to answer 'none' to a leftward drag - touching the drawer and
        // dragging left did nothing. Routing on the drawer's live rect (not a
        // hardcoded width) keeps the rule true at every viewport: at >= 509px the
        // 0.45 zone starts right of the drawer and nothing changes. Outside the
        // body the files routing, including its leftward no-op, is untouched.
        const drawer = findDrawer();
        const drawerRect = drawer === null ? null : drawer.getBoundingClientRect();
        const insideDrawer = drawerRect !== null && event.clientX >= drawerRect.left && event.clientX <= drawerRect.right;
        strokeMode = openStateStartMode(insideDrawer, filesZoneHit(event.clientX, viewportWidthPx, rtl, filesZonePx));
    }
    else if (hitTestStart(event.clientX, viewportWidthPx, rtl, { startZonePx: startZonePxFor(viewportWidthPx) })) {
        strokeMode = 'drawer';
    }
    else if (filesZoneHit(event.clientX, viewportWidthPx, rtl, filesZonePx)) {
        strokeMode = 'files';
    }
    else {
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
    // Second timing window for the drag mark (same pattern as the selection
    // check in onPointerMove): the dragger often raises the mark in its own
    // pointerdown/move handler, i.e. AFTER our beginStroke ran. Re-check at
    // every lock attempt so the stroke yields before the axis locks.
    if (dragMarkYields(event) || floatingWidgetYields(event)) {
        reset();
        return false;
    }
    if (Math.abs(dx) <= Math.abs(dy)) {
        // Vertical-dominant: hand the touch back to scrolling.
        reset();
        return false;
    }
    tracking = true;
    lockDrawerOpen = drawerOpen();
    if (strokeMode === 'files') {
        lockFilesOpen = filesPanelOpen();
        // An OPEN drawer shares this stroke (the right-edge rightward close):
        // bind the drawer's close follow so the commit animates exactly like
        // today's right-zone close. applyFollow stays mode-guarded, so no follow
        // ever paints for files strokes — the LEFTWARD narrowing stroke gets no
        // painting either, which is the point (it must not drag the drawer).
        if (lockDrawerOpen)
            startFollow();
        (0, gesture_guard_ts_1.markStrokeLocked)();
        return true;
    }
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
    // The stroke's mode and panel flag must be captured BEFORE reset(): reset()
    // rewrites strokeMode to 'drawer' and clears lockFilesOpen, and the verdict
    // below branches on them. Harmless for drawer strokes (whose mode already
    // reads 'drawer'), fatal for files strokes — the verdict silently degraded
    // to the drawer classifier and every files commit turned into 'none'.
    const filesMode = strokeMode === 'files';
    const filesOpenAtLock = lockFilesOpen;
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
        : filesMode
            ? classifyFilesSwipe({
                distanceRatio: FILES_DISTANCE_RATIO,
                velocity: FILES_VELOCITY,
                lockPx: LOCK_PX,
                viewportWidthPx,
                panelOpen: filesOpenAtLock,
                drawerOpen: lockDrawerOpen,
                // The drawer-open cell commits a drawer close, so it keeps the
                // drawer's own close distance (the spec's "┍ identical to
                // today's close").
                drawerCloseDistanceRatio: CLOSE_DISTANCE_RATIO,
            }, { dx, dy, velX: vel }, rtl)
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
    if (verdict === 'files') {
        // The files-panel commit: open (both closed + leftward) or close (panel
        // open + rightward). Toggle FIRST, then mark: the consume mark walks the
        // stroke-start target's ancestors up to the FRAME, and the frame is also
        // an ancestor of the host control the toggle clicks programmatically —
        // marking first matches that very click through the shared upper chain
        // segments and swallows it, so the panel never opens (0.1.5 live
        // observation). The mark still covers the browser's own synthetic click,
        // which is dispatched asynchronously after the release (the send button,
        // a row button, … sit under the release point).
        filesToggleFn();
        markStrokeConsumed(event.target);
        cooldownUntil = performance.now() + COOLDOWN_MS;
    }
    if (filesMode && verdict === 'none') {
        // A 'none' files release is still a gesture (panel open + leftward, or
        // too short): consume its synthetic click so it cannot flip the panel
        // through the host toggle under the release point. This must run AFTER
        // the commit branches above, never before a programmatic toggle click.
        markStrokeConsumed(event.target);
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
    strokeMode = 'drawer';
    lockFilesOpen = false;
    (0, gesture_guard_ts_1.clearStrokeLocked)();
}
/** The logical reading direction of the frame (RTL support). */
function frameRtl() {
    const frame = (0, phone_chrome_ts_1.getFrame)();
    return frame !== null && getComputedStyle(frame).direction === 'rtl';
}
/** Install the gesture layer for the current mobile breakpoint. */
function installSidebarSwipe(ctx, filesToggle) {
    (0, phone_chrome_ts_1.installMobileEffect)(ctx, 'dsh-web-mobile: sidebar swipe gestures', () => {
        filesToggleFn = filesToggle;
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
__modules["effects/phone-chrome.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.TAP_CLOSE_NAV_SELECTOR = exports.TOUCH_QUERY = exports.DESKTOP_QUERY = exports.MOBILE_QUERY = void 0;
exports.installMobileEffect = installMobileEffect;
exports.findFrame = findFrame;
exports.getFrame = getFrame;
exports.ensureDismissShadow = ensureDismissShadow;
exports.installFrameController = installFrameController;
exports.installReconciler = installReconciler;
exports.addReconcilerTask = addReconcilerTask;
exports.detectIosWebKit = detectIosWebKit;
exports.installPhoneChrome = installPhoneChrome;
exports.installOverlayInteractions = installOverlayInteractions;
exports.registerReconcileTasks = registerReconcileTasks;
const gesture_guard_ts_1 = require("./effects/gesture-guard.js");
const session_row_fiber_ts_1 = require("./effects/session-row-fiber.js");
const reconciler_core_ts_1 = require("./core/reconciler-core.js");
const sessions_compat_ts_1 = require("./core/sessions-compat.js");
const aionui_compat_ts_1 = require("./effects/aionui-compat.js");
const stats_line_ts_1 = require("./effects/stats-line.js");
const preview_fullscreen_ts_1 = require("./effects/preview-fullscreen.js");
const git_chip_reparent_ts_1 = require("./effects/git-chip-reparent.js");
const settings_toolbar_reparent_ts_1 = require("./effects/settings-toolbar-reparent.js");
const overlay_backdrop_fab_ts_1 = require("./effects/overlay-backdrop-fab.js");
const file_viewer_compat_ts_1 = require("./effects/file-viewer-compat.js");
const sidebar_swipe_ts_1 = require("./effects/sidebar-swipe.js");
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
/** Pointer-only guard for the ONE feature that has no desktop equivalent:
 *  the session-delete menu injection. Armed on touch-primary devices at
 *  EVERY width — a large tablet in landscape (e.g. 1238px) keeps the desktop
 *  layout but still gets the 「删除会话」 item. Mouse-driven or pointer-less
 *  windows never arm it, at any width. */
exports.TOUCH_QUERY = '(pointer: coarse)';
/** Long press on a session row opens its ⋯ menu — the phone equivalent of the
 *  desktop hover that reveals the row actions (the host renders them with
 *  `display: none` until `:hover` or `menuOpen`, neither of which touch ever
 *  reaches). Long enough to be deliberate, short enough to read as a context
 *  menu. */
const LONG_PRESS_MS = 500;
/** Pointer travel that cancels a long press (the swipe layer locks at 8px). */
const LONG_PRESS_MOVE_PX = 10;
/** How long the lift may not close the menu the press opened: the host menu
 *  closes on pointerleave, and the finger lift itself fires one. */
const LONG_PRESS_MENU_GUARD_MS = 1200;
/** Window in which the press's own synthesized click is swallowed, so the lift
 *  neither navigates the row nor collapses the drawer. */
const LONG_PRESS_CLICK_SWALLOW_MS = 800;
/** Finger-down to finger-up travel that still counts as a tap on a session row
 *  (#49). Per-axis (`isTapWithinSlop` is max-norm, not Euclidean): the drawer
 *  list scrolls vertically, so a 60px vertical drift must not navigate while a
 *  diagonal wobble still reads as a tap. */
const TAP_NAV_SLOP_PX = 12;
/** Where the current touch started (null for a mouse, and between touches).
 *  The no-click row-tap fallback resolves the row's session id at pointerup and
 *  only when the finger stayed put, so every touch pointerdown records this
 *  BEFORE any early return — a missed record silently disables the whole
 *  fallback. Cleared by the effect's disposer. */
let touchDownAt = null;
/**
 * Re-arm a mobile-only DOM effect on every query change. Replaces the
 * repeated matchMedia + change-listener scaffold so all breakpoint strings
 * live in one place. `query` defaults to MOBILE_QUERY; effects that arm on a
 * different condition (e.g. TOUCH_QUERY) pass their own string instead of
 * building a private matchMedia scaffold.
 */
function installMobileEffect(ctx, label, install, query = exports.MOBILE_QUERY) {
    ctx.effect(() => {
        const narrow = window.matchMedia(query);
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
/** The third-party mobile compat shim shipped inside `@linxin666/dsh-web-all`
 *  collapses the drawer on ANY click inside `[role="treeitem"]` at ≤768px by
 *  clicking the host's logo-row toggle — with no `_rowActions` exemption, so a
 *  tap on a row's ⋯ closed the drawer instead of opening its menu (2026-09-14;
 *  its sibling implementation inside `@linxin666/dsh-remote-web-ui` does exempt
 *  the row actions). It resolves that toggle with
 *  `frame.querySelector('[data-dsh-responsive-part="sidebar-toggle"]')`, so an
 *  inert element carrying the same stamp EARLIER in tree order turns every one
 *  of its dismiss calls into a no-op and leaves dismiss ownership to us (row
 *  taps close through the navigation observer, backdrop taps through the
 *  capture click path). Gated on the shim's own stamp: hosts without it stay
 *  untouched. */
const HOST_TOGGLE_SELECTOR = '[data-dsh-responsive-part="sidebar-toggle"]:not([data-mobile-nav])';
const DISMISS_SHADOW_SELECTOR = '[data-mobile-nav="dismiss-shadow"]';
function ensureDismissShadow() {
    if (typeof document === 'undefined')
        return;
    const shadow = document.querySelector(DISMISS_SHADOW_SELECTOR);
    const real = document.querySelector(HOST_TOGGLE_SELECTOR);
    const pane = real?.closest('[data-pane="sidebar"]') ?? null;
    if (real === null || pane === null) {
        shadow?.remove();
        return;
    }
    // Must stay a no-op once in place: the reconciler observes the whole tree and
    // a task that mutates on every flush would re-trigger itself forever.
    if (shadow !== null && shadow.parentElement === pane && pane.firstElementChild === shadow)
        return;
    const element = shadow ?? document.createElement('span');
    if (shadow === null) {
        element.setAttribute('data-mobile-nav', 'dismiss-shadow');
        element.setAttribute('data-dsh-responsive-part', 'sidebar-toggle');
        element.setAttribute('aria-hidden', 'true');
        // The shim's collapsed-rail rule forces `display: inline-flex !important`
        // on anything carrying the stamp; only an inline !important outranks it.
        element.style.setProperty('display', 'none', 'important');
    }
    pane.insertBefore(element, pane.firstElementChild);
}
/**
 * Frame marker controller: owns `data-mobile-nav="frame"` and every plugin
 * marker that can survive on the shell-owned frame. Installed once at apply
 * time so effects no longer each need to find/set/clear the frame. Returns a
 * disposer that unregisters the task and resets the installed flag, so a
 * same-environment plugin reload can rebuild the reconciler from scratch.
 * (The host-generation probe this controller used to call was dead code —
 * nothing ever read `data-mobile-nav-gen`, and the plugin deliberately does
 * not yield the drawer to the host's one: see docs/maintenance/pitfalls.md
 * §0.1.5 抽屉 z 与遮罩.)
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
            ensureDismissShadow();
        },
        dispose: () => {
            if (frame !== null) {
                frame.removeAttribute('data-mobile-nav');
                frame.removeAttribute('data-mobile-preview-full');
                frame.removeAttribute('data-aionui-explorer-open');
                frame.removeAttribute('data-aionui-preview-open');
            }
            if (typeof document !== 'undefined') {
                document.querySelector(DISMISS_SHADOW_SELECTOR)?.remove();
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
 * - Tapping a navigation target inside the drawer (session row, sidebar panel
 *   row, task board / ssh takeover entries, search results) closes the drawer
 *   so the content it opened gets the whole screen. Session-row action buttons
 *   (kebab) are excluded — they open a menu that must survive the tap.
 *
 * The touch close always rides the synthesized click. Closing a non-row
 * target from pointerup collapsed the drawer before that click existed, and
 * a collapsed drawer no longer owns the touch point, so the browser
 * dispatched no click at all and the target's own onClick never ran (「新会话」
 * did nothing but retract the drawer, 2026-09-13).
 */
exports.TAP_CLOSE_NAV_SELECTOR = 'button[data-dsh-taskboard-entry], button[data-dsh-ssh-entry], [class*="newSession"], [class*="sessionRow"], [class*="searchResultRow"], [class*="searchResultWorkspace"], [class*="panelRow"]';
function installOverlayInteractions(ctx) {
    installMobileEffect(ctx, 'dsh-web-mobile: drawer close (Escape + navigate)', () => {
        // Every non-gesture close funnels through here (backdrop tap, Escape, the
        // nav observers, navigation taps). A close animates first - the host tears
        // the pane's subtree and surface at the marker flip, so the slide has to
        // land before it (closeDrawerAnimated) - while opening stays a plain toggle
        // so the host's own .28s transform transition plays.
        const toggleSidebar = () => {
            if (!(0, sidebar_swipe_ts_1.closeDrawerAnimated)(ctx))
                ctx.layout.toggleSidebar();
        };
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
        // Shared frame: inside the drawer, on a row navigation target, and not on
        // one of its buttons. Deliberately free of the DSHA tap-close exemption —
        // onDrawerPointerDown arms long-press through this base, and starving that
        // arming would make the host's ⋯ row menu unreachable (#82).
        const isDrawerNavTarget = (target) => {
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
            return target.closest(exports.TAP_CLOSE_NAV_SELECTOR) !== null;
        };
        // DSHA_SESSION_INTERACTION_V1：宿主把「单击=选中、双击=打开」拆成了两步
        // （data-dsha-session-select 标记 + dsha-session-open 事件）。上游的
        // 「点行即关抽屉」会在第一次单击就把抽屉收掉，双击永远到不了。
        // 这些行改由 dsha-session-open 事件关闭（见下方 document 监听）。
        // 非 DSHA 宿主没有这个标记，这一条天然不命中。
        // 豁免只属于点行关抽屉的两个调用方（click / pointerup），不得回流进
        // 长按武装门，否则 DSHA 行长按开不了 ⋯ 菜单（#82）。
        const shouldCloseOnTapInsideDrawer = (target) => !(target instanceof Element && target.closest('[data-dsha-session-select]') !== null)
            && isDrawerNavTarget(target);
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
        // 2026-09-22 交互契约（群内统一）：单击 = 选中、双击 = 打开、长按 = 改会话名。
        // 宿主 0.1.7 把「改会话名」挂在会话行标题的 dblclick 上（onRenameRequest），
        // 而这恰好是双击手势要用的那个事件：双击会既打开会话又弹改名框。所以真实
        // dblclick 在这里被吞掉（下方 onDrawerDoubleClick），长按则重放同一个事件去
        // 开宿主自己的改名框（requestRowRename）——只有我们派发的那一个事件被放行。
        // Touch has no hover, so the host's `_rowActions` — the ⋯ menu anchor — never
        // shows up by itself: only `:hover` and `menuOpen` reveal it. Long press used
        // to be the touch path to that menu; it belongs to rename now, so the mobile
        // stylesheet pins `_rowActions` open instead (删除 / 归档 / 分叉 仍有触屏入口).
        // The host menu closes on pointerleave, which the finger lift itself fires,
        // and that lift still synthesizes a click on the row: both need guarding.
        let pressTimer = null;
        let pressOrigin = null;
        let pressRow = null;
        let pressFired = false;
        let menuGuardUntil = 0;
        let swallowClickUntil = 0;
        let swallowClickRow = null;
        const clearPress = () => {
            if (pressTimer !== null)
                window.clearTimeout(pressTimer);
            pressTimer = null;
            pressOrigin = null;
            pressRow = null;
            pressFired = false;
        };
        const openRowMenu = (row) => {
            // A menu already on screen owns the gesture (host touch path, another
            // plugin's long press); clicking the anchor again would close it.
            if (document.querySelector('[role="menu"]') !== null)
                return;
            const button = row.querySelector('[class*="_rowActions"] button');
            if (button === null)
                return;
            menuGuardUntil = performance.now() + LONG_PRESS_MENU_GUARD_MS;
            button.click();
        };
        /** The only `dblclick`s allowed through to the host are the ones we
         *  dispatch ourselves: a real one is the double *tap* that means "open the
         *  session", and letting it reach the title would open the rename dialog on
         *  the same gesture. Identity, not a flag on the event: nothing else can
         *  forge it. */
        const syntheticDoubleClicks = new WeakSet();
        /** 长按 = 改会话名：宿主把改名挂在标题的 dblclick 上，这里重放那个事件，
         *  而不是复制一套弹窗链路（宿主的 rename 状态机是包内私有的）。
         *  @returns 是否成功派发；宿主标记变了、拿不到标题时为 false，调用方回退。 */
        const requestRowRename = (row) => {
            const title = row.querySelector('[class*="_title"]');
            if (title === null)
                return false;
            const event = new MouseEvent('dblclick', { bubbles: true, cancelable: true, view: window });
            syntheticDoubleClicks.add(event);
            title.dispatchEvent(event);
            return true;
        };
        /** Swallow the host's title-double-click rename (the 2026-09-22 contract puts
         *  rename on long press, and double tap on "open"). Capture phase on
         *  `document`, so the event never reaches React's root container and the
         *  title's own onDoubleClick cannot run. Armed only inside the mobile
         *  environment (this effect is MOBILE_QUERY-gated), so mouse-driven desktops
         *  keep the host behaviour untouched. */
        const onDrawerDoubleClick = (event) => {
            if (syntheticDoubleClicks.has(event))
                return;
            const target = event.target;
            if (!(target instanceof Element))
                return;
            if (target.closest('[class*="sessionRow"] [class*="_title"]') === null)
                return;
            event.preventDefault();
            event.stopPropagation();
        };
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
        /** Whether the session list really knows an id. The fiber walk has no
         *  shape heuristic on purpose: hop 32 of a row's chain is a ScopeProvider
         *  whose `props.scope` is the literal 'session-maybe', and
         *  `ctx.sessions.open` fails loud on unknown ids — membership is the only
         *  filter that can never hand the host a guess. */
        const isKnownSessionId = (id) => {
            const snapshot = ctx.sessions.list.getSnapshot();
            return snapshot.byId[id] !== undefined;
        };
        /** The session a finished tap on `row` should open, or null to fall back to
         *  the DOM observer: no finger-down record, a release that travelled (a
         *  scroll or a swipe, not a tap), a fiber chain offering no known id, or a
         *  row that is already the current session. */
        const tappedRowSessionId = (row, event) => {
            if (touchDownAt === null)
                return null;
            if (!(0, session_row_fiber_ts_1.isTapWithinSlop)(touchDownAt, { x: event.clientX, y: event.clientY }, TAP_NAV_SLOP_PX))
                return null;
            const id = (0, session_row_fiber_ts_1.findSessionIdInFiber)((0, session_row_fiber_ts_1.reactFiberOf)(row), isKnownSessionId);
            if (id === null)
                return null;
            return (0, sessions_compat_ts_1.currentSessionIdOf)(ctx.sessions.list.getSnapshot()) === id ? null : id;
        };
        // Close the drawer once the navigation we started ourselves lands (#49).
        // `armNav` watched the drawer's *selected row* change, but when WebKit drops
        // the tap's click the row's own onClick never runs, so that signal never
        // arrives — the store is the honest source of "navigation happened".
        let closeOnNavUnsub = null;
        let closeOnNavDone = false;
        /** Disarming means spent: mark the close done before dropping the
         *  subscription, so a `fire` a subscription tick already queued cannot
         *  toggle the drawer after the close was handed to the other closer. */
        const disarmCloseOnNav = () => {
            closeOnNavDone = true;
            closeOnNavUnsub?.();
            closeOnNavUnsub = null;
        };
        const closeOnNavigation = (id) => {
            disarmCloseOnNav();
            closeOnNavDone = false;
            const fire = () => {
                if (closeOnNavDone)
                    return;
                disarmCloseOnNav();
                if (drawerOpen())
                    toggleSidebar();
            };
            closeOnNavUnsub = ctx.sessions.list.subscribe(() => {
                if ((0, sessions_compat_ts_1.currentSessionIdOf)(ctx.sessions.list.getSnapshot()) !== id)
                    return;
                window.setTimeout(fire, 0);
            });
        };
        const onDrawerPointerDown = (event) => {
            touchDownAt = event.pointerType === 'touch' || event.pointerType === 'pen'
                ? { x: event.clientX, y: event.clientY }
                : null;
            clearPress();
            if (event.pointerType !== 'touch' && event.pointerType !== 'pen')
                return;
            if ((0, gesture_guard_ts_1.isStrokeLocked)())
                return;
            const target = event.target;
            // isDrawerNavTarget already means "inside the drawer, on a row navigation
            // target, and not on one of its buttons" — and it must stay the
            // exemption-free base: arming long-press through the tap-close predicate
            // made DSHA rows un-armable, killing their only touch path to the ⋯ menu.
            if (!isDrawerNavTarget(target) || !(target instanceof Element))
                return;
            const row = target.closest('[class*="_sessionRow"]');
            if (row === null || target.closest('[class*="_rowActions"]') !== null)
                return;
            pressOrigin = { x: event.clientX, y: event.clientY };
            pressRow = row;
            pressTimer = window.setTimeout(() => {
                pressTimer = null;
                if (pressRow === null)
                    return;
                pressFired = true;
                // 长按 = 改会话名。拿不到标题（宿主标记变了）就退回 ⋯ 菜单：长按至少还能
                // 到达行操作，而不是变成一个什么都不做的死手势。
                if (!requestRowRename(pressRow))
                    openRowMenu(pressRow);
            }, LONG_PRESS_MS);
        };
        const onDrawerPointerMove = (event) => {
            if (pressOrigin === null)
                return;
            if ((0, gesture_guard_ts_1.isStrokeLocked)()) {
                clearPress();
                return;
            }
            if (Math.abs(event.clientX - pressOrigin.x) > LONG_PRESS_MOVE_PX
                || Math.abs(event.clientY - pressOrigin.y) > LONG_PRESS_MOVE_PX) {
                clearPress();
            }
        };
        // The host menu closes on pointerleave of its anchor; the finger lift fires
        // one right after the press opened the menu, so stay out of the way until
        // the finger is long gone.
        const onDrawerPointerLeave = (event) => {
            if (performance.now() > menuGuardUntil)
                return;
            const target = event.target;
            if (!(target instanceof Element))
                return;
            if (target.closest('[class*="_rowActions"]') === null
                && target.closest('[class*="_sessionRow"]') === null)
                return;
            event.stopPropagation();
        };
        const onDrawerClick = (event) => {
            // The long press's own synthesized click is the one click that must not
            // act: the row was not tapped, and the menu it opened must survive. One
            // click only — a later tap on the ⋯ reaches React normally.
            const target = event.target;
            if (swallowClickRow !== null && performance.now() <= swallowClickUntil) {
                if (target instanceof Element && (target === swallowClickRow || swallowClickRow.contains(target))) {
                    swallowClickUntil = 0;
                    swallowClickRow = null;
                    event.preventDefault();
                    event.stopPropagation();
                    return;
                }
            }
            // A classified swipe already toggled the drawer; never let its
            // synthetic tap also close it / navigate a row (gesture-guard).
            // isStrokeLocked: a stroke axis-locked mid-swipe (audit S0) — the
            // consume marks do not exist until the gesture layer's own pointerup,
            // which runs AFTER this handler on the same release event.
            if ((0, gesture_guard_ts_1.isStrokeLocked)() || (0, gesture_guard_ts_1.consumeIfGestured)(event))
                return;
            // The backdrop keeps its own listener, but the third-party mobile shim
            // stops click propagation at the frame for anything outside the drawer
            // (its own dismiss path), so that listener never sees the tap. Decide
            // here instead — before both the shim and the element handler.
            if (target instanceof Element && target.closest('[data-mobile-nav="backdrop"]') !== null) {
                if (drawerOpen())
                    toggleSidebar();
                return;
            }
            // A touch row-tap owns the close (pointerup or the navigation observer);
            // let the row's click reach React without toggling the drawer twice.
            if (performance.now() - lastTouchNavAt < 500)
                return;
            if (shouldCloseOnTapInsideDrawer(target))
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
            const pressed = pressFired;
            const pressedRow = pressRow;
            clearPress();
            if (pressed && pressedRow !== null) {
                // The press already opened the menu: the lift must not also navigate
                // or close the drawer.
                swallowClickUntil = performance.now() + LONG_PRESS_CLICK_SWALLOW_MS;
                swallowClickRow = pressedRow;
                return;
            }
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
                    // Unselected row: navigate from the id we resolved at the touch point
                    // when this tap can supply one — on WebKit the row's own click may
                    // never come, and then nothing else would open the session. Fall back
                    // to closing once the DOM shows a navigation landed when it cannot.
                    const tappedId = tappedRowSessionId(row, event);
                    if (tappedId === null) {
                        // Exactly one closer at a time: this tap closes through the DOM
                        // observer, so drop the store subscription an earlier resolved tap
                        // armed — its stale id would toggle the drawer again on landing.
                        disarmCloseOnNav();
                        armNav();
                    }
                    else {
                        // The mirror case: this tap closes through the store, so drop the
                        // observer an earlier fallback tap armed — otherwise both fire on
                        // this one navigation (the observer on the selected-title change,
                        // the subscription on the landing) and race to toggle twice.
                        disarmNav();
                        if ((0, sessions_compat_ts_1.sessionsCanOpen)(ctx.sessions)) {
                            closeOnNavigation(tappedId);
                            ctx.sessions.open(tappedId);
                        }
                        else {
                            // a2 removed sessions.open (retain-model navigation) — the
                            // store-subscription closer above watches `current`, which a2
                            // no longer publishes, so arming it would only leak a
                            // subscription that can never fire. Degrade to the DOM
                            // observer: the row's own onClick still navigates where the
                            // browser dispatches it (audit doc §10.1 / F1).
                            disarmCloseOnNav();
                            armNav();
                        }
                    }
                }
                return;
            }
            // Non-row nav targets (newSession / taskboard / ssh / search rows that
            // are not treeitems) are closed by the capture click handler below:
            // closing here would retract the drawer before the browser dispatches
            // the tap's click, and the target's onClick would never run.
        };
        // DSHA 宿主在「真正打开会话」时才派发 dsha-session-open（单击只选中），
        // 所以抽屉的关闭挂在这个事实上，而不是挂在点击上。
        const onDshaSessionOpen = () => {
            if (drawerOpen())
                toggleSidebar();
        };
        document.addEventListener('dsha-session-open', onDshaSessionOpen);
        document.addEventListener('dblclick', onDrawerDoubleClick, true);
        document.addEventListener('keydown', onKeyDown, true);
        document.addEventListener('click', onDrawerClick, true);
        document.addEventListener('pointerdown', onDrawerPointerDown, true);
        document.addEventListener('pointermove', onDrawerPointerMove, true);
        document.addEventListener('pointerleave', onDrawerPointerLeave, true);
        document.addEventListener('pointerup', onDrawerPointerUp, true);
        return () => {
            disarmNav();
            // Also marks the close spent, so a queued `fire` cannot outlive the effect.
            disarmCloseOnNav();
            touchDownAt = null;
            clearPress();
            document.removeEventListener('dsha-session-open', onDshaSessionOpen);
            document.removeEventListener('dblclick', onDrawerDoubleClick, true);
            document.removeEventListener('keydown', onKeyDown, true);
            document.removeEventListener('click', onDrawerClick, true);
            document.removeEventListener('pointerdown', onDrawerPointerDown, true);
            document.removeEventListener('pointermove', onDrawerPointerMove, true);
            document.removeEventListener('pointerleave', onDrawerPointerLeave, true);
            document.removeEventListener('pointerup', onDrawerPointerUp, true);
        };
    });
}
/**
 * Register the shared DOM reconciler tasks. Returns a disposer that
 * unregisters every task and resets the flag, so a same-environment plugin
 * reload can rebuild the reconciler from scratch.
 *
 * @param panelExit - the sidebar-panel exit face (panel-exit.ts): its system-back
 *   route is registered here so it shares this reconciler, and the FAB reads it
 *   to switch its meaning while a panel owns the main area.
 */
function registerReconcileTasks(ctx, panelExit) {
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
        addReconcilerTask((0, overlay_backdrop_fab_ts_1.createOverlayTask)(t, () => ctx.layout.toggleSidebar(), panelExit)),
        addReconcilerTask(panelExit.task),
        addReconcilerTask((0, file_viewer_compat_ts_1.createFileViewerMarkerTask)()),
    ];
    return () => {
        for (const remove of removeTasks)
            remove();
        reconcileTasksRegistered = false;
    };
}
};
__modules["components/open-files-panel.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.HOST_FILES_CLOSER = exports.HOST_FILES_OPENER = void 0;
exports.openFilesPanel = openFilesPanel;
const phone_chrome_ts_1 = require("./effects/phone-chrome.js");
/** The host's own right-sidebar opener (ui-sidebar-right: ExpandButton). */
exports.HOST_FILES_OPENER = '[data-sidebar-right-expand]';
/** The host's collapse control, mounted while the right sidebar is open. */
exports.HOST_FILES_CLOSER = '[data-sidebar-right-toggle]';
/**
 * Open the file browser from a mobile control, preferring the surface the user
 * actually has.
 *
 * 1. The host's own right sidebar (`data-sidebar-right-expand`) is the current
 *    file browser: on 0.1.5 it holds the workspace tree (Files tab). The host
 *    renders its opener inside `headerCorner`, which the desktop layout hides
 *    with `display: none`, so the control exists and its click handler runs
 *    while it has no painted size — acting on it programmatically is the
 *    supported path, and forcing the corner visible would fight the very
 *    layout that hides it.
 * 2. Only when that surface is absent (hosts without ui-sidebar-right) do we
 *    fall back to the third-party explorer column, which needs its
 *    `data-aionui-explorer-open` marker; the preview sheet is yielded first
 *    because compat.css gives preview precedence over explorer.
 *
 * Returns true when the official sidebar took the action, so callers can skip
 * their own layout work (the host panel covers the frame on its own).
 */
function openFilesPanel(doc = document, frame = (0, phone_chrome_ts_1.getFrame)()) {
    // Toggle semantics, because the host swaps controls with the panel state
    // (measured on 0.1.5): while the panel is CLOSED the only opener is
    // `data-sidebar-right-expand`; once it is OPEN that element is unmounted and
    // only `data-sidebar-right-toggle` (Collapse right sidebar) remains. Acting
    // on the expand button alone was a no-op whenever the panel happened to be
    // already open — the exact "tap does nothing / position looks wrong" report.
    // Closing first also keeps the control reachable: the full-screen panel
    // covers the header, so a second tap could never reach our button.
    const closer = doc.querySelector(exports.HOST_FILES_CLOSER);
    const opener = doc.querySelector(exports.HOST_FILES_OPENER);
    const hostControl = typeof opener?.click === 'function' ? opener : closer;
    // Duck-typed on purpose: any element exposing click() acts as the control.
    if (typeof hostControl?.click === 'function') {
        hostControl.click();
        return true;
    }
    if (frame === null)
        return false;
    frame.removeAttribute('data-aionui-preview-open');
    frame.setAttribute('data-aionui-explorer-open', '');
    return false;
}
};
__modules["components/MobileNavToggle.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.MobileNavToggle = MobileNavToggle;
const jsx_runtime_1 = require("react/jsx-runtime");
const icon_compat_ts_1 = require("./core/icon-compat.js");
const open_files_panel_ts_1 = require("./components/open-files-panel.js");
/**
 * Mobile-only icon buttons next to the session title:
 * - toggle: opens the directory drawer on narrow screens.
 * - files: opens the file browser directly — one tap, no drawer round-trip.
 *   Which surface that is (host right sidebar vs. the third-party explorer
 *   sheet) is decided in open-files-panel.ts. The hero/blank phases have no
 *   session header, so this control is absent there; the files entry in those
 *   phases is the right-edge leftward swipe (sidebar-swipe.ts).
 * Hidden entirely on wide screens (CSS media query).
 */
function MobileNavToggle({ toggleSidebar, t }) {
    const toggleExplorer = () => {
        (0, open_files_panel_ts_1.openFilesPanel)();
    };
    return ((0, jsx_runtime_1.jsxs)(jsx_runtime_1.Fragment, { children: [(0, jsx_runtime_1.jsx)("button", { type: "button", "data-mobile-nav": "toggle", "aria-label": t('open'), title: t('open'), onClick: () => toggleSidebar(), children: (0, jsx_runtime_1.jsx)(icon_compat_ts_1.IconPanelLeft, { size: 16 }) }), (0, jsx_runtime_1.jsx)("button", { type: "button", "data-mobile-nav": "files", "aria-label": t('files'), title: t('files'), onClick: toggleExplorer, children: (0, jsx_runtime_1.jsx)(icon_compat_ts_1.IconFolderOpen, { size: 16 }) })] }));
}
};
__modules["components/MobileDrawerFooter.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.MobileDrawerFooter = MobileDrawerFooter;
const jsx_runtime_1 = require("react/jsx-runtime");
const icon_compat_ts_1 = require("./core/icon-compat.js");
const sessions_compat_ts_1 = require("./core/sessions-compat.js");
/**
 * Mobile-only drawer footer action, relocated from the session header to the
 * drawer footer (beside Settings): the official session-log-export
 * controller, so the progress/result dialog is shared with the desktop flow.
 * Hidden entirely on wide screens (CSS media query).
 *
 * The Files entry that used to live here was removed on 2026-09-17: while the
 * drawer is open neither the host (it refuses to expand the right sidebar)
 * nor the third-party drawer-dismiss shim (it swallows every frame-interior
 * click outside the drawer, programmatic ones included) lets a click reach
 * the right-sidebar opener, so the entry could only ever close the drawer.
 * Contract: docs/specs/2026-09-17-sidebar-files-coexistence-design.md
 */
function MobileDrawerFooter({ useSessions, downloadSessionLog, t }) {
    const sessionId = useSessions((state) => (0, sessions_compat_ts_1.currentSessionIdOf)(state));
    return ((0, jsx_runtime_1.jsx)("div", { "data-mobile-nav": "drawer-actions", children: (0, jsx_runtime_1.jsxs)("button", { type: "button", "data-mobile-nav": "session-log", "aria-label": t('sessionLog'), title: t('sessionLog'), disabled: sessionId === undefined, onClick: () => {
                if (sessionId !== undefined)
                    downloadSessionLog(sessionId);
            }, children: [(0, jsx_runtime_1.jsx)(icon_compat_ts_1.IconDownload, { size: 14 }), (0, jsx_runtime_1.jsx)("span", { children: t('sessionLog') })] }) }));
}
};
__modules["components/ComposerFileButton.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.ComposerFileButton = ComposerFileButton;
const jsx_runtime_1 = require("react/jsx-runtime");
const icon_compat_ts_1 = require("./core/icon-compat.js");
/**
 * Mobile-only composer file entry, kept visible outside the "+" command menu.
 *
 * The 0.1.6-alpha.2 host deleted the composer's paperclip attach button: the
 * only file entry left is the 「文件」row inside the "+" listbox (the trigger's
 * aria-label is 「添加文件或调用指令」). The host still mounts its own hidden
 * `input[type=file]` in the composer tool row and its own command opens the
 * native dialog with exactly `fileInputRef.current?.click()`, so this control
 * triggers that same input instead of reimplementing intake: file validation,
 * upload and the availability policy all stay host-owned.
 *
 * The control is contributed to the host-declared `conversation.input.left`
 * list slot ("Compact controls at the left of the composer tool row"), which
 * keeps it inside the tools lane beside the plus button without touching
 * host-owned React DOM. The seat is session-scoped, so the hero/blank phase
 * (no session) keeps the "+" menu as its only file entry.
 *
 * Availability mirrors the host's `canAcceptDrop` as far as it is observable:
 * a non-plain input phase (adjudicating/claimed/submitting = the machine is
 * busy) and a subagent session both refuse attachments. The host's own
 * `locked` / `addFiles === undefined` arms are package-private, so a missing
 * session seat also disables the control. Hidden entirely on wide screens
 * (CSS media query, and the shared desktop hide block in misc.css.ts).
 */
function ComposerFileButton({ useInput, useSession, t }) {
    const busy = useInput((state) => state.phase !== 'plain');
    const subagent = useSession((state) => state.subagent !== null);
    const disabled = busy || subagent;
    const openPicker = (event) => {
        if (disabled)
            return;
        const card = event.currentTarget.closest('[data-composer-card]');
        const input = card === null ? null : card.querySelector('input[type=file]');
        if (input !== null)
            input.click();
    };
    return ((0, jsx_runtime_1.jsx)("button", { type: "button", "data-mobile-nav": "file-upload", "aria-label": t('fileUpload'), title: t('fileUpload'), disabled: disabled, onClick: openPicker, children: (0, jsx_runtime_1.jsx)(icon_compat_ts_1.IconPaperclip, { size: 16 }) }));
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

/* Drawer footer action: the relocated Session log download. The Files entry
   was removed on 2026-09-17 (see
   docs/specs/2026-09-17-sidebar-files-coexistence-design.md). */
[data-mobile-nav="drawer-actions"] {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
[data-mobile-nav="session-log"] {
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
[data-mobile-nav="session-log"]:hover:not(:disabled) {
  background: var(--dsw-alias-interactive-bg-hover, rgba(0, 0, 0, .06));
}
[data-mobile-nav="session-log"]:disabled {
  color: var(--dsw-alias-label-dimmed, rgba(0, 0, 0, .35));
  cursor: default;
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
/* Wide touch (tablet landscape ≥1024px, pointer coarse): the card would
   otherwise span the full desktop viewport. Cap and center it with margins
   (not transform, which the entry animation would override mid-play). */
@media (min-width: 1024px) and (pointer: coarse) {
  [data-mobile-nav="delete-dialog"] {
    left: 0;
    right: 0;
    width: 420px;
    margin-inline: auto;
  }
}
@media (prefers-reduced-motion: reduce) {
  [data-mobile-nav="delete-dialog-backdrop"],
  [data-mobile-nav="delete-dialog"] {
    animation: none !important;
  }
}

/* ---------- popover band above the open drawer (mobile only) ----------
   The host portals its menus to <body> as position: fixed with z-index 1100,
   while the drawer column carries 1300 and our backdrop 1250. A menu opened
   from inside the drawer therefore painted UNDER both: measured 2026-09-14 at
   390px on the session row's ⋯ menu — menu rect [160,454,218,168] z1100, and
   elementFromPoint at its centre AND at both of its ends returned drawer
   elements, so the whole menu was unreachable and the row could not be
   renamed/forked/archived/deleted from the phone (the second half of the
   owner's report: the popup the ⋯ opens is pressed under the drawer).
   The raise is gated on the open drawer: our backdrop makes that the only
   state in which a menu can be opened from the drawer, so the closed-drawer
   and desktop stacks keep the host's own ordering.
   The plugin's own confirm card sits in the same band: it mounts on
   document.body (NOT in the frame — see session-menu.ts, which appends the
   backdrop and the card there so the third-party dismiss shim's capture-phase
   click chain cannot swallow its buttons) and carried the base z 55/56, so the
   drawer covered its left 272px (measured: elementFromPoint inside that band hit
   the drawer's own button, and the confirm card is 358px wide starting at x=8). */
@media (max-width: 1023px) and (pointer: coarse) {
  body:has([data-mobile-nav="frame"]:not([data-sidebar-collapsed])) [role="menu"] {
    z-index: 1400 !important;
  }
  /* Host modal dialogs (workspace rename, and any future dialog of the same
     shape) portal to a direct body child that carries the stacking context:
     body > div { position: fixed; z-index: 1000 } wrapping
     [role="dialog"][aria-modal="true"] (inner z-index: 1 — raising the dialog
     itself is useless, it only sorts inside that root).
     NOTE (2026-09-23): the hashes once recorded here (_root_w1urq_2 /
     _dialog_w1urq_22) are gone from both 0.1.7-alpha.1 and alpha.2, so this
     comment anchors on the SHAPE only — which is what the rule below already
     matches. Do not reintroduce a hash here without re-measuring.
     Measured 2026-09-19: with the drawer open (column z 1300) the workspace
     Rename dialog sat entirely under it and needed the drawer closed first.
     Raise the portal root, not the dialog, and only while the drawer is open —
     the closed-drawer and desktop stacks keep the host's own ordering. Our own
     delete card is untouched: its role sits on the body child itself, so
     :has(>) never matches it. */
  body:has([data-mobile-nav="frame"]:not([data-sidebar-collapsed]))
    > div:has(> [role="dialog"][aria-modal="true"]) {
    z-index: 1400 !important;
  }
  [data-mobile-nav="delete-dialog-backdrop"] {
    z-index: 1400 !important;
  }
  [data-mobile-nav="delete-dialog"] {
    z-index: 1401 !important;
  }
  /* dsh-usage-stats portals its panel to <body> as position: fixed with
     z-index 100 (desktop-designed; the drawer does not exist there). With our
     drawer open (column z 1300) the「用量/余额」panel sat under it: measured
     2026-09-20 at 390px — panel rect [12,91,366,625] z100 vs drawer 280px wide
     z1300, and elementFromPoint at the panel's centre AND its left corners
     returned drawer elements, so only a ~98px strip on the right stayed
     reachable. The plugin also unmounts the panel when the drawer closes
     (Escape-linked dismissal) and the badge only renders in the open drawer,
     so the open-drawer gate covers the panel's only reachable state; the
     closed-drawer and desktop stacks keep the plugin's own ordering. */
  body:has([data-mobile-nav="frame"]:not([data-sidebar-collapsed]))
    [data-usage-stats-panel] {
    z-index: 1400 !important;
  }
  /* AppFrame overlayLayer band — the class self-fix of our own layering
     contract (NOT a per-plugin adaptation): plugin and host sheets portal
     INTO the AppFrame overlay layer (position: absolute; z-index 20 — its
     own stacking context), so no z-index on a sheet itself can out-rank the
     drawer column's 1300 in the root context, and every sheet opened from
     the drawer landed behind it. Measured 2026-09-22 at 390px with the
     drawer open: a probe sheet inside the layer stayed hit-blocked by
     drawer elements at z auto AND z 9999 alike, and only raising the layer
     ROOT revealed it (elementFromPoint). Raise the stacking root, not the
     children — the same shape as the dialog portal-root raise above. The
     body-ported bands keep their own raises (menus 1100, dialog root 1000,
     usage-stats panel 100): each is a per-surface adaptation patch for a
     portal OUTSIDE this layer. Closed-drawer and desktop stacks keep the
     host's own ordering. */
  body:has([data-mobile-nav="frame"]:not([data-sidebar-collapsed]))
    [class*="_overlayLayer"] {
    z-index: 1400 !important;
  }
}

/* Floating fallback button (hero / blank phases without a session header).
   Top aligns with the session header's toggle row (that row sits 12px below
   the frame's safe-area padding); when the client has set viewport-fit=cover
   the safe-area inset moves it below the notch too. */
[data-mobile-nav="fab"] {
  position: absolute;
  top: calc(env(safe-area-inset-top, 0px) + 12px);
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

/* Dimmed backdrop under the open drawer; above every column, below the drawer.
   z 1250: 0.1.5 pins its native sidebarCol at z-index:1100 and paints mid
   layers up to that band; the backdrop must sit above the host stack
   (below the drawer's 1300) so the dim covers the content area on every
   host generation. Keep in sync with the drawer z in layout.css.ts. */
[data-mobile-nav="backdrop"] {
  position: absolute;
  inset: 0;
  z-index: 1250;
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
       zone (START_ZONE_RATIO 0.45 of the viewport width, ~176px at 390px,
       past every browser's edge-claim strip) is the mitigation.
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
     frame is exactly one viewport tall and the document never scrolls.

     The leading html element selector is load-bearing, not decoration: the
     third-party @linxin666/dsh-web-all sheet ships an equal-specificity
     !important grid-template-columns for this same element under
     (max-width: 768px), so without the extra element the winner is decided by
     which sheet happens to be injected later. Measured before and after with
     scripts/probes/cascade-conflict-probe.mjs: no computed value moves, the
     rule only stops depending on sheet order (audit D-5 option A). */
  html [data-mobile-nav="frame"] {
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
  /* These legacy column rules stay armed on every host generation: the phone
     owner prefers this drawer over the official overlay one (2026-09-13). The
     host's own drawer ships NO full-screen backdrop, so the conversation beside
     it stays hit-testable - the rejection reason.
     Layering contract: this column is 1300 and the backdrop 1250 (base.css),
     both deliberately above the host's native sidebarCol at 1100. The earlier
     value of 40 sat BELOW that 1100: because this same rule also forces
     position/inset/width on the element, the column kept a correct-looking box
     while neither painting nor hit-testing, which is the "all black, click
     anywhere closes" root cause. The backdrop we append carries the dimming. */
  [data-mobile-nav="frame"] > :first-child {
    position: absolute !important;
    inset: 0 auto 0 0 !important;
    /* !important is load-bearing: the host ships
       [data-dsh-frame] [data-pane="sidebar"] { width: min(88vw, 320px) !important }
       under (max-width: 768px), which at 390px resolves to a flat 320px and
       BEATS a plain declaration here - measured: our max-content never applied
       and the column stayed 320px.
       280 is the drawer's hard floor, measured by sweeping the column width from
       304 down to 264: the inner surface is a FIXED 280px box and never
       reflows, so every pixel below 280 is simply clipped off its right edge
       (the list stays 270px at every width and its right edge sits at 278, so
       270 and below cut into the list itself). At exactly 280 the panel is fully
       intact - only the 12px of its right-hand padding is given up - which is
       what the owner asked for over the previous 304. Going narrower is a
       one-line change, but it starts eating content. */
    width: min(88vw, 280px) !important;
    /* 1300 is a contract with base.css: the host pins its native sidebarCol at
       z-index:1100 and paints its mid layers up to that band, so the drawer must
       sit above the host stack AND above our own backdrop at 1250 (which dims
       the content area). At 40 the backdrop covered the drawer itself, so
       opening it showed a full-screen dim with no drawer (measured 2026-09-13
       at 390px: backdrop [0,0,390,844] z1250 over column [0,0,320,844] z40, and
       elementFromPoint(40,300) returned the backdrop). Keep in sync with the
       backdrop z in base.css. */
    z-index: 1300 !important;
    transform: translateX(-110%);
    transition: transform .28s var(--ds-ease-in-out, ease-in-out);
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

    /* The drawer's inner surface is 280px wide while the column is 88vw/320px, so
     the remaining 40px showed our own column background as a vertical strip
     along the right edge (measured: content right edge 280, column 320; the
     owner reported a white bar). The inner surface owns that band instead, so
     the strip is filled by the drawer's real surface colour. */
    /* The 40px band is a STACKING result, not a colour one: the drawer's inner
     surface is only 280px wide (host markup), while our column is 320px and
     carries z-index 1300 - so the column's own background paints OVER the
     surface's right 40px. Pixel-verified from a screenshot with the drawer open:
     x=10..270 rgb(249,250,251) (the surface) against x=285..315 rgb(255,255,255)
     (our white column). Repainting the column with the surface's own value makes
     the seam invisible whatever the theme does; the surface underneath keeps its
     own colour for the 280px it does cover. */
    background: var(--dsw-alias-bg-surface, #f9fafb);
    /* Drawer swipe gestures (edge swipe-in / content swipe-out, see
     docs/specs/2026-08-27-sidebar-swipe-gestures.md).
     One rule is load-bearing for the gesture layer: dropping pan-x on the
     drawer lets horizontal pointermove events reach the gesture code —
     WITHOUT it the browser treats a horizontal stroke as a pan, fires
     pointercancel and the gesture never classifies (vertical panning stays
     intact). Start-hit is decided purely by geometry on the document
     capture listener (START_ZONE_RATIO = 0.45 of the viewport width, ~176px
     at 390px); there is no hotspot element (removed per audit C2,
     2026-08-27). pinch-zoom rides along with the
     root value so a browser-applied zoom stays undoable inside the drawer
     too (#45); touch-action intersects down the ancestor chain, so a bare
     pan-y here would cancel the root's pinch permission. */
    touch-action: pan-y pinch-zoom !important;
  }

  /* Closed slot, at the host's OWN specificity. 0.1.5 added a narrow-branch
     rule [data-dsh-frame][data-sidebar-collapsed] [data-pane="sidebar"]
     { width:52px !important; transform:none; pointer-events:none;
     background:transparent !important } - specificity (0,3,0), one class above
     the rule above, so it won BOTH width and transform: the closed drawer
     stayed a 52px transparent shell at x=0 and the only state delta left was
     the width (52<->280), which "transition: transform" cannot animate.
     Measured 2026-09-17: closed pane transform:none / width:52 /
     rect [0,0,52,844], and every frame sampled across a toggle click stayed
     transform:none - the owner's "no slide animation on click" report.
     Matching that specificity (plus !important, since the host declaration is
     important) restores the design's own slot (spec 2026-08-27, drawer DOM):
     a min(88vw, 280px) column translated -110% of its own width, i.e. -308px
     at 390px. The gesture layer never depended on this rule - it writes an
     inline transform !important - so only the CSS-driven click paths regressed. */
  [data-mobile-nav="frame"][data-sidebar-collapsed] > :first-child {
    width: min(88vw, 280px) !important;
    transform: translateX(-110%) !important;
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


  /* The host's own drawer handle. It renders the branded fish glyph (a 24x17
     path in a 23.16x17.04 viewBox) and the phone owner reads it as a stray
     "whale" sitting at the very top-left of the header: measured [10,14,44,44]
     against our own toggle at [8,12,28,28], i.e. the two overlap in the same
     corner. It also duplicates what our toggle already does, so on the mobile
     branch it is removed. The selector keys on the host's own label - the
     element carries no distinguishing class (hHd-Xa_iconButton is shared with
     every other icon button, and the label flips to "Collapse sidebar" when the
     drawer is open, which is why the attribute prefix matches both states and
     both get removed). Nothing in this plugin queries that element; the drawer
     still opens from our toggle, the edge swipe, and closes by tapping the
     backdrop or swiping it away. */
  /* Two selectors and both are needed. The competitor is NOT the host's own
     CSS: @linxin666/dsh-web-all injects, under (max-width: 768px),
     [data-dsh-frame][data-sidebar-collapsed] [data-pane="sidebar"]
     [data-dsh-responsive-part="sidebar-toggle"] { pointer-events: auto;
     display: inline-flex !important }. That is 4 attribute selectors AND
     !important - exactly what the first selector below is - so this is NOT an
     out-specify, it is a TIE decided by sheet order, and it holds only because
     our sheet is injected after theirs. Measured twice, not inferred:
     scripts/probes/cascade-conflict-probe.mjs reports both sides imp=true at
     (0,4,0) and lists this as a reviewed order-tie; if the injection order
     flips, the dismiss shadow returns as a visible inline-flex box with
     pointer-events restored. Done (audit D-5 option A, 2026-09-16): every
     selector below carries a leading html, which lifts the first one to
     (0,4,1) and ends the tie - the outcome no longer depends on which sheet is
     injected later. The hash class and the label stay as fallbacks for hosts
     without that hook. */
  html [data-mobile-nav="frame"][data-sidebar-collapsed] [data-pane="sidebar"] [data-dsh-responsive-part="sidebar-toggle"],
  html [data-mobile-nav="frame"] [data-dsh-responsive-part="sidebar-toggle"],
  html [data-mobile-nav="frame"] [class*="hHd-Xa_toggle"]:is([aria-label*="sidebar" i], [aria-label*="侧边栏"]),
  /* The label-only fallbacks MUST stay scoped to the seats the host's own
     drawer handle can live in. Unscoped they match by aria-label substring,
     and the session row's ⋯ carries 会话“<title>”的操作 — so any session
     whose title contains 侧边栏 (or "sidebar") lost its ⋯ menu entirely
     (2026-09-22 phone repro: title 侧边栏不见了 → rowActions button
     display:none, row height unchanged, time shifted right by the 16px the
     button would have taken). Anchor them to the frame's leading seat and to
     the header's leading cell instead. */
  html [data-mobile-nav="frame"] [data-conversation-header-leading] button[aria-label*="sidebar" i],
  html [data-mobile-nav="frame"] [data-conversation-header-leading] button[aria-label*="侧边栏"],
  html [data-mobile-nav="frame"] [data-shell-leading] button[aria-label*="sidebar" i],
  html [data-mobile-nav="frame"] [data-shell-leading] button[aria-label*="侧边栏"] {
    display: none !important;
  }

  /* The host's own right sidebar IS the Files panel on phones, and the host
     pins it as a fixed full-bleed sheet: [data-sidebar-right-panel=fullscreen]
     carries position:fixed; inset:0 and no inset of its own (the host CSS
     never mentions safe-area at all). Its top row - the tab strip holding the
     tab label, the + button and the Split / Exit-fullscreen pair at the right
     edge - therefore sat UNDER the status bar: measured at 390x844 with the
     panel open, the strip is [0,0,390,38] and the phone's status bar owns the
     top of the screen. The frame's own safe-area padding cannot reach it: a
     fixed element's containing block is the viewport, not the frame's padding
     box. Taking the inset as padding keeps the panel's own --dsw-alias-bg-base
     covering the whole viewport (no seam behind the status bar) and drops the
     entire row below it, with the right-hand buttons still on the right edge.
     ONLY the fullscreen form: the host's docked form (measured at 820x1180 -
     form=push, position:absolute, 365px right-anchored) has the frame's
     padding box as its containing block, so it already starts below the
     status bar; padding it too would add the inset a second time. A host
     generation that renames the form value should fail the probe loudly
     instead of silently double-padding. The rule lives in the mobile branch,
     so desktop keeps the host layout. */
  [data-sidebar-right-panel="fullscreen"] {
    padding-top: env(safe-area-inset-top, 0px) !important;
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
     role="tooltip". The user message bubble (Sixlwa_bubble since the host moved
     it to dsh-client-ui-chat) and the goal
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
    /* Message text follows the host's own font-size axis (Settings -> 字号大小)
       instead of a frozen phone constant. The host writes the user's choice to
       <body> as --dsh-content-font-size and derives the longhand token
       --dsw-font-markdown-base-font-size from it; the previous 15px !important
       cut that chain at the container, so settings 12-17 did nothing for message
       text while the host's own markdown blocks still moved — two sizes mixed in
       one column (#52). Read the longhand token only: the other token ending in
       -base is the font shorthand, an invalid font-size value that the parser
       drops and the cascade silently falls back on. The fallback chain ends at
       the host's own default axis value. */
    font-size: var(--dsw-font-markdown-base-font-size, var(--dsh-content-font-size, 14px)) !important;
  }
  /* Descendants only inherit: the host already resolves the same token on its
     own markdown blocks (and its styles pin 16px on paragraphs / list items),
     so a rule per p / li / user-message text would cut the axis a second time. */
  [data-phase]
    [class*="_scroll"]:not([class*="_scrollBody"]):not(:has([data-composer-input])):has(p) p,
  [data-phase]
    [class*="_scroll"]:not([class*="_scrollBody"]):not(:has([data-composer-input])):has(p) li,
  [data-phase]
    [class*="_scroll"]:not([class*="_scrollBody"]):not(:has([data-composer-input])):has(p) [
      class*="_text_"
    ] {
    font-size: inherit !important;
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
    /* 2026-09-23 店主："每个功能键隔的空间太多"。真机实测间距主要不是 gap（6px）
       而是各控件自己的内边距；这里 gap 收到 3px，配合下面模型 chip 的 padding
       收紧，把右簇焊成一团。 */
    gap: 3px;
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
    /* 2026-09-23 店主第三轮："左边那三个功能区挨得太近了，隔开一点点"。
       权限控件收窄 16px 后，📎 跟着整体左移、贴到了 ⌄ 上（实测墨迹间距只剩 ~3px）。
       工具道 gap 单列放宽到 8px（右簇仍 3px，保持焊在一起）。 */
    gap: 8px;
    /* The permission dropdown (Menu, side: top) pops upward from inside the
       tools lane; overflow hidden here would crop it, same as the row. Text
       ellipsis is handled by the trigger label itself. */
    overflow: visible;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"] {
    flex: 1 1 auto;
    min-width: 0;
    gap: 3px;
    /* Must not clip the model dropdown; the model trigger clips its own label. */
    overflow: visible;
  }
  /* Permission / plan controls share the tools lane inside the a2-style
     'div.modes' container (class survives as 'css.modes'; audit doc §10.1 /
     E-1). The positional anchor '> :first-child > :nth-child(2)' was already
     off-target on rc.2 and dies entirely on a2, so the series re-anchors on
     the tools lane's modes container: '[class*="_tools"] > [class*="_modes"]'
     (live-verified on the rc.2 host: the modes div is a direct child of the
     tools lane, a grandchild of the row — a row-direct-child anchor matches
     nothing on either generation). The permission label uses the remaining
     tools width, while the lower-priority plan slot keeps an icon-sized
     target instead of stealing model width. */
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_tools"] > [class*="_modes"] {
    flex: 0 1 auto;
    min-width: 0;
    max-width: none;
    /* 2026-09-23 店主："左边那个权限的也缩一点点"：容器 gap 4→0。 */
    gap: 0;
    /* The permission Menu list (side: top) pops upward out of this lane;
       overflow hidden crops it. The trigger label clips its own text. */
    overflow: visible;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_tools"] > [class*="_modes"] > [class*="_trigger"] {
    flex: 1 1 auto;
    min-width: 28px;
    max-width: 100%;
    display: flex !important;
    overflow: hidden;
    /* 权限 trigger 自带内边距 + flex gap（图标与 ⌄ 之间），图标化后都是浪费：
       2026-09-23 按店主"缩一点点"归零（真机 44px 盒 → ~34px）。 */
    padding: 0 !important;
    gap: 0 !important;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_tools"] > [class*="_modes"] > [class*="_trigger"] > [class*="_triggerLabel"] {
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
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_tools"] > [class*="_modes"] > :not([class*="_trigger"]) {
    flex: 0 1 auto;
    min-width: 34px;
    max-width: max-content;
    overflow: visible;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_tools"] > [class*="_modes"] > [class*="_wrap"] > [class*="_chip"] {
    max-width: 100%;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap !important;
  }
  @container dsh-mobile-composer (max-width: 359px) {
    [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_tools"] > [class*="_modes"] > [class*="_trigger"] > [class*="_triggerLabel"] {
      display: none !important;
    }
  }
  /* 权限触发器（宿主 dsh-client-ui-permission-presets，样式哈希 iWlSmW_）自身带
     padding:0 4px 0 8px + gap:4px —— 与模型 chip 同款浪费（左 8px 是给文字留的）。
     2026-09-23 店主："左边那个权限的也缩一点点"。注意：它外面套了一层
     display:contents 包装（真机探针：modes[55,44] > div[contents] > root[55,44]），
     所以「_modes > _trigger」这类直接子代锚点命不中（上一版改了没反应），
     必须用哈希后代锚点；哈希变了整条自动失效，不会误伤别家。 */
  [data-mobile-nav="frame"] [data-phase] [class*="iWlSmW_trigger"] {
    padding: 0 !important;
    gap: 0 !important;
  }
  /* 2026-09-23 店主："权限的图标有点小，稍微大一点点，不然左边轻右边重"。
     宿主把图标包在 _triggerIcon 里、自己写死 14px（iWlSmW_triggerIcon svg
     的 width/height 都是 14px），与 + / 📎 的 16px 不齐。只放大那个包装里的
     svg：⌄ 箭头不在 _triggerIcon 内，不会被一起放大。盒子 28×28 不变（16 仍有余量）。
     注意：本文件是模板字符串，注释里**不能出现反引号**（会劈开 CSS）。 */
  [data-mobile-nav="frame"] [data-phase] [class*="iWlSmW_triggerIcon"] svg {
    width: 16px !important;
    height: 16px !important;
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

  /* Model switcher menu: was centered here with left:50% + translateX(-50%), but the
     host now PORTALS the menu to <body> (measured 2026-09-23: _7KE1Ra_menu, role=menu,
     position:fixed, parent=BODY, inline left/top), so this child-chain selector stopped
     matching and the rule had been dead. The re-anchor lives in JS instead —
     effects/model-menu-anchor.ts centers the panel on the trigger and clamps it to the
     viewport. Do not re-add a CSS rule here without checking the portal parent. */

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
  /* --- 右簇贴右：2026-09-23 重新对锚（模型胶囊改"只留图标"后暴露的旧账）---
     宿主把右簇（模型座位 standardControls / 麦克风 activity / 发送 primary）
     放进可增长的 trailing 车道，靠"某个成员带 margin-left:auto"把整簇顶到右缘。
     插件原来把吸收器挂在模型 root 上：
       > [class*="_trailing"] [class*="_root"]:has(> [class*="_trigger"][aria-haspopup="menu"])
     但 0.1.7 的祖先链变成了
       trailing > standardControls(flex item) > div[display:contents] > _root > _trigger
     于是 root 只是 standardControls **内部**的 flex item，auto 外边距落在一个
     内容宽度的盒子里 ⇒ 等于失效。真机探针实测三者 ml 全 = 0px，就是铁证。
     2026-09-23 之前胶囊很宽、把车道填满，看不出来；胶囊一收成图标，右簇立刻
     塌到左边（发送 x≈316 → 224，右边空出 ~76px，店主一眼看出"位置被移了"）。
     修法：把吸收器改锚到「车道的第一个 flex item」，并只在模型座位在场时生效
     —— 那时宿主那条把 primary 的 auto 清零的规则也在生效，避免两个 auto 平分
     空隙；模型不在场（子代理视图）照旧由 primary 自己的 auto 收尾。
     justify-content: flex-end 是兜底：万一首个 child 是 display:none，auto
     无处可挂时仍能贴右（此时无 auto 外边距，flex-end 才起作用）。 */
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"]:has([class*="_trigger"][aria-haspopup="menu"]) {
    justify-content: flex-end;
  }
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"]:has([class*="_trigger"][aria-haspopup="menu"]) > :first-child {
    margin-left: auto;
  }
  /* ContextMeter (JObwrW_ hash family) hugging the primary key. This single
     value is the whole spacing knob, and because the trigger box is centred on
     the ring ink it doubles as the ink offset:
       6px + margin-right = the sliver before the primary key = the ink's
       leftward shift. 0px (current) therefore shifts the ink 6px -- exactly the
       official lane gap, with no negative-margin trick left in the chain --
       while -6px pins the ink perfectly still and +8px was vetoed on
       2026-09-17 as "too much" (14px). The phone owner asked for a visible
       shift after 1px (-5px) proved imperceptible, and will re-tune this number
       by eye: change it and nothing else moves.
     Anchor on the unique aria-haspopup="dialog" trigger (no other composer
     control uses it), not the hashed class, so an upstream hash bump cannot
     silently unhook us. */
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"] > [class*="_root"]:has(> [class*="_trigger"][aria-haspopup="dialog"]) {
    margin-right: 0px;
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
  /* Grow only the invisible trigger BOX, never the ring ink: 24x24 -> 28x34.
     The WIDTH is capped at 28 by pure geometry, not by taste: the box is
     centred on the ink, and the primary key's hit box begins 14px right of the
     ink's centre, so 28 is the widest box that can reach that boundary without
     stealing a single pixel from the destructive key (the current 1px sliver
     is the spacing knob on the root rule above); the same arithmetic puts the
     left edge on the model pill's edge. The 34px HEIGHT is free: the primary
     key is already the tallest control in the lane, so the box cannot overlap
     anything vertically and the row height does not move. Hit area 576 -> 952
     square px (+65%) with the ink within 1px of its old spot (probe-asserted),
     and the ring's ink stays at its official 14px -- enlarging it is rejected
     as attention-grabbing. Knob: height can drop to 28 if the tap halo should
     be a circle rather than a stadium. */
  [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) > [class*="_trailing"] > [class*="_root"]:has(> [class*="_trigger"][aria-haspopup="dialog"]) > [class*="_trigger"] {
    width: 28px;
    height: 34px;
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

  /* --- Composer file entry (0.1.6 host) ---
     The 0.1.6-alpha.2 host deleted the composer's paperclip attach button, so
     the only file entry left is the 文件 row inside the "+" listbox. The
     control is contributed to the host-declared conversation.input.left list
     slot ("Compact controls at the left of the composer tool row"), inside the
     tools lane beside the plus button, so only its own box is needed here:
     28x28 like the plus button and fixed — one of the row's hit targets, never
     part of the adaptive shrink. Its click handler triggers the host's own
     hidden input[type=file], so intake validation and upload stay host-owned. */
  [data-composer-card] [data-mobile-nav="file-upload"] {
    flex: 0 0 auto !important;
    /* 2026-09-23 店主："触发点有点小，没那么容易点" ⇒ 盒子 28×28 → 34×34
       （面积 +47%），再由下面的 ::after 向外扩 4px（最终命中区约 42×42）。
       **图标位置不变**：盒宽 +6 后 margin-left 从 -10 收到 -13，图标中心原地不动；
       高度对齐发送键的 34px，行高不受影响。 */
    width: 34px !important;
    min-width: 34px !important;
    max-width: 34px !important;
    height: 34px !important;
    min-height: 34px !important;
    padding: 0 !important;
    position: relative !important;
    /* 左移 10px + 图标 14→16px（2026-09-23，店主："太往右了、有点小"）：
       工具道现在是 [+][⚠⌄][📎]，宿主给 modes 控件留了较宽的尾部留白，📎 看着
       离左边一截。与参考图逐像素对齐（以 + 为锚点）：参考 📎 墨迹 107..117 CSS，
       我们原先是 114..123；而墨迹高度 48 vs 参考 54 物理 px ⇒ 图标 14 偏小，
       换成宿主通用的 16（+ / ⚠ 都是 16）。28px 盒 + 16px 图标居中 ⇒ 墨迹左缘
       = 盒左缘 + 8.85，故盒左缘取 98 ⇒ margin-left: -10px（吃掉 6px gap 后再
       压进 modes 尾部留白 4px，不碰它的墨迹：chevron 墨迹止于 ~91）。
       这一个数值就是"往左多少"的旋钮，可按眼睛调，别动别的。 */
    margin: 0 0 0 -11px !important;
    display: grid !important;
    place-items: center;
    border: 0 !important;
    border-radius: 8px;
    background: transparent;
    color: inherit;
    cursor: pointer;
    -webkit-tap-highlight-color: transparent;
  }
  /* 按下/悬停反馈：宿主其它按钮（加号常驻、模型与权限触发器按下）都有灰胶囊，
     只有我们这个是纯透明、也没有 :active —— 店主 2026-09-23："文件上传那个图标
     怎么没有胶囊？"（点了没反应）。用宿主自己的 hover token，视觉与官方一致。 */
  /* 可见胶囊只在 ::before 上画 28×28 的圆（与加号同尺寸，店主："胶囊有点太大"），
     按钮盒子仍是 34×34 + ::after 外扩 —— 命中区大、看起来小，两者解耦。 */
  [data-composer-card] [data-mobile-nav="file-upload"]::before {
    content: '';
    position: absolute;
    inset: 3px;
    border-radius: 999px;
    background: transparent;
    transition: background .12s ease;
  }
  [data-composer-card] [data-mobile-nav="file-upload"]:hover::before,
  [data-composer-card] [data-mobile-nav="file-upload"]:active::before {
    background: var(--dsw-alias-interactive-bg-hover, rgba(0, 0, 0, .06));
  }
  /* 命中区外扩：::after 属于按钮本身，一起参与命中测试，视觉完全不变。 */
  [data-composer-card] [data-mobile-nav="file-upload"]::after {
    content: '';
    position: absolute;
    inset: -4px;
    border-radius: 12px;
  }
  /* A busy submit phase or a subagent session refuses attachments. The host
     gates intake on canAcceptDrop (package-private), so this reads the closest
     observable facts — input phase and subagent — and keeps the control from
     opening a dialog the host would then reject. */
  [data-composer-card] [data-mobile-nav="file-upload"]:disabled {
    opacity: 0.38;
    cursor: default;
  }
  /* The hidden input[type=file] this control triggers only exists on
     0.1.6-alpha.2+ hosts (the rc generation's intake is paste/drop only),
     while the host renders the input.left seat on both generations. Hide the
     control wherever the host has no file input for it to trigger — its click
     would otherwise be a permanent silent no-op there (review 2026-09-19). */
  [data-composer-card]:not(:has(input[type=file])) [data-mobile-nav="file-upload"] {
    display: none !important;
  }

  /* --- Composer vertical slack on mobile (0.1.6 host) ---
     The host's own .card padding-top:8px + gap:12px and .row padding leave 29px of pure
     blank space in a 98px single-line card (measured). Only the vertical slack is trimmed;
     horizontal padding and both hit targets stay untouched. Scoped to the active phase on
     purpose: the hero composer's input carries the host's own min-height floor, and trimming
     it there re-creates the clip/scrollbar defect recorded under Pitfalls「hero 输入框下限」. */
  /* DSHA：输入卡片自身留白偏大。宿主那两声明全出自它自己的
     dsh-client-ui-conversation（.uV2eYG_card 是 padding-top:8px + gap:12px，
     .uV2eYG_row 再吃 padding:2px 8px 6px），单行输入时卡片 98px 里有 29px
     是纯空白。手机上只压纵向留白（真机实测 moderate 档）：
       卡片 98 -> 78、编辑器 36 -> 32、按钮行 42 -> 36、文字底到按钮顶 29 -> 19px。
     横向 padding（8px）与两个按钮尺寸（28/34px）一律不动，触控目标不变；
     编辑器仍是可增长的多行框（max-height 336px），只是单行时不再垫高。 */
  [data-mobile-nav="frame"] [data-phase="active"] [data-composer-card] {
    padding-top: 2px !important;
    gap: 4px !important;
  }
  [data-mobile-nav="frame"] [data-phase="active"] [data-composer-card] [class*="_row"] {
    padding: 0 8px !important;
  }
  [data-mobile-nav="frame"] [data-phase="active"] [data-composer-card] [data-composer-input],
  [data-mobile-nav="frame"] [data-phase="active"] [data-composer-card] [class*="_scroll"] {
    min-height: 28px !important;
    padding-top: 2px !important;
  }
  /* --- Session header on mobile ---
     Keep the host-owned metadata in one responsive row. The conversation
     title, the mode text and the running/subagent status all keep their
     words; the one tenant that yields width when a phone runs out of it is
     the background-job trigger's verbose label ("1 background job running"),
     while Files keeps its hit area. */
  /* Both !important flags are load-bearing. The host's session-controller sheet
     ships [data-dsh-frame] [data-dsh-responsive-part="conversation-header"] with
     padding-left: 60px !important under (max-width: 768px), so a plain
     declaration here loses however specific it is: measured 2026-09-13 at
     390px, the computed padding-left stayed 60px and the title still began at
     x=100 with our rule present, matching and later in source order. The value
     is 0 because our own toggle already occupies that left seat (painted at
     x=8-36), so the host reservation is pure dead space on a phone. */
  [data-mobile-nav="frame"] [data-phase] header {
    padding-left: 0 !important;
    padding-right: 8px !important;
    position: relative !important;
  }
  /* The hero phase's empty header must stay hidden on phones. The host hides
     it via the headerHidden class at (0,1,0), but its own session-controller
     sheet re-shows the conversation header as a grid at <=768px —
     [data-dsh-frame] [data-dsh-responsive-part="conversation-header"] with
     display grid at (0,2,0) — and that beats the hide on the very element
     carrying both classes. Result measured 2026-09-19 at 390px: an empty 85px
     header paints only its 1px border-bottom (--dsw-alias-border-l3) as a stray
     gray hairline under the status bar (pixel-scanned at y=84-85,
     rgb(224,224,224)); desktop keeps display none and no line. Our (0,3,1)
     re-hide needs no !important: the grid rule's display is a normal
     declaration and our style tag loads last. The header carries no children in
     hero (drawer entry is the FAB), so hiding it frees the dead 85px too. */
  [data-mobile-nav="frame"] [data-phase] header[class*="_headerHidden"] {
    display: none;
  }
  /* 0.1.6-alpha.2 renamed the hero-empty marker: headerHidden -> headerBlank
     (audit §1 row 3), so the rule above is a dead needle on alpha.2 and this
     one is dead on rc hosts — together they cover both generations. Same
     (0,3,1) shape, same no-!important reasoning as above. */
  [data-mobile-nav="frame"] [data-phase] header[class*="headerBlank"] {
    display: none;
  }
  /* Header popovers resolve against the header, not against their 28px flow
     box. 0.1.5's background-job chip anchors its menu with
     position:absolute; top:calc(100% + 5px) inside .QsffPG_root
     {position:relative} — a 28px-tall chip — so the menu was laid out at
     x=-16 (our right:8px resolved against that 156px chip root) and then
     clipped twice: by our own overflow:hidden on the chip root and by the
     host's [data-dsh-responsive-part="session-title-cluster"]
     {overflow:hidden}. The chip still reported aria-expanded=true with
     nothing painted and nothing hit-testable: measured 2026-09-14 at 390px,
     menu rect [-16,49,336,40], elementFromPoint at its centre returned the
     view tabs row. A positioned header plus a static chip root puts the same
     menu at [46,77,336,73] — inside the viewport, its rows hit-testable, and
     an outside tap still dismisses it (menus 1 -> 0). On 0.1.6-alpha.2 the
     chip leaves the flow entirely (absolute, in the gated block below), and
     this rule's higher specificity ((0,6,2) vs the new (0,4,1)) would pin it
     static there too — so it is excluded on alpha.2 hosts via :not(:has(...))
     and keeps governing rc hosts (review 2026-09-19).
     BOTH halves are load-bearing: forcing the chip root static without
     positioning the header moves the containing block out to the frame, and
     the menu lands at x=8 y=849 — past the 844px viewport (A/B 2026-09-13).
     Scoped to the header actions slot, so the subagent lineage root inside
     the crumbs keeps its own anchored, fixed-position menu. */
  [data-mobile-nav="frame"] [data-phase] header:not(:has([class*="_headerLeading"])) [class*="_headerActions"] [class*="_root"]:not([class*="_switcherRoot"]):has(> button[class*="_trigger"]) {
    position: static !important;
  }
  /* The tab strip is a separate grid item from the title row and does not
     inherit the title row's inset, so after the header padding above went to 0
     it sat flush against the bezel (measured: tablist x=0, first tab 0..30
     while the title starts at 40). Give it the same left inset as the toggle so
     the two rows read as one column. */
  /* ---------- 手机档专属：头部留白收紧（≤767px + coarse）----------
     数值是照 360×754 真机量的（页签条 margin-top -4 / 页签下划线 5px /
     页签按钮去上内边距、靠底对齐 / 标题行回到内容高度）。**只对真·手机档生效**：
     768–1023 的平板档保持上游手机 UI 的排布，不套这台手机的魔数
     （仓库既有惯例，见文件末尾的 DSHA 预设块）。
     真机读数（修前 → 修后）：头部 77 → 67px、标题↔页签文字间距 22 → 15px。 */
  @media (max-width: 767px) and (pointer: coarse) {
    [data-mobile-nav="frame"] [data-phase] header [class*="wSkVaW_tabs"] {
      padding-left: 8px !important;
      /* 2026-09-23 店主："标题和下面『对话』中间的空白有点多"。
         宿主给这条页签条 margin-top:10px，页签按钮自己还带 padding-bottom:9px
         （给选中下划线留位），两行文字之间就空出一条。收紧：
         margin 归零（真机 4 → 0）+ 下划线贴到 5px（页签条 36 → 31px）。
         ⚠ 选择器必须用**后代**：页签条外面套了一层 display:contents 的 div
         （真机链：div.wSkVaW_tabs < div[0..0] < header.wSkVaW_header），
         所以原来的「header > [class*="wSkVaW_tabs"]」是条死规则 —— padding-left
         从来没生效过（现按后代写，值仍是实测的 8px，视觉不变）。 */
      margin-top: 0 !important;
      margin-bottom: 0 !important;
    }
    [data-mobile-nav="frame"] [data-phase] header [class*="wSkVaW_tabs"] [class*="wSkVaW_tab"] {
      padding-bottom: 5px !important;
    }
    /* 真机诊断：页签条的 margin-top 计算值是 4px，但把 document.styleSheets 里
       所有能读的规则拿来和它 matches()，命中的 margin/padding 规则是 **0 条**
       —— 说明这 4px 来自一张读不到 cssRules 的表（跨源，App 自己注入的样式表），
       普通 !important 平级打不过它。所以这里加码：前缀 html + 钉住 header.wSkVaW_header，
       特异性抬到 (0,5,1)，实测能压过（页签条 4 → 0）。 */
    html [data-mobile-nav="frame"] [data-phase] header.wSkVaW_header [class*="wSkVaW_tabs"] {
      margin-top: -4px !important;
    }
    /* 真机读数：头部的 grid-template-rows 被钉成固定的 40px 36px（宿主自己没写行高，
       是 DSHA 那张表给的），于是标题行、页签行都各留一截死空间。改成 auto：两行各自
       贴住内容，标题行按 36px 的预设 chip 走、页签行由页签按钮撑开。 */
    /* 标题行实测 40px 高，而里面最高的东西是 36px 的预设 chip（"标准模式"）——
       多出来的 4px 是死空间。让行高回到内容高度（用 auto + min-height:0，
       不写死 36：将来标题簇里出现更高的东西（子代理谱系等）也不会被裁）。 */
    html [data-mobile-nav="frame"] [data-phase] header.wSkVaW_header [class*="wSkVaW_titleRow"] {
      height: auto !important;
      min-height: 0 !important;
    }
    /* 页签按钮文字上方还有 6px 空白（按钮被容器撑到 32px 高、文字居中）：去掉上内边距，
       下内边距 5px 已在上面钉住（下划线位置不变）。 */
    html [data-mobile-nav="frame"] [data-phase] header.wSkVaW_header [class*="wSkVaW_tab"] {
      padding-top: 0 !important;
      align-self: flex-end !important;
    }
  }
  /* NOTHING extra here on purpose. The header's own padding is already forced
     to 0 above, and the title row carries padding-left:40px of its own, so the
     title lands at x=40 - the toggle's right edge (36) plus 4px. A negative
     margin added on top of that over-corrected and pulled the title off the
     left edge (measured 2026-09-13: crumb x=20, and the string's first glyph
     painted partially outside the viewport), so the reclaim lives in exactly
     one place: the header padding. */

  [data-mobile-nav="frame"] [data-phase] header > :first-child {
    display: flex !important;
    align-items: center;
    box-sizing: border-box;
    width: 100%;
    min-width: 0;
    gap: 2px;
    /* Just enough for the toggle (28px at left:8 -> right edge 36) plus 4px of
       breathing room; the host's 60px rail reservation is neutralised above. */
    padding-left: 40px;
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
  /* The files opener is pinned to the header's right corner, mirroring the
     directory toggle on the left (same 8px edge, same 12px seat). In flow it
     can never reach that corner: the host reserves the last 44px of the title
     cluster for a utilities seat that is EMPTY on mobile - measured at 390px,
     headerUtilities sits at x=374 with width 0 while the title cluster carries
     padding-right: 44px - so the button stopped at x=300..328 and left 62px of
     bare header to its right (2026-09-14 phone-side report: the opener is not
     pinned to the top-right corner). Absolute positioning also returns its
     28px of flow width to the title lane, and the containing block is the same
     one the toggle resolves against, so both controls shift together with the
     frame's safe-area padding. The 44px reservation itself is trimmed to the
     28px band this button actually paints in the compact-rows block below, so
     the title lane keeps the difference. */
  [data-mobile-nav="files"] {
    position: absolute !important;
    right: 8px !important;
    left: auto !important;
    top: 12px !important;
    z-index: 2 !important;
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
  /* min-width is a readable floor (2026-09-13 phone report: the title showed a
     single glyph then an ellipsis). This lane has flex basis 0, so it is the
     first thing every crowding neighbour eats: measured at 320px with a lineage
     chip in the row, the crumb client width collapsed to 16px and NOTHING of
     the title was painted. 30% of the row keeps 2-4 CJK glyphs plus the host's
     own ellipsis whatever else is pinned next to it. */
  [data-mobile-nav="frame"] [data-phase] header [class*="_crumbs"] {
    flex: 1 1 0;
    min-width: 30%;
    max-width: none;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap !important;
  }
  /* Mode label: keep the icon AND the words. On a phone this chip is the only
     mode switcher there is, so its text is not the surplus it was once
     treated as: the longest preset name measured needs 121px including the
     18px icon seat, while the old cap min(22vw, 220px) allowed just 85.8px at
     390px — the text was clipped at every phone width even before the
     crowding rules below pinned it to the icon alone (2026-09-14 phone
     report: the mode label showed only its glyph). 38vw keeps the label whole
     from 320px up and still lets it ellipsize before the title on wider
     screens. */
  [data-mobile-nav="frame"] [data-phase] header [class*="_label"]:has(> svg) {
    order: 1;
    flex: 0 1 auto;
    min-width: 0;
    max-width: min(38vw, 220px);
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
     template-literal className — so [class$="_root"] never matches it. Use
     [class*="_root"] and exclude the switcher root ([class*="_switcherRoot"])
     so only the count/job roots get pinned (the switcher must stay shrinkable
     so its own title can ellipsize). */
  /* Pinned (flex 0 0 auto) with a max-width cap. A shrinkable chip is squeezed
     below its content and the count reads as clipped or overwritten (the
     2026-08-22 report), while a bare max-content pin eats the session title,
     whose flex basis is 0: measured 2026-09-13 at 320px, the crumb went 68px
     -> 16px and the painted title was EMPTY while the chip kept its full text.
     Pinned + capped + the crumbs min-width floor above is what holds both —
     the title ellipsizes, the count keeps its words, and the hit area stays
     one inline-flex button.
     NOTE: the popover containment lives with the header rules above, which
     force this root position:static. That only works together with the
     positioned header: static on its own moved the containing block out to
     the frame and the menu landed at x=8 y=849, past the 844px viewport
     (A/B 2026-09-13). */
  [data-mobile-nav="frame"] [data-phase] header [class*="_root"]:not([class*="_switcherRoot"]):has(> button[class*="_trigger"]) {
    order: 2;
    flex: 0 0 auto;
    min-width: 0;
    max-width: min(40vw, 180px);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap !important;
  }
  [data-mobile-nav="frame"] [data-phase] header [class*="_root"]:not([class*="_switcherRoot"]):has(> button[class*="_trigger"]) > button {
    min-width: 0;
    max-width: 100%;
  }
  [data-mobile-nav="frame"] [data-phase] header [class*="_root"]:not([class*="_switcherRoot"]):has(> button[class*="_trigger"]) > button > * {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
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
  /* The header's right-hand slot clips its own dropdown away (0.1.5 host bug).
     wSkVaW_headerUtilities is a 44x44 grid cell with overflow:auto, and the host
     mounts its "More actions" menu INSIDE it: the menu is 218x52, so the cell
     clipped it to 44x44 and the menu was never painted and never hit-testable
     (measured: menu rect 156,56 218x52, computed flex/visible/opacity 1, yet
     elementsFromPoint at the item centre returned the view tabs row and nothing
     from the menu). Raising the menu z-index cannot help - the cell's own
     stacking context traps it. Releasing the overflow paints the menu where the
     host positioned it, and the item then works (verified: a real tap opening
     the session-log export dialog, menus 1 -> 0 dialogs 1). Scoped to the mobile
     branch and to this one cell, so desktop keeps the host layout. The section
     is hidden on mobile anyway - the drawer footer carries the same action - but
     the release stays for any plugin that registers a header dropdown here. */
  [data-mobile-nav="frame"] [data-phase] header [class*="wSkVaW_headerUtilities"] {
    overflow: visible !important;
    /* The seat is empty on a phone (its only button is hidden just below) yet
       still 44px tall, which floors the whole title row — see the compact-rows
       block after the tab strip. */
    height: 30px !important;
    min-height: 0 !important;
  }
  [data-mobile-nav="frame"] [data-phase] header [class*="wSkVaW_headerUtilities"] [class*="nL4_yW_moreButton"] {
    display: none !important;
  }
  [data-mobile-nav="frame"] [data-phase] header [data-mobile-nav="files"] {
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
  /* Compact session header rows (2026-09-14 phone report: the top is very
     empty). The host's own mobile sheet lays the header out as
     grid-template-rows: minmax(32px, auto) minmax(44px, auto) with
     [role="tab"] { min-height: 44px }, and both rows then grow to 44: the
     title row is floored by the empty utilities seat above, the tab row by the
     buttons' own floor. Measured at 390px: header 97px = 8 padding + 44 + 44 +
     1 border, for 36px of painted content. Capping the rows at 36/32 and the
     tabs at their own content height gives 77px, with nothing else degraded —
     title, mode text, status chips, chevrons and both pinned corner buttons
     keep their measured geometry, and the tab strip keeps its #41 contract
     (horizontal scroll, 16px gap, whole labels, pan-x).
     The host's 8px padding-top is deliberately kept: the title row's 28px
     content then centres at y=26, exactly the centre of the pinned corner
     controls (toggle and Files opener both sit at top:12, 28px tall). Trimming
     that padding to 4 shaved 4 more px but left the text row visibly riding
     above both buttons (2026-09-14 phone report: the text row sits too high
     against the drawer and Files controls), so the row height is what pays for
     the compaction, not the alignment.
     :has(> *) guards the hero header: it is an EMPTY, host-hidden grid that
     still occupies 85px while the composer is laid out under it. In the hero
     the header has 0 element children, so the guard leaves it at its official
     height — measured, the hero composer rect [0,349,388,231] is identical
     with and without this block. */
  [data-mobile-nav="frame"] [data-phase] header:has(> *) {
    min-height: 0 !important;
    /* 2026-09-23 二轮：页签行地板 32 → 26（店主："标题和下面『对话』中间空白有点多"）。
       标题行地板保持 36 —— 它下面的文字要跟 top:6 的圆形按钮对齐（实测文字中心
       y=20 = 圆形按钮中心），压标题行会把文字顶得比按钮高（2026-09-14 已踩过）。
       页签按钮的 32px 地板同理下到 26px（文字 16px + 下划线留 5px），
       页签条的下沿随之从 76 收到 66。 */
    grid-template-rows: minmax(36px, auto) minmax(32px, auto) !important;
  }
  [data-mobile-nav="frame"] [data-phase] header [role="tab"] {
    min-height: 32px !important;
  }
  /* 手机档专属（≤767px + coarse）：页签行地板 32 → 26（下划线收到 5px 后仍够点）。
     平板档保留 32px 的既有值，不跟手机一起压。 */
  @media (max-width: 767px) and (pointer: coarse) {
    [data-mobile-nav="frame"] [data-phase] header:has(> *) {
      grid-template-rows: minmax(36px, auto) minmax(26px, auto) !important;
    }
    [data-mobile-nav="frame"] [data-phase] header [role="tab"] {
      min-height: 26px !important;
    }
  }
  /* The title cluster reserves its last 44px for that empty utilities seat.
     The lane's right edge must stay clear of the Files opener's HIT BOX,
     otherwise the opener eats the trailing chips' taps. The pre-2026-09-22
     value (26px, a width optimisation) only cleared at the 390px test width:
     measured at 360x754 (dpr 4) the Agent Team chip ran to x=334 while the
     opener's 36px box started at x=316 — an 18px overlap, so tapping the
     chip's tail opened the Files panel. The reference phone UI shows the lane
     ending at ~314 with the opener box at 316..352, i.e. the full 44px seat
     plus 2px of breathing room, so restore that instead of narrowing the
     opener: 46px clears the 36px box at right:8 by 2px at every width. */
  [data-mobile-nav="frame"] [data-phase] header [class*="wSkVaW_titleCluster"] {
    padding-right: 46px !important;
  }
  /* Header crowding on narrow phones.
     Three tenants want the same row: the session title, the mode chip and the
     status chips. The status chips are the only ones whose words are
     redundant — the background-job chip keeps its state dot, its chevron and
     its aria-label, and the popover above now lists the jobs — so the job
     trigger's verbose label ("1 background job running") is what yields. The
     mode chip is the only mode switcher a phone has and the title is the only
     session identity, so both keep their words and the title ellipsizes
     instead (measured 2026-09-14 at 390px with a lineage chip present: after
     this the mode label keeps 101px of text and the crumb 135px).
     The lineage root (dsh-client-ui-subagent) sits in the crumbs for BOTH
     running and idle descendants, so the guards below key on that root rather
     than the transient running-state dot — otherwise the row would reflow the
     moment agents go idle. Match roots with [class*="_root"] (the real class
     carries a trailing space; [class$="_root"] matches nothing). */
  @media (max-width: 440px) {
    /* The job label is the single widest tenant of the actions lane and the
       only one whose text is already carried elsewhere (aria-label + popover).
       Truncating it to a number instead would print the wrong count for a
       double-digit job list, so it is dropped whole — dot, chevron and tap
       target stay. */
    [data-mobile-nav="frame"] [data-phase] header [class*="_headerActions"] [class*="_root"]:not([class*="_switcherRoot"]):has(> button[class*="_trigger"]) [class*="_count"] {
      display: none !important;
    }
  }
  /* With the subagent lineage (any state) AND a background job present
     together, 390px cannot hold the title, the mode words, the lineage count
     and the job label at once; the job label goes first, above 440px too. */
  @media (max-width: 559px) {
    [data-mobile-nav="frame"] [data-phase] header [class*="_crumbs"] {
      padding-right: 8px;
    }
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_crumbs"] [class*="_root"]) [class*="_headerActions"] [class*="_root"]:not([class*="_switcherRoot"]):has(> button[class*="_trigger"]) [class*="_count"] {
      display: none !important;
    }
  }
  /* Last resort on 320px-class screens: the title and both status chips cannot
     share the row with the mode words, so the mode chip keeps only its icon. */
  @media (max-width: 359px) {
    [data-mobile-nav="frame"] [data-phase] header:has([class*="_crumbs"] [class*="_root"]):has([class*="_headerActions"] [class*="_root"]) [class*="_label"]:has(> svg) {
      display: none !important;
    }
  }

  /* --- Header popovers on mobile (dsh-client-ui-jobs / dsh-client-ui-subagent) --- */
  /* Both entries sit in the session header and both anchor their panel to the
     trigger's left edge (left:0 inside their own root), so clamp them to the
     viewport. The background-job menu resolves against the header (see the
     containment rules at the top of this section) and the subagent lineage
     menu is position:fixed, so right:8px pins either panel 8px from the
     phone's right edge: measured [46,77,336,73] for the job menu and
     [38,41,336,58] for the lineage menu at 390px, both fully inside the
     viewport. Do NOT clamp with left:8px: measured, that put the panel at
     x=350..686 (off-screen) against a right-anchored x=30..366. */
  [data-mobile-nav="frame"] [data-phase] header [class*="_menu"] {
    left: auto !important;
    right: 8px !important;
    width: min(336px, calc(100vw - 16px));
    max-width: none;
    max-height: min(420px, calc(100dvh - 120px));
  }

  /* --- 0.1.6-alpha.2 session-header adaptation (audited on a real device) ---
     The 16-item reconciliation in docs/upstream/2026-09-19-mobile-header-0.1.6-adaptation.md,
     landing the 14 items whose anchors exist in 0.1.6-alpha.2 host builds. Two preset items
     (#6/#7) are deliberately omitted: they anchor on .dsha-preset-header-anchor, a marker
     that exists only in the DSHA build, so they would be dead rules here.
     GENERATION GATING: only _headerLeading/_crumbCurrent/_crumbSeg/_headerCorner are
     alpha.2-only classes — every other anchor below (_titleCluster/_crumbs/_headerActions/
     _headerUtilities/tablist/QsffPG_/ZKlsPq_ and the :first-child chains) also exists on
     0.1.5-rc hosts, where these rules would silently re-tune geometry the older rules
     measured (review 2026-09-19). Every selector therefore carries
     header:has([class*="_headerLeading"]): the whole block is dead on pre-alpha.2 hosts and
     the rc-generation rules keep governing there unchanged. */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) {
    /* 顶部留白收窄：宿主 header 自带 padding-top: 10px、标题行再垫 2px，
       叠在刘海/状态栏避让之上就显空。这两处一起清零。 */
    padding-left: 8px !important;
    padding-right: 8px !important;
    padding-top: 0 !important;
    /* 宿主 header 有 min-height: 76px，而内容只有 ~69px，底部会垫出 7.6px 空白
       （实测：标签行底边 106，header 底边 113.6）。贴底定位的状态 chip 会被这
       段空白顶下去、和标签行错开。手机上让 header 贴住内容高度。 */
    min-height: 0 !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) > :first-child {
    flex-wrap: nowrap !important;
    align-items: center !important;
    gap: 0 !important;
    padding-left: 32px !important;
    padding-right: 0 !important;
    padding-top: 0 !important;
  }
  /* 目录开关跟着一起上移，保持与标题/按钮同一行居中。 */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [data-mobile-nav="toggle"] {
    top: 6px !important;
  }
   /* 空座位不判空、只塌宽：a2 槽位渲染器永远在 headerLeading 里挂一个
      [data-slot] 包装元素（display:contents、0×0），:empty 与 :not(:has(*))
      两种「空」判定都恒不命中（宿主自己的 :empty 规则同样失效），而
      display:none 又会在某代真的渲染控件时误藏真控件。这里不判定空不空，
      只把第三方误标进来的预留 padding 塌掉——web-all 兼容层按 0.1.5 结构
      把本座位误标成 session-title-cluster，注入 padding-inline-end:44px，
      座位于是 0 内容 + 44 padding = 44px 死占（实测 390px：座位
      [40,22,44,0]、titleCluster 被顶到 x=84）。padding 归零后空座位 = 0×0，
      真有内容的宿主也不受影响（内容盒照常渲染）。 */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_headerLeading"] {
    /* 不能塌掉左侧座位本身：上面那条（> :first-child）给本座位留了
       padding-left:32px 作面板开关的座位，而 padding: 0 !important 是
       简写，会把它一并清零。两条规则特异性同为 (0,4,1)，按源序本块在后
       ⇒ 简写胜出，座位塌成 0×0、网格第一列 0px、标题直接压到 left:8 的
       面板按钮上（真机实测 2026-09-22，360x754@4：crumbs x=8、
       toggle 8,6 28x28；真机 DOM 规则枚举确认胜出者就是本块）。
       NOTE: 本文件整体是 JS 模板字符串，注释里绝不能出现反引号。 */
    padding: 0 !important;
    padding-left: 32px !important;
  }
  /* 0.1.6 的新头部里，titleRow 的第一个孩子是新增的空座位
     headerLeading（macOS 桌面控件，安卓上渲染 null）。插件按 0.1.5 老结构
     写的「header > :first-child > :first-child { flex: 1 1 auto }」现在套在
     这个空座位上，于是它吃掉全部剩余宽度、把标题顶到右侧（实测 411px 宽
     屏幕上标题被推到 131px 处）。让它不参与伸缩即可——有内容时也不会塌。 */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) > :first-child > :first-child {
    flex: 0 0 auto !important;
    width: auto !important;
    min-width: 0 !important;
    gap: 0 !important;
  }
   /* 第三方兼容层（@linxin666/dsh-web-all 的 web-ui-compat 行）按 0.1.5 结构
      把本代 titleCluster 误标成 session-utilities，给里面所有按钮注入
      min-width/min-height:44px + flex:none：toggle/files、模式/团队/面包屑
      按钮全被顶成 44 —— toggle (8,6,44,44) 中心 28、files (338,2,44,44)
      中心 24、标题带中心 22 三心不齐；files 加宽后越过 headerActions 流右缘
      6px（338 < 344）。宿主 0.1.6-alpha.2 自身没有任何 44px 下限（全包
      grep 零命中），这里把外来下限归零：控件回到各自设计尺寸（toggle 28
      来自 base.css、files 36 来自下面的 a2 专条、chips/面包屑回宿主自然
      高度），三心回到 20，titleCluster 的 min-height:40 !important 重新
      主导行高。QsffPG/ZKlsPq 两个状态 chip 用 :not 明确豁免：它们的
      25px 下限由后面 min-height:25px !important 专条供给，特异性 (0,4,1)
      低于本条 (0,7,1)，不豁免会被顺手压掉，不靠书写顺序。 */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_titleCluster"] :is(button, [role="button"]):not([class*="QsffPG_root"] button):not([class*="ZKlsPq_root"] button) {
    min-width: 0 !important;
    min-height: 0 !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_titleCluster"] {
    display: flex !important;
    flex-wrap: nowrap !important;
    flex: 1 1 auto !important;
    width: auto !important;
    max-width: none !important;
    min-width: 0 !important;
    min-height: 40px !important;
    gap: 0 6px !important;
    justify-content: flex-start !important;
    align-items: center !important;
    /* 簇溢出守卫，随断点 A 无条件化并入本显示规则（原为独立条）：极端
       字体下 crumbs 触地板后的残余溢出保持可横滑，不依赖 web-all 垫片
       （缺席时簇溢出默认 visible，会压画到 corner 按钮上）；内容放得下时
       本声明完全惰性。x:auto 把 y 也算成 auto，簇内容高 ≤40px 恒不纵溢
       无实害；findHorizontalScroller 对 overflow-x 容器让位。 */
    overflow-x: auto !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_titleCluster"] > [class*="_crumbs"] {
    /* 标题改成自适应：面包屑条吃掉动作区之外的剩余宽度，标题多长就显示多少，
       装不下时由每一段自己的滑动窗口（见下）横向滑。min-width 保底 4 字，
       防止预设名字很长时把标题挤没。 */
    flex: 1 1 auto !important;
    width: auto !important;
    min-width: 72px !important;
    max-width: none !important;
    margin-left: 0 !important;
    margin-right: 0 !important;
    min-height: 0 !important;
    padding-right: 0 !important;
    overflow: visible !important;
    white-space: nowrap !important;
  }
  /* 标题本体：自适应宽度 + 横向滑动。宽度由上面面包屑条的剩余空间决定，
     装不下时在本段内左右滑（touch-action: pan-x 让浏览器先认领横滑，
     左缘抽屉手势不会抢走这一笔）。 */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_crumbs"] [class*="_crumbCurrent"] {
    flex: 0 1 auto !important;
    width: auto !important;
    min-width: 0 !important;
    /* 6 个汉字上限：6×14px + 左右 padding 16px = 100px。再长就在本段内横滑，
       这样标题永远不会顶到右侧的预设。 */
    max-width: 100px !important;
    overflow-x: auto !important;
    overflow-y: hidden !important;
    text-overflow: clip !important;
    white-space: nowrap !important;
    text-align: left !important;
    justify-content: flex-start !important;
    touch-action: pan-x !important;
    overscroll-behavior-x: contain !important;
    scrollbar-width: none;
    -webkit-overflow-scrolling: touch;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_crumbs"] [class*="_crumbCurrent"]::-webkit-scrollbar {
    display: none;
  }
  /* 面包屑的父会话段同样是 <button>，不设窗口就会顶出去（子代理会话实测）。 */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_crumbs"] [class*="_crumbSeg"] > button {
    flex: 0 1 auto !important;
    min-width: 0 !important;
    max-width: 100px !important;
    overflow-x: auto !important;
    overflow-y: hidden !important;
    text-overflow: clip !important;
    white-space: nowrap !important;
    text-align: left !important;
    touch-action: pan-x !important;
    overscroll-behavior-x: contain !important;
    scrollbar-width: none;
    -webkit-overflow-scrolling: touch;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_crumbs"] [class*="_crumbSeg"] {
    flex: 0 1 auto !important;
    min-width: 0 !important;
    justify-content: flex-start !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_headerActions"] {
    /* 断点 A（用户拍板 2026-09-19，全移动档无条件生效）：动作行不参与收缩，
       chips 按自然宽渲染，收缩职责全数交还 crumbs 滑动窗口当避震器。
       根因链：内容是字体相对的、预算是固定像素的——headless（CJK
       fallback 字体）文字窄、真机（另叠加 Android fontScale）文字宽，
       flex:0 1 auto 按 basis 比例收缩时行内唯一无下限的项是模式 label
       （min-width:0），真机截成「创造…」「Agent Te…」而 headless 全字。
       为什么无条件化：首版用 min-width:377 分档（按 390 假设视口的 k≈1.2
       破坏点推演），真机 diag 读数证伪——设备实测视口 360、dpr 3.5
       （vivo V2425A，Android 16，Chrome 151），整台设备落在档位之下，A 档
       从未绘制、旧收缩机制照跑、芯片照压；同一读数里无门的 stats 规则真机
       验证生效、A 档未生效，对照坐实是分档包裹死档而非声明无效。目标任何
       手机宽度芯片全字、极端窄屏靠滑窗降级不靠截断——分档与目标矛盾，删，
       flex 直接并入本几何规则唯一声明。新几何：lane 停缩后行内唯一可缩项
       是 crumbs（flex 1 1 auto，地板 72px；窗口帽 max-width:100px 是字体
       无关盒子，窗内 pan-x 滑动保证长标题可读），避震容量 = crumbs 自然
       宽−72（根会话约 28px、子代理会话双窗最多 128px；360 真机肥字体
       k>1.22 时 crumbs 触地板、残余走 cluster 横滑）。级联核查：本规则是
       全档唯一 flex 来源、无其他 flex 分量；rc 代 840 行是普通权重且
       prelude 不同（无 :has 门），被本条 importance 压制，无 order-tie。 */
    flex: 0 0 auto !important;
    width: auto !important;
    max-width: none !important;
    min-height: 36px !important;
    margin-left: auto !important;
    padding: 0 !important;
    border-top: 0 !important;
    justify-content: flex-end !important;
    gap: 6px !important;
    overflow-x: auto !important;
    scrollbar-width: none;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_headerActions"]::-webkit-scrollbar {
    display: none;
  }
  /* stats 行左端「N 轮」被裁且不可达（用户真机两帧 + headless 390 复现）：
     宿主 bOPqQW_root 是 justify-content:center 的横向滚动容器，内容溢出
     49px（scrollWidth 333 / clientWidth 284）时两侧对称各裁 ~50px——右侧
     scrollLeft 最大 49 可达，左侧起点 x=-34 是负坐标、scrollLeft 恒 ≥0
     永不可达，center+overflow 经典陷阱。改 flex-start 后溢出全落在右侧，
     滑动全程可达；取舍：内容放得下时行内从宿主的居中变左对齐（视觉差异
     仅空隙分布），功能缺陷（指标永久丢一段）优先。特异性 (0,3,0) 带
     !important 胜宿主 (0,1,0) 普通声明，与书写顺序无关；data-mobile-nav=
     "stats" 是 stats-line 效果打的稳定标记，无哈希、跨宿主代际可用。
     本条置于 ①嵌套块外：裁切陷阱与断点 A 的档位无关，全移动宽度生效。 */
  [data-mobile-nav="frame"] [data-phase] [data-mobile-nav="stats"] {
    justify-content: flex-start !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [data-mobile-nav="files"] {
    width: 36px !important;
    height: 36px !important;
    flex: 0 0 36px !important;
    /* Keep the 36px seat the reference phone UI shows (opener box 316..352 at
       360px, icon 326..342): it is the geometry the lane's 46px reservation
       above is tuned against. Mirror the toggle's centre (top:6px for a 28px
       control -> centre y=20) by lifting the taller box to top:2px. */
    top: 2px !important;
  }
  /* 新宿主把「右侧栏入口」放进了 titleRow 的 headerCorner。手机上市宿右侧栏
     就是 Files 面板，所以它和插件的文件按钮是同一个面板的两个入口；而它带
     margin-right:-16px，36px 盒子在 360px 视口下会从文件按钮右侧漏出一角
     （2026-09-22 实测：corner [332,2 36x36]、图标 343..358 外露，被视口裁切），
     与参考图"右上角只有一个文件夹图标"不一致，也与插件自己的文件按钮重复。
     只针对标题行内的 corner，老一代宿主（corner 是唯一入口）不受影响。 */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="wSkVaW_titleRow"] > [class*="_headerCorner"] {
    display: none !important;
  }
  /* 右上角换人：0.1.6 把「右侧栏展开按钮」放进了 headerCorner，而插件的
     老规则「header > :first-child > :last-child 显示 none」在 0.1.5
     藏的是「会话日志胶囊」；新结构里 titleRow 的 :last-child 变成 corner，
     于是右侧栏入口被误藏、面板在手机上打不开。这里把 corner 放出来，
     同时让出「⋯」菜单那一格（360px 一行塞不下两个）。 */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) > :first-child > :last-child[class*="_headerCorner"] {
    display: flex !important;
    flex: 0 0 auto !important;
    margin-left: 4px !important;
    margin-right: 0 !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_headerCorner"] button {
    width: 36px !important;
    height: 36px !important;
    min-width: 36px !important;
    min-height: 36px !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_headerUtilities"] {
    display: none !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [role="tablist"] {
    width: 100% !important;
    margin-top: 4px !important;
  }
  /* 标签行右侧的两个状态 chip：
     · 后台任务 chip（dsh-client-ui-jobs 的 QsffPG_root）
     · 子代理谱系 chip（dsh-client-ui-subagent 的 ZKlsPq_root）
     它们在动作行里会和标题窗口 + 预设 + 文件抢同一条 flex，实测直接叠在一起
     （进子代理会话时最明显）。两块都绝对定位到「对话/轨迹」行右侧，动作行只留
     [预设][文件]；标签行右侧按 chip 宽度预留，标签变多横向滑动也不会钻到下面。
     两个 chip 同时存在时，子代理排在后台任务左边。 */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) {
    position: relative !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [role="tablist"] {
    padding-right: 8px !important;
    /* 宿主的标签行宽度是满宽、默认 content-box，加 padding 会把它顶到
       x=8..368（右缘越过 header 右缘 360 共 8px，header.scrollWidth-clientWidth=8），
       也就是下面那条 118px 预留里有 8px 落在屏外。补 border-box 把它收回来，
       预留才是"整整 118px"。 */
    box-sizing: border-box !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]):has([class*="QsffPG_root"]) [role="tablist"] {
    padding-right: 118px !important;
  }
   /* Agent Team chip（VoX2oq_root，data-team-action）被 rc 代 pin 规则钉死
      （flex 0 0 auto + order 2，实测 98.7px），动作行里唯一可缩的模式 chip
      被压到 56.2px（390px 实测「创造模式」只剩「创造…」）。模式 chip 是
      手机端唯一的模式切换入口（pitfalls ⑤：必须保字），团队 chip 的完整
      文字在自己的面板里有承载（点开即达），所以让它先让：保持 order:2
      不变（创造在前、团队在后的次序不能翻），只把不可缩改成可缩，并加
      44px 收缩下限（数值可调）保住图标点击区；内部省略号窗口由 rc 代的
      > button / > button > * 规则继续供给。特异性 (0,5,1) 高于 pin 规则
      (0,4,1)，且 !important，不依赖书写顺序；:has 门控保证 rc 宿主不命中。 */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [data-team-action][class*="_root"] {
    flex: 0 1 auto !important;
    min-width: 44px !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_headerActions"] [class*="QsffPG_root"] {
    position: absolute !important;
    right: 8px !important;
    /* 和子代理 chip 同一套：贴 header 底边 + 下内边距 9px = 与标签文字齐平。 */
    bottom: 0 !important;
    height: 25px !important;
    min-height: 25px !important;
    /* 必须显式 flex：宿主 .QsffPG_root 只声明了 position:relative，是 block 容器，
       下面那条 align-items 在 block 上完全无效 —— 里面的 inline-flex 按钮会按基线
       落位，实测低 6.8px、内容挂出 header 下沿（69.5 -> 75.8），和第 11 条那类
       "chip 与标签行不齐平"是同一毛病。谱系 chip 的 .ZKlsPq_root 本身就是
       inline-flex，所以只有 jobs 这个 root 需要补。 */
    display: flex !important;
    align-items: stretch !important;
    z-index: 3 !important;
    margin: 0 !important;
    min-width: 0 !important;
    max-width: 118px !important;
    flex: 0 0 auto !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="QsffPG_root"] > button {
    height: 25px !important;
    min-height: 25px !important;
    padding: 0 2px 9px !important;
    line-height: 16px !important;
    align-items: center !important;
  }
  /* 头部弹层定位（jobs 任务列表 / subagent 谱系 / 预设菜单都会命中的同一族）：
     插件老规则是「弹层左缘 = chip 左缘 + 8px」，那条规则成立的年代 chip 都
     贴着 header 左缘；现在标题窗口 72px + 子代理 chip + 预设都靠中右，336px
     宽的面板会被整体推到视口外 —— 点开就像没反应。
     统一改成视口定位：贴在 header 下方、左右各留 8px 满宽展开；顺带脱离
     headerActions 的 overflow 裁剪（绝对定位的面板会被那个 auto 裁掉）。 */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_menu"]:not([class*="_menuAnchor"]) {
    position: fixed !important;
    left: 8px !important;
    right: 8px !important;
    top: calc(env(safe-area-inset-top, 0px) + 80px) !important;
    bottom: auto !important;
    width: auto !important;
    max-width: none !important;
    max-height: calc(100dvh - 96px) !important;
  }
  /* 官方 agent-team 插件（@deepseek-ai/dsh-experimental-client-ui-agent-team）的
     TeamAction 弹层：根元素 data-team-action（VoX2oq_root，挂在 headerActions 槽
     order 20），面板 VoX2oq_panel 是 absolute 弹层。它和上面 _menu 族栽在同一个
     裁剪问题上 —— _headerActions 的 overflow 滚动盒把它整个裁掉（实测 390/360px
     视口均不可见、关闭键落在视口外），但类名不含 _menu，上面那条规则救不到，
     所以这里同款视口定位脱离裁剪。哈希前缀 VoX2oq_ 跨版本会变，按仓库约定用
     _panel 子串匹配；不会误伤其他弹层 —— data-team-action 根标记只有 agent-team
     插件在用，特异性 (0,5,1) 也高于 _menu 族的 (0,4,1)。代际上整条已由外层
     header:has([class*="_headerLeading"]) 门控，pre-alpha.2 宿主不命中。 */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [data-team-action] [class*="_panel"] {
    position: fixed !important;
    left: 8px !important;
    right: 8px !important;
    top: calc(env(safe-area-inset-top, 0px) + 80px) !important;
    bottom: auto !important;
    width: auto !important;
    max-width: none !important;
    /* 底部让位 composer 区：会话页 composer 卡顶缘实测 y=738、stats line
       到 840（844 视口，底部区共 106px）——原 max-height 100dvh-96px 让
       面板伸到 y=828，底部 90px 的任务列表被输入框盖住（2026-09-19 用户
       报障）。120px = composer 区 106px + 14px 呼吸间距；键盘弹出时 dvh
       收缩，面板随之再缩。 */
    max-height: calc(100dvh - 200px) !important;
    /* 面板虽被拖出头部渲染点，white-space 仍继承 0.1.6 头部的 nowrap
       （头部整行防换行是既有决策）——手机 374px 宽 + 长任务标题时内容
       单行撑出面板（实测 scrollWidth 541 / clientWidth 374，任务状态
       徽标被推到面板外 x=496 处）。恢复面板内正常换行。 */
    white-space: normal !important;
  }
  /* 子代理谱系 chip（ZKlsPq_root）：0.1.6 把它渲染在标题面包屑内部。进子代理
     会话时面包屑变成「父会话 / 当前会话」两段 + 这个 chip，动作行就叠在一起，
     所以整块搬到「对话/轨迹」这一行的空白区里居中，并与标签文字纵向对齐。 */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="ZKlsPq_root"] {
    position: absolute !important;
    /* 在「标签右侧的空白区」里居中（左边界让开对话/轨迹，约 104px），
       比整行居中往右一些。 */
    left: 104px !important;
    right: 8px !important;
    /* 纵向对齐标签：直接镜像标签的盒模型 —— 标签是「16px 行高 + 9px 下内边距」，
       总高 25px 且贴着 header 底边。chip 也做成 25px 高、bottom:0、下内边距 9px，
       内容区正好落在同一段 16px 里，文字必然与「对话/轨迹」齐平。 */
    bottom: 0 !important;
    height: 25px !important;
    min-height: 25px !important;
    align-items: stretch !important;
    z-index: 3 !important;
    margin: 0 auto !important;
    width: max-content !important;
    min-width: 0 !important;
    max-width: min(32vw, 116px) !important;
    flex: 0 0 auto !important;
  }
  /* 后台任务 chip 也在标签行时，聚合 chip 往左让出它那一格，仍保持居中。
     :not(_switcherRoot)：switcher 变体不参与让位——它由下面的专属定位规则
     右锚 right:8，若被本族 right:126 拖走，179.4 宽会横穿 tab 带（取证
     实测 84.6..264 盖住轨迹/记忆两 tab；headless 中任务已结束但
     QsffPG_root 仍在 DOM，:has 命中幽灵元素）；聚合态不受影响，让位语义
     原样保留。 */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]):has([class*="QsffPG_root"]) [class*="ZKlsPq_root"]:not([class*="_switcherRoot"]) {
    right: 126px !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="ZKlsPq_root"] > button {
    height: 25px !important;
    min-height: 25px !important;
    line-height: 16px !important;
    padding: 0 4px 9px !important;
    align-items: center !important;
  }
  /* 已知边界：标签行出现第三个标签时，标签总宽约
     252px，已经越过子代理 chip 居中区的左边界（104px），两者会叠在一起。
     这里用 :has() 按标签数量切换策略 —— ≥3 个标签时不再居中，改成停靠在标签行
     右侧的空白区（右缘 8px；有后台任务 chip 时让到 126px）。标签行本身可横向
     滑动，chip 不会被挤到下面，也不再盖住第三个标签：
       chip 占 268~352（宽 84），标签止于 8+252=260，右侧余量 8px。
     两个变体并列，兼容「tab 是 tablist 直接子按钮」与「tab 被容器包裹」两种渲染；
     两条变体均 (0,5,2)（带 QsffPG 的二次覆盖规则为 (0,6,2)），高于上面两条既有规则，
     不依赖书写顺序。 */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]):has([role="tablist"] button:nth-of-type(3)) [class*="ZKlsPq_root"]:not([class*="_switcherRoot"]),
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]):has([role="tablist"] > button:nth-child(3)) [class*="ZKlsPq_root"]:not([class*="_switcherRoot"]) {
    left: auto !important;
    right: 8px !important;
    margin: 0 !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]):has([role="tablist"] button:nth-of-type(3)):has([class*="QsffPG_root"]) [class*="ZKlsPq_root"]:not([class*="_switcherRoot"]),
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]):has([role="tablist"] > button:nth-child(3)):has([class*="QsffPG_root"]) [class*="ZKlsPq_root"]:not([class*="_switcherRoot"]) {
    right: 126px !important;
  }
  /* 真机反馈：「标题下面多了一条灰色滑条」。第 4/5 条为了让长标题能左右拖着看，
     把面包屑做成了横向滚动容器 —— 实测 button.wSkVaW_crumb: overflow-x:auto、
     scrollWidth − clientWidth = 88；像素实测那条灰条是 x=40.0~89.5、高 7.8、
     拇指宽 ≈50 的圆角滚动条（100×100/188 ≈ 53，吻合）。
     本机 WebView 不认 scrollbar-width（CSS.supports 为 false），只有
     ::-webkit-scrollbar 生效；而且滚动条是「经典占位式」的 8px（合成容器实测
     offsetHeight − clientHeight = 8）。所以这里对整个会话头部统一掐掉滚动条：
     滑动能力保留，视觉上不再多一条。头部里任何位置的滚动条在 360px 宽的手机上
     都不是想要的，故不再按具体类名收窄范围。 */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]),
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) * {
    scrollbar-width: none !important;
    -ms-overflow-style: none !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"])::-webkit-scrollbar,
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) *::-webkit-scrollbar {
    display: none !important;
    width: 0 !important;
    height: 0 !important;
  }
  /* 单子代理运行态的 switcher 变体（宿主 SubagentHeaderLineage variant=
     "switcher"，根类是「基类 + 修饰类」双类 ZKlsPq_root ZKlsPq_switcherRoot，
     挂在 crumbs 内同一 lineage 槽位，a2 bundle line 615 实锤）：pin 规则按
     设计排除 _switcherRoot（切换器必须保持可缩），于是它从我方链里继承了
     零溢出约束——宿主 trigger 上限 max-width:244px 大于我方根帽 116px，
     根又没有 overflow，trigger 连同标题从右锚定的根左缘向右画出最多
     128px：真机 390 上文字冲到 ≈389、越过条带右缘 374，省略号点也在视口
     外，看起来像「无省略号」。聚合态「N 个子代理」类表不含 _switcherRoot，
     不被本条命中（结构锚区分，文本无关）。修法：根帽提到 min(46vw,180px)
     （数值可调，给运行中标题比计数 chip 更多余地）+ 根 overflow 收口 +
     trigger max-width:100%，让宿主自带的 title 省略号链（flex:1 +
     min-width:0 + ellipsis）在根内收口；svg 宿主自带 flex:none，⋮⋮/箭头
     图标与省略号共存；菜单是 position:fixed，不受根 overflow 裁剪，点击
     不受损。特异性与上面 ZKlsPq_root 规则同类同权 (0,4,1)，靠书写在后接管
     switcher 变体；h8S2Va 旧代是否有同名修饰类未取证，a2 (ZKlsPq_) 已实测
     对号。 */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_switcherRoot"] {
    max-width: min(46vw, 180px) !important;
    overflow: hidden !important;
  }
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="_switcherRoot"] > button {
    max-width: 100% !important;
    min-width: 0 !important;
  }
  /* switcher 定位（用户拍板 2026-09-19：右靠 + 与 tab 基线对齐）：基础规则
     把 switcher 与聚合 chip 一起居中/让位，取证实测 179.4 宽被推到
     84.6..264，整个压进 tab 带（tab 按钮 y 44-76、中心 y=60）盖住轨迹/
     记忆两 tab。本条用双类结构锚（聚合态类表无 _switcherRoot，零误伤）
     把 switcher 拉回右缘 8px 惯例位；top:48 使 25 高中心 60.5 ≈ tab 中心
     60，完成基线对齐——基础规则的 bottom:0 因 top+height+bottom 全非 auto
     过约束，按 spec 忽略 bottom、top 执政，行为确定。right:8 能落地靠上
     一条 yield 规则的 :not(_switcherRoot)（否则幽灵 QsffPG 在场时
     right:126 特异性更高会把 right:8 压掉，实测右缘 264 即此因）。
     360 真机推演：右锚后左缘 360−8−180=172 > tabs 端 ~126，46px 空隙，
     与 QsffPG 同场时本条让位取消后二者同靠右——QsffPG 真在场时由
     findHorizontalScroller/后续实测定去留（数值 48/8/180 均可调）。菜单
     position:fixed 独立定位层，不受本条影响（取证已证）。 */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="ZKlsPq_root"][class*="_switcherRoot"] {
    left: auto !important;
    right: 8px !important;
    top: 48px !important;
  }
  /* 聚合 chip 右锚（用户拍板 2026-09-19：中置的子代理元素应右靠）：与
     switcher 同款右靠 + 基线（top:48 → 25 高中心 60.5 ≈ tab 中心 60；
     bottom:0 过约束被忽略、top 执政）。聚合 ~97 宽右锚后 285..382，无
     QsffPG 时零碰撞（tabs 端 ≤170）。:not(_switcherRoot) 把变体让给上面
     switcher 专属规则，二者匹配集不相交、无 order-tie。共场（QsffPG 在
     场）由既有 QsffPG yield 族接管（right:126 → 聚合 166.6..264）：126
     沿用 yield 族既有几何——按旧代 84 宽 chip（268..352）定的安全距，
     同时覆盖用户实测 31 窄态（349-380）；示例值 right:44 只够窄态、84 宽
     态会叠，不采纳。yield 的幽灵副作用（任务结束后聚合停在 264）无
     tab/QsffPG 重叠，属无害惰性，彻底解（JS 可见性标记）留 effects
     车道。聚合 max-width min(32vw,116) 沿用基础规则不动；数值 48/8 可
     调。 */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="ZKlsPq_root"]:not([class*="_switcherRoot"]) {
    left: auto !important;
    right: 8px !important;
    top: 48px !important;
  }
  /* 谱系 chip 里的文字（子代理标题 /「N 个子代理」）给一个规矩的省略号窗口：
     不要裁成半个字，也不要靠滚动去够剩下的字。 */
   /* :not([class*="_separator"])：rc 代有 (0,4,1) !important 的
      [class*="_crumbs"] [class*="_separator"] display:none 规则，专门隐藏
      谱系计数前的「/」（小屏上它读起来像多出一层面包屑层级）；本条原来
      同为 (0,4,1) !important 且书写在后，同特异性后到先得把 separator
      顶回 display:block（实测 390px separator [269.1,·,5.5,25] 实绘可见）。
      加 :not 把 separator 从本条管辖范围摘掉，隐藏权交还 rc 代那条。 */
  [data-mobile-nav="frame"] [data-phase] header:has([class*="_headerLeading"]) [class*="ZKlsPq_root"] span:not([class*="_separator"]) {
    display: block !important;
    overflow: hidden !important;
    text-overflow: ellipsis !important;
    white-space: nowrap !important;
    min-width: 0 !important;
    max-width: 100% !important;
  }
  /* composer 模型选择 chip（dsh-client-ui-model-selection，样式哈希
     _7KE1Ra_）。2026-09-23 改档：真·手机档一律**只留图标**
     （IconDataOutlineRegular），模型名与 effort 不再常驻 —— 官方新加的语音
     按钮吃掉宽度后「图标+名字」把动作行挤爆；点图标打开菜单后再选模型。

     上游 0.1.7 已把这个能力做成契约：conversation 的 observeControlRow()
     实测 row 装不下时才打 data-model-compact，其 CSS
     「.uV2eYG_row[data-model-compact]」向下传
       --dsh-composer-model-text-display: none;
       --dsh-composer-model-icon-display: block;
     由 model-selection 消费：triggerIcon display:var(…icon…,none)、
     triggerLabel/triggerEffort display:var(…text…,block)。
     **旧规则 [class*="_7KE1Ra_triggerLabel"]{display:inline !important}
     正是把它顶掉的那一条** —— 图标被 compact 显形、名字又被我们拉回来，
     所以现场是"图标和名字同时出现"，也就是"上游代码里有、却不生效"。
     这里删掉它，并在 ≤767px 直接钉住这两个变量，不去赌宿主的实测结果
     （否则一旦某项变窄让 row 装得下，文字又会长回来，来回抖）。

     _7KE1Ra_ 是本代 model-selection 的样式哈希，包不在则整条死规则，无需
     另加代际门；必须带哈希前缀，裸 [class*=_triggerLabel] 会误伤
     permission-presets / settings-general 的同名片段。 */
  @media (max-width: 767px) {
    [data-mobile-nav="frame"] [data-phase] [class*="_card"]:has(textarea, [data-composer-input]) [class*="_row"]:has([class*="_trailing"]) {
      --dsh-composer-model-text-display: none;
      --dsh-composer-model-icon-display: block;
    }
  }
  /* 模型 chip 宽度预算（只对仍显示文字的 768–1023 平板档有意义）：宿主
     trigger 的 max-width min(360px,45cqw) 在窄容器下只给
     label+icon+effort+chevron 留 ~160px，模型名会省略成「GLM-5.3-Fla…」
     （headless 字体窄恰好放得下，同一盲区）。放宽到 60cqw，effort 有宿主
     自带 flex-shrink:1000 先让位。手机档文字已隐藏，这条不参与。特异性
     (0,3,0)+!important 胜宿主 (0,1,0) 普通声明；60 数值可调。 */
  [data-mobile-nav="frame"] [data-phase] [class*="_7KE1Ra_trigger"] {
    max-width: min(360px, 60cqw) !important;
    /* 图标化后宿主那套 padding:0 4px 0 8px 纯属浪费（左 8px 是给文字留的）。
       2026-09-23 店主第二轮："范围有点大、⌄ 离图标远" ⇒ padding 归零、gap 归零，
       匣子只剩「图标 + ⌄」本身（实测墨迹间距 10px → ~4px，匣宽 46 → ~32px）。 */
    padding: 0 !important;
    gap: 0 !important;
  }
  /* ⌄ 的 svg 自身带内边距（墨迹比 viewBox 窄），再拉近 2px。 */
  [data-mobile-nav="frame"] [data-phase] [class*="_7KE1Ra_chevron"] {
    /* gap 归零后两个 svg 的内边距会让墨迹直接贴住（实测墨迹连成一段），
       这里不再加负 margin，留 ~2px 呼吸 —— 间距从 10px 收到 2px。 */
    margin-left: 0 !important;
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
     group cards (Kwoi6G_header / Jh0q7G_header / rUBhvW_header; the bpnj3G_/jmhvDG_
     siblings were renamed upstream in dsh-web-all 0.3.20, verified 2026-09-18), all sharing the upstream template text-align:left,
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
  /* 0.1.6-alpha.2 宿主的插件管理页（dsh-client-ui-plugin-manager 渲染的
     section[data-plugin-panel]）。FAB 是全站恒定的左上角控件（用户明确
     要求：右下角不对，就放左上角），所以规则不做的是挪 FAB，做的是给
     面板自己的左上角内容让位。两个页面都要让，偏移量同一个：
     - 列表页：**有**内容在左上角 —— H1「插件」实测 [24,28,91,28]，与 FAB 盒
       [10,12,38,38] 重叠 24×22；点标题左缘命中的是 FAB 而不是标题
       （2026-09-19 报障）。上一版这里记的是「热区为空、零规则」，实测不成立。
     - 详情页（DetailTop 组件）：宿主返回键「返回插件列表」crumb，文字实测
       [24,28,70,19]，同样压在 FAB 左半——点它会触发开抽屉而不是返回。
       详情根的 data-* 标记有三种：内置插件详情 data-plugin-item-detail、市场
       插件详情 data-plugin-row-detail、builtin 详情 data-plugin-detail
       （实测「智能体团队」卡走的就是第三种），三条选择器并列全覆盖。
     让位量 = FAB 右缘（10 + 38 = 48）+ 8px 间距 = 56px，**写成相对量**：
     减掉宿主自己的 padding（clamp(24px,4vw,48px)），这样它跟着视口走，而不是
     把 390px 上量到的 32px 钉死（768px 平板上宿主 padding 是 30.7px，钉死的
     32px 会过量）。在 390px 上它算出来正好还是 32px，与上一版行为一致。
     锚点全部是宿主 data-* 标记，比 css-module 哈希类（X_2TxG_）稳定；
     pre-alpha.2 宿主没有这些标记，规则天然不命中（代际门控）。 */
  [data-mobile-nav="frame"] section[data-plugin-panel] {
    --dsh-web-mobile-panel-clearance: calc(56px - clamp(24px, 4vw, 48px));
  }
  /* 页首是宿主滚动盒的直接子元素，宿主给它 width:100%（.X_2TxG_page>*）。
     这种盒子上用 margin 会把整行顶出右缘、给面板加出一条横向滚动条，所以
     这里用 margin + 等量收窄：margin 盒仍是 100%，左缘让开 FAB，右缘不动
     （工具栏「添加插件」保持贴右）。 */
  [data-mobile-nav="frame"] section[data-plugin-panel] [class*="_pageHead"] {
    margin-left: var(--dsh-web-mobile-panel-clearance) !important;
    width: calc(100% - var(--dsh-web-mobile-panel-clearance)) !important;
  }
  /* 详情 crumb 是被拉伸的 flex item（没有 width:100%），margin 就是对的工具。 */
  [data-mobile-nav="frame"] section[data-plugin-panel] [data-plugin-detail] > button:first-child,
  [data-mobile-nav="frame"] section[data-plugin-panel] [data-plugin-item-detail] > button:first-child,
  [data-mobile-nav="frame"] section[data-plugin-panel] [data-plugin-row-detail] > button:first-child {
    margin-left: var(--dsh-web-mobile-panel-clearance) !important;
  }
  /* ---------- sidebar panel enter / exit (see effects/panel-exit.ts) ----------
     A sidebar panel REPLACES the main area. Two motions, both short and
     horizontal, matching the drawer's own rail-in (.15s, translate + fade):
       · enter — the panel slides in from the right;
       · exit  — the panel does NOT animate out; the conversation it hands the
         main area back to fades in instead.
     The asymmetry is deliberate. selectPanel(null) remounts the whole
     conversation and that commit blocks the main thread long enough to matter
     (measured on a phone: ~390 ms for a long session), so fading the panel out
     first would leave the screen blank for that whole window — panel already
     transparent, conversation not mounted yet. Keeping the panel opaque until
     the commit means the two swap on one frame.
     The enter rule is a CSS condition on purpose: :has() matches in the same
     commit that swaps the main slot, so the animation is already running at the
     element's first style resolution and there is no full-opacity frame first.
     The exit marker is set by JS before the swap for the same reason. */
  @keyframes dsh-web-mobile-panel-in {
    from { opacity: 0; transform: translateX(16px); }
  }
  /* Deliberately NOT reusing dsh-web-mobile-fade: the exit cleanup listens on
     animationend BY NAME, and that keyframe also runs on the backdrop and the
     dialogs, which are frame descendants too — reusing it would end the
     transition early. */
  @keyframes dsh-web-mobile-panel-reveal {
    from { opacity: 0; }
  }
  [data-mobile-nav="frame"]:has([class*="panelRow"][aria-current="page"]) [class*="_centerCol"] > * > * {
    animation: dsh-web-mobile-panel-in .15s var(--ds-ease-in-out, ease-in-out) backwards;
  }
  [data-mobile-nav="frame"][data-mobile-panel-exit]:not(:has([class*="panelRow"][aria-current="page"])) [class*="_centerCol"] > * > * {
    /* ease-out rather than the shared in-out curve: the panel vanishes and the
       conversation appears on the same frame, so the fade has to come up fast
       or the first frames read as a flash of empty background. */
    animation: dsh-web-mobile-panel-reveal .15s cubic-bezier(0, 0, .2, 1) backwards;
  }
  @media (prefers-reduced-motion: reduce) {
    [data-mobile-nav="frame"]:has([class*="panelRow"][aria-current="page"]) [class*="_centerCol"] > * > *,
    [data-mobile-nav="frame"][data-mobile-panel-exit]:not(:has([class*="panelRow"][aria-current="page"])) [class*="_centerCol"] > * > * {
      animation: none !important;
    }
  }

  /* ---------- DSHA 集成层：预设 chip 布局（宿主 @deepseek-ai/dsh-client-ui-agent-preset
     的 DSHA 补丁标记 .dsha-preset-header-anchor / [data-dsha-agent-preset]）。
     按 CSS 宽分两档，自动切换：
     ① 结构修正：图标与下拉箭头都是 position:absolute; left:0，会双双叠在
        「标准模式」文字上。这是宿主 DOM 决定的 bug，**任何手机档都要修** ——
        否则换到 768–1023 的平板/折叠屏就复现同一处叠字。
     ② 按 360px 实测钉出来的调优值（left 归零、团队 chip 在场时预设名 4 字上限）：
        只在「真·手机」档（CSS 宽 ≤ 767px，对齐上游 768px 平板档边界）生效；
        768–1023 保留上游手机 UI 的排布，不套这台手机的魔数。
     非 DSHA 宿主上没有这些标记，整块天然不命中（死规则）。 ---------- */
  [data-mobile-nav="frame"] [data-phase] header .dsha-preset-header-anchor {
    order: 1;
    width: max-content;
    flex: 0 1 auto;
    min-width: 0;
    max-width: min(40vw, 130px);
    margin-left: auto;
  }
  [data-mobile-nav="frame"] [data-phase] header .dsha-preset-header-anchor [data-dsha-agent-preset="header"] {
    display: inline-flex !important;
    align-items: center;
    gap: 4px;
    width: 100%;
    max-width: 100%;
    height: 36px !important;
    min-height: 36px !important;
    padding: 0 6px;
    border: 0;
    background: transparent;
    font: inherit;
    font-size: 12px;
  }
  [data-mobile-nav="frame"] [data-phase] header .dsha-preset-header-anchor [data-dsha-agent-preset="header"] > svg {
    position: static !important;
    transform: none !important;
    flex: 0 0 auto;
  }
  [data-mobile-nav="frame"] [data-phase] header .dsha-preset-header-anchor [data-dsha-agent-preset="header"] > span {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  /* ② 真·手机档（CSS 宽 ≤ 767px）才生效的调优值。 */
  @media (max-width: 767px) and (pointer: coarse) {
    [data-mobile-nav="frame"] [data-phase] header .dsha-preset-header-anchor {
      /* 宿主 cubgiG_menuAnchor 带 left:-8px（原本是给弹层对位用的），
         手机上和标题窗口右缘叠 2px；这里把它拉回 0，整体右移 8px。 */
      left: 0 !important;
    }
    /* 智能体团队 Web 开启后头部多一个 Agent Team chip；预设名超过 4 个字就会
       把它挤掉（实测 6 个字时 Agent Team 被裁成「Agent Te」并压住文件按钮）。
       此时把预设名收成 4 个字 + 省略号 —— 完整名字在预设菜单里点开即达。 */
    [data-mobile-nav="frame"] [data-phase] header:has([data-team-action]) .dsha-preset-header-anchor [data-dsha-agent-preset="header"] > span {
      max-width: 4em;
    }
  }
  /* ---------- 会话行的 ⋯ 菜单在触屏常显（2026-09-22 交互契约） ----------
     宿主只在 :hover 和 menuOpen 时显示 _rowActions，而手机没有 hover。
     长按以前是触屏进这个菜单的唯一路径，现在长按改成「改会话名」（见
     phone-chrome.ts 的 requestRowRename → 标题 dblclick），所以把锚点常显，
     删除 / 归档 / 分叉 继续有触屏入口。行内布局不动：标题是 flex:1 +
     min-width:0，自己让位并省略；host 的 time / pinIndicator 保持原样。
     只作用于抽屉里的会话行，搜索行（searchResultRow）不受影响。 */
  [data-mobile-nav="frame"] [class*="sessionRow"] [class*="_rowActions"] {
    display: inline-flex !important;
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
     toggle is a descendant of this column, so it is hidden with the rest of
     the column — there is no separate drawer-open rule for it. */
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
    /* 手机端「文字在上、控件在下」之后，宿主给文字容器留的 padding-right:48px
       （桌面版给右侧控件让位用的）就成了纯溢出：真机实测 rowText 内容盒 315
       + 48 = 363 > 内容面板 339 ⇒ **整个设置内容区能横向拖 36px**
       （店主反馈："通用设置里怎么还可以往左滑"）。归零即根治。 */
    padding-right: 0 !important;
  }
  [aria-modal="true"] [class*="_section"] [class*="_row"]:not([class*="_rows"]):not([class*="_rowCard"]):not([class*="_rowHead"]):not([class*="_rowIdentity"]):not([class*="_rowActions"]) > :last-child {
    width: 100% !important;
    max-width: none !important;
  }
  /* 开关不是"整行控件"。宿主的 Switch 是 button[role=switch]，官方尺寸
     36×20（flex:0 0 auto）——上面那条 width:100% 把「开发者工具」那行的开关
     拉成横贯整行的长条（店主 2026-09-23 截图："设置里的开关变得好长好长"）。
     钉回原尺寸、靠左对齐（行已是 column + stretch，固定宽不会被拉伸）。
     只命中 role=switch：其他"整行控件"（输入框 / 下拉 / 分段）保持原样。 */
  [aria-modal="true"] [class*="_section"] [class*="_row"]:not([class*="_rows"]):not([class*="_rowCard"]):not([class*="_rowHead"]):not([class*="_rowIdentity"]):not([class*="_rowActions"]) > button[role="switch"] {
    width: 36px !important;
    min-width: 36px !important;
    max-width: 36px !important;
    height: 20px !important;
    flex: 0 0 auto !important;
    align-self: flex-start !important;
  }
  /* Models provider editor: a CLOSED <details> ("_customized", the customized
     models section) must not paint its body. This engine paints the ~1500px
     model catalog of the closed details as a ghost layer anyway: it overlays
     the editor's own action rows (Fetch/Cancel/Apply/Add model) and the
     provider rows BEFORE the editor row in DOM order (those paint under the
     ghost and lose hit-testing), while rows after it paint above. Result
     (owner report 2026-09-19): providers cannot be deleted, "fetch available
     models" does nothing — every tap lands on whatever row overlaps the
     ghost. The host layout is computed for the collapsed details (editor
     217px, rows 903px), so the fix is to restore what the browser should do
     on its own: hide the body while the details is closed. Tapping the
     summary then opens it for real (details 33 → 1532px, rows re-flow,
     every button hittable — verified in place before this rule was written). */
  [aria-modal="true"] details[class*="_customized"]:not([open]) > [class*="_customizedBody"] {
    display: none !important;
  }
  /* Owner dialog footers (_w1urq family: the provider delete confirm, the
     workspace rename dialog, ...): the footer buttons keep white-space
     normal, so any width squeeze — a narrow viewport, a long provider name,
     Android font scaling (owner report 2026-09-19, verified at 320px with a
     1.3x font bump) — wraps the label inside the fixed 36px row where the
     second line clips. Keep each label on one line and let the footer wrap
     whole buttons to a second row instead. */
  [role="dialog"][aria-modal="true"] [class*="_footer"] {
    flex-wrap: wrap !important;
  }
  [role="dialog"][aria-modal="true"] [class*="_footer"] button[class*="_button"] {
    white-space: nowrap !important;
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
     The single injected footer action (the session-log download) becomes a
     full-width pill instead of a text-width capsule. */

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
     between metric groups, a 2px scrollbar as the swipe affordance.

     2026-09-23 改档（店主："把那个滑动的压缩一下，固定住，不再滑动"）：
     真机探针实测 可见宽 251px、内容 390px（"10 轮 268 步·244 tok/s" 178 +
     "57.5M tok·缓存命中 99%" 187，gap 12、font 12），右边 ~75px 被 dock 里的
     上下文百分比那块占着 ⇒ 一行本来就放不下。按店主选择：**保持一行 + 末尾
     省略号**。做法：字号 12→10（≈0.83×）、组间距 12→6、去掉为滚动条留的
     4px 下内边距；overflow 改 hidden（不可滑）、滚动条显式干掉；第一组
     flex:0 0 auto 保持完整，最后一组 flex:0 1 auto + min-width:0 自己吃掉
     差额并在末尾出省略号（实测截到"…缓存命…"，tok 数字仍完整可读）。
     高度仍是 28px：composer 的底部占位（8px + 28px）不变，其它几何不跟着动。 */

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
    overflow: hidden !important;
    overscroll-behavior-x: none;
    scrollbar-width: none !important;
    padding: 0 !important;
    line-height: 18px !important;
    font-size: 10px !important;
  }
  [data-mobile-nav="stats"]::-webkit-scrollbar {
    display: none !important;
    width: 0 !important;
    height: 0 !important;
  }
  [data-mobile-nav="stats"] > * {
    display: flex !important;
    flex-flow: row nowrap !important;
    align-items: center !important;
    white-space: nowrap !important;
    margin-right: 6px !important;
    padding: 0 !important;
  }
  /* 第一组（轮次·步数·tok/s）保持完整。 */
  [data-mobile-nav="stats"] > *:first-child {
    flex: 0 0 auto !important;
    width: max-content !important;
    min-width: max-content !important;
    max-width: none !important;
  }
  /* 最后一组（tok 总量·缓存命中）吃掉剩余宽度，末尾省略号。
     2026-09-23 第二版修正：第一版把整组改成 display:block + 子元素 inline，
     结果药丸里的图标变成 inline、基线对齐错位（店主："图标都出现位移"）。
     这版保持 flex 对齐，只让药丸**内部的文字 span** 收缩 + 出省略号；
     图标 svg 固定不缩。另外把两组药丸的左右内边距压到 6px、组间距压到 4px，
     抠出来的宽度全部让给第二组（实测它原本只分到 76px 而需要 156px）。 */
  [data-mobile-nav="stats"] > *:last-child {
    display: flex !important;
    align-items: center !important;
    flex: 0 1 auto !important;
    width: auto !important;
    min-width: 0 !important;
    max-width: none !important;
    overflow: hidden !important;
    margin-right: 0 !important;
  }
  [data-mobile-nav="stats"] > *:last-child > * {
    display: flex !important;
    align-items: center !important;
    min-width: 0 !important;
    max-width: 100% !important;
    overflow: hidden !important;
  }
  [data-mobile-nav="stats"] > *:last-child svg {
    flex: 0 0 auto !important;
  }
  [data-mobile-nav="stats"] > *:last-child span {
    flex: 0 1 auto !important;
    min-width: 0 !important;
    overflow: hidden !important;
    text-overflow: ellipsis !important;
  }
  [data-mobile-nav="stats"] button {
    padding: 0 3px !important;
    margin: 0 !important;
    min-width: 0 !important;
    max-width: 100% !important;
  }
  /* 药丸内部的 span/svg 有自己的字号（宿主 .pill 自带），只在外层设 10px 不会被
     继承进去 —— 真机上第二组仍差 ~20px 被省略号切掉，所以这里显式压到内部。 */
  [data-mobile-nav="stats"] button,
  [data-mobile-nav="stats"] button span,
  [data-mobile-nav="stats"] button svg,
  [data-mobile-nav="stats"] > * {
    font-size: 10px !important;
    line-height: 18px !important;
  }
  [data-mobile-nav="stats"] > *:not(:last-child) {
    margin-right: 3px !important;
  }
  [data-mobile-nav="stats"] * {
    white-space: nowrap !important;
  }
  /* 「上下文环」被 stats-line 效果挪进输入框行的右簇（店主 2026-09-23 确认：
     环要、百分比数字不要）。font-size:0 只塌掉文本、环 svg 有显式尺寸不受影响；
     它是右簇的固定成员，不参与压缩。 */
  [data-mobile-nav="stats-ring"] {
    flex: 0 0 auto !important;
    display: inline-flex !important;
    align-items: center !important;
    min-width: 0 !important;
    margin: 0 2px 0 0 !important;
    padding: 0 !important;
    background: transparent !important;
    box-shadow: none !important;
    border: 0 !important;
  }
  /* 文本要连**药丸内部**一起塌掉：药丸自带字号，只在外层设 0 不继承进去，
     真机上会留下半个 "46"。整棵子树 font-size:0，环 svg 用显式 px 不受影响。 */
  [data-mobile-nav="stats-ring"],
  [data-mobile-nav="stats-ring"] * {
    font-size: 0 !important;
  }
  [data-mobile-nav="stats-ring"] button {
    padding: 0 !important;
    margin: 0 !important;
    gap: 0 !important;
    min-width: 0 !important;
    width: auto !important;
    /* 宿主给药丸画的灰底/描边在输入框行里显得比环大一倍（店主："圆圈占了很多空间"），
       全去掉，只留环本身。 */
    background: transparent !important;
    box-shadow: none !important;
    border: 0 !important;
  }
  [data-mobile-nav="stats-ring"] svg {
    display: inline-block !important;
    width: 16px !important;
    height: 16px !important;
    flex: 0 0 auto !important;
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
  /* The one-line collapse above is deliberately NOT mirrored onto the Lexical
     generation (0.1.2+), even though it signals its empty state with the
     separate [data-composer-placeholder] node. That host pins the hero input
     itself — hero-scoped min-height: 52px, because its hero hint wraps to two
     lines — and a min-height floor beats an outer height: the 28px wrappers of
     2026-09-05 only shrank the scrollport under a 52px input, i.e. overflow-y
     auto with scrollHeight 52 against clientHeight 28 → scrollbar plus a
     clipped first input line and hint line (phone report 2026-09-14, probe
     scripts/probes/hero-composer-clip-probe.mjs). The textarea generation this
     collapse was written for has no such floor — its input is a transparent
     height:100% layer over the wrappers — so it still collapses there. */
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
      QuestionComposer.module.css). Raise them to 16px on iOS only, where the
      zoom can actually happen: on Android and desktop there is nothing to
      suppress, so they keep the compact size they were designed with
      (2026-09-16, audit D-1 option A; the iOS WebKit floor below covers these
      fields too, this rule keeps the requirement stated where it applies).
      Scoped to the ask
      composer's stable [data-question-key] root (AGENTS.md: scope hashed-class
      selectors to the owning region, prefer stable data-* markers); the
      class-name suffix match follows the plugin's established harness
      CSS-module convention (verified against the live app: generated names
      end with the original local name, e.g. uV2eYG_input / bhn1Oq_searchInput). */
  html[data-mobile-nav-ios] [data-question-key] [class*="_customInput"],
  html[data-mobile-nav-ios] [data-question-key] [class*="_customTextarea"] {
    font-size: 16px !important;
  }

  /* ---------- dsh-file-viewer inputs: kill iOS Safari auto-zoom ----------
     Same rule as the ask composer above: the file viewer's search / jump-to-
     line / pdf-page fields ship at 13-14px, which Safari auto-magnifies on
     focus inside a panel that does not blur on tap-away. Raise them to 16px
     on iOS only for the same reason as the ask composer above (2026-09-16,
     audit D-1 option A). Scoped to the frame marker; the
     viewer itself is scoped by its stable dsfv prefix.
     (Port of community fork fix 2ff7976.) */
  html[data-mobile-nav-ios] [data-mobile-nav="frame"] [class*="dsfv-search-input"],
  html[data-mobile-nav-ios] [data-mobile-nav="frame"] [class*="dsfv-jump-input"],
  html[data-mobile-nav-ios] [data-mobile-nav="frame"] [class*="dsfv-page-input"] {
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
  /* Centered, never edge-to-edge — for the modal shapes below, not for every
     modal dialog. Covered: modals that are not sheet-shaped, plus sheet-shaped
     ones with neither a navigation element nor a directory picker. A modal
     that is sheet-shaped AND carries the directory picker is left out on
     purpose — layout.css.ts holds the dedicated rule for it. The settings
     sheet has a higher-specificity full-width rule above, so repeat its
     selector here to win; the generic export/other-modal rule is covered by
     the second selector. */
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
   mobile shell on desktop).

   The session-delete trio (menu item + confirm/error dialog) is the ONE
   deliberate exception: its effect arms on TOUCH_QUERY (pointer: coarse at
   every width — large tablets in landscape), so it lives in the pointer-only
   block below instead of this width arm. */

@media (min-width: 1024px), (pointer: fine), (pointer: none) {
  [data-mobile-nav="toggle"],
  [data-mobile-nav="files"],
  [data-mobile-nav="file-upload"],
  [data-mobile-nav="fab"],
  [data-mobile-nav="backdrop"],
  [data-mobile-nav="session-log"],
  [data-mobile-nav="preview-full-toggle"],
  [data-mobile-nav="drawer-actions"] {
    display: none !important;
  }
}

/* Session-delete trio: hide on mouse-driven or pointer-less windows at ANY
   width. No width term — the injection is armed on touch at every width, so
   a width arm here would hide the item on wide touch (the device class the
   injection exists for). */
@media (pointer: fine), (pointer: none) {
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
const sessions_compat_ts_1 = require("./core/sessions-compat.js");
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
 * Install the mobile session-delete menu machinery. Touch-gated: the whole
 * effect arms under TOUCH_QUERY — (pointer: coarse) at EVERY width — so a
 * large tablet in landscape keeps the desktop layout but still gets the
 * delete item, while any mouse-driven or pointer-less window stays a
 * complete no-op. Returns a disposer (via installMobileEffect) that removes
 * every listener, observer, injected node, and the confirm dialog.
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
            const candidates = sessions.ids.filter((id) => {
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
            const owned = new Set(workspaces.items.flatMap((workspace) => workspace.sessionIds));
            const workspace = headerTitle === undefined
                ? undefined
                : workspaces.items.find((candidate) => candidate.title === headerTitle);
            const workspaceIds = workspace === undefined ? [] : workspace.sessionIds;
            const groupIds = workspace === undefined
                ? sessions.ids.filter((id) => !owned.has(id) && !archived.has(id) && sessions.byId[id] !== undefined)
                : workspaceIds.filter(id => !archived.has(id) && sessions.byId[id] !== undefined);
            const sameTitleGroupIds = groupIds.filter(id => sessions.byId[id]?.displayTitle === title);
            const rows = [...group.querySelectorAll(':scope > [class*="_sessionRow"]')];
            const rowIndex = rows.indexOf(row);
            const sameTitleBefore = rowIndex === -1
                ? 0
                : rows.slice(0, rowIndex).filter(candidate => candidate.querySelector('[class*="_title"]')?.textContent?.trim() === title).length;
            return sameTitleGroupIds[sameTitleBefore];
        };
        /**
         * Read one menu item's visible label across host generations: rc.2 nests
         * the text in an `_itemLabel` span (beside an `_itemIcon`), while 0.1.5
         * puts it directly in the button (`_item_1nxmc_92`, no child elements).
         * Falling back to the item's own textContent covers both — svg icons
         * contribute no text, so rc.2 items read identically either way.
         */
        const itemLabel = (item) => {
            const label = item.querySelector('[class*="_itemLabel"]');
            return (label ?? item).textContent?.trim() ?? '';
        };
        /** Whether a menu list is the host's per-session row menu. */
        const isSessionMenu = (menu) => {
            const labels = [...menu.querySelectorAll('[role="menuitem"]')]
                .map(itemLabel);
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
        /** Show the delete confirmation as a bottom card over the frame.
         *  Mounted on <body>, NOT in the frame: the third-party mobile shim
         *  (@linxin666/dsh-web-all) listens in the CAPTURE phase on the frame and,
         *  while the drawer is open, answers every click inside the frame but
         *  outside [data-pane="sidebar"] with preventDefault + stopPropagation.
         *  A card inside the frame therefore had dead buttons — measured
         *  2026-09-14: a real touch tap on 「取消」 left the card open, and only
         *  Escape closed it. Body-level, the shim's listener never sees these
         *  clicks (its sibling menus are portaled there for the same reason), and
         *  the card's own band lives in base.css (z 1400/1401, above the drawer). */
        const showDeleteDialog = (sessionId, title) => {
            closeDialog();
            const host = document.body;
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
                const wasCurrent = (0, sessions_compat_ts_1.currentSessionIdOf)(ctx.sessions.list.getSnapshot()) === sessionId;
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
                if (wasCurrent && (0, sessions_compat_ts_1.sessionsCanClear)(ctx.sessions))
                    ctx.sessions.clear();
                // Repull the baseline so the deleted row disappears. Must be called AS
                // A METHOD on ctx.sessions: refresh() reads `this.manager`, and an
                // extracted reference would throw "this is undefined" — the failure
                // mode that left deleted cold sessions lingering as ghost rows.
                const sessions = ctx.sessions;
                await sessions.refresh?.();
                // On the mobile branch the drawer hosts the list, so closing it is
                // the right follow-up after deleting the current session; on the
                // desktop layout (wide touch) the same call would collapse the
                // always-visible sidebar panel, so gate it on the mobile query.
                if (wasCurrent && window.matchMedia(phone_chrome_ts_1.MOBILE_QUERY).matches)
                    ctx.layout.toggleSidebar();
            });
            host.appendChild(backdrop);
            host.appendChild(card);
            dialogHost = { backdrop, card };
        };
        /** Show a non-destructive error card (session could not be resolved). */
        const showError = (message) => {
            closeDialog();
            const host = document.body;
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
            host.appendChild(backdrop);
            host.appendChild(card);
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
            else if (button.firstElementChild === null) {
                // 0.1.5 shape: the menuitem button carries its text directly (no
                // `_itemLabel` span, no icon element). Replace the whole text and let
                // the danger color ride the button itself. A button WITH element
                // children but no label span is an unknown future shape — leave its
                // text alone rather than guess.
                button.textContent = navT('deleteSession');
                button.style.color = DANGER_COLOR;
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
    }, phone_chrome_ts_1.TOUCH_QUERY);
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
        // 2026-09-23 扩档（店主报"点加号会弹键盘、而且再点关不掉"）：
        // `+` 的 onClick 是宿主的 onToggleCommandMenu，它先 focusDraftEditor()
        // 再 toggleCommandMenu(caretSpan) —— 命令菜单需要光标，于是每次点 + 都
        // 把键盘顶起来。键盘一开，整行上移 ~283px（真机探针：composer y 687→404），
        // 店主第二次点的是"加号原来的位置"，自然关不掉，看起来像 toggle 坏了。
        // 同一段 focusDraftEditor 在 iOS 上就是本守卫要拦的调用，所以把启用条件
        // 从"仅 iOS WebKit"放宽到"触屏档（pointer: coarse）"：桌面（精细指针）保持
        // 原样，手机/平板上一律不让 composer 按钮去抢编辑器焦点。拦截的是**程序
        // 化** focus()，原生点输入框聚焦不受影响（点输入框的路径已被下面的 early
        // return 排除）。
        const ios = (0, phone_chrome_ts_1.detectIosWebKit)(navigator, typeof CSS !== 'undefined' && typeof CSS.supports === 'function' ? CSS.supports.bind(CSS) : null);
        const coarse = typeof matchMedia === 'function' && matchMedia('(pointer: coarse)').matches;
        if (!ios && !coarse) {
            return undefined;
        }
        /** 影子撤除计时器（每次按钮点按重置；见 onPointerDown 里的时间轴注释）。 */
        let shadowTimer = 0;
        /**
         * 撤影子 **并且关掉守卫窗口**。
         *
         * 2026-09-23 二次修订（店主报"点两下加号之后输入框动不了了"）：
         * 原来这里只删影子、不归零 `shadowTimer`，而下面 `onFocusIn` 的开关就是
         * `shadowTimer === 0` —— 于是**点过一次 composer 按钮之后守卫永久生效**：
         * 任何 focusin 都被当场 `blur()`，店主点输入框再也弹不出键盘。
         * 真机行为级取证（探针：先合成一次 composer 按钮 pointerdown，等过 700ms 窗口，
         * 再 blur + focus 编辑器）：
         *   修前 `shadowAttrLeft=false ownFocusLeft=false blurWorked=true focusHeld=false k=754`
         *   ⇒ 影子已撤、计时器却还挂着 ⇒ 守卫一直在，编辑器拿不回焦点。
         */
        const restore = () => {
            window.clearTimeout(shadowTimer);
            shadowTimer = 0;
            const el = document.querySelector(`[${SHADOW_MARKER}]`);
            if (el === null)
                return;
            el.removeAttribute(SHADOW_MARKER);
            const shadowed = el;
            if (Object.prototype.hasOwnProperty.call(el, 'focus'))
                delete shadowed.focus;
        };
        const onPointerDown = (event) => {
            const target = event.target;
            if (!(target instanceof Element))
                return;
            if (typeof target.closest !== 'function')
                return;
            const card = target.closest(COMPOSER_CARD_SELECTOR);
            if (card === null)
                return;
            const editor = card.querySelector(COMPOSER_INPUT_SELECTOR);
            if (editor === null)
                return;
            // 店主自己点编辑面：这是"我要打字"的正路，立刻解除守卫窗口，
            // 绝不让兜底 blur 打到这一下（窗口内点输入框也必须能弹键盘）。
            if (target.closest(COMPOSER_INPUT_SELECTOR) !== null) {
                restore();
                return;
            }
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
            // 影子的存活窗口 = 700ms 固定窗口，**不能**"click 后立刻撤"。
            // 2026-09-23 真机探针的事件轨迹（点一次 `+`）：
            //   51.5 clicks:添加文件或调用指令 / shadow:ON
            //   51.6 shadow:off          ← 旧的"click 后 setTimeout(0) 撤"
            //   51.7 vv 754→471          ← 键盘此时才弹 ⇒ 宿主是在"菜单打开后的 effect"
            //                              里再 focus 一次，撤早了等于白装。
            // 影子只拦**程序化** focus()；窗口内用户点输入框由上面那个 early return
            // 当场解除窗口，所以放宽到 700ms 是安全的；窗口内新的按钮点按会重置计时。
            window.clearTimeout(shadowTimer);
            shadowTimer = window.setTimeout(restore, 700);
        };
        // 2026-09-23：只挂 mousedown 会空转。Android WebView 上按钮的点击经常吃不到
        // 兼容性 mousedown（touchstart 被 preventDefault 时更甚），于是影子从没装上，
        // 宿主 click 里的 focusDraftEditor 照样把键盘顶起来 —— 店主实测"两个问题都还在"。
        // 三个入口都挂上，处理体是幂等的（每次先 restore 再重装影子）。
        // 兜底：万一"按钮点按 → 编辑器被聚焦"仍然把键盘顶起来（真机可能走
        // 我们拦不到的路径），在同一个 700ms 窗口内立刻把焦点还回去 —— blur 会
        // 收起软键盘。宿主的光标/草稿来自它自己的 keyboard 状态，不依赖 DOM focus，
        // 所以这里 blur 不会丢草稿（用户随后点输入框照常输入）。
        const onFocusIn = (event) => {
            if (shadowTimer === 0)
                return;
            const target = event.target;
            if (!(target instanceof HTMLElement))
                return;
            if (target.closest(COMPOSER_INPUT_SELECTOR) === null)
                return;
            // 同步 blur：放到宏任务里 IME 已经开始弹了（真机实测 setTimeout 版无效，
            // vv 仍然 754→471）。在 focusin 的捕获阶段当场 blur，键盘根本不会出现。
            target.blur();
        };
        document.addEventListener('pointerdown', onPointerDown, true);
        document.addEventListener('touchstart', onPointerDown, true);
        document.addEventListener('mousedown', onPointerDown, true);
        document.addEventListener('focusin', onFocusIn, true);
        return () => {
            document.removeEventListener('pointerdown', onPointerDown, true);
            document.removeEventListener('touchstart', onPointerDown, true);
            document.removeEventListener('mousedown', onPointerDown, true);
            document.removeEventListener('focusin', onFocusIn, true);
            restore();
        };
    });
}
};
__modules["effects/composer-plus-toggle.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.installComposerPlusToggle = installComposerPlusToggle;
const phone_chrome_ts_1 = require("./effects/phone-chrome.js");
/**
 * 加号「再点关闭」的接管（2026-09-23 二轮修订，推翻上一轮的根因判断）。
 *
 * 症状：加号点开候选菜单后，**再点加号关不掉**（菜单原地不动，等于又开一次）。
 *
 * 真根因（读 0.1.7 产物得出，另有 `aria-expanded` 旁证）：
 * `dsh-client-ui-input-trigger` 的 `toggleSource()` 本来就会关 ——
 *
 *   toggleSource(source, hit) {
 *     if (this.launcher.getSnapshot() === source && this.menu.getSnapshot().open) {
 *       this.dismiss()            // ← 第二击本应走这里
 *       return
 *     }
 *     …打开…
 *   }
 *
 * 但加号的 onClick 先跑 `focusDraftEditor(editor, revealSelection)`，编辑器一有
 * update 就回调 `onEditorUpdate()` → `inputTriggers.track(...)`；而 `track()` 开头是
 *
 *   const launched = this.launcher.getSnapshot() !== null
 *   this.clearLauncher()                              // ← launcher 被清成 null
 *   const raw = detectTrigger(draft, caret, guard)
 *   if (raw === null) { if (launched) return; … }     // ← 菜单留着，launcher 却没了
 *
 * ⇒ 轮到 toggleSource 时 `launcher` 已是 null，"已开就关"这一支永远不可达，
 *   于是每一击都等于"再开一次"。
 * 旁证：`+` 的 `aria-expanded` = `useMenuLauncher(s => s === 'command')`，
 *   菜单明明开着它却恒为 false —— 正是 launcher 被 track 清掉后的直接读数。
 *
 * 上一轮的两个误判，一并纠正在这里：
 *   · `shell.dismissPopup()` 关的是 `commandUi.popupFor()` 的 **popupSelect 壳**
 *     （选完指令后的选项面板，此刻根本没开），跟这个菜单无关，删它不解决问题；
 *   · 这个菜单是 `input-trigger` 的 MenuView：根节点带 **`data-trigger-menu`**，
 *     `role="listbox"` 只是它内部那一层 viewport；它挂在 `[data-composer-card]`
 *     里面，MenuView 的 outside-pointerdown 还专门豁免了这张卡片
 *     （`listRef.closest('[data-composer-card]')`），所以点卡片内的加号根本不会
 *     触发它的关闭。而 popupSelect 壳的卡片**没有任何 role**，上一轮按 role 找它
 *     必然找不到（`probe_esc` 全程 `menu=none` 就是这么来的）。
 *
 * 修法（只补这一个缺口，宿主行为原样保留）：
 *   click **捕获**阶段记下"点加号之前菜单是不是开着"；
 *   click **冒泡**阶段（React 挂在 root 容器上的监听器早已跑完）若菜单**仍然**开着，
 *   说明宿主的关闭分支又被 launcher 清空吃掉了 —— 这时才补一刀：朝 Lexical 根
 *   （`[data-composer-input]`）发一次 Escape。
 *   Escape 是宿主自己的关闭路径（编辑器的 escape 命令 → `arbitrate('escape')`
 *   → `reduce({ close })`），而且只认挂在编辑器根上的 keydown —— 必须 dispatch 在
 *   编辑器上，不能像上一轮那样发在菜单元素上（那边事件根本到不了 Lexical）。
 * 菜单本来就关着时（第一击的开启路径）完全不介入。
 */
/** 宿主加号按钮的类名片段；模型/权限触发器是 `_trigger`，不会被误伤。 */
const ADD_SELECTOR = '[class*="_add"]';
/** slash/命令候选菜单的根：MenuView 自带这个标记，比样式哈希稳定。 */
const MENU_SELECTOR = '[data-trigger-menu]';
/** Lexical 的可编辑根：Escape 只在这里被宿主映射成命令。 */
const EDITOR_SELECTOR = '[data-composer-input]';
/** 收起后 React 会立刻摘掉节点，这里再兜一层"看得见才算开着"。 */
const isVisible = (el) => {
    const box = el.getBoundingClientRect();
    return box.width > 0 && box.height > 0 && el.getClientRects().length > 0;
};
const openMenu = () => {
    for (const el of document.querySelectorAll(MENU_SELECTOR))
        if (isVisible(el))
            return el;
    return null;
};
const editorEl = () => {
    const el = document.querySelector(EDITOR_SELECTOR);
    return el instanceof HTMLElement ? el : null;
};
/**
 * 命令菜单不需要软键盘。
 *
 * 真机机制（2026-09-23 三次取证）：点 `+` 之前编辑器往往**还"逻辑上"聚焦着**
 * （用户滚屏收起了键盘，DOM focus 没走），宿主的 `keepFocus` 又对按钮的 mousedown
 * 做了 `preventDefault()` ⇒ 这一下不会 blur，于是 Android 在真实手势里把刚收起的
 * IME 重新顶起来（探针：点前 `k754`，点后 ~170ms `k471`），整行上移 ~283 CSS px，
 * 店主第二下点的"加号原位"就落进软键盘了（页面收不到任何事件）。
 * 守卫那套影子只拦**程序化 focus()**，拦不到这条 IME 路径，所以必须主动收：
 * 按下加号的捕获阶段先把 DOM 焦点放掉，IME 就没有可依附的编辑面。
 */
const dropEditorFocus = () => {
    const editor = editorEl();
    if (editor !== null && document.activeElement === editor)
        editor.blur();
};
/** 走宿主自己的关闭路径。菜单没开时它一路 no-op（`arbitrate` 回 'pass'），可以放心重发。 */
const escapeEditor = () => {
    const editor = editorEl();
    if (editor === null)
        return;
    editor.dispatchEvent(new KeyboardEvent('keydown', {
        key: 'Escape',
        code: 'Escape',
        keyCode: 27,
        which: 27,
        bubbles: true,
        cancelable: true,
    }));
};
function installComposerPlusToggle(ctx) {
    (0, phone_chrome_ts_1.installMobileEffect)(ctx, 'dsh-web-mobile: composer plus toggle', () => {
        /** 这一击落下之前菜单开着吗（捕获阶段读，早于宿主 onClick）。 */
        let openBeforeClick = false;
        /** 菜单开着期间的兜底收键盘计时器；用户一点编辑面就全部取消。 */
        let collapseTimers = [];
        const cancelCollapse = () => {
            for (const id of collapseTimers)
                window.clearTimeout(id);
            collapseTimers = [];
        };
        const onClickCapture = (event) => {
            const target = event.target;
            openBeforeClick =
                target instanceof Element && target.closest(ADD_SELECTOR) !== null && openMenu() !== null;
        };
        const onPointerDown = (event) => {
            const target = event.target;
            if (!(target instanceof Element))
                return;
            if (target.closest(ADD_SELECTOR) === null)
                return;
            // 断掉 IME 的依附面（见 dropEditorFocus 的注释）。
            dropEditorFocus();
        };
        const onClickBubble = (event) => {
            const wasOpen = openBeforeClick;
            openBeforeClick = false;
            const target = event.target;
            if (!(target instanceof Element) || target.closest(ADD_SELECTOR) === null)
                return;
            // 宿主那套 focus → track(clearLauncher) → toggle 此刻已经跑完：
            // 菜单还开着 = 它的关闭分支又被吃了，由我们关；已经关掉就什么都不做。
            if (wasOpen && openMenu() !== null)
                escapeEditor();
            // 兜底：宿主可能在"菜单打开后的 effect"里再聚焦一次，把键盘重新顶起来。
            // 只在菜单开着时按，用户一碰编辑面就全撤（见 onEditorPointerDown）。
            cancelCollapse();
            for (const delay of [120, 320, 640]) {
                collapseTimers.push(window.setTimeout(() => {
                    if (openMenu() !== null)
                        dropEditorFocus();
                }, delay));
            }
        };
        /** 用户点了编辑面 = 要打字，任何兜底收键盘立刻作废（别和手指抢）。 */
        const onEditorPointerDown = (event) => {
            const target = event.target;
            if (!(target instanceof Element))
                return;
            if (target.closest(EDITOR_SELECTOR) === null)
                return;
            cancelCollapse();
        };
        document.addEventListener('pointerdown', onPointerDown, true);
        document.addEventListener('pointerdown', onEditorPointerDown, true);
        document.addEventListener('click', onClickCapture, true);
        document.addEventListener('click', onClickBubble, false);
        return () => {
            cancelCollapse();
            document.removeEventListener('pointerdown', onPointerDown, true);
            document.removeEventListener('pointerdown', onEditorPointerDown, true);
            document.removeEventListener('click', onClickCapture, true);
            document.removeEventListener('click', onClickBubble, false);
        };
    });
}
};
__modules["effects/model-menu-anchor.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.installModelMenuAnchor = installModelMenuAnchor;
const phone_chrome_ts_1 = require("./effects/phone-chrome.js");
/**
 * 模型 / 推理等级菜单的锚点修正（2026-09-23 店主："这个模型打开，是不是有点偏左边？"）。
 *
 * 真机取证（360×754）：
 *   MENU  box=12,605,246,74   class=_7KE1Ra_menu  role=menu  aria-label="模型与推理等级"
 *         style="left: 12px; top: 605px"  position:fixed  **parent=BODY**（portal 出去的）
 *   CARD  [data-composer-card] = 16..342      TRIG = 219..249, 687..715
 *
 * 宿主按「菜单右缘贴触发器右缘」定位 ⇒ 菜单落 12..258（店主第一眼："是不是有点偏左边？"）。
 * 本插件早先用 CSS 居中过它，但菜单 portal 到 body 之后那条 `_root > _menu` 子代链断掉，
 * 规则成了**死规则**。CSS 够不到 portal 节点，所以在这里用 JS 重锚。
 *
 * 落位（三轮定稿，2026-09-23）：**菜单在输入框里水平居中** —— 菜单中心 = 输入框中心。
 * 真机：卡片中心 179、菜单宽 246 ⇒ left 56（即 56..302，左右各留约 40px）。
 * 历史对照：宿主原样 12..258（偏左）／居中于触发器 106..352（触发器在右半边 ⇒ 偏右）。
 * 拿不到卡片时退回「居中于触发器 + 视口 GUTTER」。只认模型菜单的哈希锚点，其它菜单不碰。
 *
 * ## 成本（2026-09-23 优化，店主批准）
 *
 * 本机实测（18,359 节点）：`querySelectorAll('[class*="_7KE1Ra_menu"]')` = **0.568ms/次**、
 * `querySelector('[data-composer-card]')` = 0.08ms/次。旧版把这些查询挂在
 * `pointerdown`/`click`/`resize`/`scroll` 上 ⇒ **菜单关着时每次滚动也白花约 0.65ms/帧**
 * （模型流式输出时页面每帧都在滚，最吃这一口；60Hz 帧预算的 4%、120Hz 的 8%）。
 *
 * 现在分两条路径：
 *   · `refresh()` —— **唯一的查询入口**，只在"可能开关菜单"的交互后跑（点到/聚焦/按键在
 *     触发器或菜单上）。查到就把节点记进 `active`。
 *   · `follow()` —— 滚动/改变尺寸只对 `active` 重算位置（读两个 rect，约 0.02ms），
 *     `active === null` 时**直接返回、零查询**。
 *
 * 为什么不能干脆删掉 scroll 监听：实测滚动时输入框卡片会移动（同会话内 365 → 648），
 * 菜单开着时得跟着挪，否则会和卡片错位。
 *
 * 为什么不用 MutationObserver：会话在流式输出，`subtree` 观察等于每帧扫全场。
 */
/** 模型触发器（图标形态的 chip）。 */
const MODEL_TRIGGER = '[class*="_7KE1Ra_trigger"]';
/** 模型 / 推理等级菜单（portal 在 body 下）。 */
const MODEL_MENU = '[class*="_7KE1Ra_menu"]';
/** 输入框（composer 卡片）—— 菜单在它里面水平居中。 */
const COMPOSER_CARD = '[data-composer-card]';
/** 贴边留白。 */
const GUTTER = 8;
/** 宿主在打开动画/二次测量里会再写位置，补几次收尾（毫秒）。 */
const SETTLE_MS = [0, 60, 200];
function installModelMenuAnchor(ctx) {
    (0, phone_chrome_ts_1.installMobileEffect)(ctx, 'dsh-web-mobile: model menu anchor', () => {
        let raf = 0;
        const timers = [];
        /** 已确认「开着」的菜单节点；null 表示当前没有菜单（滚动路径据此零查询）。 */
        let active = null;
        const laidOut = (el) => {
            if (el === null)
                return null;
            const box = el.getBoundingClientRect();
            return box.width > 0 && box.height > 0 ? box : null;
        };
        /** 唯一的全文档查询入口（只在交互路径调用，见文件头「成本」）。 */
        const findMenu = () => {
            for (const el of document.querySelectorAll(MODEL_MENU)) {
                if (laidOut(el) !== null)
                    return el;
            }
            return null;
        };
        /** 把菜单水平居中在输入框里（拿不到卡片则居中于触发器）。只写 inline left。 */
        const place = (menu) => {
            const menuBox = laidOut(menu);
            if (menuBox === null)
                return;
            const width = menuBox.width;
            const viewport = document.documentElement.clientWidth;
            const max = Math.max(GUTTER, viewport - width - GUTTER);
            const card = document.querySelector(COMPOSER_CARD);
            const cardBox = card === null ? null : card.getBoundingClientRect();
            const trigger = cardBox !== null && cardBox.width > 0 ? null : document.querySelector(MODEL_TRIGGER);
            const triggerBox = trigger === null ? null : trigger.getBoundingClientRect();
            const center = cardBox !== null && cardBox.width > 0
                ? cardBox.left + cardBox.width / 2
                : triggerBox === null ? null : triggerBox.left + triggerBox.width / 2;
            if (center === null)
                return;
            const left = Math.min(Math.max(center - width / 2, GUTTER), max);
            const next = `${Math.round(left)}px`;
            // 只在真的不同时才写：避免和宿主来回抢同一帧。
            if (menu.style.left !== next)
                menu.style.left = next;
        };
        /** 交互路径：刷新缓存（会查询）并按新位置落位。 */
        const refresh = () => {
            active = findMenu();
            if (active !== null)
                place(active);
        };
        /** 滚动 / 改变尺寸路径：只用缓存节点重算，不查询。 */
        const follow = () => {
            if (active === null)
                return;
            if (laidOut(active) === null) {
                active = null;
                return;
            }
            place(active);
        };
        const schedule = (run) => {
            if (raf !== 0)
                return;
            raf = window.requestAnimationFrame(() => {
                raf = 0;
                run();
            });
        };
        /** 交互后补几次落位（宿主在打开动画/二次测量里还会再写一次）。 */
        const scheduleRefresh = () => {
            schedule(refresh);
            for (const delay of SETTLE_MS)
                timers.push(window.setTimeout(() => schedule(refresh), delay));
            // 计时器只留最近一轮，避免长会话里越积越多。
            while (timers.length > SETTLE_MS.length * 2) {
                const stale = timers.shift();
                if (stale !== undefined)
                    window.clearTimeout(stale);
            }
        };
        /** 只有"可能开关菜单"的交互才需要查询：命中触发器或菜单本身。 */
        const touchesMenu = (event) => {
            const target = event.target;
            if (!(target instanceof Element))
                return false;
            return target.closest(MODEL_TRIGGER) !== null || target.closest(MODEL_MENU) !== null;
        };
        const onPointerDown = (event) => {
            if (touchesMenu(event))
                scheduleRefresh();
        };
        // 键盘/无障碍路径（聚焦触发器后按 Enter）与合成 click 也要覆盖。
        const onKeyDown = (event) => {
            if (touchesMenu(event))
                scheduleRefresh();
        };
        const onFocusIn = (event) => {
            if (touchesMenu(event))
                scheduleRefresh();
        };
        const onClick = (event) => {
            if (touchesMenu(event))
                scheduleRefresh();
        };
        // 视口变化只走"零查询"的重算路径。
        const onViewportChange = () => {
            schedule(follow);
        };
        document.addEventListener('pointerdown', onPointerDown, true);
        document.addEventListener('keydown', onKeyDown, true);
        document.addEventListener('focusin', onFocusIn, true);
        document.addEventListener('click', onClick, true);
        window.addEventListener('resize', onViewportChange);
        document.addEventListener('scroll', onViewportChange, true);
        return () => {
            document.removeEventListener('pointerdown', onPointerDown, true);
            document.removeEventListener('keydown', onKeyDown, true);
            document.removeEventListener('focusin', onFocusIn, true);
            document.removeEventListener('click', onClick, true);
            window.removeEventListener('resize', onViewportChange);
            document.removeEventListener('scroll', onViewportChange, true);
            if (raf !== 0)
                window.cancelAnimationFrame(raf);
            for (const timer of timers)
                window.clearTimeout(timer);
            timers.length = 0;
            active = null;
        };
    });
}
};
__modules["core/layout-compat.js"] = function (require, module, exports) {
"use strict";
// The layout service face drifted between host generations. rc.6's ILayout
// carries only toggleSidebar/openDetails/closeDetails; 0.1.6-alpha.2 added
// selectPanel(panelId | null) for the sidebar's main-area panels. This helper
// lets call sites stay compile-green against rc.6 typings while degrading
// explicitly on a host that cannot select a panel, instead of throwing.
//
// Kept DOM-free and dependency-free (like sessions-compat.ts) so it is directly
// unit-testable and compiles against either generation's typings — never import
// the layout service types here.
Object.defineProperty(exports, "__esModule", { value: true });
exports.panelSelectorOf = panelSelectorOf;
/**
 * The "leave the panel" action, or null when the host has no panel-selection
 * API at all (rc.6).
 *
 * The sidebar panel list is an alpha.2-era surface, so on rc.6 nothing arms
 * this — but the capability is probed rather than assumed, because a plugin
 * that throws on an older host is worse than one that goes inert.
 *
 * @param layout - `ctx.layout` as handed to the plugin.
 * @returns a no-argument action calling `selectPanel(null)`, or null.
 */
function panelSelectorOf(layout) {
    if (typeof layout !== 'object' || layout === null)
        return null;
    const select = layout.selectPanel;
    if (typeof select !== 'function')
        return null;
    return () => {
        ;
        select.call(layout, null);
    };
}
};
__modules["effects/panel-exit.js"] = function (require, module, exports) {
"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.panelOwnsMainArea = panelOwnsMainArea;
exports.createPanelExit = createPanelExit;
exports.createPanelBackExitTask = createPanelBackExitTask;
exports.installPanelRowExit = installPanelRowExit;
const layout_compat_ts_1 = require("./core/layout-compat.js");
const phone_chrome_ts_1 = require("./effects/phone-chrome.js");
/**
 * Sidebar panel exit.
 *
 * The host's sidebar panels REPLACE the main area (`ctx.layout.selectPanel(id)`),
 * and the host ships no way back out of one: `PanelRow.onClick` is just
 * `selectPanel(id)` (tapping the already-selected row stays on the panel), and
 * the only caller of `selectPanel(null)` in the whole host is
 * `workspace.replaceMain` — i.e. opening a session. So on a phone, once the
 * drawer row puts you on the plugin-manager page, that page is a dead end
 * unless you already know to open the drawer and pick a session.
 *
 * Three exits, roughly in the order a phone user reaches for them:
 *  1. the system back key / back gesture (popstate) — the returned task
 *  2. tapping the already-selected panel row again — installPanelRowExit
 *  3. the top-left FAB, which reads as 「返回会话」 while a panel owns the main
 *     area — see createOverlayTask in overlay-backdrop-fab.ts
 *
 * All three funnel through the same `exit`, so they cannot drift apart.
 */
/**
 * The host marks the SELECTED sidebar panel row with `aria-current="page"`.
 * Measured on 0.1.6-alpha.2: `null` in the conversation, `"page"` on a panel —
 * the two states are cleanly separable. `panelRow` is a CSS-module fragment
 * (the host class is `hHd-Xa_panelRow`), so it is matched as a substring per
 * the repo's hashed-class convention.
 *
 * Why DOM and not `ctx.layout`: the layout face carries no readable panel id —
 * the selected panel lives on the host's own `PanelInfo` store, which is handed
 * only to the host's components.
 */
const PANEL_ROW_ACTIVE = '[class*="panelRow"][aria-current="page"]';
/**
 * True from the moment an exit starts until React has committed the swap.
 * During that window the panel row STILL carries `aria-current="page"`, but the
 * panel is semantically gone — history bookkeeping must treat it as closed,
 * otherwise it arms a second entry while the first exit is still in flight.
 */
let panelLeaving = false;
/** Whether a panel owns the main area (DOM truth, ignoring the exit window). */
function panelOwnsMainArea() {
    return document.querySelector(PANEL_ROW_ACTIVE) !== null;
}
/** Whether a panel owns the main area, counting the in-flight exit as closed. */
function panelViewOpen() {
    return !panelLeaving && panelOwnsMainArea();
}
/** Marker set on the frame while the incoming conversation fades in. */
const PANEL_EXIT_ATTR = 'data-mobile-panel-exit';
/** Safety net: clears the marker if the reveal animation never fires. */
const PANEL_EXIT_FALLBACK_MS = 2000;
/**
 * Leave the panel: switch back to the conversation and let the incoming content
 * fade in.
 *
 * ⚠ The switch is deliberately NOT delayed behind an outgoing animation.
 * `selectPanel(null)` makes React remount the whole conversation, and that
 * commit blocks the main thread long enough to matter (measured on a phone:
 * ~390 ms for a long session). Fading the panel out first would show a blank
 * screen for that entire window — the panel is already transparent but the
 * conversation has not mounted yet. Keeping the panel opaque until the very
 * commit means it disappears on the same frame the conversation appears, and
 * the only transition is the conversation's fade-in.
 *
 * @param layout - `ctx.layout`; probed, never assumed.
 * @returns the exit action (idempotent while an exit is in flight, so a double
 *   tap cannot queue two swaps) and the system-back reconciler task.
 */
function createPanelExit(layout) {
    const selectPanel = (0, layout_compat_ts_1.panelSelectorOf)(layout);
    const supported = selectPanel !== null;
    let leaving = false;
    let cleanupTimer = null;
    // The reveal animation runs on a descendant of the frame, so listen in the
    // capture phase; that is also the precise end-of-transition signal, which
    // beats guessing a timeout.
    function onAnimationEnd(event) {
        if (event.animationName === 'dsh-mobile-panel-reveal')
            cleanup();
    }
    function cleanup() {
        if (cleanupTimer !== null) {
            window.clearTimeout(cleanupTimer);
            cleanupTimer = null;
        }
        const frame = (0, phone_chrome_ts_1.getFrame)();
        if (frame !== null) {
            frame.removeEventListener('animationend', onAnimationEnd, true);
            frame.removeAttribute(PANEL_EXIT_ATTR);
        }
        panelLeaving = false;
        leaving = false;
    }
    const exit = () => {
        if (!supported || leaving)
            return;
        leaving = true;
        panelLeaving = true;
        // The marker goes on BEFORE the swap so the incoming conversation carries
        // the animation from its first style resolution — no full-opacity frame.
        const frame = (0, phone_chrome_ts_1.getFrame)();
        if (frame !== null) {
            frame.setAttribute(PANEL_EXIT_ATTR, '');
            frame.addEventListener('animationend', onAnimationEnd, true);
        }
        selectPanel();
        cleanupTimer = window.setTimeout(cleanup, PANEL_EXIT_FALLBACK_MS);
    };
    return { exit, supported, panelOpen: panelOwnsMainArea, task: createPanelBackExitTask(exit, supported) };
}
/**
 * The system back key / back gesture exits the panel.
 *
 * The host core does not touch the browser history at all (only the PDF preview
 * plugin does, and that is unrelated), and measured on a phone the panel view
 * sits at `history.length === 1` — pressing back there leaves the page
 * entirely. So this layer is free to take over: arm one history entry while a
 * panel is open, and exit the panel when it is popped.
 *
 * Two edges are handled explicitly:
 *  - Our own `history.back()` (used when the panel is left by another route)
 *    echoes back as a popstate. `selfBackPending` swallows that echo, with a
 *    timeout so a host WebView that never emits popstate cannot leave the flag
 *    stuck and eat the user's next real back press.
 *  - Between the click and React's commit the panel row is still marked active;
 *    `panelViewOpen()` reports closed during that window, so no second history
 *    entry is armed.
 *
 * @param exitPanel - the shared exit action.
 * @param supported - false on hosts with no panel-selection API; the task then
 *   never arms a history entry at all.
 */
function createPanelBackExitTask(exitPanel, supported) {
    let armed = false;
    let listening = false;
    let selfBackPending = false;
    let selfBackTimer = null;
    function clearSelfBack() {
        selfBackPending = false;
        if (selfBackTimer !== null) {
            window.clearTimeout(selfBackTimer);
            selfBackTimer = null;
        }
    }
    function selfBack() {
        selfBackPending = true;
        if (selfBackTimer !== null)
            window.clearTimeout(selfBackTimer);
        selfBackTimer = window.setTimeout(clearSelfBack, 1200);
        try {
            history.back();
        }
        catch {
            clearSelfBack();
        }
    }
    function onPopState() {
        if (selfBackPending) {
            clearSelfBack();
            return;
        }
        if (!armed)
            return;
        armed = false;
        if (panelViewOpen())
            exitPanel();
    }
    function listen() {
        if (listening)
            return;
        window.addEventListener('popstate', onPopState);
        listening = true;
    }
    function unlisten() {
        if (!listening)
            return;
        window.removeEventListener('popstate', onPopState);
        listening = false;
    }
    return {
        name: 'panel-back-exit',
        scopes: ['*'],
        ensure: () => {
            if (!supported)
                return;
            listen();
            if (panelViewOpen()) {
                if (armed)
                    return;
                armed = true;
                try {
                    // Second argument empty: add a poppable entry without touching the URL.
                    history.pushState({ mobilePanelExit: true }, '');
                }
                catch {
                    // Sandboxed frames refuse pushState; give up on this exit rather than
                    // breaking anything else.
                    armed = false;
                }
                return;
            }
            if (armed) {
                armed = false;
                selfBack();
            }
        },
        dispose: () => {
            unlisten();
            clearSelfBack();
            if (armed) {
                armed = false;
                selfBack();
            }
        },
    };
}
/**
 * Tapping the already-selected panel row again returns to the conversation.
 * Unselected rows are left alone — they still go through the host's own
 * `selectPanel(id)`.
 *
 * Runs in the capture phase so it can stop the host's onClick. The drawer close
 * rides the same click (phone-chrome's navigation-tap whitelist), which is the
 * only ordering that survives a touch: closing the drawer on pointerup cancels
 * the synthesized click entirely (see the 抽屉导航 click pitfall).
 */
function installPanelRowExit(ctx, exitPanel) {
    (0, phone_chrome_ts_1.installMobileEffect)(ctx, 'dsh-web-mobile: panel row returns to conversation', () => {
        const onClick = (event) => {
            const target = event.target;
            if (!(target instanceof Element) || typeof target.closest !== 'function')
                return;
            const row = target.closest('[class*="panelRow"]');
            if (row === null)
                return;
            if (row.getAttribute('aria-current') !== 'page')
                return;
            event.preventDefault();
            event.stopPropagation();
            exitPanel();
        };
        document.addEventListener('click', onClick, true);
        return () => document.removeEventListener('click', onClick, true);
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
        const params = new URLSearchParams(location.search);
        if (!params.has('mobile-nav-debug'))
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
            // Safe-area diagnosis (read-only: the observer below re-enters on any
            // node this adds to the document). framePad IS the resolved
            // env(safe-area-inset-top) - the frame already consumes it - and
            // rightPanel reports whether the host panel carries it too. If a phone
            // shows a covered top row while framePad reads 0px, the inset is 0 on
            // that device and the fix needs another source for the height.
            const rightPanel = () => {
                const el = document.querySelector('[data-sidebar-right-panel]');
                if (el === null)
                    return 'absent';
                const b = el.getBoundingClientRect();
                return `${el.getAttribute('data-sidebar-right-panel')} pad ${getComputedStyle(el).paddingTop} rect ${Math.round(b.top)},${Math.round(b.left)} ${Math.round(b.width)}x${Math.round(b.height)}`;
            };
            return [
                `build 20260919 (diag chips)`,
                `URL ${location.pathname}${location.search}`,
                `W ${innerWidth} x ${innerHeight} dpr ${devicePixelRatio}`,
                `mq≤1023 ${matchMedia(phone_chrome_ts_1.MOBILE_QUERY).matches}  mq≥1024 ${matchMedia(phone_chrome_ts_1.DESKTOP_QUERY).matches}`,
                `safeTop framePad ${frame === null ? 'n/a' : getComputedStyle(frame).paddingTop}  rightPanel ${rightPanel()}`,
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
        // Opt-in device beacon: the same readings are POSTed to a local listener
        // (default http://127.0.0.1:3199/diag, override with ?beacon=<url>) so a
        // phone-side repro can be read from the machine serving the page without
        // anyone copying numbers off the screen. no-cors + a string body keeps it a
        // simple request (no preflight); a missing listener is ignored.
        // Rects of the plugin's own header controls plus the host row they live in:
        // the phone-side position of the files opener is what a "not pinned to the
        // top-right corner" report is about, and it cannot be measured headless.
        const marker = (sel) => {
            const el = document.querySelector(sel);
            if (el === null)
                return 'absent';
            const b = el.getBoundingClientRect();
            return `${Math.round(b.x)},${Math.round(b.y)} ${Math.round(b.width)}x${Math.round(b.height)}`;
        };
        const payload = () => [
            read(),
            `rects toggle ${marker('[data-mobile-nav="toggle"]')} files ${marker('[data-mobile-nav="files"]')} header ${marker('[data-phase] header')} titleCluster ${marker('[class*="_titleCluster"]')}`,
            `chips crea ${marker('[class*="SVAs4q_"]')} team ${marker('[data-team-action]')} model ${marker('[class*="_7KE1Ra_trigger"]')} crumbs ${marker('[class*="_crumbs"]')} crumbCurrent ${marker('[class*="_crumbCurrent"]')}`,
            `ua ${navigator.userAgent}`,
            `screen ${screen.width}x${screen.height} standalone ${matchMedia('(display-mode: standalone)').matches}`,
            `vv ${visualViewport === null ? 'n/a' : `${Math.round(visualViewport.width)}x${Math.round(visualViewport.height)}@${Math.round(visualViewport.offsetTop)}`}`,
        ].join('\n');
        const beacon = params.get('beacon') || 'http://127.0.0.1:3199/diag';
        const beaconTimer = setInterval(() => {
            void fetch(beacon, { method: 'POST', mode: 'no-cors', body: payload() }).catch(() => { });
        }, 2000);
        return () => {
            window.removeEventListener('error', onError);
            window.removeEventListener('unhandledrejection', onRejection);
            observer.disconnect();
            clearInterval(timer);
            clearInterval(beaconTimer);
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
    'backToConversation': '返回会话',
    'sessionLog': '导出会话日志',
    'files': '文件浏览',
    'fileUpload': '添加文件',
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
    'backToConversation': 'Back to conversation',
    'sessionLog': 'Session log',
    'files': 'Files',
    'fileUpload': 'Add files',
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
const ComposerFileButton_tsx_1 = require("./components/ComposerFileButton.js");
const open_files_panel_ts_1 = require("./components/open-files-panel.js");
const index_ts_1 = require("./styles/index.js");
const phone_chrome_ts_1 = require("./effects/phone-chrome.js");
const sidebar_swipe_ts_1 = require("./effects/sidebar-swipe.js");
const subagent_chip_touch_ts_1 = require("./effects/subagent-chip-touch.js");
const session_menu_ts_1 = require("./effects/session-menu.js");
const composer_keyboard_guard_ts_1 = require("./effects/composer-keyboard-guard.js");
const composer_plus_toggle_ts_1 = require("./effects/composer-plus-toggle.js");
const model_menu_anchor_ts_1 = require("./effects/model-menu-anchor.js");
const aionui_compat_ts_1 = require("./effects/aionui-compat.js");
const panel_exit_ts_1 = require("./effects/panel-exit.js");
const raf_scheduler_ts_1 = require("./core/raf-scheduler.js");
const debug_ts_1 = require("./debug.js");
const locales_ts_1 = require("./i18n/locales.js");
/** Required services (cordis fiber inject — the loader passes all module exports as an object plugin). */
exports.inject = ['slots', 'layout', 'locale', 'sessionLogDownload', 'sessions', 'workspaces'];
/**
 * Mobile-adaptive shell, browser half: injects the mobile stylesheet, then
 * contributes the directory toggle to the session header and the backdrop +
 * floating button to the shell overlay.
 * @param ctx - client root context.
 */
function apply(ctx) {
    ctx.effect(() => ctx.locale.register(locales_ts_1.NS, { zh: locales_ts_1.zh, en: locales_ts_1.en }), 'dsh-web-mobile: dictionaries');
    ctx.effect(() => {
        // 先清掉可能残留的同 id 样式表。
        // 2026-09-23：app 的 P8 自愈会把 client.js 换回上游版、页面里也随之挂着
        // **上游那份 CSS**；我们重新部署产物后，只 append 不清旧的话，页面里那份旧表
        // 仍然压在上面（实测：开关/权限图标/头部留白改完"看着没生效"）。
        // 本插件是原地热重载（不整页刷新），所以这一步必须自己保证"页面里的样式表
        // 就是当前产物里的这一份"。
        for (const stale of document.querySelectorAll('style[data-plugin-css="dsh-web-mobile/mobile.css"]')) {
            stale.remove();
        }
        const tag = document.createElement('style');
        tag.dataset.plugin = 'dsh-web-mobile';
        tag.dataset.pluginCss = 'dsh-web-mobile/mobile.css';
        tag.textContent = index_ts_1.MOBILE_CSS;
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
    // Leaving a sidebar panel. The host's panels replace the main area and ship
    // no way back, so every exit route (system back, re-tapping the selected
    // panel row, the FAB) shares this one action.
    const panelExit = (0, panel_exit_ts_1.createPanelExit)(ctx.layout);
    // Shared mobile infrastructure: frame marker ownership and the single
    // full-tree reconciler. Installed inside one effect so a plugin reload in
    // the same JS environment tears the whole reconciler down and rebuilds it.
    ctx.effect(() => {
        const stops = [
            (0, phone_chrome_ts_1.installFrameController)(),
            (0, phone_chrome_ts_1.installReconciler)(ctx),
            (0, phone_chrome_ts_1.registerReconcileTasks)(ctx, panelExit),
        ];
        return () => {
            for (const stop of stops)
                stop();
        };
    }, 'dsh-web-mobile: reconciler infrastructure');
    // Drawer close interactions: Escape and navigation taps inside the drawer.
    (0, phone_chrome_ts_1.installOverlayInteractions)(ctx);
    // Sidebar panel exit: re-tapping the already-selected panel row returns to
    // the conversation (the system-back route is a reconciler task; both call the
    // same action).
    (0, panel_exit_ts_1.installPanelRowExit)(ctx, panelExit.exit);
    // Session deletion, injected into each session row's ⋯ menu (beside
    // rename / fork / archive) with a confirm dialog. Mobile-only.
    (0, session_menu_ts_1.installSessionMenuDelete)(ctx);
    // Sidebar swipe gestures: edge swipe-in opens the drawer, content swipe-out
    // closes it (release-classified, zero inline transforms — A 档). Since
    // 2026-09-13 the layer also owns the right-edge files gesture (leftward
    // opens the files panel via openFilesPanel, rightward closes whatever is
    // on top — the panel or the drawer); a leftward stroke never collapses
    // anything.
    (0, sidebar_swipe_ts_1.installSidebarSwipe)(ctx, open_files_panel_ts_1.openFilesPanel);
    // Lineage-count chip: reliable open/close on touch pointers (upstream is
    // hover-timer driven and has no onClick on the count variant).
    (0, subagent_chip_touch_ts_1.installSubagentChipTouch)(ctx);
    // iOS: tapping the composer's send/stop/+ buttons must not re-raise the
    // dismissed keyboard (upstream keepFocus focuses the editor on mousedown).
    (0, composer_keyboard_guard_ts_1.installComposerKeyboardGuard)(ctx);
    (0, composer_plus_toggle_ts_1.installComposerPlusToggle)(ctx);
    // Model/reasoning menu portals to <body>; the CSS centering rule died with the
    // portal move, so re-anchor it on the trigger here (owner report: opens far left).
    (0, model_menu_anchor_ts_1.installModelMenuAnchor)(ctx);
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
        }),
    }, MobileNavToggle_tsx_1.MobileNavToggle));
    // Session log download, relocated from the session header to the drawer
    // footer on mobile (the header capsule is hidden by CSS). The footer's
    // Files action was removed on 2026-09-17 — see
    // docs/specs/2026-09-17-sidebar-files-coexistence-design.md.
    //
    // Footer stacking relies on the list-slot sort by (priority, order):
    // dsh-remote-web-ui leaves it unset (default 0, its two icon buttons stay
    // on top) and dsh-usage-stats uses 10. Order 5 keeps the session-log pill
    // directly under the icon row with the usage/balance badge below it —
    // instead of a tie at 10 where registration order could wedge the badge
    // between the icons and the pill.
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
        }),
    }, MobileDrawerFooter_tsx_1.MobileDrawerFooter));
    // Composer file entry (0.1.6 host): the host deleted the paperclip attach
    // button, leaving the 「文件」row inside the "+" listbox as the only file
    // entry. Re-add a permanent one in the host's own conversation.input.left
    // seat (inside the tools lane, beside the plus button). It triggers the
    // host's hidden input[type=file] — the same fileInputRef.current.click()
    // the host's own command runs — so intake validation, upload and the
    // availability policy stay host-owned.
    ctx.slots.inject('conversation.input.left', () => ctx.slots.register({
        name: 'conversation.input.left',
        id: 'mobile-nav-file-upload',
        order: 10,
        locale: locales_ts_1.NS,
        inject: () => ({}),
    }, ComposerFileButton_tsx_1.ComposerFileButton));
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
