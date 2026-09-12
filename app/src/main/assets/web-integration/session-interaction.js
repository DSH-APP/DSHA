// 单击只选择；两次邻近点击打开。键盘和辅助功能的显式激活保留单次打开。
function createDshaSessionSelection(now = () => performance.now()) {
    let picked = null, lastTap = null;
    const listeners = new Set();
    const select = (id, currentId) => {
        picked = { id, currentId };
        for (const listener of listeners) listener();
    };
    return {
        subscribe(listener) { listeners.add(listener); return () => listeners.delete(listener); },
        selected(currentId) { return picked !== null && picked.currentId === currentId ? picked.id : currentId; },
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
