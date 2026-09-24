// 单击只选择；两次邻近点击打开。键盘和辅助功能的显式激活保留单次打开。
function createDshaSessionSelection(now = () => performance.now()) {
    let picked = null, lastTap = null;
    const listeners = new Map(), all = new Set(), contexts = new Map();
    const rowListeners = (currentId, rowId, create) => {
        let rows = contexts.get(currentId);
        if (!rows && create) contexts.set(currentId, rows = new Map());
        let group = rows?.get(rowId);
        if (!group && create) rows.set(rowId, group = new Set());
        return group;
    };
    const select = (id, currentId) => {
        const previous = picked;
        picked = { id, currentId };
        const previousId = previous !== null && previous.currentId === currentId ? previous.id : currentId;
        const affected = new Set(all);
        const collect = (current, row) => { for (const listener of rowListeners(current, row, false) || []) affected.add(listener); };
        collect(currentId, previousId); collect(currentId, id);
        if (previous !== null && previous.currentId !== currentId) {
            collect(previous.currentId, previous.id); collect(previous.currentId, previous.currentId);
        }
        for (const listener of affected) listener();
    };
    return {
        subscribe(listener, currentId, rowId) {
            listeners.get(listener)?.();
            const group = rowId === void 0 ? all : rowListeners(currentId, rowId, true);
            group.add(listener);
            const off = () => {
                if (listeners.get(listener) !== off) return;
                listeners.delete(listener); group.delete(listener);
                if (rowId !== void 0 && group.size === 0) {
                    const rows = contexts.get(currentId); rows?.delete(rowId);
                    if (rows?.size === 0) contexts.delete(currentId);
                }
            };
            listeners.set(listener, off);
            return off;
        },
        selected(currentId, rowId) {
            const selected = picked !== null && picked.currentId === currentId ? picked.id : currentId;
            return rowId === void 0 ? selected : selected === rowId;
        },
        select(id, currentId) { lastTap = null; select(id, currentId); },
        click(event, id, currentId, open) {
            if (event.defaultPrevented || event.button > 0) return;
            if (event.detail === 0) { lastTap = null; select(id, currentId); open(); return; }
            const time = now(), x = event.clientX ?? 0, y = event.clientY ?? 0;
            const double = lastTap !== null && lastTap.id === id && lastTap.currentId === currentId
                && time - lastTap.time <= 500 && time >= lastTap.time
                && Math.abs(x - lastTap.x) <= 24 && Math.abs(y - lastTap.y) <= 24;
            lastTap = double ? null : { id, currentId, time, x, y };
            select(id, currentId);
            if (double) open();
        }
    };
}
