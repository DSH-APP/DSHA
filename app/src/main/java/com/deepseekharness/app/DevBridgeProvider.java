package com.deepseekharness.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;

/**
 * 开发者直连通道：让 ADB shell 直接查状态、读文件、执行容器命令。
 *
 * <p><b>为什么需要它</b>：App 不是 debuggable（issue #43 明确拒绝过），所以
 * {@code run-as} 进不了私有目录；rootfs 又整个躺在私有目录里。此前想在真机上验一件事，
 * 只能靠截图看 UI、靠人转述 —— 上一次就因此在「插件实体是不是残缺」这种能一条命令
 * 问清的事情上来回猜了三轮。{@code DshaDocumentsProvider} 那条 SAF 通道是给文件管理器的，
 * 受 {@code MANAGE_DOCUMENTS}（signature 级）保护，adb 拿不到。
 *
 * <p><b>信任边界</b>：只接受 uid 2000（adb shell）/ 0 / 1000（system）的调用，
 * 普通 App 是 10xxx，进不来。这条线划得住的前提是**每个入口方法都自己核对一次 UID** ——
 * ContentProvider 有六个入口，漏掉任何一个就是任意命令执行漏洞，所以下面每个方法
 * 第一行都是 {@link #guard()}。
 *
 * <p>没有用 {@code android:permission} 声明保护：没有哪个现成权限能表达「只允许 adb」，
 * signature 级权限第三方 App 也可能持有，normal/dangerous 级则谁都能申请。防线放在代码里。
 *
 * <p><b>默认关闭</b>：有 ADB 授权的电脑本来就能装卸应用、读公共存储，但读不了私有目录，
 * 也拿不到容器内的 root 执行能力 —— 这个通道给的是后者，属于提权，所以要用户显式打开
 * （配置页「设备与权限」里那一项），并且每次调用都记进活动日志，事后能查。
 *
 * <p>用法（adb shell）：
 * <pre>
 *   content query --uri content://com.dsh.client.dev/info
 *   content query --uri "content://com.dsh.client.dev/exec?cmd=ls%20/root"
 *   content query --uri "content://com.dsh.client.dev/read?path=root/.dsh/settings.yaml"
 *   content query --uri content://com.dsh.client.dev/token
 * </pre>
 */
public class DevBridgeProvider extends ContentProvider {

    /** prefs 开关键。默认 false —— 这是提权通道，必须用户显式打开。 */
    static final String KEY_ENABLED = "dev_bridge";

    /** 单次输出上限。Binder 事务大概 1MB 就会炸，留足余量；更大的输出让调用方分片取。 */
    private static final int MAX_OUT = 512 * 1024;

    /** 命令默认超时。可以用 ?timeout= 覆盖，上限 10 分钟。 */
    private static final long DEFAULT_TIMEOUT_MS = 30_000L;
    private static final long MAX_TIMEOUT_MS = 600_000L;

    @Override
    public boolean onCreate() {
        return true;
    }

    /** 调用方是否可信 + 开关是否打开。不可信时抛 SecurityException，不返回 null ——
     *  null 会被误读成「没有数据」，而这里要的是明确的拒绝。 */
    private Context guard() {
        int uid = Binder.getCallingUid();
        if (uid != 2000 && uid != 0 && uid != 1000) {
            throw new SecurityException("DevBridge 只接受 adb shell / system 调用，当前 uid=" + uid);
        }
        Context ctx = getContext();
        if (ctx == null) throw new SecurityException("DevBridge 还没就绪");
        boolean on = ctx.getSharedPreferences("deepseekharness", Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false);
        if (!on) {
            throw new SecurityException(
                    "DevBridge 没开。到 DSHA「配置 → 设备与权限 → 开发者直连（ADB）」打开它");
        }
        return ctx;
    }

    private static Cursor one(String column, String value) {
        MatrixCursor c = new MatrixCursor(new String[]{column});
        c.addRow(new Object[]{value == null ? "" : value});
        return c;
    }

    private static String clip(String s) {
        if (s == null) return "";
        if (s.length() <= MAX_OUT) return s;
        return s.substring(0, MAX_OUT) + "\n[…输出被截断，共 " + s.length() + " 字符]";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        Context ctx = guard();
        String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("^/+", "");
        HarnessController hc = HarnessController.get(ctx);
        try {
            switch (path) {
                case "info": {
                    StringBuilder sb = new StringBuilder();
                    sb.append("versionName=").append(appVersion(ctx)).append('\n');
                    sb.append("rootfs=").append(hc.getProot().getRootfsDir()).append('\n');
                    sb.append("stage=").append(hc.getStage()).append('\n');
                    sb.append("percent=").append(hc.getPercent()).append('\n');
                    sb.append("message=").append(hc.getMessage()).append('\n');
                    String err = hc.getError();
                    if (err != null && !err.isEmpty()) sb.append("error=").append(err).append('\n');
                    return one("info", sb.toString());
                }
                case "token":
                    // 桥 token。给出来之后调用方就能直接打 3090 的完整端点集，
                    // 比一条条走这个 provider 顺手。
                    return one("token", HttpShellService.currentToken());
                case "exec": {
                    String cmd = uri.getQueryParameter("cmd");
                    if (cmd == null || cmd.trim().isEmpty()) {
                        return one("error", "缺 cmd 参数");
                    }
                    long timeout = DEFAULT_TIMEOUT_MS;
                    String t = uri.getQueryParameter("timeout");
                    if (t != null) {
                        try {
                            timeout = Math.max(1000L, Math.min(MAX_TIMEOUT_MS, Long.parseLong(t)));
                        } catch (Throwable ignored) {
                        }
                    }
                    hc.logActivity("DevBridge exec（ADB 直连）: "
                            + (cmd.length() > 200 ? cmd.substring(0, 200) + "…" : cmd));
                    String out = hc.getProot().execAndRead(cmd, timeout);
                    return one("out", clip(out));
                }
                case "pref": {
                    // 直接读写配置项。UI 自动化（input tap）在滚动列表上不可靠 —— 要点一个
                    // 控件得先把它滑到可见，滑多少全凭猜，实测四次滑动才碰上一次。
                    // 而验证 UI 效果的前提往往只是「把某个开关拨过去」，走这条一条命令就够。
                    // 不算额外提权：这个通道本来就能在容器里跑任意命令。
                    String k = uri.getQueryParameter("key");
                    String v = uri.getQueryParameter("value");
                    if (k == null || k.isEmpty()) return one("error", "缺 key 参数");
                    android.content.SharedPreferences sp =
                            ctx.getSharedPreferences("deepseekharness", Context.MODE_PRIVATE);
                    if (v == null) {
                        Object cur = sp.getAll().get(k);
                        return one("value", cur == null ? "(未设置)" : String.valueOf(cur));
                    }
                    String type = uri.getQueryParameter("type");
                    android.content.SharedPreferences.Editor e = sp.edit();
                    if ("bool".equals(type)) {
                        e.putBoolean(k, "true".equals(v) || "1".equals(v));
                    } else if ("int".equals(type)) {
                        e.putInt(k, Integer.parseInt(v));
                    } else {
                        e.putString(k, v);
                    }
                    e.apply();
                    hc.logActivity("DevBridge 改配置（ADB 直连）: " + k + "=" + v);
                    return one("ok", k + "=" + v);
                }
                case "read": {
                    String p = uri.getQueryParameter("path");
                    if (p == null || p.isEmpty()) return one("error", "缺 path 参数");
                    // 相对 rootfs 根。刻意不做路径白名单：调用方已经是 adb shell，
                    // 它能读的东西本来就不该靠这一层来限制；但要挡住 .. 逃出 rootfs，
                    // 免得把「读 rootfs 里的文件」变成「读 App 私有目录任意文件」。
                    java.io.File root = hc.getProot().getRootfsDir();
                    java.io.File f = new java.io.File(root, p);
                    String canon = f.getCanonicalPath();
                    if (!canon.startsWith(root.getCanonicalPath())) {
                        return one("error", "路径逃出 rootfs：" + p);
                    }
                    if (!f.isFile()) return one("error", "不是文件或不存在：" + canon);
                    if (f.length() > MAX_OUT) {
                        return one("error", "文件太大（" + f.length() + " 字节），用 exec 分片读");
                    }
                    byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
                    return one("content", new String(b, java.nio.charset.StandardCharsets.UTF_8));
                }
                case "ls": {
                    String p = uri.getQueryParameter("path");
                    java.io.File root = hc.getProot().getRootfsDir();
                    java.io.File d = (p == null || p.isEmpty()) ? root : new java.io.File(root, p);
                    if (!d.isDirectory()) return one("error", "不是目录：" + d);
                    java.io.File[] kids = d.listFiles();
                    StringBuilder sb = new StringBuilder();
                    if (kids != null) {
                        java.util.Arrays.sort(kids, (a, b2) -> a.getName().compareTo(b2.getName()));
                        for (java.io.File k : kids) {
                            sb.append(k.isDirectory() ? "d " : "- ")
                              .append(k.length()).append(' ')
                              .append(k.getName()).append('\n');
                        }
                    }
                    return one("list", clip(sb.toString()));
                }
                default:
                    return one("error", "不认识的路径：" + path
                            + "（可用：info / token / exec / read / ls / pref）");
            }
        } catch (SecurityException e) {
            throw e;
        } catch (Throwable t) {
            return one("error", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    // ── 下面四个入口一律先 guard()：漏一个就是任意命令执行 ──────────────

    @Override
    public String getType(Uri uri) {
        guard();
        return "text/plain";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        guard();
        throw new UnsupportedOperationException("DevBridge 不支持 insert");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        guard();
        throw new UnsupportedOperationException("DevBridge 不支持 delete");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        guard();
        throw new UnsupportedOperationException("DevBridge 不支持 update");
    }

    @Override
    public android.os.ParcelFileDescriptor openFile(Uri uri, String mode)
            throws java.io.FileNotFoundException {
        guard();
        throw new java.io.FileNotFoundException(
                "DevBridge 不提供文件句柄：大文件用 exec 写到公共目录再 adb pull");
    }

    /** 自己的版本号。ContentProvider 里拿不到 BuildConfig 之外的上下文，直接问 PM 最稳。 */
    private static String appVersion(Context ctx) {
        try {
            return ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }
}
