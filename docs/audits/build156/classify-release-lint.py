#!/usr/bin/env python3
"""Read-only, location-by-location Release Lint classification for fresh build156 reports.

No source, resource, test, APK or Gradle mutation. Stale XML cannot produce a final
count. Each issue keeps the original index/id/severity/message/locations; unknown
or actionable findings are explicit, never silently waived by an issue-ID bucket.
"""
from __future__ import annotations

import argparse
import collections
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
AUDIT = Path(__file__).resolve().parent
JAVA = ROOT / 'app/src/main/java/com/deepseekharness/app'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def stamp(value):
    return datetime.fromtimestamp(value, timezone.utc).isoformat()


def relative(value):
    spelling = value.replace('\\', '/')
    prefix = ROOT.as_posix().rstrip('/') + '/'
    return spelling[len(prefix):] if spelling.lower().startswith(prefix.lower()) else spelling


def source_evidence(location, radius=8):
    file = relative(location.get('file', ''))
    path = ROOT / file
    row = {'file': file, 'line': int(location.get('line', '0')), 'xmlLocation': location}
    if path.is_dir():
        row.update(available=True, objectType='directory',
                   members=[{'file': child.relative_to(ROOT).as_posix(), 'sha256': digest(child)}
                            for child in sorted(path.rglob('*')) if child.is_file()])
        return row
    if not path.is_file():
        row['available'] = False
        return row
    row.update(available=True, sha256=digest(path))
    if path.suffix in {'.java', '.xml', '.gradle', '.properties', '.json'}:
        lines = path.read_text(encoding='utf-8-sig').splitlines()
        index = max(0, row['line'] - 1)
        start, end = max(0, index - radius), min(len(lines), index + radius + 1)
        row['excerpt'] = '\n'.join(f'{i+1}: {lines[i]}' for i in range(start, end))
    else:
        row['byteLength'] = path.stat().st_size
    return row


def witnesses(file, patterns):
    path = ROOT / file
    if not path.is_file():
        return []
    lines = path.read_text(encoding='utf-8').splitlines()
    return [{'file': file, 'line': i + 1, 'text': text.strip()}
            for i, text in enumerate(lines) if any(re.search(pattern, text) for pattern in patterns)][:18]


def result(classification, reason, action=False, confidence='source_review', basis=None):
    return {'classification': classification, 'reason': reason,
            'softwareActionRequired': action, 'confidence': confidence,
            'basis': basis or []}


def classify(issue, evidence, flavor):
    identity, message = issue['id'], issue['message']
    main = evidence[0] if evidence else {}
    file = main.get('file', '')
    excerpt = main.get('excerpt', '')
    name = Path(file).name
    severity = issue['severity'].lower()
    if severity in {'error', 'fatal'}:
        return result('software_residual_requires_fix',
                      '本轮实际 Lint 错误/致命项，须由对应 owner 处理后重新验证；不能作为警告选择接受。', True)
    if identity == 'ObsoleteSdkInt':
        # The XML location is authoritative. A nearby API31 guard must not hide
        # the API26 branch actually reported at this line.
        line_text = next((line.split(': ', 1)[1] for line in excerpt.splitlines()
                          if line.startswith(str(main.get('line', 0)) + ': ')), '')
        expression = re.search(r'SDK_INT\s*(?:>=|>|<=|<|==)\s*(?:([0-9]+)|(?:[\w$]+\.)*VERSION_CODES\.([A-Z_][A-Z_0-9]*))', line_text)
        if expression:
            known = {'LOLLIPOP':21, 'LOLLIPOP_MR1':22, 'M':23, 'N':24, 'N_MR1':25,
                     'O':26, 'O_MR1':27, 'P':28, 'Q':29, 'R':30, 'S':31, 'S_V2':32,
                     'TIRAMISU':33, 'UPSIDE_DOWN_CAKE':34, 'VANILLA_ICE_CREAM':35}
            api = int(expression[1]) if expression[1] else known.get(expression[2], 999)
            if name == 'VirtualScreenCore.java' and api == 30:
                before_line = (ROOT / file).read_text(encoding='utf-8').splitlines()[:max(0, main['line'] - 1)]
                prior_gate = any('SDK_INT < 30' in line for line in before_line)
                if prior_gate:
                    return result('redundant_guard_after_prior_api_exit',
                                  '同一个main入口前面已在API<30时抛出并退出；这处第二次检查在两个flavor都属可清理冗余。保留它不放宽执行范围，也不是Low/API23虚拟屏可运行证明。',
                                  basis=witnesses(file, [r'SDK_INT < 30', r'API_30_REQUIRED']))
            if flavor == 'low' and api == 30 and name in {'DshaAccessibilityService.java', 'VirtualScreenCore.java'}:
                return result('runtime_guard_under_higher_api_annotation',
                              'Lint按@TargetApi/@RequiresApi契约假定API30，但实际运行入口仍显式拒绝API<30；截图在takeScreenshot之前返回，独立core在系统初始化之前退出。该防御不能因注解假定而移除，不代表旧设备功能验收。',
                              basis=witnesses(file, [r'TargetApi\(30\)', r'RequiresApi\(30\)', r'SDK_INT < 30', r'takeScreenshot\(', r'API_30_REQUIRED']))
            if flavor == 'standard' and '/main/' in file and 23 < api <= 30:
                return result('necessary_dual_flavor_api_guard',
                              f'该公共 main 源在 Standard(min30) 中冗余，但 Low(min23) 仍需 API {api} 分支；保留同一生产实现的版本判断，不代表已验旧设备。')
            if api <= 23:
                return result('defensive_guard_below_supported_min',
                              f'API {api} 判断对两个当前 minSdk 均冗余，是保留的防御/兼容代码与清理机会；不是证明低于 API23 支持或必须保留。')
        annotation = re.search(r'@(?:[\w$]+\.)*(RequiresApi|TargetApi)\(([0-9]+)\)', line_text)
        if annotation and '/main/' in file:
            return result('shared_member_api_annotation_contract',
                          f'公共main成员声明API{annotation[2]}契约；Standard min30中冗余，Low min23仍需要明确较新成员/平台回调要求。TargetApi只给局部Lint信息，RequiresApi才传播要求；注解本身不替代调用guard与旧设备证据。',
                          basis=witnesses(file, [r'RequiresApi', r'TargetApi', r'SDK_INT']))
        if name == 'AndroidManifest.xml' and 'tools:targetApi="30"' in line_text:
            return result('shared_manifest_new_permission_annotation',
                          '公共manifest为Android11 MANAGE_EXTERNAL_STORAGE注明tools:targetApi=30；Standard冗余，Low仍面向旧平台，实际授权入口有SDK分支。此工具注解不授予权限或提高minSdk，保留清楚的声明与实际权限边界。',
                          basis=witnesses('app/src/main/java/com/deepseekharness/app/ui/DeviceGrantsFragment.java', [r'openAllFilesAccess', r'VERSION_CODES.R', r'MANAGE_ALL_FILES', r'WRITE_EXTERNAL_STORAGE']))
        if '/main/res/' in file and re.search(r'-v(26|28)$', file):
            return result('necessary_shared_resource_api_qualifier',
                          '该v26/v28资源位于两个flavor共用main；Standard min30中冗余，Low min23仍按平台选择旧资源与新资源。合到无版本目录会改变Low资源选择，不能仅为Standard警告合并。真实OEM/旧系统资源呈现仍未验证。')
        if '/main/' in file:
            return result('guard_requires_owner_review',
                          'Lint 判断了当前 flavor 的常量条件；此处不能从一个 excerpt 推定另一 flavor 需要。完整 source/调用者条件须复核。', confidence='uncertain')
    if identity == 'InlinedApi':
        if name == 'OverlayController.java' and 'BREAK_STRATEGY_SIMPLE' in message:
            proof_path = AUDIT / 'overlay-break-strategy-proof/receipt.json'
            if proof_path.is_file():
                proof = json.loads(proof_path.read_text(encoding='utf-8'))
                source = ROOT / file
                if (source.is_file() and digest(source) == proof.get('sourceSha256')
                        and proof.get('constantValue') == 0
                        and proof.get('dex', {}).get('minApi') == 23
                        and proof.get('dex', {}).get('lineBreakerReferences') == 0):
                    return result('verified_inlined_compatibility_constant',
                                  'SDK37 IntDef 接受 LineBreaker 常量且 ConstantValue=0；JDK17 实参 iconst_0，SDK D8 minAPI23 DEX 无 LineBreaker 引用。两个目标方法从API23起存在。此证明绑定当前Overlay SHA；没有API23真实渲染证据。',
                                  confidence='sdk_and_bytecode_proof',
                                  basis=[{'file': proof_path.relative_to(ROOT).as_posix(), 'sha256': digest(proof_path)}])
        if name == 'DshaDocumentsProvider.java' and any(key in message for key in ('QUERY_ARG_DISPLAY_NAME', 'EXTRA_HONORED_ARGS')):
            return result('inlined_protocol_constant_choice',
                          '此项是 Bundle/Provider 协议的编译期 String 常量，不是调用缺失方法；新 Bundle override 的平台进入点与旧入口分开。旧 SAF/OEM 的实际行为仍未验证。', basis=witnesses(file, [r'queryChildDocuments', r'querySearchDocuments', r'QUERY_ARG_DISPLAY_NAME', r'EXTRA_HONORED_ARGS']))
        return result('api_guard_requires_owner_review',
                      '新 API 常量出现于实际 Low 报告；不能仅凭“常量可内联”证明对应行为受 guard 保护。须核对完整调用路径。', confidence='uncertain')
    if identity == 'RequiresFeature' and name == 'RecoveryWebSurface.java':
        if 'WEB_MESSAGE_LISTENER' in message:
            return result('source_guarded_webview_feature',
                          'installLanguageListener 在 view/origin 校验后以 WEB_MESSAGE_LISTENER guard 返回；detach 也直接判 feature。Lint 未消除复合早返回条件。无真实旧 provider 矩阵。', basis=witnesses(file, [r'WEB_MESSAGE_LISTENER', r'installLanguageListener', r'removeWebMessageListener', r'addWebMessageListener']))
        if 'DOCUMENT_START_SCRIPT' in message:
            return result('source_guarded_resource_lifetime',
                          'script 仅在 DOCUMENT_START_SCRIPT 支持时由 addDocumentStartJavaScript 创建；close 只对非空既有 handle remove。创建与释放跨方法使 Lint 无法推导；provider 回收时序未真机验证。', basis=witnesses(file, [r'DOCUMENT_START_SCRIPT', r'addDocumentStartJavaScript', r'script != null', r'script.remove']))
    if identity == 'RequiresFeature':
        return result('feature_guard_requires_owner_review',
                      '必须逐调用核对 feature 创建/使用/释放关系；未找到本轮明确跨方法证明，不能笼统接受。', confidence='uncertain')
    if identity == 'WebViewApiAvailability' and name in {'DiagnosticRepository.java', 'WebPreviewActivity.java'}:
        return result('guarded_provider_diagnostic_choice',
                      '调用用于识别当前 provider；公共源有 API26 判断，Low API23优先 Gecko/实际页面能力检查。Compat 查询可扩大诊断覆盖，是改进机会；不证明旧 provider 实际通过。', basis=witnesses(file, [r'getCurrentWebViewPackage', r'SDK_INT', r'getDefaultUserAgent']))
    if identity == 'DefaultLocale':
        return result('locale_invariant_dependency_needs_review',
                      '该 casefold 依赖进程默认 Locale，内部协议/应用搜索通常应显式 Locale.ROOT。本轮 DshaApp 在服务前将默认 Locale 解析为 zh/en，减少当前触发面；仍是明确的隐式依赖与后续 caller 风险，不能称无问题。', confidence='bounded_source_context', basis=witnesses('app/src/main/java/com/deepseekharness/app/DshaApp.java', [r'SystemLanguage.initialize', r'LanguageController.apply']) + witnesses('app/src/main/java/com/deepseekharness/app/ui/LanguageController.java', [r'Locale.setDefault']))
    if identity == 'ApplySharedPref':
        return result('synchronous_durability_ack_choice',
                      'commit 返回持久化结果，配置/计划/维护回执不能以异步 apply 已发起冒充已保存。该具体调用保留源码上下文；UI线程写入仍有延迟风险，未做设备I/O/ANR量测。')
    if identity == 'PrivateApi' and name == 'PrivilegedPackageContext.java':
        return result('platform_hidden_api_unverified',
                      'app_process 特权入口的 PackageContext/ActivityThread 适配需要非公开接口；编译/本机夹具不能证明未来 Android/OEM hidden-API 策略。当前保留实验能力，但平台兼容风险真实存在。', basis=witnesses(file, [r'ActivityThread', r'systemMain', r'getDeclaredMethod', r'createPackageContext']))
    if identity == 'CustomX509TrustManager' and name == 'TrustedNetwork.java':
        return result('custom_trust_source_checked_platform_unverified',
                      '组合 manager 逐一调用系统/随包CA checkServerTrusted，全部失败继续抛 CertificateException；不是空实现或 trust-all。调用者未替换默认主机名验证。旧 Android trust-chain/所有外部服务矩阵尚未覆盖。', basis=witnesses(file, [r'system.init', r'bundled.init', r'manager.checkServerTrusted', r'throw last']) + witnesses('app/src/main/java/com/deepseekharness/app/core/UpdateEngine.java', [r'setSSLSocketFactory', r'HostnameVerifier']))
    if identity == 'InsecureBaseConfiguration':
        return result('explicit_cleartext_lan_contract',
                      '应用本机DSH及用户主动LAN功能需要HTTP，base cleartext保持开放；官方更新/下载域名单独deny HTTP。该边界不能保护所有第三方HTTP，LAN流量确实未加密，界面已明确说明。', basis=witnesses(file, [r'cleartextTrafficPermitted', r'dsha.cc', r'github.com']))
    if identity in {'ScopedStorage', 'BatteryLife'}:
        return result('user_authorized_capability_distribution_choice',
                      '当前独立APK的文件/后台能力由用户显式授权，不能把Google Play政策提示当作已获得Play资格或可免系统权限。商店分发资格/OEM后台效果未由本轮软件测试证明。')
    if identity == 'AppBundleLocaleChanges':
        return result('apk_distribution_choice_aab_unverified',
                      '当前交付两份完整APK且动态切换 zh/en；没有交付AAB语言分包。以后若使用App Bundle须关闭语言拆分或实现下载，当前缺此AAB配置不能写成已支持AAB。', basis=witnesses('app/build.gradle', [r'productFlavors', r'versionNameSuffix', r'bundle']))
    if identity == 'Aligned16KB':
        proof_path = AUDIT / 'final-termux-alignment-evidence.json'
        if proof_path.is_file():
            proof = json.loads(proof_path.read_text(encoding='utf-8'))
            selected = next((item for item in proof.get('apks', []) if item.get('flavor') == flavor), None)
            if (proof.get('status') == 'PASS_CURRENT_APK_TERMUX_STATIC_ALIGNMENT' and selected
                    and selected.get('pLoadAlignmentMinimum', 0) >= 16384
                    and selected.get('equalsCurrentOwnedJni') is True
                    and all(item.get('fullyMapped') for item in selected.get('relroPageMappings', []))):
                return result('final_apk_alignment_verified_device_missing',
                              'Lint指出上游依赖缓存libtermux.so；实际本轮该flavor APK的同名成员已按整APK摘要读取，字节等于当前自有JNI。PT_LOAD最小对齐16KiB、offset/address同余，RELRO在4KiB/16KiB页映射均完整；物理16KiB内核加载仍未验证，不能扩写成设备兼容通过。',
                              confidence='actual_apk_elf_metadata',
                              basis=[{'file': proof_path.relative_to(ROOT).as_posix(), 'sha256': digest(proof_path),
                                      'apk': selected['apk'], 'apkSha256': selected['apkSha256'],
                                      'member': selected['member'], 'memberSha256': selected['memberSha256']}])
        return result('artifact_and_device_alignment_evidence_required',
                      'Lint指向依赖缓存的 libtermux.so，app还含自行重编的同名JNI且pickFirst；缓存成员不等同最终APK选择。必须看本轮最终APK该entry的PT_LOAD/RELRO和实际16KiB设备证据，不能凭声明消除此风险。', basis=witnesses('app/build.gradle', [r'libtermux.so', r'pickFirsts']))
    if identity == 'SetJavaScriptEnabled':
        return result('required_web_application_feature',
                      'DSH与试运行/应急网页需执行JS；具体surface仍限制来源/导航/音频/能力代次。开放同源插件不构成恶意脚本隔离，XSS/同UID权限影响仍是真实信任边界。', basis=witnesses(file, [r'setJavaScriptEnabled', r'sameOrigin', r'sameService', r'shouldOverrideUrlLoading']))
    if identity == 'SdCardPath':
        if name == 'RuntimeDataTransfers.java':
            return result('framework_bound_storage_alias_contract',
                          '实际storage来自Environment.getExternalStorageDirectory并canonical；/sdcard与/storage/self/primary只是显式输入别名映射到该授权根。传输lease还复核private/rootfs inode与数据映射，不能把旧别名直接当新的物理根；FUSE/多用户仍需设备证据。',
                          basis=witnesses(file, [r'Environment.getExternalStorageDirectory', r'getCanonicalFile', r'aliases.add', r'TRANSFER_DOMAIN_CHANGED']))
        if name == 'HttpShellService.java':
            return result('share_storage_path_boundary_contract',
                          '该/sdcard字面量用于外部分享的边界识别，另接受canonical /storage/emulated/<user>/路径；随后凭据拒绝、源对象验证、私有缓存快照和content URI授权。它不是用硬编码位置创建数据根。厂商存储别名与真实chooser仍未验证。',
                          basis=witnesses(file, [r'startsWithPath\(canon', r'exportDeniedByCanonical', r'BridgeFileSnapshot', r'FileProvider', r'FLAG_GRANT_READ_URI_PERMISSION']))
        if name in {'DeviceShellPolicy.java', 'GuestDataResolver.java', 'ContainerRuntime.java', 'ProotBootstrap.java', 'RuntimeLauncher.java', 'RuntimeExecution.java'}:
            return result('guest_path_or_denial_boundary_contract',
                          '该路径属于guest别名/挂载或拒绝访问规则，不能替换成宿主getFilesDir而改变命名空间。源码保留精确映射；实际Android FUSE/权限路径仍另需平台验证。')
        return result('hardcoded_path_requires_owner_review',
                      '本条路径可能是设备命令协议或宿主文件位置；只凭字符串不能接受，需要对应映射/权限入口依据。', confidence='uncertain')
    if identity == 'UnusedAttribute':
        return result('older_platform_ignored_attribute',
                      'manifest/服务声明面向支持该API的系统，旧平台会忽略相应属性；该属性不是缺失方法调用。实际PiP、截图、网络策略、返回手势仍必须按API guard及设备证据声明。')
    if identity == 'UseRequiresApi':
        return result('api_annotation_propagation_improvement',
                      'TargetApi只能抑制局部警告，RequiresApi可传播调用要求；这是仍有价值的注释/检查改进。当前调用点是否guard需看引用证据，本条不自动证明旧Android可执行。', confidence='bounded_source_context', basis=witnesses(file, [r'TargetApi', r'SDK_INT', r'uiScreenshot', r'getScreenshot', r'media\(']))
    if identity == 'UseCompatTextViewDrawableXml':
        return result('supported_api_drawable_choice_platform_unverified',
                      'Low min23已有平台向量/相对drawable属性，当前是平台drawable选择而非min14支持。AppCompat的着色/旧OEM差异仍是改进与设备验证范围，未视觉验收。')
    if identity == 'ClickableViewAccessibility' and name == 'DshaSelectView.java':
        return result('custom_spinner_click_contract_platform_unverified',
                      '父Spinner performClick会再打开系统popup；定制入口发点击/选择无障碍事件并显示唯一底部菜单。不能机械调用父方法打开两菜单，也不能据源码宣称TalkBack键盘全部通过。', basis=witnesses(file, [r'performClick', r'TYPE_VIEW_CLICKED', r'TYPE_VIEW_SELECTED', r'setStateDescription']))
    if identity == 'ClickableViewAccessibility':
        return result('accessibility_requires_owner_review',
                      '实际156源码仍有自定义touch/click警告；需要验证performClick、真实点击动作和拖动取消，不能沿用旧整改摘要。', confidence='uncertain')
    if identity in {'HardcodedText', 'LabelFor'}:
        return result('current_ui_residual_requires_source_review',
                      '本轮实际XML仍报文字/标签问题，须核对具体资源、输入hint与labelFor关系；不能按155已修摘要关闭。', True, confidence='lint_current_source')
    if identity == 'Autofill':
        line_text = next((line.split(': ', 1)[1] for line in excerpt.splitlines()
                          if line.startswith(str(main.get('line', 0)) + ': ')), '')
        if 'importantForAutofill="no"' in line_text:
            return result('sensitive_input_autofill_choice',
                          '该实际配对码EditText明确importantForAutofill=no与saveEnabled=false；不为满足hint建议把一次性凭据暴露给自动填充。真实旋转/系统服务/OEM行为仍未验证。')
        roles = {'activity_adb_pair.xml': '连接地址（textUri，saveEnabled=false但未关闭autofill）',
                 'activity_diagnostics.xml': '用户填写的复现步骤（textMultiLine）',
                 'fragment_terminal.xml': '用户原文shell命令（actionSend/text）',
                 'plugin_list_header.xml': '插件搜索词（text/actionDone）'}
        role = roles.get(name, '当前实际输入项')
        if name == 'activity_adb_pair.xml' and 'adb_pair_port' in line_text:
            role = '连接端口（number，saveEnabled=false但未关闭autofill）'
        return result('autofill_semantics_requires_review',
                      f'这是{role}，当前XML没有autofillHints，也未从运行源码找到统一禁用。需明确其填充语义或显式选择退出后再判断；缺hint本身不证明发生错误/泄露，当前也不能称已验证可接受。', confidence='uncertain')
    if identity == 'RtlHardcoded' and name == 'VirtualScreenOverlayController.java':
        return result('physical_screen_coordinate_choice',
                      'overlay params.x/rawX采用物理左边原点；改成逻辑START却不改拖动坐标会镜像/错位。当前保留物理坐标，非LTR/不同方向实际效果未本轮验证。', basis=witnesses(file, [r'Gravity.TOP', r'params.x', r'getRawX']))
    if identity == 'RtlHardcoded':
        return result('rtl_literal_requires_source_review',
                      '该当前布局/重力仍使用物理左右，若不是明确设备坐标应改相对方向。不能以只支持zh/en免除源码审查。', confidence='uncertain')
    if identity == 'RtlSymmetry':
        return result('layout_spacing_choice_visual_evidence_missing',
                      '此处相对padding只设置一侧，可能为图标/尾部箭头保留空间；当前两种应用语言均LTR，但短屏/大字体/RTL外文内容未实测。不是全部布局对称或可访问性的证明。')
    if identity in {'IconLauncherShape', 'MonochromeLauncherIcon', 'IconDuplicates'}:
        same = identity == 'IconDuplicates' and len(evidence) > 1 and len({e.get('sha256') for e in evidence}) == 1
        return result('branding_asset_choice_platform_unverified',
                      ('实际多个密度内legacy/round/v137入口文件字节相同，保留历史资源身份；不是性能/内存故障结论。' if same else '该图标形状/monochrome属于实际品牌资源选择，不能宣称符合所有launcher或Android13主题图标期望。') + '最终launcher裁剪/主题呈现无真机证据。')
    if identity == 'StaticFieldLeak':
        if name == 'WebPreviewActivity.java':
            return result('retained_view_lifetime_platform_unverified',
                          'Retained ViewModel特意保留WebView与页面，但使用可换baseContext/弱owner并在onCleared destroy，不能只靠弱owner证明所有callback引用释放。需以完整detach/客户端替换和实际旋转/heap证据判断。', basis=witnesses(file, [r'MutableContextWrapper', r'WeakReference', r'onCleared', r'setBaseContext', r'setWebChromeClient', r'setWebViewClient']))
        if name == 'OverlayController.java':
            return result('application_overlay_lifetime_platform_unverified',
                          '应用级overlay使用application/window context并在teardown移除WindowManager视图、callback与静态字段；活动期间持有UI是功能所需。真实OEM异常/长时heap释放未证明。', basis=witnesses(file, [r'getApplicationContext', r'teardown', r'windowContext = null', r'root = null', r'removeView']))
        if name == 'PrivilegedPackageContext.java':
            return result('privileged_process_system_context_contract',
                          '字段缓存的是独立 Root/Shizuku app_process 中 ActivityThread.systemMain/getSystemContext 创建的 SystemContext，不是 Activity。10秒有界初始化且本进程只初始化一次；隐藏API/OEM与进程结束后资源行为仍需真实设备证据。',
                          basis=witnesses(file, [r'INIT_TIMEOUT_MS', r'systemMain', r'getSystemContext', r'systemContext =']))
        if name == 'VirtualScreenCore.java':
            return result('privileged_vscreen_process_context_contract',
                          '这是独立 app_process 核心，main 从 PrivilegedPackageContext 的 SystemContext 创建 com.android.shell 包上下文；不是应用页面持有 Activity。当前 singleton 请求/会话按该独立进程生命周期管理；真实厂商隐藏API、长时资源释放仍未验证。',
                          basis=witnesses(file, [r'public static void main', r'PrivilegedPackageContext.systemContext', r'createPackageContext', r'server.close', r'session.close', r'CLIENTS.shutdown']))
        if name.startswith('VirtualScreen'):
            return result('vscreen_owner_lifetime_requires_current_review',
                          'root本轮正在把虚拟屏静态状态移至AppOwner；只有当前156 XML/源码能判断剩余引用。即使实例化，活动view/bitmap/callback仍需release与真实heap生命周期证据。', confidence='uncertain', basis=witnesses(file, [r'ApplicationOwner', r'getApplicationContext', r'detach', r'close\(', r'clear\(', r' = null']))
        path = ROOT / file
        if path.is_file() and 'getApplicationContext' in path.read_text(encoding='utf-8'):
            return result('application_context_lifetime_contract',
                          '该类归一到Application context，其singleton/owner lifetime不等同Activity泄漏；仍保留本条具体字段/构造证据，不能据此覆盖未知callback引用或平台内存试验。', basis=witnesses(file, [r'getApplicationContext', r'ApplicationOwner', r'WeakReference', r'onCleared']))
        return result('context_retention_requires_owner_review',
                      '未从当前源证明静态字段只持Application或按活动生命周期释放，需owner逐引用审查。', confidence='uncertain')
    if identity == 'SetTextI18n':
        if name == 'VirtualScreenActivity.java' and all(token in excerpt for token in (
                'optString("package")', 'optInt("width")', 'optInt("height")', 'manager.channel()')):
            return result('raw_preview_metadata_display_contract',
                          '该行仅展示实际package、width×height与通道原标识，分隔符为固定“ · ”/“×”；没有应用句子或标签片段。包名/协议标识与数字保留原文，不为消除警告引入自动翻译。实际短屏/大字体呈现仍未验证。',
                          basis=witnesses(file, [r'optString\("package"\)', r'optInt\("width"\)', r'optInt\("height"\)', r'manager.channel\(']))
        if 'String.valueOf' in excerpt or '.optString(' in excerpt or '.getString(' in excerpt or 'UiText.format(' in excerpt or 'UiStateText.render(' in excerpt:
            return result('formatted_or_raw_content_boundary_needs_review',
                          '本处包含显式整句模板或原始数据字段；需区分应用标签与用户/版本/协议正文。保留当前表达式/参数证据，不自动翻译原文，也不据function名直接宣布警告无问题。', confidence='bounded_source_context')
        return result('text_composition_requires_owner_review',
                      '当前setText仍报拼接或literal；需要核对完整模板/复数与原文边界。此脚本不会把ASCII符号/数据行和应用句子同批机械改写。', confidence='uncertain')
    if identity == 'NotifyDataSetChanged':
        return result('list_update_performance_evidence_missing',
                      '完整adapter刷新仍有粒度改进机会；当前调用可对应列表排序/筛选/整体替换。缓存diff减少重绑不等于测过所有大列表性能，保持本条实际caller。')
    if identity == 'UsableSpace':
        return result('conservative_disk_bound_platform_unverified',
                      'getUsableSpace用于实际可用字节/事务余量预检，allocatable包含可回收数据且API26+并不能证明SAF/provider空间。当前偏保守可能拒绝可完成操作；低空间/FUSE/Provider实际容量仍未验证。')
    if identity == 'InflateParams' and name == 'AdbPairActivity.java':
        return result('activity_root_inflation_choice',
                      'activity_adb_pair的完整根由setContentView安装，不是把某child塞进未知父容器；根LayoutParams不继承不会证明所有OEM measure正确，保留布局/短屏验证范围。', basis=witnesses(file, [r'buildUi', r'setContentView', r'activity_adb_pair']))
    if identity in {'MergeRootFrame', 'DisableBaselineAlignment', 'NestedWeights', 'Overdraw', 'UselessParent', 'TooManyViews'}:
        return result('layout_complexity_performance_unmeasured',
                      '此实际布局有层级/测量/背景性能改进空间；模块字段与短屏滚动需要保留，不能批量merge/删背景换警告数字。当前无设备GPU/帧耗时/大字体全视图证据。')
    if identity == 'AndroidGradlePluginVersion':
        return result('pinned_toolchain_choice',
                      '本轮构建按已锁定AGP9.1.1/Gradle9.3.1/SDK37，版本更新建议不是当前代码错误。升级须以完整工具链验证为依据，未声称最新版本全部验证。', basis=witnesses('ci/toolchain.lock.json', [r'agp', r'gradle', r'android']))
    return result('unclassified_requires_owner_review',
                  '没有足够的本轮位置/源码/平台依据，保留不确定并要求对应owner复核；不算自动接受。', confidence='uncertain')


def source_floor():
    paths = [ROOT / 'app/build.gradle', ROOT / 'tools/i18n/messages.json']
    for source_set in ('main', 'standard', 'low'):
        for folder in ('java', 'res'):
            paths += [p for p in (ROOT / 'app/src' / source_set / folder).rglob('*') if p.is_file()]
        manifest = ROOT / 'app/src' / source_set / 'AndroidManifest.xml'
        if manifest.is_file(): paths.append(manifest)
    newest = max(paths, key=lambda p: p.stat().st_mtime_ns)
    return newest, newest.stat().st_mtime_ns


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=AUDIT/'release-lint-classification.json')
    parser.add_argument('--write-pending', action='store_true', help='write only freshness metadata if reports are still stale')
    parser.add_argument('--check', action='store_true', help='validate freshness/classification without writing final output')
    args = parser.parse_args()
    output = args.output.resolve()
    output.relative_to(AUDIT)
    gradle = (ROOT/'app/build.gradle').read_text(encoding='utf-8')
    if not re.search(r'\bversionCode\s+156\b', gradle):raise SystemExit('LINT_CLASSIFICATION_WRONG_BUILD')
    floor, floor_ns = source_floor()
    freshness, reports, stale = [], [], []
    for flavor in ('standard','low'):
        path = ROOT/f'app/build/reports/lint-results-{flavor}Release.xml'
        stat = path.stat()
        fresh = stat.st_mtime_ns >= floor_ns
        freshness.append({'flavor':flavor,'path':path.relative_to(ROOT).as_posix(),'sha256':digest(path),'mtimeUtc':stamp(stat.st_mtime),'freshRelativeToProductionInputs':fresh})
        if not fresh:stale.append(flavor)
    if stale:
        pending={'schema':1,'status':'WAITING_FOR_FRESH_BUILD156_REPORTS','build':156,'sourceFloor':{'path':floor.relative_to(ROOT).as_posix(),'mtimeUtc':stamp(floor_ns/1e9)},'reportFreshness':freshness,'finalCounts':None,'note':'The existing reports may be build155; their counts are not this build156 final classification.'}
        if args.write_pending:
            (AUDIT/'release-lint-classification-pending.json').write_text(json.dumps(pending,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
        print('WAITING_FOR_FRESH_BUILD156_REPORTS: '+','.join(stale))
        return 2
    for report in freshness:
        path = ROOT/report['path']
        root = ET.parse(path).getroot()
        issues=[]
        for index, item in enumerate(root.findall('issue')):
            row={'issueIndex':index,'id':item.get('id'),'severity':item.get('severity'),'message':item.get('message'),'locations':[dict(loc.attrib) for loc in item.findall('location')]}
            evidence=[source_evidence(loc) for loc in row['locations']]
            row.update(classify(row,evidence,report['flavor']))
            row['sourceEvidence']=evidence
            issues.append(row)
        reports.append({**report,'lintAttributes':dict(root.attrib),'issues':issues})
    summary={report['flavor']:{'warnings':sum(row['severity'].lower()=='warning' for row in report['issues']),'errors':sum(row['severity'].lower()=='error' for row in report['issues']),'fatal':sum(row['severity'].lower()=='fatal' for row in report['issues']),'allIssues':len(report['issues']),'classificationCounts':dict(collections.Counter(row['classification'] for row in report['issues']))} for report in reports}
    document={'schema':1,'status':'CLASSIFIED_CURRENT','build':156,'indexBase':0,'sourceFloor':{'path':floor.relative_to(ROOT).as_posix(),'mtimeUtc':stamp(floor_ns/1e9)},'reports':reports,'summary':summary,'softwareActionRequired':[{'flavor':report['flavor'],'issueIndex':row['issueIndex'],'id':row['id'],'message':row['message']} for report in reports for row in report['issues'] if row['softwareActionRequired']],'unknownReviewRequired':[{'flavor':report['flavor'],'issueIndex':row['issueIndex'],'id':row['id'],'message':row['message']} for report in reports for row in report['issues'] if row['confidence']=='uncertain'],'scope':'Current fresh Release XML and current source read only; no Android/OEM/heap/renderer/16KiB device result or source suppression implied.'}
    if not args.check:
        output.write_text(json.dumps(document,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
    print(json.dumps({'status':document['status'],'summary':summary,'softwareActionRequired':len(document['softwareActionRequired']),'unknownReviewRequired':len(document['unknownReviewRequired'])},ensure_ascii=True))
    return 0


if __name__=='__main__':
    raise SystemExit(main())
