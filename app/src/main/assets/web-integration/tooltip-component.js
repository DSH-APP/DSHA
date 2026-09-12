/* 替换锁定版本的 Tooltip，保留其布局与 React 管理的 DOM。 */
function Fr({label, side = "right", delayMs = 0, disabled = false, maxWidth, children}) {
  const anchor = I.useRef(null), bubble = I.useRef(null), childRef = children.ref;
  const ref = I.useCallback(element => {
    anchor.current = element;
    if (typeof childRef === "function") childRef(element);
    else if (childRef != null) childRef.current = element;
  }, [childRef]);
  const [position, setPosition] = I.useState(null), [placement, setPlacement] = I.useState(side);
  const live = I.useRef(null), interaction = I.useRef(null);
  live.current = {disabled, delayMs, side};
  if (interaction.current === null) interaction.current = dshaTooltipRuntime.create({
    enabled: () => !live.current.disabled && anchor.current !== null,
    delay: () => live.current.delayMs,
    open: () => {
      const bounds = anchor.current.getBoundingClientRect(), direction = live.current.side;
      setPlacement(direction);
      setPosition({x: direction === "right" ? bounds.right + 10 : bounds.left + bounds.width / 2, top: bounds.top, bottom: bounds.bottom});
    },
    close: () => setPosition(null)
  });
  const behavior = interaction.current;
  I.useEffect(() => behavior.mount(), [behavior]);
  I.useLayoutEffect(() => { if (disabled) behavior.dismiss(); }, [disabled, behavior]);
  const text = position === null ? null : typeof label === "function" ? label() : label;
  const top = position === null ? 0 : placement === "right" ? position.top + (position.bottom - position.top) / 2
    : placement === "top" ? position.top - 8 : position.bottom + 8;
  I.useLayoutEffect(() => {
    if (position === null) return;
    const update = () => {
      const element = bubble.current;
      if (element === null) return;
      element.style.left = `${position.x}px`;
      const box = element.getBoundingClientRect();
      let shift = box.right > window.innerWidth - 12 ? window.innerWidth - 12 - box.right : 0;
      if (box.left + shift < 12) shift = 12 - box.left;
      element.style.left = `${position.x + shift}px`;
      if (side === "right") return;
      const fitsBottom = position.bottom + 8 + box.height <= window.innerHeight - 12;
      const fitsTop = position.top - 8 - box.height >= 12;
      if (placement === "bottom" && !fitsBottom && fitsTop) setPlacement("top");
      if (placement === "top" && !fitsTop && fitsBottom) setPlacement("bottom");
    };
    update(); window.addEventListener("resize", update);
    return () => window.removeEventListener("resize", update);
  }, [placement, position, text, side]);
  return d.jsxs(d.Fragment, {children: [I.cloneElement(children, {
    ref,
    onPointerEnter: event => { children.props.onPointerEnter?.(event); behavior.pointerEnter(event); },
    onPointerLeave: event => { children.props.onPointerLeave?.(event); behavior.pointerLeave(); },
    onFocus: event => { children.props.onFocus?.(event); behavior.focus(); },
    onBlur: event => { children.props.onBlur?.(event); behavior.blur(); },
    onClick: event => { try { children.props.onClick?.(event); } finally { behavior.activate(); } }
  }), position !== null && d.jsx("span", {
    ref: bubble, className: zd.bubble, "data-side": placement, "data-dsha-tooltip": "1",
    style: {left: position.x, top, ...(maxWidth === undefined ? {} : {maxWidth})}, role: "tooltip", children: text
  })]});
}
