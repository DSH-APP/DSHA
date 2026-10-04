# 本轮持续任务检查点

## 最新检查点（2026-10-03 10:xx，覆盖下文旧进度）

任务未完成，不能最终停止/宣称新APK已交付。source已升154/0.2.5-20261003.0922-dsh0.2.0-rc.2。release仍旧153两包，必须最终软件/签名/ELF/资产验证后替换。

全236项已有before清单，G1/G2/G3/UI/mobile/engineering/docs/sourceText/proof增量与G2 coverage60；ROOT ledger仍旧18checkpoint需合并。Mobile正写final-disposition-gaps只读一轮，发现真实DSEC02与DAPI08：G1独占Proot push/pull安全host/guest解析原子流及tests，G2独占vscreen MCP schema/op真实派发/有界POST及Native route/tests。新发现才修，不能用重复audit无限扩展；两者完成即descriptor/完整build。

全部生产生成器改tools/source_text.py LF/byte-exact。根393实际文本已归LF，history exact原blob未改(.gitattributes -text)。Gradle相关生成task声明helper；managed-runtime-inputs generatorSources13含source_text；新增StartupPageGate source。后续Agent文件再统一必要LF，保留第一次lf-normalization receipt不要覆盖为0。

新ARM archive从fresh npm-ci(ignore scripts、锁integrity)生成，SHA dd5436dda084fb4d34efd5eeff0a0a599dd58545f35cc89a0c09bd97b2a8d519（prepare-arm-runtime-fresh.log；source app/build/locked-dsh-runtime154）。首次offline在旧cache缺ws8.22 ENOTCACHED且旧目录EPERM cleanup失败，未当成功。独立recovery锁及旧D8/b9固定副本保持，不隐式升级；新正式archive不同必须包内pinned救援副本，APK会增加体积。

Host fixture schema2完整内容证明，旧v1 fresh npmci保原件，新v2篡改拒认证；特殊patch严格分consumer/receipt，tooltip0.1.5旧minifier不应用rc2。首次新hostnpmci后Windows长路径stat失败，无marker；G1 io_path/logical_path已修真实>500字节读取、copytree和Node拒篡改。第二次生成session87225日志prepare-host-runtime-longpath.log，npmci611成功9s，managed-131f85b740b0正在准备，尚须读completion。不绕过fingerprint/完整proof。

G2 host runner/manifest103已正确args/env/平台、独立失败汇集final非零，stage all包括current fast/runtime/windows/linux但不device/history；cli建议--toolchain-root F:/DSHA/_toolchains --pnpm-cli app/build/stability-tools/pnpm-10.34.5/dist/pnpm.cjs --linux-host app/build/audit-build154/linux-host。package stage最后2apks。raw受完整proof保护，不写shared module，root已改trial fixture到owned目录+junction，test-runtime-publish改managed，trial改现RuntimeTools/registry+actual managed session publisher。Root earlierfast跑到semver缺argv失败，G2已修清单；不计全通过。Mobile修oldrestore-merge-wsdir temp路径，标history不算当前数据恢复。

Root已完成：新module/ADR/engineering/CONTRIB/security双语/current README本地154区别公开147；NOTICES自动锁/实际mobile hashes +ShizukuMIT/TermuxApache/prootGPL全文实际字节；source-provenance明确旧CFR与未知不法律定性；root历史5offline/scripts链搬history有SHA。UpdateActivity changelog改raw/raw-en从build154-changes源notes生成；公开入口Native Constants，site构建读取它。website当前版本/DSH/runtimeId来自finalAPK，builtin名单动态，安装文案免审阅/开放依赖，skill单源，部署脚本从manifest取固定APK名、多段版本regex/显式checks，nginx下载补全部headers，实际Windows nginx1.28.3 isolated -t PASS(receipt)，未部署。

CI fast加两flavorGradle JUnit但skip离线asset tasks（source-only），package固定HTTPS+SHA输入/unsigned gates，protectedrelease从same-repo/head-sha/success package Run及receipt/hash读取2unsignedAPK，E7/v1v2v3签并draft+source，不已上线。verify-ci-package4testsPASS；signingtool还未真正执行。Gradle unsigned signPublishRelease仅execution拒绝不配置时throw；key passwords显式。

生成已执行builtin/credential/runtimeTools/webCompat，backup package proofs588。runtime descriptor尚未生成，必须等G1/G2最终生产source冻结并asset-deployment gate/readers同步。G1 sourceText helper改变recipe，ARM已最新，若builder/helper再改重建。不操作手机，不新debug/auditAPK，不GitHub push/release或服务器deploy。

剩余硬要求：最终所有script参数/source registrations；清账236无漏项并有真实残余；完整两flavor单测/ReleaseLint/正式assemble；所有current宿主回归（真实Linux9过既有，WSL目前fallback只读但uname可行，不能修系统或假称LinuxJVM/重编）；APK/ELF/现archive/recovery/plugin/MCP/modal/签名E7 v1v2v3 manifest154非debug；final常规release两APK+sha、snapshot/report、localwebsitebuild/check/package。不假造手机、全矩阵、五次线上CI或20以下分数。

用户授权：对外部DSHA-deliverables-20261002全部资料从头到尾复核、修复、验证、交付两正式APK。任务仍未完成，不在打包/证据前停止。当前本地153；目标154(0.2.5+北京时间戳+DSH0.2.0-rc.2)，版本尚未提升。

全236记录已在FINDINGS-BEFORE.md指出(215open、6device、6policy、4duplicate、2fixed、3不适用)。source-items/source-receipt保存原输入。POLICY.md按当前用户约束覆盖附件另一会话决策：插件免审阅、开放依赖与修复终端；内部自动副本加密不删；用户导出无密码tar.gz；保留历史读取/原件/未知guest屏障；E7E3+v1/v2/v3；不额外debug/auditAPK、不操作手机。

## 正在运行的owner

- G1 backup_extension151：CredentialPaths/SAF/v5机器排除/系统XML、token header+STABLE原子文件/真实peak兼容shim、Tar实提取、屏幕/设备policy、敏感脱敏已大部完成；现窄处理ConfigFragment安全输入/UpdateEngine下载验证/KeyVault和BackupManager两个APIkey同步方法。changes-G1增量。其它Bridge/UI ownership别抢。
- G2 migration82：runtime/Harness/Proot/DshaApp/RuntimeHostPorts、Launcher/StartupPipeline/Output/Stop/补丁链、完整ColdInstallTransaction、rc1失败快照复用、caller lease退出保留。给root注册清单；manifest/descriptor由root统一。ColdInput针对Android平台alias保持153边界。changes-G2。
- G3 plugin_perf_edit：backup/markers/history/guestpath/Ids/scopes/builtinregistry/kinds/legacy YAML/插件回执/依赖metadata与导出。EnvMaintenance owner，已合并BrowserProbe。正在cold pending/private-root接线和export边界，完成后会释放slot并交mobile compress/delete给backup_password。changes-G3。不要忘余下mobile！

## root已改

- .gitattributes全部文本LF+二进制显式exclude；还未归一化全工作树，等owner写完避免CRLF反复产生。
- MavenLocal已删除，offline verifyBackupDependencies通过。gradle/verification-metadata.xml已通过正确离线命令生成；online曾因PowerShell未quote -D拆错Task失败，已更正，不当失败证据通过。
- Gradle明确密码、历史证书从ci/release-identity读取、unsignedPackage显式仅CI不入release、pnpm BuildConfig常量、Builtin/runtime tools verify tasks、Adb assetsDir属性。signPublishRelease unsigned配置throw需再审避免任务枚举触发。
- runtime-tools.lock单源及生成assets/runtime-tools；adb-wheels.lock提取，G3已改Python读锁，AdbWheelBundleTest读JSON+property、run-unit传property。
- 两APK核验51assert改require，-O错误版本9旧exit0新exit1；test-verification-require2PASS。UI源测试32PASS。run-unit编译全部辅助类+临时目录收尾。
- 测试selectors当前raw/managed proof独立；profile/queue/states/conversation/issue67/trial/perf更新。chat真实console/pageerror+3s预算待实际执行。
- host-tests.manifest 92入口显式登记；run-host-tests严格coverage/no-O/device禁用，runtime arg modules/fixture/storageSource显式，新owner test文件要追加。完整runtime/fast尚未运行，不能宣称92通过。
- 5旧危险debug入口退出编译并原件保留tools/history/android-audits，manifest剔除。其它R11源码问题仍需处理/编译。readOnly TerminalOwner代替不存在sessions反射，语言保存原始pref，PDF读spec路径，脱敏裁剪长度。
- WebPageScripts和Gecko compat builder接同一ES5 bridge-token-compat.cjs，webextension1.8缓存失效，删除死models事件(Gecko relay仍有老派发待清)。真实peak tgz函数/Request/Signal迁移PASS，未知curl/http库限制如实写；old147浏览器source无3090 token仍不冒称所有前代已验。
- native-session跨平台builder锁NDK26/API23/16k，Windows独立重编SHA完全相同0ab7a3c3...，JNI原件未替换。Linux实际重编未做。
- proot GPL全文补main；GNU网络失败，GPL3全文从现有Ubuntu usr/share/common-licenses/GPL-3取35149bytes(见license-download)。Termux LICENSEupstream明确view/emulator Apache例外，不能盲写所有termux组件GPL3。
- npm-licenses.json锁文件实际693包登记(非输入报告761)，区分foreign optional vs实际arm归档；THIRD_PARTY_NOTICES重写待做。
- DiagnosticHistory有界安全FS原子发布，选择previous防断写丢史；3独立JUnit PASS。DiagnosticLog已接、ConfigStore读/存成功registerKnownSecret，UI刷新吞Throwable仍待修。
- JVMBackupFS非final允许故障注入，unix actualdev/mode+Windows FileStore，move父类型/dev检查；全部JVM需最终跑。danglinglink取parent FileStore已修。
- 新CI fast/package/release workflows有SHA pins；fast脚本可用但LF未冻结暂不跑；package手动输入CI-assets exactZIP，缺完整真实线上验证。release.yml目前仅prereq，必须完成真实sign+approved package-run读取+draft流程或明确prepared未部署不能伪称全自动发版。ci-assets不能含密钥/用户数据，fixed成员/边界检查；pack还未做。

## root仍必须完成

1. 所有rootG4/G5逐项落实，补debug剩余/资源/单源通知ports/纯协议控制文本/i18n边界，死assets核引用并保历史，source部署清单门禁，文档/技能单源/网站数据当前交付同步。不能只加违规基线说债务已清。
2. 处理startup report source/nonce可靠性(DSEC13)、plugin localStorage token(DSEC17等待G3文件证据)、NetworkSecurityConfig(低API23与用户HTTP模型/LAN不能破坏，先确认actualcaller)。
3. 工程规范/ADR/MODULE/CONTRIBUTING/AGENTS收敛以现行Policy；README区分公开147与本地154，不伪称GitHub新版/网站上线。旧用户1.1.10主页段保留且标历史。
4. 收注册：runtime-patches.json/builtin-plugins.json/runtime-tools.json/bridge-token-compat.cjs/adb-wheels.lock/credential-yaml-filter.cjs/legacy-preset-convert.cjs等；launcher新util/Runtime相关和DiagnosticHistory。launcher runtime tree已自动涵盖新runtime Java；纯util单项需要加入。刷generated Builtin/webcompat/backup proofs/descriptor与打包输入。
5. 全source LF原始实际字节归一化(不能对bin替换CRLF)，更新所有fingerprints/recipes。如runtime-fs/构建器变化重建dsh-runtime.bin，独立recovery旧archive保持pinned副本。prepare-test-runtime现在用runtime-patches active/specialized，不套retired；version失配显式失败。specialized tooltip当前version若不等需G2明确retired/迁移。
6. 源冻结后一次跑两flavor全JUnit/ReleaseLint/assemble正式(E7E3 explicitpwd)、所有host测试、真实rc2 browserModal/流恢复/插件/MCP/backup/ELF+签名v1/v2/v3、manifest/package/nondebug，fix失败只重跑相关再最终完整。无手机操作。
7. localrelease常规同名dsha-0.2.0-rc2[low].apk+sha256替换，旧其它版本保留。源码快照与完整逐项after台账/metrics/残余/发布报告全部绑定最终字节；不将215全部草率称修好。没有新GitHubpush/release或dsha.cc部署，本轮root授权范围先本地交付。

工具：Python C:/Users/18768/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe；Java F:/DSHA/_toolchains/jdk-17；SDK F:/DSHA/_toolchains/android-sdk；Gradle9.3.1旧toolchainwrapper路径。GRADLE_USER_HOME=F:/DSHA_RESTART/.gradle-user-home，-I tmp-gradle-init.gradle。原历史key F:/DSHA/dsha/杂项/DSHA-ACTUAL-PUBLISH-KEY-debug.keystore；密码必须明确env提供，别输出；正式签名原E7E3别换钥。Docker daemon无，WSL docker-desktop只有sh/x86_64无Java/GCC/Node；已有Linux C8旧fixture不等于Android真机。
