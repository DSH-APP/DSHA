// DSHA_WELCOME_NOTICE_RC2_V1：仅修改本说明界面，离开等待不取消宿主写入。
function WelcomeNotice(props) {
  const { complete, controller, useWelcome, t } = props;
  const state = useWelcome((snapshot) => snapshot);
  const finished = (0, react.useRef)(false);
  const mounted = (0, react.useRef)(true);
  const [now, setNow] = (0, react.useState)(Date.now);
  const finish = (0, react.useCallback)(() => {
    if (!mounted.current || finished.current) return;
    finished.current = true;
    complete();
  }, [complete]);
  (0, react.useEffect)(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);
  (0, react.useEffect)(() => {
    if (state.status === "idle") {
      Promise.resolve().then(() => controller.load()).catch(() => {
        if (mounted.current) controller.reportError("load-exception");
      });
    }
  }, [controller, state.status]);
  (0, react.useEffect)(() => {
    if (state.acknowledged || state.sessionOnly) finish();
  }, [finish, state.acknowledged, state.sessionOnly]);
  (0, react.useEffect)(() => {
    if (state.status !== "saving") return;
    setNow(Date.now());
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [state.status, state.request]);
  if (state.status === "idle" || state.status === "loading" || state.acknowledged || state.sessionOnly) return null;
  const acknowledge = () => {
    // 即使控制器意外拒绝，也不能从点击处理器漏出未处理的 Promise。
    void Promise.resolve().then(() => controller.acknowledge()).then((saved) => {
      if (saved) finish();
    }).catch(() => {
      if (mounted.current) controller.reportError("save-exception");
    });
  };
  const saving = state.status === "saving";
  const actionStyle = { maxWidth: "100%", whiteSpace: "normal", height: "auto", minHeight: 36, padding: "8px 14px", overflowWrap: "anywhere" };
  const paragraphs = t("welcomeBody").split("\n\n");
  const diagnostic = JSON.stringify({
    notice: WELCOME_NOTICE_VERSION,
    status: state.status,
    persistence: state.mode,
    request: state.request,
    phase: state.phase,
    attempts: state.attempts,
    elapsedSeconds: saving ? Math.max(0, Math.floor((now - state.startedAt) / 1000)) : null,
    result: state.error,
    // 这里只描述界面等待状态，不能据此宣称宿主请求已经取消。
    hostRequestMayStillComplete: saving || state.detached === true || state.error === "save-exception"
  }, null, 2);
  return (0, react_jsx_runtime.jsxs)(OnboardingModal, {
    title: t("welcomeTitle"),
    focusTitle: true,
    children: [
      (0, react_jsx_runtime.jsx)("div", {
        className: WelcomeNotice_module_css_default.copy,
        children: paragraphs.map((paragraph) => (0, react_jsx_runtime.jsx)("p", { children: paragraph }, paragraph))
      }),
      state.error === null ? null : (0, react_jsx_runtime.jsx)("p", {
        className: WelcomeNotice_module_css_default.error,
        role: "alert",
        children: t(state.error === "wait-left-host-request-not-cancelled" ? "welcomeWaitLeft" : "welcomeError")
      }),
      saving ? (0, react_jsx_runtime.jsx)("p", {
        role: "status",
        children: t("welcomeWaiting")
      }) : null,
      (0, react_jsx_runtime.jsx)("p", { children: t("welcomeSessionHint") }),
      (0, react_jsx_runtime.jsxs)("details", {
        children: [
          (0, react_jsx_runtime.jsx)("summary", { children: t("welcomeDiagnostics") }),
          (0, react_jsx_runtime.jsx)("pre", {
            style: { whiteSpace: "pre-wrap", overflowWrap: "anywhere", fontSize: 12 },
            children: diagnostic
          })
        ]
      }),
      (0, react_jsx_runtime.jsxs)("div", {
        className: WelcomeNotice_module_css_default.actions,
        style: { flexWrap: "wrap", gap: 8 },
        children: [
          saving ? (0, react_jsx_runtime.jsx)(_deepseek_ai_dsh_client_ui_primitives.Button, {
            style: actionStyle,
            onClick: () => controller.returnToNotice(),
            children: t("welcomeReturn")
          }) : null,
          (0, react_jsx_runtime.jsx)(_deepseek_ai_dsh_client_ui_primitives.Button, {
            style: actionStyle,
            onClick: () => controller.continueForSession(),
            children: t("welcomeSessionOnly")
          }),
          (0, react_jsx_runtime.jsx)(_deepseek_ai_dsh_client_ui_primitives.Button, {
            variant: "primary",
            className: WelcomeNotice_module_css_default.primary,
            style: actionStyle,
            disabled: saving,
            onClick: acknowledge,
            children: t(saving ? "welcomeSaving" : state.error ? "welcomeRetry" : "welcomeContinue")
          })
        ]
      })
    ]
  });
}
