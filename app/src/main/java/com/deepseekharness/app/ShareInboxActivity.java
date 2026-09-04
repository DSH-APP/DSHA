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
 * <p>落地位置选公开的 {@code Download/DSHA/收件/}，不是 rootfs 里的工作区。两个原因：
 * 一是容器已经把 {@code /storage/emulated/0} 整根挂到 {@code /root/手机存储}，
 * agent 读得到；二是用户自己也能在文件管理器里看到、确认东西真的进来了 ——
 * 写进私有目录的话「分享成功」只能靠一句提示，出问题无从排查。
 *
 * <p>没有界面（透明主题 + 立刻 finish）。分享是个动作，不该为它开一个页面让人再点一次确认。
 */
public class ShareInboxActivity extends Activity {

    /** 单个文件的上限。分享来的东西大小不受我们控制，而 rootfs 在 App 私有目录 ——
     *  一个几个 G 的视频灌进来会把用户的存储和整个环境一起拖死。 */
    private static final long MAX_BYTES = 512L * 1024 * 1024;

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
                android.util.Log.w("DSHA", "接收分享失败: " + t);
                result = "接收失败：" + t;
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
        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri u = it.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
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
        for (int i = 0; i < uris.size(); i++) {
            String name = displayName(ctx, uris.get(i));
            if (name == null || name.isEmpty()) {
                name = "文件-" + stamp + (uris.size() > 1 ? "-" + (i + 1) : "");
            }
            File out = unique(dir, name);
            try (InputStream in = ctx.getContentResolver().openInputStream(uris.get(i));
                 OutputStream os = new FileOutputStream(out)) {
                if (in == null) continue;
                byte[] buf = new byte[64 * 1024];
                int n;
                long total = 0;
                boolean tooBig = false;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_BYTES) {
                        tooBig = true;
                        break;
                    }
                    os.write(buf, 0, n);
                }
                if (tooBig) {
                    os.close();
                    // 半成品不留下 —— 否则用户看到文件在那儿，实际是截断的
                    if (!out.delete()) {
                        android.util.Log.w("DSHA", "超限文件删除失败: " + out);
                    }
                    skipped++;
                    continue;
                }
            }
            files++;
        }

        CharSequence text = it.getCharSequenceExtra(Intent.EXTRA_TEXT);
        String subject = it.getStringExtra(Intent.EXTRA_SUBJECT);
        boolean wroteText = false;
        if (text != null && text.length() > 0) {
            // 存成 markdown 而不是 txt：链接与引用能直接读，agent 也更容易解析结构。
            File out = unique(dir, "分享-" + stamp + ".md");
            StringBuilder sb = new StringBuilder();
            if (subject != null && !subject.isEmpty()) sb.append("# ").append(subject).append("\n\n");
            sb.append(text);
            sb.append("\n");
            try (OutputStream os = new FileOutputStream(out)) {
                os.write(sb.toString().getBytes("UTF-8"));
            }
            wroteText = true;
        }

        if (files == 0 && !wroteText) {
            return skipped > 0 ? "文件超过 512MB，没有保存" : "没有可保存的内容";
        }
        StringBuilder msg = new StringBuilder("已放进工作区的「收件」");
        if (files > 0) msg.append("　文件 ").append(files).append(" 个");
        if (skipped > 0) msg.append("　跳过 ").append(skipped)
                .append(" 个（超过 512MB）");
        if (wroteText) msg.append("　文本 1 份");
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
            String workdir = ctx.getSharedPreferences("deepseekharness", MODE_PRIVATE)
                    .getString("workdir", "deepseek-harness");
            if (workdir == null || workdir.trim().isEmpty()) workdir = "deepseek-harness";
            File dir = new File(ctx.getFilesDir(),
                    "linux/ubuntu/root/" + workdir + "/" + PublicDirs.INBOX);
            if (!dir.exists() && !dir.mkdirs()) return null;
            return dir.isDirectory() ? dir : null;
        } catch (Throwable t) {
            android.util.Log.w("DSHA", "收件目录不可用: " + t);
            return null;
        }
    }

    /** 从 content Uri 取原始文件名。取不到就返回 null，交给调用方兜底命名。 */
    private String displayName(android.content.Context ctx, Uri u) {
        if (u == null) return null;
        try (android.database.Cursor c = ctx.getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return sanitize(c.getString(idx));
            }
        } catch (Throwable ignored) {
        }
        String last = u.getLastPathSegment();
        return last == null ? null : sanitize(last);
    }

    private String sanitize(String s) {
        if (s == null) return null;
        // 路径分隔符与控制字符一律换掉 —— 分享来的文件名是外部输入，不能直接拼路径。
        return s.replaceAll("[/\\\\\\x00-\\x1f]", "_").trim();
    }

    /** 同名时加序号，不覆盖已有文件。 */
    private File unique(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        String base = name, ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        for (int i = 2; i < 1000; i++) {
            File c = new File(dir, base + "-" + i + ext);
            if (!c.exists()) return c;
        }
        return new File(dir, base + "-" + System.currentTimeMillis() + ext);
    }
}
