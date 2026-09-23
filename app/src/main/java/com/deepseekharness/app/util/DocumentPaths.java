package com.deepseekharness.app.util;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** SAF 稳定文档编号及容器软链接解析；不依赖 Android 或宿主对 guest 链接的识别。 */
public final class DocumentPaths {
    public interface Links { String read(File path) throws IOException; }
    public interface Alias {
        String rewrite(String relative)throws IOException;
        default boolean child(String parent,String child,File actual)throws IOException{return false;}
    }
    private final File base, guest;
    private final Links links;
    private final Alias alias;
    public DocumentPaths(File base, Links links) throws IOException {
        this(base,links,relative->relative);
    }
    public DocumentPaths(File base,Links links,Alias alias)throws IOException{
        this.base = base.getCanonicalFile(); this.guest = new File(this.base, "linux/ubuntu"); this.links = links;
        this.alias=alias;
    }
    public File base() { return base; }
    public String relative(String id) throws IOException {
        if (id == null) throw new IOException(com.deepseekharness.app.util.UiText.text("文档编号为空"));
        if (id.isEmpty() || id.equals("root")) return "";
        if (id.startsWith("/") || id.indexOf('\\') >= 0 || id.indexOf('\0') >= 0)
            throw new IOException(com.deepseekharness.app.util.UiText.text("无效的文档路径"));
        for (String part : id.split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.indexOf(':') >= 0)
                throw new IOException(com.deepseekharness.app.util.UiText.text("无效的文档路径"));
        return id;
    }
    public String id(String relative) throws IOException {
        String value = relative(relative); return value.isEmpty() ? "root" : value;
    }
    public String child(String parent, String name) throws IOException {
        checkName(name); String relative = relative(parent);
        return relative.isEmpty() ? name : relative + "/" + name;
    }
    public static void checkName(String name) throws IOException {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0
                || name.indexOf(':') >= 0) throw new IOException(com.deepseekharness.app.util.UiText.text("名称不能包含路径分隔符或 . / .."));
    }
    public String parent(String id) throws IOException {
        String relative = relative(id); int slash = relative.lastIndexOf('/');
        return slash < 0 ? "root" : relative.substring(0, slash);
    }
    public boolean anchor(String id) throws IOException {
        String relative = relative(id);
        return relative.isEmpty() || relative.equals("linux") || relative.equals("linux/ubuntu")
                || relative.equals("linux/ubuntu/root")||relative.equals("linux/ubuntu/root/.dsh")||relative.equals("user-data-v5/dsh");
    }
    public boolean childOf(String parent, String child) throws IOException {
        String p = relative(parent), c = relative(child);
        if (p.equals(c) || (!p.isEmpty() && !c.startsWith(p + "/"))) return false;
        // 同时检查逻辑层级和实际目标，子目录授权不能借链接读取其他目录。
        File parentFile = resolve(parent, true), target = resolve(child, true);
        return (within(parentFile, target)||alias.child(p,c,target)) && !parentFile.equals(target);
    }
    public File resolve(String id, boolean followLast) throws IOException {
        ArrayDeque<String> pending = parts(relative(alias.rewrite(relative(id)))); File current = base; int followed = 0;
        while (!pending.isEmpty()) {
            File next = new File(current, pending.removeFirst());
            String target = !pending.isEmpty() || followLast ? links.read(next) : null;
            if (target == null) { current = next; continue; }
            if (++followed > 40) throw new IOException(com.deepseekharness.app.util.UiText.text("软链接循环或层级过多"));
            File linked;
            if (target.startsWith("/") || new File(target).isAbsolute()) {
                File absolute = new File(target), canonical = absolute.getCanonicalFile();
                // Android /data/data 与 /data/user/0 的宿主别名只在仍位于私有目录时接受。
                if (within(base, absolute)) linked = normalize(base, absolute.getPath().substring(base.getPath().length()), false);
                else if (within(base, canonical)) linked = canonical;
                else if (within(guest, next)) linked = normalize(guest, target, true);
                else throw new IOException(com.deepseekharness.app.util.UiText.text("软链接目标超出 DSHA 目录"));
            } else {
                File boundary = within(guest, next) ? guest : base;
                String prefix = current.getPath().substring(boundary.getPath().length());
                linked = normalize(boundary, prefix + "/" + target, boundary.equals(guest));
            }
            if (!within(base, linked)) throw new IOException(com.deepseekharness.app.util.UiText.text("软链接路径越界"));
            String rel = linked.equals(base) ? "" : linked.getPath().substring(base.getPath().length() + 1);
            ArrayDeque<String> replacement = parts(relative(alias.rewrite(rel.replace(File.separatorChar, '/'))));
            replacement.addAll(pending); pending = replacement; current = base;
        }
        if (!within(base, current)) throw new IOException(com.deepseekharness.app.util.UiText.text("文档路径越界"));
        return current;
    }
    private static ArrayDeque<String> parts(String value) {
        ArrayDeque<String> parts = new ArrayDeque<>();
        if (!value.isEmpty()) for (String part : value.split("/")) if (!part.isEmpty()) parts.add(part);
        return parts;
    }
    private static File normalize(File boundary, String path, boolean guestRoot) throws IOException {
        List<String> parts = new ArrayList<>();
        for (String part : path.replace(File.separatorChar, '/').split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (!parts.isEmpty()) parts.remove(parts.size() - 1);
                else if (!guestRoot) throw new IOException(com.deepseekharness.app.util.UiText.text("软链接路径越界"));
            } else parts.add(part);
        }
        File result = boundary; for (String part : parts) result = new File(result, part); return result;
    }
    public static boolean within(File base, File target) {
        String parent = base.getAbsolutePath(), child = target.getAbsolutePath();
        return child.equals(parent) || child.startsWith(parent + File.separator);
    }
}
