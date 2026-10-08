"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.installShortcutModalKeyboardGuard = installShortcutModalKeyboardGuard;
const phone_chrome = require("./effects/phone-chrome.js");
const focus_shadow = require("./core/prototype-focus-shadow.js");

// DSHA：只抑制 rc2 首次挂载期间的自动搜索聚焦。原生触摸、Tab、读屏和后续
// focus 请求保留；弹层本身获得焦点，以便 Escape/Tab 和读屏仍拥有正确上下文。
// 此片段由 apply-mobile-client-patches.mjs 放入锁定上游 bundle，行为测试执行产物。
function installShortcutModalKeyboardGuard(ctx) {
    phone_chrome.installMobileEffect(ctx, 'dsh-web-mobile: shortcut modal keyboard guard', () => {
        const selector = '[data-shortcut-modal="shortcuts"] [data-modal-autofocus]';
        // 断点切回移动布局时，已存在的输入框不是首次挂载。
        const initialized = new WeakSet(document.querySelectorAll(selector));
        const pending = new WeakSet();
        return focus_shadow.shadowFocus(element => {
            if (element.matches(selector)) {
                // focusWithoutRing 是当前宿主明确的自动聚焦入口。一次 commit 内
                // Modal 和快捷键组件各有 layoutEffect，必须一起抑制，然后立即解除。
                if (!initialized.has(element) && element.hasAttribute('data-dsh-automatic-focus')) {
                    if (!pending.has(element)) {
                        pending.add(element);
                        Promise.resolve().then(() => initialized.add(element));
                        const dialog = element.closest('[role="dialog"][aria-modal="true"]');
                        if (dialog && !dialog.contains(document.activeElement)) {
                            // 官方 Modal 已带 tabindex=-1，不修改 React 管理的属性。
                            dialog.focus({ preventScroll: true });
                        }
                    }
                    return true;
                }
                initialized.add(element);
            }
            return false;
        });
    });
}
