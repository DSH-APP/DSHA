package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 安装六步的只读探测协议。成功必须有命令退出码，不能从版本输出的首行猜测。 */
public final class InstallProbe {
    private InstallProbe() { }
    public static final String SESSION_BASE = "/usr/local/lib/node_modules/@deepseek-ai/";
    public static final String SESSION_TOP = SESSION_BASE + "dsh-session-persistence-jsonl/lib/index.js";
    public static final String SESSION_NESTED = SESSION_BASE + "dsh/node_modules/@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js";
    public static final String SESSION_PUBLISH_IMPORT = "import { publishSessionExclusive as link } from \"dsha-runtime-fs\";";
    /** alpha.2 的会话后端用受管 cwd 直达提示，仍保留真实磁盘读取与格式校验。 */
    public static final String SESSION_DIRECT_HINT_MARKER = "DSHA_SESSION_DIRECT_HINTS_V1";
    public static final String SETTINGS = SESSION_BASE + "dsh/node_modules/@deepseek-ai/dsh-client-ui-settings/lib/client.js";

    public static final class Check {
        public final int step;
        public final String key, label, command;
        Check(int step, String key, String label, String command) {
            this.step = step; this.key = key; this.label = label; this.command = command;
        }
    }
    public static List<Check> checks(int selected) {
        List<Check> all = new ArrayList<>();
        all.add(new Check(2, "curl", "curl", "curl --version"));
        all.add(new Check(2, "git", "git", "git --version"));
        all.add(new Check(2, "python", com.deepseekharness.app.util.UiText.text("Python 与标准库"), "python3 -B -c 'import ssl, sqlite3, readline, tarfile, zipfile, sys; print(sys.version); ssl.create_default_context()'"));
        all.add(new Check(3, "node", "Node.js", "node --version"));
        all.add(new Check(4, "pnpm", "pnpm", "pnpm --version"));
        all.add(new Check(5, "dsh", com.deepseekharness.app.util.UiText.text("dsh 入口与版本"), "test -x /usr/local/bin/dsh && node -e "
                + ShellQuote.arg("const p=require('/usr/local/lib/node_modules/@deepseek-ai/dsh/package.json'); if(!/^\\d+\\./.test(p.version))process.exit(1); console.log(p.version)")));
        all.add(new Check(6, "dns", com.deepseekharness.app.util.UiText.text("DNS 配置"), "grep -Eq '^[[:space:]]*nameserver[[:space:]]+[^[:space:]#]+' /etc/resolv.conf || { echo '缺少 nameserver 配置'; exit 1; }"));
        all.add(new Check(6, "session", com.deepseekharness.app.util.UiText.text("会话写入补丁"), "found=0; for f in " + ShellQuote.arg(SESSION_TOP) + " " + ShellQuote.arg(SESSION_NESTED)
                + "; do [ -f \"$f\" ] || continue; found=1; if grep -Fq 'await link(tmp, finalPath)' \"$f\" && ! grep -Fq "
                + ShellQuote.arg(SESSION_PUBLISH_IMPORT) + " \"$f\" && ! grep -Fq " + ShellQuote.arg(SESSION_DIRECT_HINT_MARKER)
                + " \"$f\"; then echo \"会话写入补丁缺失：$f\"; exit 1; fi; done; [ \"$found\" = 1 ] || { echo '会话模块缺失'; exit 1; }"));
        all.add(new Check(6, "settings", com.deepseekharness.app.util.UiText.text("局域网设置补丁"), "test -f " + ShellQuote.arg(SETTINGS)
                + " || { echo '设置模块缺失'; exit 1; }; if grep -Fq "
                + ShellQuote.arg("const persistence = ctx.remote.$host.isLoopback ? \"host\" : \"memory\";")
                + " " + ShellQuote.arg(SETTINGS) + "; then echo '局域网设置补丁缺失'; exit 1; fi"));
        all.add(new Check(6, "groups", com.deepseekharness.app.util.UiText.text("Android 用户组"), "id -Gn"));
        if (selected != 0) all.removeIf(check -> check.step != selected);
        return all;
    }

    public static String script(List<Check> checks) {
        StringBuilder script = new StringBuilder("export PYTHONDONTWRITEBYTECODE=1 CI=1 NO_UPDATE_NOTIFIER=1 PNPM_DISABLE_SELF_UPDATE_CHECK=1\n");
        for (Check check : checks) {
            script.append("printf '%s\\n' 'DSHA_CHECK_BEGIN:").append(check.key).append("'\n");
            // 子检查上限避免一个坏命令吞掉其余步骤；容器总超时由 InstallProcess 兜底。
            script.append("if command -v timeout >/dev/null 2>&1; then timeout 20s /bin/bash -c ")
                    .append(ShellQuote.arg(check.command)).append("; else /bin/bash -c ")
                    .append(ShellQuote.arg(check.command)).append("; fi\n");
            script.append("code=$?; printf '\\nDSHA_CHECK_RESULT:").append(check.key).append(":%s\\n' \"$code\"\n");
        }
        return script.append("exit 0\n").toString();
    }

    /** 只接受本次探测中的已知键；缺失、重复或无效结果均不能算成功。 */
    public static final class Results {
        private final List<Check> checks;
        private final Map<String, Integer> codes = new LinkedHashMap<>();
        public Results(List<Check> checks) { this.checks = checks; }
        public Check beginning(String line) {
            if (!line.startsWith("DSHA_CHECK_BEGIN:")) return null;
            String key = line.substring("DSHA_CHECK_BEGIN:".length());
            for (Check check : checks) if (check.key.equals(key)) return check;
            return null;
        }
        public synchronized boolean accept(String line) {
            if (!line.startsWith("DSHA_CHECK_RESULT:")) return false;
            String[] parts = line.split(":", -1);
            if (parts.length != 3) return false;
            for (Check check : checks) {
                if (!check.key.equals(parts[1])) continue;
                try {
                    int code = Integer.parseInt(parts[2]);
                    codes.put(check.key, codes.containsKey(check.key) || code < 0 || code > 255 ? -1 : code);
                } catch (NumberFormatException error) { codes.put(check.key, -1); }
                return true;
            }
            return false;
        }
        public synchronized boolean ok(String key) { return Integer.valueOf(0).equals(codes.get(key)); }
        public synchronized boolean ok(int step) {
            boolean found = false;
            for (Check check : checks) if (check.step == step) { found = true; if (!ok(check.key)) return false; }
            return found;
        }
        public synchronized String detail(int step) {
            StringBuilder out = new StringBuilder();
            for (Check check : checks) if (check.step == step) {
                if (out.length() > 0) out.append(com.deepseekharness.app.util.UiText.text("；"));
                Integer code = codes.get(check.key);
                out.append(check.label).append(code == null ? com.deepseekharness.app.util.UiText.text("：未收到结果") : code == 0 ? com.deepseekharness.app.util.UiText.text("：正常") : com.deepseekharness.app.util.UiText.text("：失败（退出码 ") + code + com.deepseekharness.app.util.UiText.text("）"));
            }
            return out.toString();
        }
    }

    /** 只改已知补丁位置，不遍历工作区、会话数据或运行全量 l2s 修复。 */
    public static String patchScript() {
        String python = "import os, stat, tempfile\n"
                + "def write(path, text):\n"
                + " mode=stat.S_IMODE(os.stat(path).st_mode) if os.path.exists(path) else 0o644\n"
                + " fd,tmp=tempfile.mkstemp(prefix='.dsha-install-',dir=os.path.dirname(path))\n"
                + " try:\n"
                + "  with os.fdopen(fd,'w') as f: f.write(text); f.flush(); os.fsync(f.fileno())\n"
                + "  os.chmod(tmp,mode); os.replace(tmp,path)\n"
                + " finally:\n"
                + "  if os.path.exists(tmp): os.unlink(tmp)\n"
                + "def read(path):\n"
                + " with open(path) as f: return f.read()\n"
                + "p='/etc/resolv.conf'\n"
                + "s=read(p) if os.path.isfile(p) else ''\n"
                + "import re\n"
                + "if not re.search(r'^\\s*nameserver\\s+[^\\s#]+',s,re.M): write(p,s+'\\nnameserver 8.8.8.8\\nnameserver 223.5.5.5\\n'); print('已补齐 DNS',flush=True)\n"
                + "for p in ['" + SESSION_TOP + "','" + SESSION_NESTED + "']:\n"
                + " if not os.path.isfile(p): continue\n"
                + " s=read(p)\n"
                + " if 'await link(tmp, finalPath)' in s and '" + SESSION_PUBLISH_IMPORT + "' not in s:\n"
                + "  old='import { link, mkdir, mkdtemp, open,'\n"
                + "  if old not in s or 'await link(tmp, finalPath);' not in s: raise RuntimeError('会话模块结构已变化，保留原文件：'+p)\n"
                + "  s=s.replace('await link(tmp, finalPath);','await rename(tmp, finalPath);').replace(old,'import { mkdir, mkdtemp, open, rename,')\n"
                + "  write(p,s); print('已修复会话写入：'+p,flush=True)\n"
                + "p='" + SETTINGS + "'\n"
                + "if os.path.isfile(p):\n"
                + " s=read(p); old='const persistence = ctx.remote.$host.isLoopback ? \"host\" : \"memory\";'\n"
                + " if old in s: write(p,s.replace(old,'const persistence = \"host\"; // DSHA patch: LAN')); print('已修复局域网设置',flush=True)\n";
        return "python3 -B -u -c " + ShellQuote.arg(python);
    }
}
