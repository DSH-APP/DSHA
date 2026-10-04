# build156 剩余项与验证边界

本轮236项审计已逐项处置，当前软件修复剩余0；两版正式APK已交付，单台Android13真机各19项有限场景已验证。详见[最终验证报告](v0.2.7-build156-20261003.md)与[逐项最终台账](../audits/build156/execution-ledger-final.json)。

当前主要待补证的4项为：

| ID | 尚缺材料或实测 |
|---|---|
| D-PROV-01 | ShellService的原始代码来源及许可/授权证明。 |
| D-BAK-02 | 实际环境重建的峰值空间与容量比标定。 |
| D-DOC-19 | alpha1旧摘要对应的原始APK或可信官方校验材料；alpha2已追回。 |
| D-DOC-20 | 本轮Hosted Linux CI、受保护环境及仓库保护的真实记录。 |

另有更广覆盖限制：历史记录跨50分页、当前Provider完整读/改名/删除、真实npm镜像失败降级，以及Android6/7、物理16KiB和其它设备/服务矩阵。它们未被本次有限场景结果代替。

下方111/17是原条目携带的设备/外部覆盖标记集合，交集1、并集127；其中包含本次已新增有限证据的条目，不是111个未修软件问题。原owner记录保留其检查时点，本轮新增实际范围以最终报告及设备摘要为准。

| 当前集合 | 数量 | ID |
|---|---:|---|
| 仍需当前软件修复 | 0 | — |
| 保留设备/平台覆盖限制的原条目 | 111 | D-SEC-01, D-SEC-02, D-ADB-01, D-SEC-03, D-SEC-04, D-SEC-05, D-ROOT-01, D-SEC-06, D-SEC-07, D-SEC-08, D-NET-01, D-PRIV-01, D-UPD-01, D-SEC-09, D-CFG-01, D-SEC-10, D-SEC-11, D-SEC-13, D-SEC-14, D-PRIV-02, D-NET-02, D-SEC-15, D-SEC-16, D-SEC-17, D-SEC-18, D-SEC-19, D-SEC-21, D-SEC-22, D-PRIV-04, D-ARCH-01, D-RT-01, D-RT-03, D-RT-04, D-I18N-01, D-ARCH-02, D-RT-05, D-PLAT-01, D-RT-06, D-RT-07, D-RT-08, D-RT-09, D-IO-01, D-RT-10, D-PLAT-02, D-RT-11, D-ARCH-03, D-RT-12, D-IO-02, D-API-01, D-RT-13, D-RT-14, D-RT-15, D-RT-16, D-ARCH-04, D-CONC-01, D-RT-17, D-PERF-01, D-I18N-02, D-IO-03, D-PKG-01, D-ARCH-07, D-IO-04, D-HEUR-01, D-RT-18, D-A11Y-01, D-I18N-03, D-ERR-01, D-PERF-02, D-CONC-02, D-RT-19, D-RES-01, D-PERF-03, D-API-08, D-PERF-04, D-FS-01, D-RT-20, D-HEUR-02, D-RES-02, D-DUP-02, D-DUP-03, D-PATCH-01, D-DUP-05, D-LEGACY-01, D-PRESET-01, D-DUP-10, D-BAK-01, D-BAK-02, D-BAK-03, D-PLUG-01, D-BAK-04, D-BUG-01, D-BUG-02, D-BUG-03, D-DUP-17, D-BUG-04, D-BUG-05, D-DUP-20, D-COMPAT-01, D-DUP-22, D-BUG-06, D-BUG-08, D-PLUG-03, D-PLUG-04, D-TEST-02, D-SUP-03, D-TEST-11, D-TEST-17, D-TEST-20, D-TEST-21, D-TEST-22, D-TEST-24 |
| 保留外部证据限制的原条目 | 17 | D-PROV-01, D-LIC-01, D-UPD-01, D-UPD-02, D-PRIV-03, D-SEC-23, D-LIC-02, D-WEB-01, D-CI-01, D-BUILD-01, D-BUILD-02, D-REPRO-01, D-TEST-31, D-DOC-09, D-DOC-19, D-DOC-20, D-DOC-25 |
| 保留的功能/保护契约 | 19 | D-NET-01, D-SEC-10, D-SEC-23, D-ARCH-05, D-RT-18, D-DUP-02, D-DUP-06, D-DUP-07, D-DUP-09, D-DUP-10, D-BAK-01, D-BAK-03, D-BAK-04, D-DUP-14, D-DUP-20, D-TEST-06, D-TEST-28, D-TEST-35, D-DOC-05 |
| 重复源条目 | 4 | D-RT-06, D-DOC-02, D-DOC-14, D-DEAD-08 |

实际整合的软件回执：

- Standard：1195 项，1191 通过、4 跳过、0 失败、0 错误；跳过没有计为通过。
- Low：1195 项，1191 通过、4 跳过、0 失败、0 错误；跳过没有计为通过。
- 宿主：112 个实际声明的 active 入口，实际日志和最终夹具字节检查通过；stage数量为 {'fast': 44, 'runtime': 63, 'windows': 3, 'linux': 2}。package host 条目 0 个，不从选择标签虚构执行。两版真正APK门禁由软件回执另证。
- 样式：785 个当前项逐字节绑定格式化/真实 token 证明；旧 focused 回执不冒充格式化后执行。
- 网站：真实156产物本地构建和 28 项检查通过，未据此声称 dsha.cc 部署。

- standard Release Lint：222 条 Warning，所有 222 条实际 XML 记录逐项分类；没有声称警告清零。
- low Release Lint：152 条 Warning，所有 152 条实际 XML 记录逐项分类；没有声称警告清零。
- Lint分类仍标注 5 条需要进一步位置/owner审查的不确定记录；不能把分类动作称为这些警告已消除。

正式包已按实际设备回执完成同签名、非调试、非破坏性覆盖验收，release 常规文件及 sidecar 与被验收 APK 逐字节一致。该单设备/声明检查范围没有扩写成 Android6/7、真实16KiB、OEM/Root/Shizuku/FUSE/掉电或外部模型矩阵。原 owner 未验历史保留，并由新回执补充实际范围。

owner原记录的具体限制与缺口（保留原文；有新设备回执时，下列旧“未操作”句子仅描述owner当时范围，不能覆盖新的实际验收结论；完整矩阵仍不自动算通过）：

- D-SEC-01：Android syscall/device extraction and filesystem matrix untested; this is defence in depth for authorized app-UID code.
- D-SEC-02：Android/OEM proc/syscall and FUSE behavior untested. Windows links use explicit modelling where privileges are unavailable; original and published copy are retained on unknown post-publication outcomes.
- D-ADB-01：Root-off real /exec denial not exercised on a device.
- D-SEC-03：Real Android HTTP service/client integration was not executed.
- D-SEC-04：No phone foreground-switch, payment-app launch, focused-node or accessibility dispatch acceptance.
- D-PROV-01：Original external code source/license grant or independently reviewed replacement provenance
- D-SEC-05：Android clipboard rendering, actual close/reopen sockets and old-browser cookie rejection were not performed.
- D-ROOT-01：KernelSU/APatch/Magisk authorization and uid=0 results require actual devices; none operated. App-controlled PATH remains intentionally untrusted.
- D-SEC-06：Actual Android/FUSE descriptor behavior, the installed APK cold app_process/PM context, Root/Shizuku permissions and cancellation/process-lifecycle evidence are still missing.
- D-LIC-01：Complete transitive Gradle license and embedded native library/source-offer legal review
- D-SEC-07：Android DocumentsUI/SAF URI grant and syscall/device behavior untested.
- D-SEC-08：Android Keystore not exercised; key-only historical archives still need explicit key restoration choice.
- D-NET-01：Android platform enforcement and API23/newer networking matrix not exercised.
- D-PRIV-01：This lane did not instantiate Android collect(Context) or inspect private real-device logs. Source confirms default export excludes every raw log/process/plugin/conversation body; source evidence is not labeled device evidence.
- D-UPD-01：Live update service/CDN validation; Android physical update/install device matrix
- D-UPD-02：Live current CDN/network edge validation
- D-SEC-09：No phone/ADB/extra debug or audit APK was used by user instruction. Android 6/7, vendor /proc restrictions, 16 KiB hardware and native UI actions are not claimed.
- D-CFG-01：No device UI-to-process environment observation.
- D-SEC-10：No Android deep-link or browser tap was performed by user instruction; host checks do not claim that device path.
- D-SEC-11：No Android savedInstanceState/rotation instrumentation.
- D-SEC-13：Nonce/provenance is not isolation from authorized same-origin/same-UID plugin code. No real WebView/Gecko console spoof/device matrix.
- D-SEC-14：Real virtual sensitive-app launch, tree/preview gating and hidden-API behavior not executed.
- D-PRIV-02：Unregistered arbitrary secrets and custom formats cannot be guaranteed masked; physical logging paths were not device-tested.
- D-NET-02：Runtime lane owns DNS JVM fixtures; real VPN/private DNS/network transitions not executed by this UI lane.
- D-SEC-15：Same-UID authorized plugin code is not sandboxed by file-export policy. Device HTTP/share/provider behavior untested.
- D-SEC-16：User-selected projects and user provider credentials may legitimately contain secrets; this is not a globally secret-free archive claim. Android Downloads/SAF execution untested.
- D-SEC-17：Fast run excludes real browser IndexedDB; data lane owns full real-Chromium storage proof. No Android first-frame URL/localStorage evidence.
- D-SEC-18：Android/FUSE live mutation and descriptor behavior were not tested.
- D-SEC-19：Actual Root/Shizuku/SELinux, hard-link/alias and shared-storage race conditions not validated on a phone.
- D-SEC-21：Actual Android Keystore/browser signing-secret rotation/provider restore untested; project scope is explicitly preserved.
- D-PRIV-03：本轮不能擦除Git历史和既有公开副本；私有原件须继续从公开快照排除
- D-SEC-22：Android system backup/cloud/device-transfer transport not exercised. Filename allowlists cannot prove arbitrary user profile files contain no embedded secrets.
- D-PRIV-04：No real screenshot/cache cleanup/permission lifecycle evidence.
- D-SEC-23：Private key storage/hosted environment assurance
- D-LIC-02：Legal/source-offer and original binary provenance proof
- D-WEB-01：没有dsha.cc本轮公网APK响应/实际Linux服务器回执；Windows本地语法检查不代替部署
- D-ARCH-01：Android device/platform acceptance was not performed by user instruction
- D-RT-01：No phone/ADB/extra debug or audit APK was used by user instruction. Android 6/7, vendor /proc restrictions, 16 KiB hardware and native UI actions are not claimed.
- D-RT-03：No phone/ADB/extra debug or audit APK was used by user instruction. Android 6/7, vendor /proc restrictions, 16 KiB hardware and native UI actions are not claimed.
- D-RT-04：No phone/ADB/extra debug or audit APK was used by user instruction. Android 6/7, vendor /proc restrictions, 16 KiB hardware and native UI actions are not claimed.
- D-I18N-01：ShellService.java is protected external decompiled source; its single signature is an explicit provenance restriction, not an allowed offset for future product defects. No whole-screen English/device proof.
- D-ARCH-02：Android device/platform acceptance was not performed by user instruction
- D-RT-05：No phone/ADB/extra debug or audit APK was used by user instruction. Android 6/7, vendor /proc restrictions, 16 KiB hardware and native UI actions are not claimed.
- D-PLAT-01：No phone/ADB/extra debug or audit APK was used by user instruction. Android 6/7, vendor /proc restrictions, 16 KiB hardware and native UI actions are not claimed.
- D-RT-06：No phone/ADB/extra debug or audit APK was used by user instruction. Android 6/7, vendor /proc restrictions, 16 KiB hardware and native UI actions are not claimed.
- D-RT-07：No phone/ADB/extra debug or audit APK was used by user instruction. Android 6/7, vendor /proc restrictions, 16 KiB hardware and native UI actions are not claimed.
- D-RT-08：No phone/ADB/extra debug or audit APK was used by user instruction. Android 6/7, vendor /proc restrictions, 16 KiB hardware and native UI actions are not claimed.
- D-RT-09：No phone/ADB/extra debug or audit APK was used by user instruction. Android 6/7, vendor /proc restrictions, 16 KiB hardware and native UI actions are not claimed.
- D-IO-01：Android syscall, power-loss persistence and FUSE fsync evidence missing; a post-publication sync failure retains the complete output and reports failure.
- D-RT-10：No phone/ADB/extra debug or audit APK was used by user instruction. Android 6/7, vendor /proc restrictions, 16 KiB hardware and native UI actions are not claimed.
- D-PLAT-02：No phone/ADB/extra debug or audit APK was used by user instruction. Android 6/7, vendor /proc restrictions, 16 KiB hardware and native UI actions are not claimed.
- D-RT-11：No phone/ADB/extra debug or audit APK was used by user instruction. Android 6/7, vendor /proc restrictions, 16 KiB hardware and native UI actions are not claimed.
- D-ARCH-03：Android device/platform acceptance was not performed by user instruction
- D-RT-12：No phone/ADB/extra debug or audit APK was used by user instruction. Android 6/7, vendor /proc restrictions, 16 KiB hardware and native UI actions are not claimed.
- D-IO-02：Actual Android link/chmod/ELF cold extraction/FUSE behavior untested.
- D-API-01：Android signal permission and phone lifecycle acceptance not run by user instruction
- D-RT-13：No phone/ADB/extra debug or audit APK was used by user instruction. Android 6/7, vendor /proc restrictions, 16 KiB hardware and native UI actions are not claimed.
- D-RT-14：No phone/ADB/extra debug or audit APK was used by user instruction. Android 6/7, vendor /proc restrictions, 16 KiB hardware and native UI actions are not claimed.
- D-RT-15：No phone/ADB/extra debug or audit APK was used by user instruction. Android 6/7, vendor /proc restrictions, 16 KiB hardware and native UI actions are not claimed.
- D-RT-16：No phone/ADB/extra debug or audit APK was used by user instruction. Android 6/7, vendor /proc restrictions, 16 KiB hardware and native UI actions are not claimed.
- D-ARCH-04：No phone/ADB/extra debug or audit APK was used by user instruction. Android 6/7, vendor /proc restrictions, 16 KiB hardware and native UI actions are not claimed.
- D-CONC-01：No phone/ADB/extra debug or audit APK was used by user instruction. Android 6/7, vendor /proc restrictions, 16 KiB hardware and native UI actions are not claimed.
- D-RT-17：No phone/ADB/extra debug or audit APK was used by user instruction. Android 6/7, vendor /proc restrictions, 16 KiB hardware and native UI actions are not claimed.
- D-PERF-01：No phone/ADB/extra debug or audit APK was used by user instruction. Android 6/7, vendor /proc restrictions, 16 KiB hardware and native UI actions are not claimed.
- D-I18N-02：Inventory is production-source lexical evidence; it does not claim real English screenshots of every future/dynamic/plugin screen.
- D-IO-03：Kernel descriptor/rename/parent-race/OEM/FUSE matrix not executed; authorized same-UID code is not an attacker sandbox.
- D-PKG-01：Android device/platform acceptance was not performed by user instruction
- D-ARCH-07：Android device/platform acceptance was not performed by user instruction
- D-IO-04：Actual Android crash/power-loss behavior untested.
- D-HEUR-01：No phone/ADB/extra debug or audit APK was used by user instruction. Android 6/7, vendor /proc restrictions, 16 KiB hardware and native UI actions are not claimed.
- D-RT-18：No phone/ADB/extra debug or audit APK was used by user instruction. Android 6/7, vendor /proc restrictions, 16 KiB hardware and native UI actions are not claimed.
- D-A11Y-01：TalkBack, real short-screen/font-1.3/night/day walkthrough not performed by user instruction. Existing Spinner parent performClick opens its own second menu; custom click sends accessibility event and selected state without opening two menus. Root final Lint must classify this explicit contract.
- D-I18N-03：No physical dialog/pairing output evidence; dynamic argument classification is source-specific rather than a blanket variable ban.
- D-ERR-01：Android device/platform acceptance was not performed by user instruction
- D-PERF-02：No phone/ADB/extra debug or audit APK was used by user instruction. Android 6/7, vendor /proc restrictions, 16 KiB hardware and native UI actions are not claimed.
- D-CONC-02：No phone/ADB/extra debug or audit APK was used by user instruction. Android 6/7, vendor /proc restrictions, 16 KiB hardware and native UI actions are not claimed.
- D-RT-19：No phone/ADB/extra debug or audit APK was used by user instruction. Android 6/7, vendor /proc restrictions, 16 KiB hardware and native UI actions are not claimed.
- D-RES-01：Unknown drafts and unclosed cross-page references stay retained. Arbitrary 30-day/oldest-draft eviction would violate preservation without deletion proof. Physical mobile quota/crash lifecycle untested.
- D-PERF-03：No phone/ADB/extra debug or audit APK was used by user instruction. Android 6/7, vendor /proc restrictions, 16 KiB hardware and native UI actions are not claimed.
- D-API-08：Android device/platform acceptance was not performed by user instruction
- D-PERF-04：No phone/ADB/extra debug or audit APK was used by user instruction. Android 6/7, vendor /proc restrictions, 16 KiB hardware and native UI actions are not claimed.
- D-FS-01：Real host tmpfs /dev/shm, not Android FUSE or disk power-loss persistence. Windows managed fixture refused stale client-combo-cache recipe input; root regenerated fixture verification remains separate.
- D-RT-20：No phone/ADB/extra debug or audit APK was used by user instruction. Android 6/7, vendor /proc restrictions, 16 KiB hardware and native UI actions are not claimed.
- D-HEUR-02：No phone/ADB/extra debug or audit APK was used by user instruction. Android 6/7, vendor /proc restrictions, 16 KiB hardware and native UI actions are not claimed.
- D-RES-02：Two real POSIX symlink cases require Linux privilege/device evidence; partial/corrupt/original snapshots retained.
- D-DUP-02：Android vendor /proc access and actual process-reuse matrix not exercised.
- D-DUP-03：Actual Android trial/guest matrix remains root/device scope.
- D-PATCH-01：Actual Android/WebView/Gecko touch rendering and vendor behavior remain untested in this no-phone round.
- D-PATCH-02：Root owns final archive/descriptor regeneration and full signed APK gates; lane did not rebuild them.
- D-DUP-05：Device installer/terminal launcher behavior not exercised by this lane.
- D-LEGACY-01：Android mapped aliases and actual device startup transitions not exercised.
- D-PRESET-01：Explicit native restore UI and Android guest YAML execution were not exercised; actual locked YAML host converter passed.
- D-DUP-10：Android NOFOLLOW syscall extraction, all signed archive shape/device cases not run in this lane.
- D-BAK-01：Local protected copies and key have no uninstall-survival guarantee; actual Android Keystore/JobScheduler/OEM lifecycle untested.
- D-BAK-02：Observed free-space delta includes unrelated filesystem activity. No Android physical peak-space ratio/capacity calibration was measured; no <=1 ratio claim.
- D-BAK-03：No device format or destructive user-data action was run.
- D-PLUG-01：Host LinkFs/JVM proves exact guest target and outside-root rejection; real Android/proot links were not exercised.
- D-BAK-04：Retained user/failed/unknown originals can continue to consume disk; their deletion remains outside the latest read-only contract. Physical disk-capacity and long-term device lifecycle evidence missing.
- D-BUG-01：Actual Android ViewModel/job record fallback is owned and tested by data lane; this UI lane did not execute Android context.
- D-BUG-02：No Android chooser/share-recipient evidence in this run.
- D-BUG-03：Root/data lane final restore fixture owns runtime verification; no Android token handshake matrix here.
- D-DUP-17：No Android Ctrl input device behavior test in this turn.
- D-BUG-04：No physical preview-button click evidence.
- D-BUG-05：No device MediaStore/chooser verification.
- D-DUP-20：Actual Android hidden-process and proroot close matrix not exercised.
- D-COMPAT-01：No real old WebView/Gecko provider or Android 6/7 matrix. Runtime patch integrity checks owned by runtime/root.
- D-DUP-22：Actual Android browser/uploads/lifecycle matrix not exercised.
- D-BUG-06：Actual Android SAF/provider offsets, FUSE races, Keystore and browser-session/signing-secret rotation remain untested in this no-device run. The 72-test/30-case receipts prove the stated current host/source scope, not physical Android execution.
- D-PLUG-02：Windows host budget/partial-registration fixtures pass; an actual Linux flock holder and Android quarantine filesystem were not exercised.
- D-BUG-08：No Android network/device compression matrix.
- D-PLUG-03：No destructive deletion or backup of production phone sessions was performed.
- D-PLUG-04：No production Android startup was attempted; receipt continuation was executed in isolated host state.
- D-PLUG-05：One Windows symlink identity test skipped; locally computed evidence and authorClaims separation passed.
- D-CI-01：Hosted runs and branch required-check settings; Actual protected release environment/secrets configuration
- D-TEST-02：Android kernel UID/proc/SIGTERM/EPERM and descriptor adapters remain unexecuted; current algorithms use explicit platform outcomes in JVM tests.
- D-SUP-03：Android physical script installation behavior (phone excluded this round)
- D-TEST-11：Real Android Activity/WebView/Gecko single/multiple chooser and lifecycle scenarios were not executed.
- D-TEST-17：Actual Android WebView/PDF Worker compatibility was not executed.
- D-TEST-20：Signed non-debug Android PTY fork/exec identity timing was not executed; independent Linux C evidence cannot certify Android release timing.
- D-TEST-21：Actual Android /device/plan dry-plan and native diagnosis execution were not performed.
- D-BUILD-01：Clean Linux checkout/hosted CI execution
- D-TEST-22：Android FUSE/private-directory attachment publication and guest absolute-link behavior were not exercised on a device.
- D-TEST-24：Native Android O_PATH/fstat descriptor and concurrent-link boundary behavior was not exercised.
- D-BUILD-02：Hosted complete offline input/package job
- D-REPRO-01：Hosted Linux native byte reproducibility/real native DNS case
- D-TEST-31：Hosted CI execution of the current source/manifest was not performed.
- D-DOC-09：网站本轮未部署，新技能源分发仅本地；历史线上内容不能当已更新
- D-DOC-19：仅alpha1旧63位记录的对应原件/官方旁证仍未匹配；本地另一版本143候选不能代替该原件。alpha2已追回，未补猜
- D-DOC-20：没有本轮Hosted Linux发布job/受保护环境/仓库保护的真实运行回执；本地软件和tmpfs不代替该证据
- D-DOC-25：未部署dsha.cc或核对公网新版本下载响应；本轮无部署授权

旧源码来源授权和旧63位摘要没有可信材料时保持未知；本轮未发布 GitHub 或部署服务器。历史读者、未知退出屏障、原件保护、完整性复核与开放正式插件/终端是现行契约，不以删除它们降低技术债分数。

项目ZIP覆盖冻结时的明确源码成员；最终台账、本剩余稿和最终交付报告分离交付。冻结后不为追写这些报告重做ZIP，避免摘要循环。
