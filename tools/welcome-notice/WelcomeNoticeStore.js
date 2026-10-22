// DSHA_WELCOME_STORE_RC2_V3：有界核实 Host 保存结果；未知响应不重发写入。
var WelcomeNoticeStore = class {
  scope;
  mirror;
  store = (0, _deepseek_ai_dsh_client_store.createSnapshotStore)({
    status: "idle", acknowledged: false, sessionOnly: false, error: null,
    mode: "host", request: 0, startedAt: null, detached: false,
    phase: "idle", attempts: 0
  });
  saving = false;
  request = 0;
  confirmedRequest = 0;
  detached = false;
  sessionOnly = false;
  following;
  constructor(scope, mirror) { this.scope = scope; this.mirror = mirror; }
  async load() {
    const request = this.request;
    this.following ??= this.scope.subscribe(() => { this.derive(); });
    this.derive();
    if (this.scope.getSnapshot().status === "ready") return;
    await this.readHost(request, true);
    if (request !== this.request || this.detached) return;
    this.derive();
    if (this.scope.getSnapshot().status !== "ready") this.reportError("settings-not-ready");
  }
  async readHost(request, initial = false) {
    // 只给界面核实读设置等待上限；不取消或重放已经交给 Host 的写入。
    // 官方共享 mirror 自行合并读请求，晚到的真实视图仍由订阅消费。
    if (!this.mirror || request !== this.request) return false;
    let timer;
    try {
      const read = initial && this.scope.getSnapshot().status === "loading" ? this.mirror.ensure() : this.mirror.load();
      return await Promise.race([
        Promise.resolve(read).then(() => true, () => false),
        new Promise((resolve) => { timer = setTimeout(() => resolve(false), 8000); })
      ]);
    } finally { clearTimeout(timer); }
  }
  async acknowledge() {
    if (this.saving || this.sessionOnly) return false;
    const request = ++this.request;
    this.detached = false;
    this.saving = true;
    this.store.update((state) => {
      state.status = "saving";
      state.error = null;
      state.request = request;
      state.startedAt = Date.now();
      state.detached = false;
      state.phase = "write";
      state.attempts = 0;
    });
    try {
      // 返回说明后，旧请求可能已由 Host 确实保存；重试先读共享镜像，
      // 避免把相同确认再排到仍未结算的表单写队列后面。
      this.derive();
      if (this.confirmedRequest === request) return true;
      if (this.scope.getSnapshot().status !== "ready" || !this.scope.getSnapshot().writable) {
        this.store.update((state) => { state.phase = "read"; });
        await this.readHost(request);
        if (request !== this.request) return false;
        this.derive();
        if (this.confirmedRequest === request) return true;
        const scope = this.scope.getSnapshot();
        if (scope.status !== "ready" || !scope.writable) {
          this.reportError(scope.status !== "ready" ? "settings-not-ready" : "settings-read-only");
          return false;
        }
      }
      for (let attempt = 1; attempt <= 2; attempt++) {
        const revision = this.scope.getSnapshot().revision;
        this.store.update((state) => { state.phase = "write"; state.attempts = attempt; });
        const accepted = await this.scope.set(WELCOME_NOTICE_ACK_FIELD, WELCOME_NOTICE_VERSION);
        if (request !== this.request) return false;
        this.derive();
        if (this.confirmedRequest === request) return true;
        const current = this.scope.getSnapshot();
        // ConfigForm 对明确拒绝做了真实恢复读。仅版本已变化且仍可写时，
        // 有限重试这个幂等确认字段；传输异常/未知执行结果绝不走此分支。
        if (accepted === false && attempt === 1 && current.status === "ready" && current.writable &&
            revision !== void 0 && current.revision !== void 0 && current.revision !== revision) continue;
        if (accepted !== false) {
          this.store.update((state) => { state.phase = "verify"; });
          await this.readHost(request);
          if (request !== this.request) return false;
          this.derive();
          if (this.confirmedRequest === request) return true;
        }
        this.reportError(accepted === false ? "save-refused" : "save-not-persisted");
        return false;
      }
      return false;
    } catch (_error) {
      if (request === this.request && this.confirmedRequest === request) return true;
      if (request !== this.request) return false;
      this.store.update((state) => { state.phase = "verify"; });
      await this.readHost(request);
      if (request !== this.request) return false;
      this.derive();
      if (this.confirmedRequest === request) return true;
      this.reportError("save-exception");
      return false;
    } finally {
      // 晚到结果无权覆盖新请求或用户已经选择的退出方式。
      if (request === this.request) this.saving = false;
    }
  }
  reportError(code, detached = false) {
    this.saving = false;
    // 自动失败仍观察精确 Host 确认；用户明确离开等待、临时进入或销毁后才分离。
    this.detached = detached;
    this.store.update((state) => {
      state.status = "error";
      state.acknowledged = false;
      state.error = code;
      state.detached = detached;
      state.phase = "error";
    });
  }
  returnToNotice() {
    ++this.request;
    this.reportError("wait-left-host-request-not-cancelled", true);
  }
  continueForSession() {
    ++this.request;
    this.saving = false;
    this.detached = true;
    this.sessionOnly = true;
    this.store.update((state) => {
      state.status = "ready";
      state.acknowledged = false;
      state.sessionOnly = true;
      state.error = null;
      state.detached = true;
    });
  }
  dispose() {
    ++this.request;
    if (this.saving) this.reportError("disposed-host-request-not-cancelled", true);
    this.saving = false;
    this.detached = true;
    this.following?.();
    this.following = void 0;
  }
  derive() {
    if (this.detached || this.sessionOnly) return;
    const scope = this.scope.getSnapshot();
    // Host 先持久化并广播 settings/document-updated，RPC 回答可能稍后才到。
    // ConfigForm 没有乐观赋值：这里的 ready/value 来自实际 Host describe/write view。
    // 等待期间只允许精确确认值结束说明；其它推送不能重置 saving 或制造成功。
    const acknowledged = scope.status === "ready" && scope.value?.[WELCOME_NOTICE_ACK_FIELD] === WELCOME_NOTICE_VERSION;
    const state = this.store.getSnapshot();
    if (this.saving || state.status === "error") {
      const settingsBecameReady = (state.error === "settings-not-ready" || state.error === "settings-unavailable") && scope.status === "ready";
      if (!acknowledged && !settingsBecameReady) return;
      if (acknowledged) this.confirmedRequest = this.request;
      this.saving = false;
    }
    this.store.update((state) => {
      // 0.2.1 binds this notice to the durable Host section. A session-only
      // choice remains explicit below and never fabricates persisted state.
      state.mode = "host";
      state.sessionOnly = false;
      state.acknowledged = false;
      state.error = null;
      state.phase = "ready";
      switch (scope.status) {
        case "loading": state.status = "loading"; break;
        case "unavailable":
          state.status = "error";
          state.error = "settings-unavailable";
          break;
        case "ready":
          state.status = "ready";
          state.acknowledged = scope.value?.[WELCOME_NOTICE_ACK_FIELD] === WELCOME_NOTICE_VERSION;
          break;
        default: return assertNever(scope.status);
      }
    });
  }
};
