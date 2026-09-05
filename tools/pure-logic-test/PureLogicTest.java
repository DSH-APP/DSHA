package com.deepseekharness.app;

/**
 * 无 Android 依赖的纯逻辑断言集（LanAuth + AssetPath）。用 javac 直接编译运行，
 * 不需要设备、SDK、网络：
 *
 * <pre>bash tools/pure-logic-test.sh</pre>
 *
 * 每条用例都对应一个真实踩过的坑，不是为了凑覆盖率 —— 这些手写的字符串切分出错时
 * 症状都隔着一层（400 / 一直转圈 / 静默泄漏 / 文件落到别处），只靠读代码看不出来。
 */
public final class PureLogicTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        // ---------- SensitiveData ----------
        // dsh BrowserAuth uses base64url, so both '-' and '_' must be covered;
        // diagnostics must not retain any credential suffix after redaction.
        String sensitiveToken = "Abc-def_01234567890123456789012345678901234";
        String sensitive = "http://127.0.0.1:3080/?token=" + sensitiveToken
                + " Cookie: dsh-auth-test=backend-secret"
                + " dsha_lan=lan-secret X-Dsha-Token: header-secret"
                + " DEEPSEEK_API_KEY=api-secret";
        String redacted = SensitiveData.redact(sensitive);
        ok("redact: URL token 完整移除（含 base64url -/_）",
                !redacted.contains(sensitiveToken)
                        && redacted.contains("?token=<redacted>"));
        ok("redact: Cookie、LAN token、header、API key 不泄漏",
                !redacted.contains("backend-secret")
                        && !redacted.contains("lan-secret")
                        && !redacted.contains("header-secret")
                        && !redacted.contains("api-secret"));
        String legacyCookies = "Cookie: dsha_t=legacy-secret; dsha_token=other-secret";
        ok("redact: 旧 Cookie 凭据也不泄漏",
                !SensitiveData.redact(legacyCookies).contains("legacy-secret")
                        && !SensitiveData.redact(legacyCookies).contains("other-secret"));
        String mixedSensitive = "https://host/?ToKeN=query-secret&API_Key=query-key\r\n"
                + "sEt-CoOkIe: dsh-auth-web=cookie-secret; HttpOnly\r\n"
                + "AuThOrIzAtIoN: BeArEr auth-secret\r\n"
                + "X-ToKeN: bridge-secret\r\n"
                + "Exception: Bearer bare-secret token: text-secret";
        String mixedRedacted = SensitiveData.redact(mixedSensitive);
        ok("redact: query、混合大小写 header、异常文本均脱敏",
                !mixedRedacted.contains("query-secret")
                        && !mixedRedacted.contains("query-key")
                        && !mixedRedacted.contains("cookie-secret")
                        && !mixedRedacted.contains("auth-secret")
                        && !mixedRedacted.contains("bridge-secret")
                        && !mixedRedacted.contains("bare-secret")
                        && !mixedRedacted.contains("text-secret"));
        String cookieEquals = "cOoKiE=dsh-auth-web=equals-secret; path=/\n"
                + "sEt-CoOkIe=dsh-auth-web=equals-set-cookie; HttpOnly";
        String cookieEqualsRedacted = SensitiveData.redact(cookieEquals);
        ok("redact: Cookie/Set-Cookie 等号形式也脱敏",
                !cookieEqualsRedacted.contains("equals-secret")
                        && !cookieEqualsRedacted.contains("equals-set-cookie"));
        String bareDshCookie = "dsh-auth-standalone=standalone-secret; Path=/";
        ok("redact: 独立 dsh-auth Cookie 行也脱敏",
                !SensitiveData.redact(bareDshCookie).contains("standalone-secret"));
        SensitiveData.Stream redactor = new SensitiveData.Stream();
        String splitPrefix = "dsh web: http://127.0.0.1:3080/?to";
        String splitSuffix = "ken=" + sensitiveToken + "\n";
        String splitRedacted = redactor.accept(splitPrefix)
                + redactor.accept(splitSuffix) + redactor.finish();
        ok("redact: 凭据跨输出块仍完整移除",
                !splitRedacted.contains(sensitiveToken)
                        && splitRedacted.contains("?token=<redacted>"));
        String jsonSensitive = "{\"ToKeN\":\"json-token\","
                + "\"api_key\": \"json-api\","
                + "\"Authorization\":\"Bearer json-bearer\","
                + "\"Set-Cookie\":\"dsh-auth-json=json-cookie; HttpOnly\"}";
        String jsonRedacted = SensitiveData.redact(jsonSensitive);
        ok("redact: JSON 引号字段和混合大小写字段均脱敏",
                !jsonRedacted.contains("json-token")
                        && !jsonRedacted.contains("json-api")
                        && !jsonRedacted.contains("json-bearer")
                        && !jsonRedacted.contains("json-cookie")
                        && jsonRedacted.contains("\"ToKeN\":\"<redacted>\""));

        // ---------- stripTokenFromRequestLine ----------
        // 回归：token 是唯一参数时，原实现把 HTTP 版本一起吃掉（→ "GET /"），
        // 后端 Node parser 当畸形请求直接 400。这是最常见的场景：打开首页。
        eq("strip: 唯一参数", "GET / HTTP/1.1",
                LanAuth.stripTokenFromRequestLine("GET /?token=abc123 HTTP/1.1"));
        eq("strip: 参数在后", "GET /a?x=1 HTTP/1.1",
                LanAuth.stripTokenFromRequestLine("GET /a?x=1&token=abc HTTP/1.1"));
        eq("strip: 参数在前", "GET /a?x=1 HTTP/1.1",
                LanAuth.stripTokenFromRequestLine("GET /a?token=abc&x=1 HTTP/1.1"));
        eq("strip: 参数在中", "GET /a?x=1&y=2 HTTP/1.1",
                LanAuth.stripTokenFromRequestLine("GET /a?x=1&token=abc&y=2 HTTP/1.1"));
        // 回归：[&]?token= 没有参数名边界，csrf_token / api_token 会被连带剥掉
        eq("strip: 不误删同后缀参数", "GET /a?csrf_token=z HTTP/1.1",
                LanAuth.stripTokenFromRequestLine("GET /a?csrf_token=z HTTP/1.1"));
        eq("strip: 无 query 原样返回", "GET /a HTTP/1.1",
                LanAuth.stripTokenFromRequestLine("GET /a HTTP/1.1"));
        eq("strip: 保留 fragment", "GET /a#frag HTTP/1.1",
                LanAuth.stripTokenFromRequestLine("GET /a?token=t#frag HTTP/1.1"));
        eq("strip: POST 同样处理", "POST /api/x HTTP/1.1",
                LanAuth.stripTokenFromRequestLine("POST /api/x?token=t HTTP/1.1"));
        eq("strip: 只有方法和目标（无版本）", "GET /a",
                LanAuth.stripTokenFromRequestLine("GET /a?token=t"));

        // ---------- queryToken ----------
        eq("query: 正常取值", "abc123", LanAuth.queryToken("GET /?token=abc123 HTTP/1.1"));
        // 回归：原来是整头 indexOf("token=")，xtoken= 的尾部也会命中并取到错误值
        eq("query: 不被同后缀参数误命中", null, LanAuth.queryToken("GET /?xtoken=bad HTTP/1.1"));
        eq("query: 无 query", null, LanAuth.queryToken("GET /a HTTP/1.1"));
        eq("query: 忽略 fragment", "t", LanAuth.queryToken("GET /a?token=t#x HTTP/1.1"));

        // ---------- tokenOk ----------
        final String T = "0123456789abcdef";
        // 回归：原来空 token 直接 return true（放行）。桥监听 0.0.0.0，
        // 放行等于同 WiFi 任意设备都能操作 dsh（agent 可执行 bash）。
        eqi("auth: 空 token 一律拒绝", LanAuth.AUTH_DENY,
                LanAuth.tokenOk(req("GET /?token=" + T, ""), ""));
        eqi("auth: query 命中 → 需回设 Cookie", LanAuth.AUTH_OK_SET_COOKIE,
                LanAuth.tokenOk(req("GET /?token=" + T, ""), T));
        eqi("auth: Cookie 命中", LanAuth.AUTH_OK,
                LanAuth.tokenOk(req("GET /", "Cookie: dsha_token=" + T), T));
        eqi("auth: Cookie 与后端 dsha_t 共存", LanAuth.AUTH_OK,
                LanAuth.tokenOk(req("GET /", "Cookie: dsha_t=zzz; dsha_token=" + T), T));
        eqi("auth: 显式头命中", LanAuth.AUTH_OK,
                LanAuth.tokenOk(req("GET /", "X-DSHA-Token: " + T), T));
        // 回归：这是原实现「能用」的真正原因，也是 token 泄漏面 ——
        // 用户在 WebUI 里点任何外链，Referer 就把 token 送给对方站点。
        eqi("auth: Referer 里的 token 不算凭据", LanAuth.AUTH_DENY,
                LanAuth.tokenOk(req("GET /api/x",
                        "Referer: http://192.168.1.5:3081/?token=" + T), T));
        eqi("auth: 换过 token 时旧 Cookie 不挡新地址", LanAuth.AUTH_OK_SET_COOKIE,
                LanAuth.tokenOk(req("GET /?token=" + T, "Cookie: dsha_token=stale"), T));
        eqi("auth: 无凭据", LanAuth.AUTH_DENY, LanAuth.tokenOk(req("GET /", ""), T));
        eqi("auth: 显式头错值", LanAuth.AUTH_DENY,
                LanAuth.tokenOk(req("GET /", "X-DSHA-Token: wrong"), T));
        eqi("auth: WebSocket 握手带 Cookie 可过", LanAuth.AUTH_OK,
                LanAuth.tokenOk(req("GET /api/ws",
                        "Upgrade: websocket\r\nCookie: dsha_token=" + T), T));

        // ---------- queryTokenFromTarget（3090 桥也用这一份）----------
        eq("target: 正常取值", "abc", LanAuth.queryTokenFromTarget("/exec?cmd=ls&token=abc"));
        // 回归：3090 桥原来是 query.indexOf("token=")，xtoken= 的尾部先命中 → 取到 junk 而误拒
        eq("target: 不被同后缀参数抢先", "abc",
                LanAuth.queryTokenFromTarget("/exec?xtoken=junk&token=abc"));
        eq("target: 无 token 参数", null, LanAuth.queryTokenFromTarget("/exec?cmd=ls"));
        eq("target: 无 query", null, LanAuth.queryTokenFromTarget("/exec"));

        // ---------- AssetPath：清单 asset 名当路径用之前的校验 ----------
        // asset 名会被直接拼成覆盖层下的相对路径落盘，带 ../ 就能覆盖 shared_prefs
        // （API key 密文、LAN token 都在里面）或者往 rootfs 里投文件。
        ok("asset: 普通文件名", AssetPath.isSafe("selftest.py"));
        ok("asset: 多级路径", AssetPath.isSafe("device-shell-guide/lib/index.js"));
        ok("asset: 允许点开头", AssetPath.isSafe(".keep"));
        ok("asset: 拒绝上跳", !AssetPath.isSafe("../shared_prefs/deepseekharness.xml"));
        ok("asset: 拒绝中间上跳", !AssetPath.isSafe("a/../../b.py"));
        ok("asset: 拒绝绝对路径", !AssetPath.isSafe("/etc/passwd"));
        ok("asset: 拒绝反斜杠", !AssetPath.isSafe("a\\..\\b"));
        ok("asset: 拒绝空段", !AssetPath.isSafe("a//b.py"));
        ok("asset: 拒绝单点段", !AssetPath.isSafe("./a.py"));
        ok("asset: 拒绝目录形", !AssetPath.isSafe("lib/"));
        ok("asset: 拒绝空串", !AssetPath.isSafe(""));
        ok("asset: 拒绝 null", !AssetPath.isSafe(null));
        ok("asset: 拒绝空格", !AssetPath.isSafe("a b.py"));
        ok("asset: 拒绝 NUL 截断", !AssetPath.isSafe("a.py\u0000.txt"));
        ok("asset: 拒绝超长", !AssetPath.isSafe(new String(new char[300]).replace('\0', 'a')));

        // ---------- BackupInspector：恢复前的备份体检 ----------
        // 它是「这份备份能不能用」的守门人：判错了要么放过一个截断包（用户以为恢复成功，
        // 实际少了一半会话），要么拦住一份好备份（更糟，人在急着恢复数据）。造三种包来验。
        try {
            java.io.File tmpDir = java.nio.file.Files.createTempDirectory("dsha-bi").toFile();

            java.io.File good = new java.io.File(tmpDir, "good.tar.gz");
            writeTarGz(good, new String[][]{
                    {".dsh/settings.yaml", "port: 3080\n"},
                    {".dsh/sessions/a.jsonl", "{\"x\":1}\n"},
                    {".dsh/sessions/b.jsonl", "{\"x\":2}\n"},
                    {".dsh/.dsha-backup-manifest.json", "{\"appVersion\": \"1.1.7\"}"},
            }, true);
            BackupInspector.Info gi = BackupInspector.inspect(good);
            ok("inspect: 正常包可读", gi.readable, "error=" + gi.error);
            ok("inspect: 认出是 DSHA 备份", gi.looksLikeDsha);
            ok("inspect: 数出会话文件", gi.sessionFiles == 2, "实际 " + gi.sessionFiles);
            ok("inspect: 认出清单", gi.hasManifest);
            eq("inspect: 读出备份方版本", "1.1.7", gi.appVersion);

            // 截断：把好包砍掉后 1/3。gzip 的 CRC/长度尾部就是为这种情况准备的。
            byte[] all = java.nio.file.Files.readAllBytes(good.toPath());
            java.io.File cut = new java.io.File(tmpDir, "cut.tar.gz");
            java.nio.file.Files.write(cut.toPath(),
                    java.util.Arrays.copyOf(all, all.length * 2 / 3));
            BackupInspector.Info ci = BackupInspector.inspect(cut);
            ok("inspect: 截断包判为不可用", !ci.readable, "error=" + ci.error);
            ok("inspect: 截断有说明", ci.error != null && !ci.error.isEmpty());

            // 不是 DSHA 备份：能读，但里面没有 .dsh
            java.io.File other = new java.io.File(tmpDir, "other.tar.gz");
            writeTarGz(other, new String[][]{{"photos/1.txt", "hello\n"}}, true);
            BackupInspector.Info oi = BackupInspector.inspect(other);
            ok("inspect: 无关包可读但不认", oi.readable && !oi.looksLikeDsha,
                    "readable=" + oi.readable + " dsha=" + oi.looksLikeDsha);

            // 空文件 / 不存在
            java.io.File empty = new java.io.File(tmpDir, "empty.tar.gz");
            //noinspection ResultOfMethodCallIgnored
            empty.createNewFile();
            ok("inspect: 空文件判为不可用", !BackupInspector.inspect(empty).readable);
            ok("inspect: 不存在的文件不炸",
                    !BackupInspector.inspect(new java.io.File(tmpDir, "nope.tar.gz")).readable);

            deleteRec(tmpDir);
        } catch (Throwable t) {
            fail++;
            System.out.println("  FAIL inspect: 用例本身抛异常 " + t);
        }

        // ---------- PluginErrorHint：把「哪个插件把 Web 弄挂了」从日志里认出来 ----------
        // 用的是用户真实贴过来的报错原文。这类靠正则读别人日志的代码，最容易在上游改一句
        // 话之后静默失效 —— 有样本才知道它还认得。
        {
            String realPending = "Error: dsh: plugin tree failed to load: dsh: 1 entry did not activate\n"
                    + "dsh-device-shell-guide: pending (waiting for service: systemPrompt)\n"
                    + "    at boot (file:///usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/"
                    + "@deepseek-ai/dsh-app-boot/lib/index.js:1187:9)\n";
            PluginErrorHint.Hint h1 = PluginErrorHint.detect(realPending);
            ok("hint: 认出 pending 的插件名", h1 != null && "dsh-device-shell-guide".equals(h1.plugin),
                    h1 == null ? "null" : h1.plugin);
            ok("hint: 提到缺的服务", h1 != null && h1.what.contains("systemPrompt"));
            ok("hint: 给了可点的下一步", h1 != null && h1.fix.contains("自检"));
            ok("hint: describe 可直接上屏",
                    PluginErrorHint.describe(realPending).startsWith("⚠"));

            PluginErrorHint.Hint h2 = PluginErrorHint.detect(
                    "Error: cannot resolve profile bundle \"dsh-client-ui-mobile-adapt\"");
            ok("hint: 认出解析不到的 bundle",
                    h2 != null && "dsh-client-ui-mobile-adapt".equals(h2.plugin),
                    h2 == null ? "null" : h2.plugin);

            PluginErrorHint.Hint h3 = PluginErrorHint.detect(
                    "Error: cannot get property \"systemPrompt\" without inject\n"
                            + "    at file:///root/.dsh/profiles/web/node_modules/dsh-foo-plugin/lib/index.js:12:3\n");
            ok("hint: 从堆栈里猜出第三方插件",
                    h3 != null && "dsh-foo-plugin".equals(h3.plugin),
                    h3 == null ? "null" : h3.plugin);

            // 关键：官方包不能被当成「出问题的插件」—— 插件故障时堆栈里几乎全是官方路径
            PluginErrorHint.Hint h4 = PluginErrorHint.detect(
                    "Error: cannot get property \"x\" without inject\n"
                            + "    at file:///usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js:1:1\n");
            ok("hint: 不把官方包当成肇事插件",
                    h4 != null && h4.plugin.isEmpty(), h4 == null ? "null" : h4.plugin);

            ok("hint: 认出 pnpm 空壳",
                    PluginErrorHint.detect("_pnpmPlaceholder found in dsh-bar") != null);
            ok("hint: 认出坏掉的 patch",
                    PluginErrorHint.detect("YAMLException: bad indentation of /root/.dsh/cordis.patch.yml") != null);
            ok("hint: 正常日志不误报",
                    PluginErrorHint.detect("dsh web listening on http://127.0.0.1:3080") == null);
            ok("hint: 空输入不炸", PluginErrorHint.detect(null) == null
                    && PluginErrorHint.detect("") == null);
        }

        // ---------- RuntimeHealth.parse ----------
        // 判据全是字符串匹配，所以能在这里用真实现场原文回归。
        {
            // 用户贴过来的原文（两段崩溃：assert 调 abort，abort 在信号层再炸一次）
            String real = "Fatal glibc error: ../sysdeps/unix/sysv/linux/sysconf-sigstksz.h:25"
                    + " (sysconf_sigstksz): assertion failed: minsigstacksize != 0\n"
                    + "[proroot] SIGSEGV pc=0x7f addr=0x0 code=-6\n";
            RuntimeHealth.Probe p = RuntimeHealth.parse(real);
            ok("health: 认出用户贴的崩溃原文", !p.healthy());

            // 探针完整输出 —— 坏环境
            p = RuntimeHealth.parse("PROBE_BEGIN\nAUXV: AT_MINSIGSTKSZ:  0x0\nPYEXIT=134\n"
                    + "Fatal glibc error: sysconf_sigstksz\nPROBE_END\n");
            ok("health: 坏环境判 fatal", !p.healthy());
            ok("health: 认出 auxv 为 0", p.minsigstkszZero);

            // 回归：LD_SHOW_AUXV 打的是十六进制。原实现走 Integer.parseInt("0x1400")
            // 抛异常被吞，minsigstkszZero 一直是 false —— 好机器结论恰好正确，
            // 掩盖了下面那条「auxv=0x0 但 python 退出码取不到」的漏报。
            p = RuntimeHealth.parse("PROBE_BEGIN\nAUXV: AT_MINSIGSTKSZ:  0x1400\nPYEXIT=0\nPROBE_END\n");
            ok("health: 十六进制 0x1400 不误判", p.healthy() && !p.minsigstkszZero);

            // 修好之后才成立的一条：auxv=0x0，而 python3 压根不在（PYEXIT=127，不是 134）
            p = RuntimeHealth.parse("PROBE_BEGIN\nAUXV: AT_MINSIGSTKSZ:  0x0\nPYEXIT=127\n"
                    + "bash: python3: command not found\nPROBE_END\n");
            ok("health: auxv=0x0 单独也能判出来", !p.healthy() && p.minsigstkszZero);

            // 十进制 0（有的 ld.so 版本按十进制打）
            p = RuntimeHealth.parse("AUXV: AT_MINSIGSTKSZ: 0\nPYEXIT=0\n");
            ok("health: 十进制 0 也认", !p.healthy());

            // 正常机器：内核压根不提供这一项，glibc 用架构默认常量。
            // 回归：标记行自己带 MINSIGSTKSZ 字样，\D 会跨过换行在 "PYEXIT=0" 上
            // 捡到那个 0，把每一台正常机器都判成不兼容并悄悄切回 proot。
            p = RuntimeHealth.parse("PROBE_BEGIN\nAUXV_NO_MINSIGSTKSZ\nPYEXIT=0\nPROBE_END\n");
            ok("health: 缺 auxv 项是好事", p.healthy() && p.auxvEntryAbsent);
            ok("health: 缺 auxv 项时不去解析值", !p.minsigstkszZero);

            // 探针自己没跑成：不能据此判死刑，否则所有人白白失去 proroot 加速
            ok("health: 空输出不判死刑", RuntimeHealth.parse("").healthy());
            ok("health: null 不炸", RuntimeHealth.parse(null).healthy());

            // 只有 SIGABRT 才算 python 被打死；其它非零退出码（如 127）不单独构成理由
            p = RuntimeHealth.parse("AUXV_NO_MINSIGSTKSZ\nPYEXIT=1\nSyntaxError\n");
            ok("health: 普通非零退出码不误判", p.healthy());
            eqi("health: 解析退出码", 134,
                    RuntimeHealth.parse("AUXV_NO_MINSIGSTKSZ\nPYEXIT=134\n").pythonExit);

            // 探针脚本自身不能依赖 python（它正是受害者）
            String script = RuntimeHealth.probeScript();
            ok("health: 探针用 LD_SHOW_AUXV 而非 python 取 auxv",
                    script.contains("LD_SHOW_AUXV") && script.contains("/bin/true"));
        }

        // ---------- constantTimeEquals ----------
        ok("ct: 相等", LanAuth.constantTimeEquals("abc", "abc"));
        ok("ct: 前缀不算相等", !LanAuth.constantTimeEquals("abc", "ab"));
        ok("ct: 更长不算相等", !LanAuth.constantTimeEquals("abc", "abcd"));
        ok("ct: null 不相等", !LanAuth.constantTimeEquals("abc", null));

        // ---------- OfflineVersion（离线包标记比大小） ----------
        // 回归：maybeOfferOfflineUpgrade 原先拿「标记不相等」当「有新版」，注释写的却是
        // 「内置 > 已解压」。于是用旧离线包打的本地测试包装上来也弹「发现新版内置环境」，
        // 用户点了升级，rootfs 被重解压成更旧的环境：dsh 从 0.1.1-rc.2 退回 0.1.0-rc.6。
        ok("offline: 修订位往上走算新",
                OfflineVersion.isNewer("dsh-0.1.1-rc.2", "dsh-0.1.0-rc.6"));
        ok("offline: 反过来不算新（这就是降级那条路）",
                !OfflineVersion.isNewer("dsh-0.1.0-rc.6", "dsh-0.1.1-rc.2"));
        ok("offline: 同标记不算新",
                !OfflineVersion.isNewer("dsh-0.1.1-rc.2", "dsh-0.1.1-rc.2"));
        ok("offline: rc 号本身也要比",
                OfflineVersion.isNewer("dsh-0.1.1-rc.10", "dsh-0.1.1-rc.2"));
        // 少了「预发布位」，0.1.1 正式版会因为末尾没数字而被判成比 0.1.1-rc.2 旧
        ok("offline: 正式版比同号 rc 新",
                OfflineVersion.isNewer("dsh-0.1.1", "dsh-0.1.1-rc.9"));
        ok("offline: rc 不比同号正式版新",
                !OfflineVersion.isNewer("dsh-0.1.1-rc.9", "dsh-0.1.1"));
        ok("offline: 10 比 9 大（不是字典序）",
                OfflineVersion.isNewer("dsh-0.1.10-rc.1", "dsh-0.1.9-rc.1"));
        ok("offline: 没有标记的老环境视为最旧",
                OfflineVersion.isNewer("dsh-0.1.1-rc.2", "0"));
        ok("offline: 解析不出版本 → 不算新（拿不准不提示）",
                !OfflineVersion.isNewer("nightly", "dsh-0.1.1-rc.2"));
        ok("offline: 另一侧解析不出也不算新",
                !OfflineVersion.isNewer("dsh-0.1.1-rc.2", "nightly"));
        eqi("offline: 无法比较有专门的返回值", OfflineVersion.NOT_COMPARABLE,
                OfflineVersion.compare("nightly", "dsh-0.1.1-rc.2"));
        ok("offline: 0.1.1.0 与 0.1.1 是同一个版本",
                OfflineVersion.compare("dsh-0.1.1.0", "dsh-0.1.1") == 0);
        // CI 实际写进 assets 的就是纯整数（仓库根 OFFLINE_VERSION，改离线包内容时 +1）。
        // 上面那些 dsh-x.y.z 的用例是防呆：谁再往标记里写版本号，比较也不会错到降级。
        ok("offline: 整数标记 2 比 1 新（老用户升级路径）",
                OfflineVersion.isNewer("2", "1"));
        ok("offline: 装回老离线包不算新（1 vs 2）",
                !OfflineVersion.isNewer("1", "2"));
        ok("offline: 本地包写的 0 谁都不如（0 vs 1）",
                !OfflineVersion.isNewer("0", "1"));
        ok("offline: 从无标记环境升到 2 算新",
                OfflineVersion.isNewer("2", "0"));
        ok("offline: 10 比 9 新（整数不走字典序）",
                OfflineVersion.isNewer("10", "9"));

        // ---------- OverlayLines（悬浮条分行与滚动） ----------
        // 用户报的毛病：悬浮条「最前端文字会不停滚走，可读性很差」。旧实现是拿整段文本
        // 取最后 N 个字符、再交给系统折行 —— 尾部窗口每来一个字就右移一格，折行点跟着变，
        // 于是每一行的内容都在变。下面第一条就是这个类存在的理由。
        {
            String a = "abcdefghij klmnopqrst uvwxyz0123 456789";
            String b = a + " tail more";
            java.util.List<String> la = OverlayLines.wrap(a, 20);
            java.util.List<String> lb = OverlayLines.wrap(b, 20);
            boolean stable = la.size() <= lb.size();
            for (int i = 0; stable && i < la.size() - 1; i++) {   // 最后一行还在长，不算
                if (!la.get(i).equals(lb.get(i))) stable = false;
            }
            ok("overlay: 末尾追加内容不会动到前面的行", stable);
        }
        eqi("overlay: 汉字算两格", 4, OverlayLines.width("中文"));
        eqi("overlay: ASCII 算一格", 4, OverlayLines.width("abcd"));
        ok("overlay: 中文按显示宽度切，30 格 = 15 字一行",
                OverlayLines.wrap("一二三四五六七八九十一二三四五六七八九十", 30).size() == 2);
        eq("overlay: 行数没满就全给", "aaa", OverlayLines.lastLines("aaa", 3, 20));
        // 「已有行数 >= 最大行数时清掉第一行、下面的上移补位」——用户要的就是这句
        eq("overlay: 满了丢最旧一行、其余上移", "cccc\ndddd",
                OverlayLines.lastLines("aaaa bbbb cccc dddd", 2, 4));
        eq("overlay: 命令确认取开头并标省略号", "aaaa …",
                OverlayLines.firstLines("aaaa bbbb cccc", 1, 4));
        eqi("overlay: 长串无空格也不空转（500 字符 / 10 格 = 50 行）", 50,
                OverlayLines.wrap("x".repeat(500), 10).size());
        ok("overlay: 空输入不炸",
                OverlayLines.wrap(null, 20).isEmpty()
                        && OverlayLines.lastLines(null, 3, 20).isEmpty()
                        && OverlayLines.firstLines("", 3, 20).isEmpty());

        // ---------- UserDataPolicy ----------
        // 这一组的重点不是「某个路径判得对不对」，而是**同一份定义派生出的两个列表必须
        // 一一对应**。真机上这两处判断分裂过两次，各造成一次静默故障：
        //   · 桥 token 被还原回来 → 一律 401（现象却是「悬浮窗不显示 + agent 工具调用失败」）
        //   · 内置插件版本标记活过重解压 → 实体随 rootfs 没了却判定「已最新」→ 永不重装
        ok("policy: 桥 token 属本机专属",
                UserDataPolicy.isMachineLocal("root/.dsh/.bridge_token"));
        ok("policy: 内置插件版本标记属本机专属",
                UserDataPolicy.isMachineLocal("root/.dsh/builtin-assets.version"));
        ok("policy: 前导斜杠与 ./ 都认",
                UserDataPolicy.isMachineLocal("/root/.dsh/.bridge_token")
                        && UserDataPolicy.isMachineLocal("./root/.dsh/.bridge_token"));
        ok("policy: 会话是用户数据",
                !UserDataPolicy.isMachineLocal("root/.dsh/sessions/a.json"));
        ok("policy: 配置是用户数据",
                !UserDataPolicy.isMachineLocal("root/.dsh/settings.yaml"));
        ok("policy: 工作区 .env 是用户数据",
                !UserDataPolicy.isMachineLocal("root/deepseek-harness/.env"));
        ok("policy: null 与空串不误判",
                !UserDataPolicy.isMachineLocal(null) && !UserDataPolicy.isMachineLocal(""));
        // 派生列表必须逐项同源 —— tar 用的模式就是去掉 root/ 前缀的同一个路径。
        // 2026-09-05 起多了一类「按名字」的条目（session.lock 那种位置运行时才定的），
        // 所以 tar 模式 = 路径型（去前缀）+ 名字型，两段都要对得上。
        String[] purge = UserDataPolicy.purgeAfterRestore();
        String[] policyNames = UserDataPolicy.purgeAfterRestoreNames();
        String[] pats = UserDataPolicy.tarExcludePatterns();
        eqi("policy: tar 模式 = 路径型 + 名字型，长度必须相加",
                purge.length + policyNames.length, pats.length);
        boolean paired = purge.length + policyNames.length == pats.length;
        for (int i = 0; paired && i < purge.length; i++) {
            if (!purge[i].equals("root/" + pats[i])) paired = false;
        }
        for (int i = 0; paired && i < policyNames.length; i++) {
            if (!policyNames[i].equals(pats[purge.length + i])) paired = false;
        }
        ok("policy: 排除项与清理项逐项同源（定义不可分裂）", paired);
        // 名字型：位置不固定，任意深度同名都算本机专属
        ok("policy: 会话锁在任意深度都算本机专属",
                UserDataPolicy.isMachineLocal("root/.dsh/sessions/proj-a/sess-1/session.lock")
                        && UserDataPolicy.isMachineLocal("root/.dsh/sessions/session.lock"));
        ok("policy: 会话日志本身仍是用户数据（别把整个会话目录排除掉）",
                !UserDataPolicy.isMachineLocal("root/.dsh/sessions/proj-a/sess-1/session.jsonl")
                        && !UserDataPolicy.isMachineLocal("root/.dsh/sessions/proj-a/sess-1/session.v2.jsonl"));
        ok("policy: 名字型条目进了 tar 排除模式（unanchored 才能匹配任意深度）",
                UserDataPolicy.tarExcludeArgs().contains("--exclude='session.lock' "));
        ok("policy: 按名字清理有明确的搜索根（不许全 rootfs 扫名字）",
                UserDataPolicy.purgeNamesSearchRoot().equals("root/.dsh/sessions"));
        String tarArgs = UserDataPolicy.tarExcludeArgs();
        ok("policy: tar 参数带引号且以空格结尾（直接拼进命令不粘连）",
                tarArgs.contains("--exclude='.dsh/.bridge_token' ") && tarArgs.endsWith(" "));
        eqi("policy: tar 参数覆盖全部条目",
                pats.length, tarArgs.split("--exclude=", -1).length - 1);
        ok("policy: 清单不为空（清空它等于悄悄关掉这层保护）",
                UserDataPolicy.MACHINE_LOCAL_PATHS.length >= 2);

        // ===== ShellQuote：拼进 bash -c 之前的转义 =====
        // 断言方式刻意不比字符串长相，而是做 round-trip：把转义结果按 POSIX 单引号规则
        // 反解一遍，看 shell 最终会拿到什么。长相对不对不重要，语义对不对才重要。
        String[] nasty = {
                "plain", "with space", "a'b", "''", "'", "a'''b",
                "$(rm -rf /)", "`id`", "a; rm -rf /", "a && b", "a|b", "a\nb",
                "*", "?", "~", "$HOME", "\\", "--flag=va'lue", "中文名",
                "'; rm -rf / #", "$IFS", "a\tb",
        };
        boolean roundTrip = true, singleWord = true;
        String badRt = "", badSw = "";
        for (String s : nasty) {
            String q = ShellQuote.arg(s);
            if (!s.equals(unquoteSingle(q))) { roundTrip = false; badRt = s; }
            if (!isSingleShellWord(q)) { singleWord = false; badSw = s; }
        }
        ok("shellquote: 转义后按单引号规则反解 == 原值（22 个样本）", roundTrip, badRt);
        ok("shellquote: 输出始终是「一个」shell 词，引号外只有 \\' 转义", singleWord, badSw);
        eq("shellquote: 内含单引号拆成 '\\'' ", "'a'\\''b'", ShellQuote.arg("a'b"));
        eq("shellquote: null 给空参数而不是抛异常", "''", ShellQuote.arg(null));
        eq("shellquote: 空串给空参数", "''", ShellQuote.arg(""));
        // 典型注入 payload：转义后 shell 只看见一个字面量参数，命令边界闭不上
        ok("shellquote: 注入 payload 不产生新的命令边界",
                "'; rm -rf / #".equals(unquoteSingle(ShellQuote.arg("'; rm -rf / #")))
                        && isSingleShellWord(ShellQuote.arg("'; rm -rf / #")));

        // ===== Query：查询串取参数（3090 桥与局域网桥共用这一份）=====
        // 核心断言是参数名边界：桥上真实存在 key 与 y 这对「一个是另一个后缀」的参数名，
        // 旧的 indexOf(key + "=") 会让 ?key=abc&y=200 取 y 拿到 abc。
        eq("query: 参数名精确匹配，key= 不劫持 y=",
                "200", Query.param("key=abc&y=200", "y", "def"));
        eq("query: 前缀参数不劫持（xtext= 不当 text=）",
                "real", Query.param("xtext=junk&text=real", "text", "def"));
        eq("query: 值截断到 &",
                "ls", Query.param("cmd=ls&token=T", "cmd", ""));
        eq("query: token 在前也取得对",
                "ls -la", Query.param("token=T&cmd=ls%20-la", "cmd", ""));
        eq("query: %20 解码", "a b", Query.param("t=a%20b", "t", ""));
        eq("query: + 按 form 语义还原成空格", "a b", Query.param("t=a+b", "t", ""));
        eq("query: fragment 不进值", "1", Query.param("a=1#frag", "a", ""));
        eq("query: 参数不存在给默认值", "D", Query.param("a=1&b=2", "zz", "D"));
        eq("query: 畸形百分号给默认值而不是半解码串",
                "D", Query.param("a=%ZZ", "a", "D"));
        eq("query: 没有 ? 时查询串为空", "", Query.of("/app/device"));
        eq("query: 切出查询串", "a=1&b=2", Query.of("/p?a=1&b=2"));
        ok("query: raw 区分「没这个参数」与「值为空」",
                Query.raw("a=1", "b") == null && "".equals(Query.raw("a=", "a")));
        ok("query: 裸参数名（没有 =）不算存在",
                Query.raw("flag&a=1", "flag") == null);
        eq("query: 同名参数取第一个", "1", Query.param("a=1&a=2", "a", ""));
        // token 走同一份解析（LanAuth 只在外面补了 trim）
        eq("query: token 解析与桥参数同源",
                "T", LanAuth.queryTokenFromTarget("/p?xtoken=junk&token=T"));
        eq("query: 路由剥掉查询串，分发可精确匹配", "/app/overlay/reply",
                Query.path("/app/overlay/reply?token=T&session=A"));
        eq("query: 没有查询串的路由原样保留", "/app/device", Query.path("/app/device"));

        // ===== SafeFiles / BridgeLimits：外部路径、原子命名与本机 HTTP 头边界 =====
        java.io.File safeRoot = null;
        try {
            safeRoot = java.nio.file.Files.createTempDirectory("dsha-safe-files-").toFile();
            java.io.File first = SafeFiles.reserve(safeRoot, "报告.md");
            java.io.File second = SafeFiles.reserve(safeRoot, "报告.md");
            ok("files: reserve 原子占名且保留扩展名",
                    first.getName().equals("报告.md") && second.getName().equals("报告-2.md"));
            ok("files: ../ 不能逃出根目录", rejectsInside(safeRoot, "../outside.txt"));
            ok("files: 同前缀兄弟目录不能算在根内",
                    !SafeFiles.isInside(safeRoot, new java.io.File(safeRoot.getPath() + "-old/x")));
            eq("files: 文件名清理路径分隔符与控制字符", "a_b_c", SafeFiles.cleanName("a/b\\c\u0001"));
        } catch (Exception e) {
            ok("files: 临时目录往返", false, String.valueOf(e));
        } finally {
            if (safeRoot != null) deleteRec(safeRoot);
        }
        ok("files: 分享只接受获授权的外部 content Uri",
                SafeFiles.shareSourceAllowed("content", "media", "com.dsh.client", true)
                        && !SafeFiles.shareSourceAllowed("file", "", "com.dsh.client", true)
                        && !SafeFiles.shareSourceAllowed("content", "com.dsh.client.documents", "com.dsh.client", true)
                        && !SafeFiles.shareSourceAllowed("content", "media", "com.dsh.client", false));
        ok("files: 外部存储前缀必须是完整目录段",
                SafeFiles.isPrimaryExternalPath("/storage/emulated/0/Download/x")
                        && !SafeFiles.isPrimaryExternalPath("/storage/emulated/0evil/x"));
        try {
            BridgeLimits.HeaderReader hr = new BridgeLimits.HeaderReader(
                    new java.io.StringReader("GET / HTTP/1.1\r\nX: y\r\n"));
            ok("bridge: HeaderReader 保留逐行 HTTP 头", "GET / HTTP/1.1".equals(hr.readLine())
                    && "X: y".equals(hr.readLine()));
        } catch (Exception e) {
            ok("bridge: HeaderReader 正常头", false, String.valueOf(e));
        }
        try {
            BridgeLimits.HeaderReader hr = new BridgeLimits.HeaderReader(
                    new java.io.StringReader("A".repeat(16 * 1024 + 8)));
            hr.readLine();
            ok("bridge: HeaderReader 拒绝超长单行", false);
        } catch (java.io.IOException expected) {
            ok("bridge: HeaderReader 拒绝超长单行", true);
        }

        // ===== BackupScope：备份范围的唯一定义 =====
        // 两条关键断言：① 部分备份的文件名前缀绝不能是 DSHA-backup-（老版本按这个前缀
        // 扫描并且会「整个 .dsh 挪走再替换」，把只含对话的包当全量恢复 = 配置与插件全丢）；
        // ② 备份打了哪些子树、恢复就合并哪些子树，两份清单必须一一对应。
        ok("scope: 只有全量用 DSHA-backup- 前缀（老版本只看得见它）",
                BackupScope.visibleToLegacyScan(BackupScope.FULL)
                        && !BackupScope.visibleToLegacyScan(BackupScope.SESSIONS)
                        && !BackupScope.visibleToLegacyScan(BackupScope.SETTINGS)
                        && !BackupScope.visibleToLegacyScan(BackupScope.PLUGINS));
        boolean prefixDistinct = true;
        for (int a : BackupScope.ALL) {
            for (int b : BackupScope.ALL) {
                if (a == b) continue;
                if (BackupScope.fileNamePrefix(a).equals(BackupScope.fileNamePrefix(b))) prefixDistinct = false;
                // 前缀之间不能互为前缀，否则按名字分类与轮换都会串
                if (BackupScope.fileNamePrefix(a).startsWith(BackupScope.fileNamePrefix(b))) prefixDistinct = false;
            }
        }
        ok("scope: 四个文件名前缀互不相同、也互不为前缀", prefixDistinct);
        boolean roundTripScope = true, nameRoundTrip = true, pathsPaired = true;
        for (int s : BackupScope.ALL) {
            if (BackupScope.fromId(BackupScope.id(s)) != s) roundTripScope = false;
            String name = BackupScope.fileNamePrefix(s) + "20260826-120000.tar.gz";
            if (BackupScope.fromFileName(name) != s) nameRoundTrip = false;
            // 回归：调用方传全路径时，前缀比对必须先剥掉目录 —— 否则部分备份被判成
            // 全量，而全量恢复会把整个 .dsh 挪走再替换（配置与插件跟着被覆盖）。
            if (BackupScope.fromFileName(
                    "/storage/emulated/0/Download/DSHA/存档/" + name) != s) nameRoundTrip = false;
            if (BackupScope.fromFileName(
                    "C:\\Users\\x\\Download\\" + name) != s) nameRoundTrip = false;
            String[] packed = BackupScope.dshPaths(s);
            String[] merged = BackupScope.mergeSubdirs(s);
            if (packed.length != merged.length) {
                pathsPaired = false;
            } else {
                for (int i = 0; i < packed.length; i++) {
                    if (!packed[i].equals(".dsh/" + merged[i])) pathsPaired = false;
                }
            }
        }
        ok("scope: id ↔ 常量往返一致", roundTripScope);
        ok("scope: 文件名 ↔ 范围往返一致", nameRoundTrip);
        ok("scope: 打包子树与恢复子树逐项同源（备份了什么就恢复什么）", pathsPaired);
        eqi("scope: 全量的子树清单为空（= 整个 .dsh）", 0, BackupScope.dshPaths(BackupScope.FULL).length);
        eqi("scope: 全量的合并清单也为空（整目录替换）", 0, BackupScope.mergeSubdirs(BackupScope.FULL).length);
        eqi("scope: 未知 id 与 null 都当全量（老备份没有这个字段）",
                BackupScope.FULL, BackupScope.fromId(null) + BackupScope.fromId("what"));
        eqi("scope: 认不出的文件名当全量", BackupScope.FULL, BackupScope.fromFileName("random.tar.gz"));
        ok("scope: 只有全量带工作区 .env 与日志",
                BackupScope.includesWorkdirFiles(BackupScope.FULL)
                        && !BackupScope.includesWorkdirFiles(BackupScope.SESSIONS)
                        && !BackupScope.includesWorkdirFiles(BackupScope.PLUGINS));
        ok("scope: 全量与插件备份都带内联插件源码",
                BackupScope.includesPluginSrc(BackupScope.FULL)
                        && BackupScope.includesPluginSrc(BackupScope.PLUGINS)
                        && !BackupScope.includesPluginSrc(BackupScope.SESSIONS));
        // 快照条目必须来自那份公开热数据名单，且插件备份不需要快照
        boolean snapSubset = true;
        for (int s : BackupScope.ALL) {
            for (String e : BackupScope.snapshotEntries(s)) {
                boolean found = false;
                for (String p : BackupScope.PUBLIC_HOT_ENTRIES) if (p.equals(e)) found = true;
                if (!found) snapSubset = false;
            }
        }
        ok("scope: 快照条目都出自 PUBLIC_HOT_ENTRIES 名单", snapSubset);
        ok("scope: 需要快照的范围与快照条目非空一致",
                BackupScope.needsPublicDataSnapshot(BackupScope.FULL)
                        && BackupScope.needsPublicDataSnapshot(BackupScope.SESSIONS)
                        && BackupScope.needsPublicDataSnapshot(BackupScope.SETTINGS)
                        && !BackupScope.needsPublicDataSnapshot(BackupScope.PLUGINS)
                        && BackupScope.snapshotEntries(BackupScope.PLUGINS).length == 0
                        && BackupScope.snapshotEntries(BackupScope.SESSIONS).length > 0);
        ok("scope: 对话备份只快照 sessions（不必把 storages/attachments 也拖进来）",
                BackupScope.snapshotEntries(BackupScope.SESSIONS).length == 1
                        && "sessions".equals(BackupScope.snapshotEntries(BackupScope.SESSIONS)[0]));
        // settings.yaml 在设备上也是指向公开目录的软链，所以「只设置」这个范围同样必须
        // 走解引用快照 —— 否则包里那一行是 lrwxrwxrwx，换设备恢复出来是个悬空链接。
        ok("scope: 设置备份只快照 settings.yaml，且它进得了快照",
                BackupScope.snapshotEntries(BackupScope.SETTINGS).length == 1
                        && "settings.yaml".equals(BackupScope.snapshotEntries(BackupScope.SETTINGS)[0]));

        // ===== PublicDirs：公开 Download 目录布局 =====
        eq("dirs: 存档子目录", "Download/DSHA/存档", PublicDirs.relative("Download", PublicDirs.ARCHIVES));
        eq("dirs: 插件子目录", "Download/DSHA/插件", PublicDirs.relative("Download", PublicDirs.PLUGINS));
        eq("dirs: 下载子目录", "Download/DSHA/下载", PublicDirs.relative("Download", PublicDirs.DOWNLOADS));
        eq("dirs: 老位置就是 DSHA 根（不带子目录）",
                "Download/DSHA", PublicDirs.relative("Download", PublicDirs.LEGACY));
        eq("dirs: null 子目录等同老位置", "Download/DSHA", PublicDirs.relative("Download", null));
        eq("dirs: 带尾斜杠形态", "Download/DSHA/存档/",
                PublicDirs.relativeSlash("Download", PublicDirs.ARCHIVES));
        eq("dirs: 展示用绝对路径", "/storage/emulated/0/Download/DSHA/存档",
                PublicDirs.display("/storage/emulated/0", "Download", PublicDirs.ARCHIVES));
        eq("dirs: 展示路径不因外部根带尾斜杠而多一道", "/storage/emulated/0/Download/DSHA/插件",
                PublicDirs.display("/storage/emulated/0/", "Download", PublicDirs.PLUGINS));
        // 扫描顺序：新目录在前、老目录必须在列（用户手机上已有的备份都在根下，
        // 漏了它恢复列表会突然空掉）
        String[] scan = PublicDirs.archiveSubdirs();
        eqi("dirs: 存档扫描两个位置", 2, scan.length);
        eq("dirs: 新目录排在前", PublicDirs.ARCHIVES, scan[0]);
        eq("dirs: 老目录兜底在后", PublicDirs.LEGACY, scan[1]);
        // 插件名当文件名：scope 里的斜杠会被当路径分隔符。
        // 这里原来拿内置的移动端插件举例，它改名成 dsh-web-mobile 之后不再是 scoped 名字，
        // 换一个仍然带 scope 的包，断言的意图才还在。
        eq("dirs: scoped 插件名去掉 @ 并把 / 换成 -",
                "deepseek-ai-dsh-client-ui-layout",
                PublicDirs.safeFileName("@deepseek-ai/dsh-client-ui-layout"));
        eq("dirs: 改名后的内置移动端插件不带 scope，原样",
                "dsh-web-mobile", PublicDirs.safeFileName("dsh-web-mobile"));
        eq("dirs: 普通插件名原样", "dsh-status-overlay",
                PublicDirs.safeFileName("dsh-status-overlay"));
        eq("dirs: 非法文件名字符被替换", "a-b-c", PublicDirs.safeFileName("a:b?c"));
        eq("dirs: 空名字有兜底", "plugin", PublicDirs.safeFileName(""));

        // ===== ArchiveProbe：导入插件时的格式与布局识别 =====
        eqi("archive: gzip magic", ArchiveProbe.GZIP,
                ArchiveProbe.kindOf(new byte[]{(byte) 0x1f, (byte) 0x8b, 0, 0}));
        eqi("archive: zip magic", ArchiveProbe.ZIP,
                ArchiveProbe.kindOf(new byte[]{'P', 'K', 3, 4}));
        eqi("archive: xz 认得出但解不了", ArchiveProbe.OTHER_COMPRESSED,
                ArchiveProbe.kindOf(new byte[]{(byte) 0xfd, '7', 'z', 'X', 'Z', 0}));
        eqi("archive: 认不出的当 UNKNOWN", ArchiveProbe.UNKNOWN,
                ArchiveProbe.kindOf(new byte[]{'h', 'e', 'l', 'l', 'o'}));
        byte[] tarHead = new byte[300];
        byte[] ustar = "ustar".getBytes();
        System.arraycopy(ustar, 0, tarHead, 257, ustar.length);
        eqi("archive: 未压缩 tar 看 offset 257 的 ustar", ArchiveProbe.TAR,
                ArchiveProbe.kindOf(tarHead));
        ok("archive: 只有 gzip/zip/tar 能解",
                ArchiveProbe.canExtract(ArchiveProbe.GZIP)
                        && ArchiveProbe.canExtract(ArchiveProbe.ZIP)
                        && ArchiveProbe.canExtract(ArchiveProbe.TAR)
                        && !ArchiveProbe.canExtract(ArchiveProbe.OTHER_COMPRESSED)
                        && !ArchiveProbe.canExtract(ArchiveProbe.UNKNOWN));
        // 布局：三种都要认（旧实现只认第二种，单插件包会被拆成一堆垃圾条目）
        ok("archive: 根有 package.json = 单插件包",
                ArchiveProbe.isSinglePlugin(ArchiveProbe.pluginRoots(
                        new String[]{"package.json", "lib/index.js", "README.md"})));
        eqi("archive: 两个子目录各有 package.json = 两个插件", 2,
                ArchiveProbe.pluginRoots(new String[]{
                        "a/package.json", "a/lib/i.js", "b/package.json"}).length);
        String[] wrapped = ArchiveProbe.pluginRoots(new String[]{
                "repo-main/a/package.json", "repo-main/b/package.json"});
        eqi("archive: GitHub zip 那样多包一层也认", 2, wrapped.length);
        eq("archive: 多包一层时插件根带上外层路径", "repo-main/a", wrapped[0]);
        ok("archive: 插件自己的依赖树不算插件（node_modules 里全是 package.json）",
                ArchiveProbe.isSinglePlugin(ArchiveProbe.pluginRoots(new String[]{
                        "package.json", "node_modules/x/package.json",
                        "node_modules/y/package.json"})));
        eqi("archive: 只取最浅那一层（插件内部子包不算）", 1,
                ArchiveProbe.pluginRoots(new String[]{
                        "a/package.json", "a/vendor/sub/package.json"}).length);
        eqi("archive: 没有 package.json 就不是插件包", 0,
                ArchiveProbe.pluginRoots(new String[]{"a.txt", "b/c.js"}).length);
        eq("archive: ./ 前缀不影响判定（tar 常带）", "",
                ArchiveProbe.pluginRoots(new String[]{"./package.json"})[0]);
        ok("archive: zip slip 被挡下（.. 与绝对路径）",
                !ArchiveProbe.safeEntryName("../evil.sh")
                        && !ArchiveProbe.safeEntryName("a/../../evil")
                        && !ArchiveProbe.safeEntryName("/etc/passwd")
                        && !ArchiveProbe.safeEntryName("C:/x")
                        && ArchiveProbe.safeEntryName("a/b/c.txt"));

        // ===== GitHubRef：链接解析必须保留 monorepo 子目录 =====
        // 主人报的 bug 就出在这：/tree/main/plugins/turn-guard 被截成 owner/repo，
        // 装的是 monorepo 仓库根（那个 package.json 不是插件）→ 命令报成功、插件页空无一物。
        GitHubRef r1 = GitHubRef.parse("https://github.com/xiaoxiao44443/dfy-dsh-plugins/tree/main/plugins/turn-guard");
        ok("ghref: tree 链接解析出仓库与子目录", r1 != null
                && "xiaoxiao44443/dfy-dsh-plugins".equals(r1.slug())
                && "main".equals(r1.branch) && "plugins/turn-guard".equals(r1.subdir)
                && r1.hasSubdir());
        GitHubRef r2 = GitHubRef.parse("https://github.com/o/r/blob/dev/plugins/x/package.json");
        ok("ghref: blob 指到文件时子目录取父目录", r2 != null
                && "plugins/x".equals(r2.subdir) && "dev".equals(r2.branch));
        GitHubRef r3 = GitHubRef.parse("https://github.com/o/r.git");
        ok("ghref: .git 后缀与无子目录", r3 != null && "o/r".equals(r3.slug()) && !r3.hasSubdir());
        GitHubRef r4 = GitHubRef.parse("o/r");
        ok("ghref: 裸 owner/repo", r4 != null && "o/r".equals(r4.slug()));
        GitHubRef r5 = GitHubRef.parse("github:o/r");
        ok("ghref: github: 前缀", r5 != null && "o/r".equals(r5.slug()));
        GitHubRef r6 = GitHubRef.parse("https://github.com/o/r/tree/main/a/b/c?tab=readme#x");
        ok("ghref: 查询串与锚点不进子目录", r6 != null && "a/b/c".equals(r6.subdir));
        ok("ghref: 认不出的返回 null",
                GitHubRef.parse("https://example.com/") == null
                        && GitHubRef.parse("onlyowner") == null
                        && GitHubRef.parse("") == null
                        && GitHubRef.parse(null) == null);
        eq("ghref: raw 前缀带上分支与子目录",
                "https://raw.githubusercontent.com/o/r/dev/plugins/x",
                GitHubRef.parse("https://github.com/o/r/tree/dev/plugins/x").rawPrefix("main"));
        eq("ghref: 分支未写时用调用方给的默认分支",
                "https://raw.githubusercontent.com/o/r/main",
                GitHubRef.parse("o/r").rawPrefix("main"));

        // ===== PluginSpec：pnpm 支持的全部来源都要认得出 =====
        // dsh plugin --profile 是很薄的 pnpm 转发器，所以 pnpm 的每一种来源都是一种
        // 合法的插件安装方式。原来那条只放过 npm 包名与 github: 的字符白名单，
        // 把 #commit 锁定、#path: 子目录、gitlab:/bitbucket:、完整 git URL、
        // 远程 tarball、jsr:、命名 registry、本地 .tgz 全挡在门外。
        eqi("spec: npm 包名", PluginSpec.NPM, PluginSpec.classify("dsh-web-ui"));
        eqi("spec: scoped 包名", PluginSpec.NPM, PluginSpec.classify("@dfy-plugins/dsh-turn-guard"));
        eqi("spec: 带版本", PluginSpec.NPM, PluginSpec.classify("express@1.0.0"));
        eqi("spec: 带 dist-tag", PluginSpec.NPM, PluginSpec.classify("express@nightly"));
        eqi("spec: 带版本范围（含空格）", PluginSpec.NPM, PluginSpec.classify("react@>=0.1.0 <0.2.0"));
        eqi("spec: jsr registry", PluginSpec.JSR, PluginSpec.classify("jsr:@hono/hono@4"));
        eqi("spec: 命名 registry", PluginSpec.NAMED_REGISTRY,
                PluginSpec.classify("gh:@my-org/private-pkg"));
        eqi("spec: git 简写 owner/repo", PluginSpec.GIT_SHORTHAND,
                PluginSpec.classify("kevva/is-positive"));
        eqi("spec: github: 前缀", PluginSpec.GIT_SHORTHAND,
                PluginSpec.classify("github:zkochan/is-negative"));
        eqi("spec: gitlab:", PluginSpec.GIT_SHORTHAND,
                PluginSpec.classify("gitlab:pnpm/git-resolver"));
        eqi("spec: bitbucket:", PluginSpec.GIT_SHORTHAND,
                PluginSpec.classify("bitbucket:pnpmjs/git-resolver"));
        eqi("spec: commit 锁定", PluginSpec.GIT_SHORTHAND,
                PluginSpec.classify("kevva/is-positive#97edff6f525f192a3f83cea1944765f769ae2678"));
        eqi("spec: semver ref", PluginSpec.GIT_SHORTHAND,
                PluginSpec.classify("kevva/is-positive#semver:^2.0.0"));
        eqi("spec: monorepo 子目录 path:", PluginSpec.GIT_SHORTHAND,
                PluginSpec.classify("RexSkz/test#path:/packages/simple-react-app"));
        eqi("spec: 分支与子目录组合", PluginSpec.GIT_SHORTHAND,
                PluginSpec.classify("RexSkz/test.git#beta&path:/packages/app"));
        eqi("spec: git+ssh 完整 URL", PluginSpec.GIT_URL,
                PluginSpec.classify("git+ssh://git@github.com:zkochan/is-negative.git#2.0.1"));
        eqi("spec: https 且 .git 结尾算 git", PluginSpec.GIT_URL,
                PluginSpec.classify("https://github.com/zkochan/is-negative.git#2.0.1"));
        eqi("spec: 远程 tarball（http 且非 .git）", PluginSpec.TARBALL_URL,
                PluginSpec.classify("https://github.com/indexzero/forever/tarball/v0.5.6"));
        eqi("spec: 本地目录", PluginSpec.LOCAL_DIR, PluginSpec.classify("./plugins/turn-guard"));
        eqi("spec: link: 本地 checkout", PluginSpec.LOCAL_DIR,
                PluginSpec.classify("link:/root/plugin-src/x"));
        eqi("spec: 本地压缩包 .tgz", PluginSpec.LOCAL_TARBALL, PluginSpec.classify("./pkg-0.1.0.tgz"));
        eqi("spec: file: 指向压缩包", PluginSpec.LOCAL_TARBALL,
                PluginSpec.classify("file:/root/a.tar.gz"));
        // 安全：以 - 开头会被 pnpm 当命令行选项，引号挡不住这种语义偷换
        ok("spec: 以 - 开头一律拒绝（会被当成 pnpm 选项）",
                !PluginSpec.isUsable("--force") && !PluginSpec.isUsable("-g")
                        && !PluginSpec.isUsable("-D"));
        ok("spec: 控制字符与换行拒绝",
                !PluginSpec.isUsable("a\nb") && !PluginSpec.isUsable("a\tb"));
        ok("spec: 空与 null 拒绝", !PluginSpec.isUsable("") && !PluginSpec.isUsable(null));
        eq("spec: 抠得出 path: 子目录（clone+构建那条路要用）", "packages/app",
                PluginSpec.subPathOf("RexSkz/test.git#beta&path:/packages/app"));
        eq("spec: 没有 path: 时为空", "", PluginSpec.subPathOf("o/r#v1.0"));
        ok("spec: 只有 git 来源算「只带源码」（可能缺构建产物）",
                PluginSpec.shipsSourceOnly(PluginSpec.GIT_SHORTHAND)
                        && PluginSpec.shipsSourceOnly(PluginSpec.GIT_URL)
                        && !PluginSpec.shipsSourceOnly(PluginSpec.NPM)
                        && !PluginSpec.shipsSourceOnly(PluginSpec.TARBALL_URL));
        // 「从哪装」与「叫什么名」是两个判据：owner/repo 是合法来源，但不是合法包名
        ok("spec: 包名判据更严，不放过 owner/repo 与 github:",
                PluginSpec.isPackageName("@a/b") && PluginSpec.isPackageName("abc")
                        && !PluginSpec.isPackageName("owner/repo")
                        && !PluginSpec.isPackageName("github:o/r"));

        // ===== PatchToggle：用官方 patch 层开关插件（不搬文件、HMR 热生效）=====
        String userYaml = "- id: my-own-row\n  config:\n    port: 8080\n";
        java.util.Set<String> off1 = new java.util.LinkedHashSet<>();
        off1.add("minigames");
        String out1 = PatchToggle.withDisabled(userYaml, off1);
        ok("patch: 用户自己写的行一字不动", out1.contains("- id: my-own-row")
                && out1.contains("port: 8080"));
        ok("patch: DSHA 区块加在末尾（后应用的层才盖得住前面 bundle 那一行）",
                out1.indexOf(PatchToggle.BEGIN) > out1.indexOf("my-own-row"));
        ok("patch: 禁用的 id 读得回来", PatchToggle.disabledIds(out1).contains("minigames"));
        eqi("patch: 只禁用了一个", 1, PatchToggle.disabledIds(out1).size());
        String out2 = PatchToggle.withDisabled(out1, new java.util.LinkedHashSet<>());
        ok("patch: 全部启用时连标记一起去掉（不留空区块）",
                !out2.contains(PatchToggle.BEGIN) && !out2.contains(PatchToggle.END));
        ok("patch: 去掉区块后用户内容原样保留",
                out2.contains("- id: my-own-row") && out2.contains("port: 8080"));
        eqi("patch: 去掉区块后禁用集合为空", 0, PatchToggle.disabledIds(out2).size());
        eq("patch: 反复开关不会越写越长（往返回到原样）", out2, PatchToggle.stripBlock(out1));
        // 上面那条比的是两个都过了 stripBlock 的结果，看不出与**原文**的漂移。
        // 实测反复开关 10 轮会在末尾攒下一个空行，所以这条直接跟原文比。
        String ptRound = userYaml;
        for (int i = 0; i < 10; i++) {
            java.util.Set<String> one = new java.util.LinkedHashSet<>();
            one.add("x");
            ptRound = PatchToggle.withDisabled(ptRound, one);
            ptRound = PatchToggle.withDisabled(ptRound, new java.util.LinkedHashSet<>());
        }
        eq("patch: 开关 10 轮之后与原文逐字节一致", userYaml, ptRound);
        java.util.Set<String> off3 = new java.util.LinkedHashSet<>();
        off3.add("@scope/name");
        ok("patch: 带 @ / 的 id 要加引号（否则 YAML 解析出错，整个 loader 起不来）",
                PatchToggle.withDisabled("", off3).contains("'@scope/name'"));
        ok("patch: 加过引号的 id 也读得回来",
                PatchToggle.disabledIds(PatchToggle.withDisabled("", off3)).contains("@scope/name"));
        // 插件自己的 patch 里定的 loader 行 id —— 它跟包名往往不一样，
        // 拿包名去写 disabled 是写不中的（loader 只 warn 一句然后忽略）
        String pluginPatch = "- insert:\n    - id: minigames\n      name: dsh-minigames\n";
        eqi("patch: 抠出插件 insert 的行 id", 1, PatchToggle.insertedIds(pluginPatch).size());
        eq("patch: 行 id 不等于包名（这正是必须抠它的原因）", "minigames",
                PatchToggle.insertedIds(pluginPatch).get(0));
        ok("patch: 注释掉的行不算 id", PatchToggle.insertedIds("# - id: fake\n").isEmpty());
        ok("patch: 没有 patch 文件时不炸", PatchToggle.disabledIds(null).isEmpty()
                && PatchToggle.insertedIds(null).isEmpty()
                && PatchToggle.stripBlock(null).isEmpty());

        // allowBuilds：dsh 在错误消息里明确给出的修法（prepare 脚本被 pnpm 挡住时）
        eq("patch: 空文件写出 allowBuilds", "allowBuilds:\n  dsh-x: true\n",
                PatchToggle.withAllowBuild("", "dsh-x"));
        String ws1 = PatchToggle.withAllowBuild("packages:\n  - 'a/*'\n", "dsh-x");
        ok("patch: pnpm-workspace 里已有的配置不动",
                ws1.contains("packages:") && ws1.contains("- 'a/*'"));
        ok("patch: 追加了 allowBuilds 段",
                ws1.contains("allowBuilds:") && ws1.contains("dsh-x: true"));
        eq("patch: 授权幂等（重试逻辑会反复调它）", ws1, PatchToggle.withAllowBuild(ws1, "dsh-x"));
        String ws2 = PatchToggle.withAllowBuild(ws1, "@s/y");
        ok("patch: 第二个包插进已有的 allowBuilds 段（不再新建一段）",
                ws2.contains("dsh-x: true") && ws2.contains("'@s/y': true")
                        && ws2.indexOf("allowBuilds:") == ws2.lastIndexOf("allowBuilds:"));
        ok("patch: scoped 包名当键要加引号",
                PatchToggle.withAllowBuild("", "@s/y").contains("'@s/y': true"));

        // 分层候选：GitHub monorepo zip 的最浅一层是仓库管理包，真插件在下一层。
        // 只取最浅一层会把管理包装进去、真插件一个都不装 —— 而且它还会「装成功」，
        // 因为管理包本身是个合法 npm 包。真实归档的端到端验证见 tools/archive-e2e-test.sh。
        java.util.List<String[]> ghLayers = ArchiveProbe.pluginRootsByDepth(new String[]{
                "repo-main/package.json", "repo-main/plugins/x/package.json",
                "repo-main/plugins/y/package.json"});
        eqi("archive: 候选按深度分两层", 2, ghLayers.size());
        eq("archive: 最浅一层是仓库根", "[repo-main]",
                java.util.Arrays.toString(ghLayers.get(0)));
        eqi("archive: 下一层是两个真插件", 2, ghLayers.get(1).length);
        eq("archive: pluginRoots 仍返回最浅那层（向后兼容）", "[repo-main]",
                java.util.Arrays.toString(ArchiveProbe.pluginRoots(new String[]{
                        "repo-main/package.json", "repo-main/plugins/x/package.json"})));

        // ===== MarketCol：市场行的列语义 =====
        // 这个类是为了挡住已经出过两次的同一类错：往某一列塞了别的东西 ——
        // it[4] 一条路填分类另一条填 npm 名（安装时拿 "ui" 当包名去装）、
        // it[3] 一条路填兼容性另一条填收录日期（「仅显示兼容」一条也筛不掉）。
        String[] good = new String[MarketCol.NPM + 1];
        good[MarketCol.NAME] = "dsh-x";
        good[MarketCol.STARS] = "55";
        good[MarketCol.OWNER] = "someone";
        good[MarketCol.COMPAT] = "⏳待定";
        good[MarketCol.CATEGORY] = "UI 增强";
        good[MarketCol.DESC] = "描述";
        good[MarketCol.URL] = "https://github.com/someone/dsh-x";
        good[MarketCol.NPM] = "dsh-x";
        ok("market: 正常一行通过自检", MarketCol.isSaneRow(good));
        String[] dateInCompat = good.clone();
        dateInCompat[MarketCol.COMPAT] = "2026-08-14";
        ok("market: 日期落进兼容性列必须被挡下（筛选器就是这么失效的）",
                !MarketCol.isSaneRow(dateInCompat));
        String[] badStars = good.clone();
        badStars[MarketCol.STARS] = "ui";
        ok("market: 星标列不是数字要挡下（否则排序静默乱掉）", !MarketCol.isSaneRow(badStars));
        String[] noName = good.clone();
        noName[MarketCol.NAME] = "";
        ok("market: 名字为空要挡下", !MarketCol.isSaneRow(noName));
        ok("market: 列数不够要挡下", !MarketCol.isSaneRow(new String[]{"a", "1"}));
        eq("market: npmOf 取出 npm 包名", "dsh-x", MarketCol.npmOf(good));
        String[] notNpm = good.clone();
        notNpm[MarketCol.NPM] = "仅GitHub仓库";
        eq("market: 不是合法包名的 npm 列当作没有", "", MarketCol.npmOf(notNpm));
        ok("market: 日期形状识别", MarketCol.looksLikeDate("2026-08-14")
                && MarketCol.looksLikeDate("2026/8/1")
                && !MarketCol.looksLikeDate("⏳待定")
                && !MarketCol.looksLikeDate("✅ 已验证"));
        eq("market: at() 越界给空串", "", MarketCol.at(good, 99));
        eq("market: at() 顺手去空格", "dsh-x", MarketCol.at(new String[]{"  dsh-x  "}, 0));

        // ---------- MarketSearch：市场搜索的匹配范围 ----------
        // 原来只比名称一列，而索引里插件名多半是 dsh-xxx 缩写 —— 用户搜「主题」「语音」
        // 一条也出不来，看起来就像市场里根本没有这类插件。这批断言钉住「搜什么该命中」，
        // 以及三条刻意不匹配的列（URL / 星标 / 兼容性标记）。
        String[] mkt = good.clone();
        mkt[MarketCol.NAME] = "dsh-theme-neo";
        mkt[MarketCol.OWNER] = "AdamPlatin123";
        mkt[MarketCol.CATEGORY] = "UI 增强";
        mkt[MarketCol.DESC] = "给 Web UI 换配色的主题插件";
        mkt[MarketCol.NPM] = "dsh-theme-neo";
        ok("search: 没输入就不筛", MarketSearch.matches(mkt, MarketSearch.terms("  ")));
        ok("search: 按名称命中", MarketSearch.matches(mkt, MarketSearch.terms("theme")));
        ok("search: 按中文描述命中（索引描述取的是 description.zh）",
                MarketSearch.matches(mkt, MarketSearch.terms("主题")));
        ok("search: 按作者命中", MarketSearch.matches(mkt, MarketSearch.terms("adamplatin")));
        ok("search: 按分类命中", MarketSearch.matches(mkt, MarketSearch.terms("增强")));
        ok("search: 大小写不敏感", MarketSearch.matches(mkt, MarketSearch.terms("THEME")));
        ok("search: 多词要全部命中", MarketSearch.matches(mkt, MarketSearch.terms("主题 配色")));
        ok("search: 一个词不命中就排除（AND 不是 OR）",
                !MarketSearch.matches(mkt, MarketSearch.terms("主题 备份")));
        ok("search: 不拿 URL 参与匹配（否则搜 github 命中全部）",
                !MarketSearch.matches(mkt, MarketSearch.terms("github")));
        ok("search: 不拿星标参与匹配（搜 55 不该命中）",
                !MarketSearch.matches(mkt, MarketSearch.terms("55")));
        ok("search: 7 列的老 Markdown 索引不越界（越界会让整页崩在过滤循环里）",
                MarketSearch.matches(
                        new String[]{"dsh-x", "1", "o", "⏳待定", "分类", "说明", "u"},
                        MarketSearch.terms("说明")));
        eqi("search: 连续空白不产生空词", 2, MarketSearch.terms("  a   b  ").length);

        // ---------- WebProcSel：停止的进程判据 ----------
        // 这批断言的由来：「停止」已经改坏过三轮，每次病根都在判据上，而症状全都长一样
        //（点了没反应 / 停了又复活），只能靠真机反复试。把判据钉在这里，改错当场就红。
        //
        // 真机地面真相：/usr/local/bin/dsh 是软链 → lib/bin.js，启动命令用 readlink -f
        // 解到真实文件，所以 cmdline 是「node --expose-internals …/dsh/lib/bin.js web」。
        ok("stop: 认出预构建模式的 dsh（…/dsh/lib/bin.js web）",
                WebProcSel.looksLikeWeb("node --expose-internals "
                        + "/usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js web"));
        ok("stop: 认出源码模式（apps/cli/lib/bin.js web --port 3080）",
                WebProcSel.looksLikeWeb("node --expose-internals apps/cli/lib/bin.js web --port 3080"));
        ok("stop: 认出看门狗", WebProcSel.looksLikeWeb("bash /root/dsh-watchdog.sh"));
        // 重启脚本正在跑 = 马上会有一个新的 Web 进程，漏掉它就是「秒复活」
        ok("stop: 认出看门狗的重启脚本", WebProcSel.looksLikeWeb("bash /root/dsh-cmd.txt"));
        ok("stop: 认出手动重启脚本", WebProcSel.looksLikeWeb("bash /root/dsh-web-restart.sh"));
        // 下面两条是「App 自杀」的防线：proot 不隔离 PID，它的命令行里带着 rootfs 路径和
        // 待执行命令，bin.js / web 都可能出现在里面 —— 杀到它等于把整个环境连 App 一起带走
        ok("stop: 绝不认容器启动器 proot（杀它 = App 一起死）",
                !WebProcSel.looksLikeWeb("/data/user/0/com.dsh.client/files/linux/libproot.so "
                        + "-r /data/.../ubuntu bash -c node apps/cli/lib/bin.js web"));
        ok("stop: 绝不认 proroot（切了运行时也不能失效）",
                !WebProcSel.looksLikeWeb("/data/user/0/com.dsh.client/files/libproroot.so bash -c dsh web"));
        // 这条是「误杀用户/agent 的 node」的防线（历史上用过 pkill -f node）
        ok("stop: 不碰用户自己的 node", !WebProcSel.looksLikeWeb("node server.js"));
        ok("stop: 不碰装包中的 pnpm", !WebProcSel.looksLikeWeb("node /usr/local/bin/pnpm install"));
        // 端口反查用 /proc/net/tcp，端口是大写十六进制补四位。算错就一个都查不到，
        // 而且完全静默 —— 正是这类「兜底把失败藏起来」的写法坑过两次
        eq("stop: 3080 → /proc/net/tcp 的 0C08", "0C08", WebProcSel.portHex(3080));
        eq("stop: 8080 → 1F90", "1F90", WebProcSel.portHex(8080));
        eq("stop: 80 补到四位", "0050", WebProcSel.portHex(80));
        ok("stop: 找进程的片段排除自身与两种容器启动器",
                WebProcSel.pidsDsh(true).contains("*pids_dsh*|*pids_port*|*proot*|*proroot*"));
        ok("stop: 停止时把看门狗一起算成目标",
                WebProcSel.pidsDsh(true).contains("*dsh-watchdog*|*\"bin.js web\"*"));
        ok("stop: 看门狗自己调用时把自己排除（否则它杀自己）",
                WebProcSel.pidsDsh(false).contains("|*dsh-watchdog*) continue")
                        && !WebProcSel.pidsDsh(false).contains("*dsh-watchdog*|*\"bin.js web\"*"));
        ok("stop: 端口反查只认 LISTEN（st=0A）",
                WebProcSel.pidsPort(3080).contains("$4==\"0A\""));
        ok("stop: 哨兵是容器内 /root 下的路径",
                WebProcSel.STOP_SENTINEL.startsWith("/root/"));
        // pid 文件通道：这条是主力（/proc/net 读不到、/proc 只见同 uid，按长相找进程不可靠）
        ok("stop: pid 通道认 Web 与看门狗两个文件",
                WebProcSel.pidsFile().contains(WebProcSel.PID_WEB)
                        && WebProcSel.pidsFile().contains(WebProcSel.PID_WATCHDOG));
        ok("stop: pid 通道杀之前核对身份（pid 会回卷复用，杀错就是杀别人）",
                WebProcSel.pidsFile().contains("'*node*'")
                        && WebProcSel.pidsFile().contains("'*dsh-watchdog*'"));
        ok("stop: pid 通道也排除容器启动器",
                WebProcSel.pidsFile().contains("*proot*|*proroot*"));
        eq("stop: pid 文件的 rootfs 相对路径（Android 侧用 File 直接读）",
                "root/.dsha-web.pid", WebProcSel.pidFileRel(WebProcSel.PID_WEB));

        // ---------- AssetBatch：一次会话跑多个自愈脚本 ----------
        // 切分错了的后果是静默的：比如 fs-write-patch 的输出被分到别人名下，
        // 「PATCHED」就再也匹配不到，功能照跑但记账没了，没人会发现。
        java.util.List<String> remotes = java.util.Arrays.asList("a.sh", "b.sh", "c.sh");
        String cmd = AssetBatch.buildCommand("SEP1", remotes);
        ok("batch: 每个脚本都 bash 了并删掉临时文件",
                cmd.contains("bash /root/a.sh; rm -f /root/a.sh")
                        && cmd.contains("bash /root/c.sh; rm -f /root/c.sh"));
        ok("batch: 每个脚本前打一行哨兵", cmd.split("echo SEP1", -1).length == 4);
        // 用 ; 而不是 && —— 前一个脚本失败时后面的照样跑（和「各自一次会话」等价）
        ok("batch: 不用 && 串联（失败不影响后面）", !cmd.contains("&&"));

        java.util.List<String> names = java.util.Arrays.asList("a.sh", "b.sh", "c.sh", "d.sh");
        // 真实形状：第一个哨兵之前可能有容器启动器的提示；c.sh 什么都没打印
        String sample = "proot info: something\nSEP\nAAA\nSEP\nline1\nPATCHED ok\nSEP\nSEP\nD1\nD2\n";
        java.util.Map<String, String> got = AssetBatch.splitOutput("SEP", names, sample);
        eq("batch: 第一个脚本的输出", "AAA", got.get("a.sh"));
        eq("batch: 多行输出完整保留", "line1\nPATCHED ok", got.get("b.sh"));
        eq("batch: 没有输出的脚本给空串（不能错位）", "", got.get("c.sh"));
        eq("batch: 最后一个脚本的输出", "D1\nD2", got.get("d.sh"));
        ok("batch: 前导段（启动器提示）不算进任何脚本",
                !String.valueOf(got.get("a.sh")).contains("proot info"));
        // 整批被超时打断：后面的脚本给空串，调用方按「没输出」处理，不能抛
        java.util.Map<String, String> cut = AssetBatch.splitOutput("SEP", names, "\nSEP\nAAA\n");
        eq("batch: 超时截断时已跑的那个仍取得到", "AAA", cut.get("a.sh"));
        eq("batch: 超时截断时没跑的给空串", "", cut.get("d.sh"));
        eq("batch: 输出为 null 时全给空串", "",
                AssetBatch.splitOutput("SEP", names, null).get("a.sh"));

        // ---------- WatchdogScript：看门狗与重启脚本的结构不变量 ----------
        // 这两段是拼字符串拼出来的，写错全是静默的。语法由 tools/stop-proc-test.sh 用
        // bash -n 真跑一遍，这里钉住「顺序」和「引用方式」——它们错了语法照样合法。
        String wd = WatchdogScript.watchdog(3080);
        ok("watchdog: 有 shebang", wd.startsWith("#!/bin/bash\n"));
        int whereWhile = wd.indexOf("while true; do\n");
        int whereSentinel = wd.indexOf("if [ -f " + WebProcSel.STOP_SENTINEL + " ]");
        int wherePids = wd.indexOf("pids_dsh() {");
        ok("watchdog: 哨兵检查在循环里（放循环外就只在启动那一刻看一次）",
                whereWhile > 0 && whereSentinel > whereWhile);
        ok("watchdog: 进程判据在循环开始前就定义好", wherePids > 0 && wherePids < whereWhile);
        ok("watchdog: 幂等闸（pid 文件 + kill -0）在最前面",
                wd.indexOf("_wd_alive()") > 0 && wd.indexOf("_wd_alive()") < wherePids);
        ok("watchdog: 失联判定是 3 次、间隔 30 秒",
                wd.contains("-ge 3") && wd.contains("sleep 30"));
        ok("watchdog: 重启走 dsh-cmd.txt（不直接内联启动命令）",
                wd.contains("nohup bash /root/dsh-cmd.txt"));
        ok("watchdog: 探测端口用传进来的那个", wd.contains("PORT=3080"));

        String rs = WatchdogScript.restart("export DEEPSEEK_API_KEY='k'\n",
                ShellQuote.arg("workspace-write"), "deepseek-harness", "exec node bin.js web");
        ok("restart: 有 shebang", rs.startsWith("#!/bin/bash\n"));
        int rsSentinel = rs.indexOf("if [ -f " + WebProcSel.STOP_SENTINEL + " ]");
        ok("restart: 哨兵检查在任何实际动作之前（这是「停止后不许被拉起」的最后一道闸）",
                rsSentinel > 0 && rsSentinel < rs.indexOf("export DSH_HOME")
                        && rsSentinel < rs.indexOf("mkdir -p"));
        ok("restart: 启动命令在最后", rs.trim().endsWith("exec node bin.js web"));
        ok("restart: 常规工作区名生成的文本与手工包裹一致（改引用方式不影响老行为）",
                rs.contains("cd /root/'deepseek-harness' || exit 1"));
        // 工作区名用户可改。手工 '…' 包裹遇到单引号会重新配对：bash -n 照样过，
        // 但 mkdir/cd 指向另一个目录（实测 it's work → its），且完全静默。
        String rsQ = WatchdogScript.restart("", ShellQuote.arg("read-only"), "it's work", "true");
        ok("restart: 工作区名带单引号时按 ShellQuote 转义（不是裸的 '…'）",
                rsQ.contains("cd /root/'it'\\''s work' || exit 1"));

        // ===== DangerShellGuard：确认弹窗的判据 =====
        // 这批断言补的是 2026-09-01 那个真机 bug 的回归：adb-shell.py 当时无条件带
        // force=1，而桥端 force=1 的语义是「所有命令都确认」，于是连 am start 打开应用
        // 都要点一次。判据本身没病，病在报备端把它整个短路了 —— isDangerous() 里那段
        // 专门处理 adb 通道的判据从写下来就没生效过。去掉 force=1 之后它才第一次真正
        // 开始工作，所以现在必须有测试盯着，否则改判据会静默回归成「什么都拦」或
        //「什么都不拦」，而这两种坏法在 UI 上都不明显。
        String[] dangerCmds = {
                "adb shell rm -rf /sdcard/x", "adb shell pm uninstall com.x",
                "adb shell reboot", "adb shell wipe data", "adb exec-out rm -f /sdcard/a",
        };
        for (String c : dangerCmds) {
            ok("isDangerous 仍拦：" + c, DangerShellGuard.isDangerous(c));
        }
        String[] safeCmds = {
                "adb shell am start -n com.a/.B", "adb shell input tap 100 200",
                "adb shell monkey -p com.a 1", "adb shell dumpsys window",
                "adb shell settings get system font_scale",
        };
        for (String c : safeCmds) {
            ok("isDangerous 放行：" + c, !DangerShellGuard.isDangerous(c));
        }
        // 我们自己注入的包装不算用户意图 —— 那段 `source dsh-guard.sh 2>/dev/null;` 里的
        // `>` 曾让「覆盖关键路径」那条判据对任何提到关键路径的命令生效，于是每条都要确认。
        ok("isDangerous 放行：带 dsh-guard.sh 包装的普通命令",
                !DangerShellGuard.isDangerous("source /root/dsh-guard.sh 2>/dev/null; ls /root"));
        ok("isDangerous 仍拦：rm${IFS}-rf 这类分隔符混淆",
                DangerShellGuard.isDangerous("rm${IFS}-rf /sdcard/x"));
        // 旧正则 [a-z]'\s*'[a-z] 会把这种正常写法也算成 r''m 混淆，导致大量误拦
        ok("isDangerous 放行：echo 'a' 'b'（正常的两个带引号参数）",
                !DangerShellGuard.isDangerous("echo 'a' 'b'"));

        System.out.println();
        System.out.println(fail == 0
                ? "全部通过：" + pass + " 条"
                : "失败 " + fail + " 条（通过 " + pass + "）");
        System.exit(fail == 0 ? 0 : 1);
    }

    /** 极简 POSIX 单引号反解：把 {@link ShellQuote#arg} 的输出还原成 shell 实际看到的值。
     *  单引号内一切字面量，引号外只处理反斜杠转义 —— 这就是 sh 对这两种结构的全部规则。 */
    private static String unquoteSingle(String s) {
        StringBuilder out = new StringBuilder();
        boolean inQuote = false;
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\'') {
                inQuote = !inQuote;
                i++;
            } else if (!inQuote && c == '\\' && i + 1 < s.length()) {
                out.append(s.charAt(i + 1));
                i += 2;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** 转义结果是否只构成「一个」shell 词：引号外不许出现空白或元字符，
     *  唯一允许的引号外字符是 {@code \} 加一个字符的转义序列。
     *  这条不成立就意味着能拼出新的参数或新的命令 —— 也就是注入。 */
    private static boolean isSingleShellWord(String s) {
        boolean inQuote = false;
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\'') {
                inQuote = !inQuote;
                i++;
            } else if (inQuote) {
                i++;                       // 引号内一切字面量
            } else if (c == '\\' && i + 1 < s.length()) {
                i += 2;                    // 引号外唯一合法形态
            } else {
                return false;              // 引号外的裸字符：空格、; 、| 、$ 都算越界
            }
        }
        return !inQuote;                   // 引号必须闭合
    }

    /** 造一个最小可用的 tar.gz：只用到 name/size/typeflag 三个头字段 + 校验和。 */
    private static void writeTarGz(java.io.File out, String[][] entries, boolean withEndBlocks)
            throws Exception {
        try (java.io.OutputStream fo = new java.io.FileOutputStream(out);
             java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(fo)) {
            for (String[] e : entries) {
                byte[] body = e[1].getBytes("UTF-8");
                byte[] h = new byte[512];
                byte[] name = e[0].getBytes("UTF-8");
                System.arraycopy(name, 0, h, 0, Math.min(name.length, 100));
                put(h, 100, "0000644\0");                       // mode
                put(h, 108, "0000000\0");                       // uid
                put(h, 116, "0000000\0");                       // gid
                put(h, 124, String.format("%011o", body.length) + "\0");
                put(h, 136, String.format("%011o", 0) + "\0");  // mtime
                h[156] = '0';                                   // 普通文件
                put(h, 257, "ustar\0" + "00");
                for (int i = 148; i < 156; i++) h[i] = ' ';     // checksum 先填空格
                int sum = 0;
                for (byte b : h) sum += (b & 0xFF);
                put(h, 148, String.format("%06o", sum) + "\0 ");
                gz.write(h);
                gz.write(body);
                int pad = (512 - body.length % 512) % 512;
                if (pad > 0) gz.write(new byte[pad]);
            }
            if (withEndBlocks) gz.write(new byte[1024]);
        }
    }

    private static void put(byte[] buf, int off, String s) {
        byte[] b = s.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, buf, off, b.length);
    }

    private static boolean rejectsInside(java.io.File root, String relative) {
        try {
            SafeFiles.inside(root, relative);
            return false;
        } catch (java.io.IOException expected) {
            return true;
        }
    }

    private static void deleteRec(java.io.File f) {
        java.io.File[] kids = f.listFiles();
        if (kids != null) for (java.io.File k : kids) deleteRec(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /** 拼一个最小请求头：请求行 + 可选附加头 + 空行结尾。 */
    private static String req(String reqLine, String extraHeaders) {
        StringBuilder sb = new StringBuilder(reqLine).append(" HTTP/1.1\r\n");
        sb.append("Host: 192.168.1.5:3081\r\n");
        if (!extraHeaders.isEmpty()) sb.append(extraHeaders).append("\r\n");
        sb.append("\r\n");
        return sb.toString();
    }

    private static void eq(String name, String expected, String actual) {
        boolean good = expected == null ? actual == null : expected.equals(actual);
        report(name, good, String.valueOf(expected), String.valueOf(actual));
    }

    private static void eqi(String name, int expected, int actual) {
        report(name, expected == actual, String.valueOf(expected), String.valueOf(actual));
    }

    private static void ok(String name, boolean good) {
        report(name, good, "true", String.valueOf(good));
    }

    private static void ok(String name, boolean good, String extra) {
        report(name, good, "true", extra);
    }

    private static void report(String name, boolean good, String expected, String actual) {
        if (good) {
            pass++;
            System.out.println("  ok   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name + "\n         期望: " + expected + "\n         实际: " + actual);
        }
    }
}
