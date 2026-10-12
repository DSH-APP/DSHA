# 虚拟屏输入在持续渲染的页面上永远被拒（STALE_FRAME）

2026-10-12，Redmi K80 Ultra（dali / 25060RK16C，Android 17 / SDK 37，HyperOS）真机定位。

## 现象

`/app/vscreen/tap`（`swipe`/`key`/`type`/`node` 同理）在画面持续变化的页面上恒返回
`{"ok":false,"error":"STALE_FRAME"}`，无论把「取帧」和「输入」的间隔压到多短。静止页面
（如系统设置）正常，因此 AI 无法在淘宝、拼多多这类常驻轮播/动效的界面上完成任何操作。

真机会话实测：淘宝首页 6 秒内 `frameSeq` 7659 → 7787（约 21 fps），拼多多 5 秒
9491 → 9576（约 17 fps）；系统设置稳定在 6096。会话记录里 62 次 `tap` 中有 23 次
`STALE_FRAME`，另有 5 次 `INVALID_frameSeq`、8 次 `INVALID_generation`。

## 根因

`VirtualScreenCore` 的输入闸门同时要求三件事：

```java
if (!VirtualScreenPolicy.fresh(observed, session.sequence)   // 必须等于「此刻最新帧」
    || observed != session.observed                          // 且等于上次 see 登记的那一帧
    || now - session.observedAt > 30_000)
  return error("STALE_FRAME");
```

而 `fresh(observed, current)` 是 `observed == current` 的精确相等，帧号由采集线程每
33ms 轮询、画面有像素变化就自增。于是这三条合起来的实际语义是「从 `see` 到输入之间
屏幕一帧都不能变」，容差不超过一次轮询。取帧与输入之间隔着一次模型往返（真机实测中位
3.7 秒、P75 7.4 秒、P90 11.8 秒），有动画的页面必然已经换帧，输入因此被永久拒绝。

附带两个同样致命的死角：`/vscreen/status` 会返回 `frameSeq` 却不登记观察，`/vscreen/preview`
在 `since == 当前帧` 的 `unchanged` 早返回分支也不登记 —— 两者拿到的帧号都必然被闸门拒绝。

## 修复

输入判定改为「必须出示上次观察登记的那一帧，且该次观察足够新」，不再要求它等于此刻的
最新帧：

- `util/VirtualScreenPolicy.inputFrame(presented, registered, current, observedAt, now)`
  返回 `ACCEPTED` / `STALE` / `EXPIRED`，窗口为常量 `OBSERVATION_WINDOW_MS = 10_000`；
  `presented != registered` 或超前于当前帧仍判 `STALE`，超时判 `EXPIRED`。
- `VirtualScreenCore` 的输入闸门与 `touch` 按下分支改用该判定；超时返回新错误码
  `OBSERVATION_EXPIRED`，其余保持 `STALE_FRAME`。每次输入成功后仍把 `observed` 置回 -1，
  动作失败不重放。
- `preview` 的 `unchanged` 分支也登记观察（调用方声明它持有的就是当前帧）；`status`
  明确不登记，避免「没看过画面就注入输入」。
- 新增 `/app/vscreen/observe?generation=&frameSeq=`：只登记不回画面，把「取帧→输入」的
  窗口压到最小；返回 `registered:true` 表示该帧仍是当前帧，`false` 表示画面已变、须重新
  `see`。插件相应新增 `android_vscreen_observe` 工具，`/app/help` 同步新语义。

## 验证

宿主侧：`VirtualScreenPolicyTest`、`VirtualScreenRoutesTest`、`VscreenBridgeRequestTest`、
`VirtualScreenBootstrapTest` 共 18 项 JUnit 通过；`tools/test-vscreen.mjs` 插件 schema 与
工具面测试通过。

真机：构建本分支的正式包（个人签名，覆盖安装 build170，数据保留）后，用应用自身的桥验证：

```text
see  -> frameSeq=99
status -> frameSeq=129          （1.2 秒内推进 30 帧，确认页面在动）
tap(generation, frameSeq=99) -> {"ok":true,...}     # 旧规则此处必然 STALE_FRAME
observe(当前帧) -> registered=true        observe(非当前帧) -> registered=false
status 的 frameSeq 直接输入 -> STALE_FRAME            # status 不算观察
观察 12 秒后输入 -> OBSERVATION_EXPIRED
伪造帧（当前+5000） -> STALE_FRAME        错误 generation -> STALE_GENERATION
```

## 构建输入说明

本机首次跑通了完整 Gradle 正式包（未签名产物 + 本地签名），此前该分支只有 JUnit 与
隔离探针验证。为复现构建，`offline-rootfs.bin` / `dsh-runtime.bin` / `ubuntu-tools.bin`
从已安装的 build170 APK 中还原（仓库不提交这些生成物），因此 `runtime-descriptor.json`
只更新源码类输入的摘要，`offline-rootfs.bin` 一项保持 CI 的原始 rootfs 摘要不变；在有原始
rootfs 的机器上重新执行 `tools/prepare-runtime-descriptor.py --write` 可得到同样结果。
