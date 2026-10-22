// 鼠标单击打开；touch/pen单击只选择、两次邻近点击打开。键盘和读屏显式激活直接打开。
function createDshaSessionSelection(now = () => performance.now()) {
    let picked = null, lastTap = null, lastPointer = null;
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
        select(id, currentId) { lastTap = null; lastPointer = null; select(id, currentId); },
        pointerDown(event, id, currentId) {
            const type = event.pointerType || event.nativeEvent?.pointerType;
            lastPointer = !event.defaultPrevented && !(event.button > 0) && ['mouse', 'touch', 'pen'].includes(type)
                ? { id, currentId, type, time: now(), pointerId: event.pointerId,
                    x: event.clientX ?? 0, y: event.clientY ?? 0 } : null;
            if (lastPointer === null || type === 'mouse' || lastTap !== null && lastTap.type !== type) lastTap = null;
        },
        pointerCancel(event, id, currentId) {
            if (lastPointer?.id === id && lastPointer.currentId === currentId
                && (event.pointerId === undefined || lastPointer.pointerId === event.pointerId)) {
                lastPointer = null; lastTap = null;
            }
        },
        click(event, id, currentId, open) {
            const time = now(), x = event.clientX ?? 0, y = event.clientY ?? 0;
            const pointer = lastPointer; lastPointer = null;
            if (event.defaultPrevented || event.button > 0) { lastTap = null; return; }
            if (event.detail === 0) { lastTap = null; select(id, currentId); open(); return; }
            // React onClick可能是MouseEvent包装；同一行的pointerdown保留实际输入来源。
            // 不按窗口主指针/media query判断：触屏设备外接鼠标仍应一次打开。
            const declared = event.pointerType || event.nativeEvent?.pointerType;
            const type = ['mouse', 'touch', 'pen'].includes(declared) ? declared
                : event.sourceCapabilities?.firesTouchEvents || event.nativeEvent?.sourceCapabilities?.firesTouchEvents ? 'touch'
                : pointer !== null && pointer.id === id && pointer.currentId === currentId
                    && time >= pointer.time && time - pointer.time <= 1500
                    && Math.abs(x - pointer.x) <= 24 && Math.abs(y - pointer.y) <= 24 ? pointer.type : 'mouse';
            if (type === 'mouse') { lastTap = null; select(id, currentId); open(); return; }
            const double = lastTap !== null && lastTap.type === type && lastTap.id === id && lastTap.currentId === currentId
                && time - lastTap.time <= 500 && time >= lastTap.time
                && Math.abs(x - lastTap.x) <= 24 && Math.abs(y - lastTap.y) <= 24;
            lastTap = double ? null : { id, currentId, type, time, x, y };
            select(id, currentId);
            if (double) open();
        }
    };
}
