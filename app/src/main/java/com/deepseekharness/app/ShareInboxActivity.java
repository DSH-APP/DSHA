package com.deepseekharness.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 分享目标：从任何 App 把文本、链接、文件送进 agent 能读到的目录。
 *
 * <p><b>为什么值得做。</b>手机上真正稀缺的不是算力而是「把东西递进去」的通道。之前想让
 * agent 处理一段网页文字或一个下载好的文件，得先用 MT 之类的文件管理器搬到工作区 ——
 * 而 rootfs 在私有目录，普通文件管理器根本进不去。挂上系统的分享菜单之后，浏览器、
 * 聊天软件、相册里选「分享到 DSHA」就完事。
 *
 * <p>落地在 rootfs 当前工作区的 {@code 收件/}。容器能直接读取，不需要额外存储权限；
 * 用户若要查看或转出，走 DSHA 的文件共享/导出入口。
 *
 * <p>没有界面（透明主题 + 立刻 finish）。分享是个动作，不该为它开一个页面让人再点一次确认。
 */
public class ShareInboxActivity extends Activity {

    /** 单个文件、整次分享与文本的硬上限：分享目标是外部输入，不能被一串 URI 灌满私有存储。 */
    private static final long MAX_BYTES = 512L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 512L * 1024 * 1024;
    private static final int MAX_FILES = 32;
    private static final int MAX_TEXT_CHARS = 256 * 1024;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        final Intent it = getIntent();
        // 用 application context：这个 Activity 下一行就 finish 了，之后再用它的
        // ContentResolver 是未定义行为。线程也因此不持有 Activity。
        final android.content.Context app = getApplicationContext();
        // 复制必须离开主线程 —— 分享一个几百 MB 的视频，在主线程上做就是 ANR。
        new Thread(() -> {
            String result;
            try {
                result = handle(app, it);
            } catch (Throwable t) {
                android.util.Log.w("DSHA", "接收分享失败: "
                        + SensitiveData.redact(String.valueOf(t)));
                result = "接收失败：无法读取分享内容";
            }
            final String msg = result;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                    Toast.makeText(app, msg, Toast.LENGTH_LONG).show());
        }, "dsha-share-inbox").start();
        finish();
    }

    private String handle(android.content.Context ctx, Intent it) throws Exception {
        if (it == null) return "没有收到内容";
        File dir = inboxDir(ctx);
        if (dir == null) return "收件目录不可用（环境还没解压好？）";

        String action = it.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            return "不支持的分享动作";
        }
        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri u = it.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else {
            ArrayList<Uri> list = it.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        }
        // 某些 App 只塞 ClipData 不塞 EXTRA_STREAM（尤其是拖放和部分相册）
        ClipData cd = it.getClipData();
        if (uris.isEmpty() && cd != null) {
            for (int i = 0; i < cd.getItemCount(); i++) {
                Uri u = cd.getItemAt(i).getUri();
                if (u != null) uris.add(u);
            }
        }

        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        int files = 0;
        int skipped = 0;
        int rejected = 0;
        long copied = 0;
        for (int i = 0; i < uris.size(); i++) {
            if (i >= MAX_FILES || copied >= MAX_TOTAL_BYTES) {
                skipped += uris.size() - i;
                break;
            }
            Uri uri = uris.get(i);
            if (!shareUriAllowed(ctx, it, uri)) {
                rejected++;
                continue;
            }
            String name = displayName(ctx, uri);
            if (name == null || name.isEmpty()) {
                name = "文件-" + stamp + (uris.size() > 1 ? "-" + (i + 1) : "");
            }
            File out = SafeFiles.reserve(dir, name);
            boolean kept = false;
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    skipped++;
                    continue;
                }
                long fileBytes = 0;
                boolean tooBig = false;
                try (OutputStream os = new FileOutputStream(out)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    int emptyReads = 0;
                    while ((n = in.read(buf)) != -1) {
                        if (n == 0) {
                            if (++emptyReads > 3) throw new java.io.IOException("分享流没有进展");
                            continue;
                        }
                        emptyReads = 0;
                        if (fileBytes + n > MAX_BYTES || copied + fileBytes + n > MAX_TOTAL_BYTES) {
                            tooBig = true;
                            break;
                        }
                        os.write(buf, 0, n);
                        fileBytes += n;
                    }
                }
                if (tooBig) {
                    skipped++;
                    continue;
                }
                copied += fileBytes;
                files++;
                kept = true;
            } catch (Throwable e) {
                skipped++;
                android.util.Log.w("DSHA", "读取分享文件失败: "
                        + SensitiveData.redact(String.valueOf(e)));
            } finally {
                // reserve() 已经创建了目标文件；任何失败、拒绝或超限都不能留下假文件。
                if (!kept && out.exists() && !out.delete()) {
                    android.util.Log.w("DSHA", "未完成的分享文件删除失败");
                }
            }
        }

        CharSequence text = it.getCharSequenceExtra(Intent.EXTRA_TEXT);
        String subject = it.getStringExtra(Intent.EXTRA_SUBJECT);
        boolean wroteText = false;
        boolean textTruncated = false;
        if (text != null && text.length() > 0) {
            String body = text.toString();
            if (body.length() > MAX_TEXT_CHARS) {
                body = body.substring(0, MAX_TEXT_CHARS);
                textTruncated = true;
            }
            if (subject != null && subject.length() > 4096) subject = subject.substring(0, 4096);
            // 存成 markdown 而不是 txt：链接与引用能直接读，agent 也更容易解析结构。
            File out = SafeFiles.reserve(dir, "分享-" + stamp + ".md");
            boolean kept = false;
            try (OutputStream os = new FileOutputStream(out)) {
                StringBuilder sb = new StringBuilder();
                if (subject != null && !subject.isEmpty()) sb.append("# ").append(subject).append("\n\n");
                sb.append(body).append("\n");
                os.write(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                kept = true;
                wroteText = true;
            } finally {
                if (!kept) out.delete();
            }
        }

        if (files == 0 && !wroteText) {
            if (rejected > 0) return "分享内容没有授予读取权限，未保存";
            return skipped > 0 ? "文件超过限制或无法读取，没有保存" : "没有可保存的内容";
        }
        StringBuilder msg = new StringBuilder("已放进工作区的「收件」");
        if (files > 0) msg.append("　文件 ").append(files).append(" 个");
        if (skipped > 0) msg.append("　跳过 ").append(skipped).append(" 个（超过限制或无法读取）");
        if (rejected > 0) msg.append("　拒绝 ").append(rejected).append(" 个（没有读取授权）");
        if (wroteText) msg.append("　文本 1 份");
        if (textTruncated) msg.append("（文本已截到 256KB）");
        msg.append("\n容器内路径：~/收件/");
        return msg.toString();
    }

    /** 落地目录：rootfs 工作区里的「收件」。
     *
     *  <p>本来想放公开的 {@code Download/DSHA/收件/}，让用户在文件管理器里也能看见 ——
     *  但 Android 10 起分区存储生效，直接按 File 路径写公共目录会 EACCES，得走 MediaStore
     *  （BackupManager 就是这么做的）。而 MediaStore 写出来的文件，容器要经
     *  {@code /root/手机存储} 那个 FUSE 挂载才读得到，中间还隔着一层限制。
     *
     *  <p>写进 rootfs 反而干净：App 私有内部目录，无需任何权限，agent 在容器里就是
     *  {@code ~/<工作区>/收件/}，路径最短。用户想看的话走 DSHA 自己的文件共享入口。 */
    private File inboxDir(android.content.Context ctx) {
        try {
            HarnessController hc = HarnessController.get(ctx);
            String workdir = hc.getWorkdir(); // 统一走已有的工作区白名单与旧值修复
            File root = new File(ctx.getFilesDir(), "linux/ubuntu/root");
            File dir = SafeFiles.inside(root, workdir + "/" + PublicDirs.INBOX);
            if (!dir.exists() && !dir.mkdirs()) return null;
            return dir.isDirectory() ? dir : null;
        } catch (Throwable t) {
            android.util.Log.w("DSHA", "收件目录不可用: "
                    + SensitiveData.redact(String.valueOf(t)));
            return null;
        }
    }

    /** 外部分享只能读被系统授予的 content Uri，且绝不反向打开自己的 provider。 */
    private static boolean shareUriAllowed(android.content.Context ctx, Intent intent, Uri uri) {
        if (ctx == null || intent == null || uri == null) return false;
        boolean grantFlag = (intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0;
        boolean granted = grantFlag || ctx.checkUriPermission(uri, android.os.Process.myPid(),
                android.os.Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        return SafeFiles.shareSourceAllowed(uri.getScheme(), uri.getAuthority(),
                ctx.getPackageName(), granted);
    }

    /** 从 content Uri 取原始文件名。取不到就返回 null，交给调用方兜底命名。 */
    private String displayName(android.content.Context ctx, Uri u) {
        if (u == null) return null;
        try (android.database.Cursor c = ctx.getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return SafeFiles.cleanName(c.getString(idx));
            }
        } catch (Throwable ignored) {
        }
        String last = u.getLastPathSegment();
        return last == null ? null : SafeFiles.cleanName(last);
    }
}
