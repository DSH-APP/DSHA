"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.shadowFocus = shadowFocus;

// 上游两个守卫共用一层包装。DSHA 保留精确属性恢复与第三方包装归属检查。
const rules = new Set();
let restore = null;
function shadowFocus(shouldSkip) {
    if (restore === null) {
        const proto = HTMLInputElement.prototype;
        const descriptor = Object.getOwnPropertyDescriptor(proto, 'focus');
        const previous = proto.focus;
        if (typeof previous !== 'function' || descriptor?.configurable === false ||
            (!descriptor && !Object.isExtensible(proto))) return () => {};
        const wrapper = function focus(options) {
            for (const rule of rules) if (rule.shouldSkip(this)) return;
            return previous.call(this, options);
        };
        try {
            Object.defineProperty(proto, 'focus', {
                configurable: true, writable: true,
                enumerable: descriptor?.enumerable ?? false, value: wrapper,
            });
        } catch { return () => {}; }
        restore = () => {
            if (Object.getOwnPropertyDescriptor(proto, 'focus')?.value !== wrapper) return;
            try {
                if (descriptor) Object.defineProperty(proto, 'focus', descriptor);
                else delete proto.focus;
            } catch {
                // 安装后被冻结时，空规则集合已使残留包装完全透传。
            }
        };
    }
    const rule = {shouldSkip};
    rules.add(rule);
    return () => {
        if (!rules.delete(rule)) return;
        if (rules.size === 0) {
            const release = restore;
            restore = null;
            release?.();
        }
    };
}
