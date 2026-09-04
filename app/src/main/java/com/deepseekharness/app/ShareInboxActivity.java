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

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String result;
        try {
            result = handle(getIntent());
        } catch (Throwable t) {
            android.util.Log.w("DSHA", "接收分享失败: " + t);
            result = "接收失败：" + t;
        }
        Toast.makeText(this, result, Toast.LENGTH_LONG).show();
        finish();
    }

    private String handle(Intent it) throws Exception {
        if (it == null) return "没有收到内容";
        File dir = inboxDir();
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
        for (int i = 0; i < uris.size(); i++) {
            String name = displayName(uris.get(i));
            if (name == null || name.isEmpty()) {
                name = "文件-" + stamp + (uris.size() > 1 ? "-" + (i + 1) : "");
            }
            File out = unique(dir, name);
            try (InputStream in = getContentResolver().openInputStream(uris.get(i));
                 OutputStream os = new FileOutputStream(out)) {
                if (in == null) continue;
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
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

        if (files == 0 && !wroteText) return "没有可保存的内容";
        StringBuilder msg = new StringBuilder("已放进工作区的「收件」");
        if (files > 0) msg.append("　文件 ").append(files).append(" 个");
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
    private File inboxDir() {
        try {
            String workdir = getSharedPreferences("deepseekharness", MODE_PRIVATE)
                    .getString("workdir", "deepseek-harness");
            if (workdir == null || workdir.trim().isEmpty()) workdir = "deepseek-harness";
            File dir = new File(getFilesDir(),
                    "linux/ubuntu/root/" + workdir + "/" + PublicDirs.INBOX);
            if (!dir.exists() && !dir.mkdirs()) return null;
            return dir.isDirectory() ? dir : null;
        } catch (Throwable t) {
            android.util.Log.w("DSHA", "收件目录不可用: " + t);
            return null;
        }
    }

    /** 从 content Uri 取原始文件名。取不到就返回 null，交给调用方兜底命名。 */
    private String displayName(Uri u) {
        if (u == null) return null;
        try (android.database.Cursor c = getContentResolver().query(u, null, null, null, null)) {
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
