package com.deepseekharness.app;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.Arrays;

/**
 * SAF 文件提供器：把 rootfs 那棵树交给系统的文件选择器，MT / 系统「文件」这类
 * 管理器就能直接浏览、编辑 App 私有目录里的内容。
 *
 * <p>此前只靠 MT 的私有 provider 契约（MTDataFilesProvider），换别的文件管理器就
 * 没有入口；而 SAF 是所有管理器都认的标准通道。声明侧用 {@code MANAGE_DOCUMENTS}
 * 保护（signature 级，只有系统 DocumentsUI 持有），第三方 App 只能经系统选择器
 * 间接访问。
 *
 * <p>暴露范围**只有** {@code files/linux/ubuntu/root}：每个 documentId 都会先
 * canonicalize 再核对是否仍在这棵树里，越界直接抛 SecurityException。
 */
public final class DshaDocumentsProvider extends DocumentsProvider {
    @Override public boolean onCreate() { return true; }
    private static final String ROOT = "root";
    private static final String[] ROOT_PROJECTION = {
            DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_ICON, DocumentsContract.Root.COLUMN_MIME_TYPES};
    private static final String[] DOC_PROJECTION = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED};

    /** rootfs 的 /root 就是暴露的根；拿不到 Context 时给一个必然不存在的路径，宁可空也不越界。 */
    private File base() {
        android.content.Context context = getContext();
        if (context == null) return new File("/data/empty");
        return new File(context.getFilesDir(), "linux/ubuntu/root");
    }
    /** documentId → 真实文件。canonical 之后必须仍在 base 内，否则拒绝（防 ../ 与软链逃逸）。 */
    private File file(String id) throws FileNotFoundException {
        if (id == null || id.isEmpty() || ROOT.equals(id)) return base();
        try {
            return SafeFiles.inside(base(), id);
        } catch (java.io.IOException e) {
            throw new FileNotFoundException("invalid document");
        }
    }
    private String id(File f) throws java.io.IOException {
        File b = base().getCanonicalFile();
        File p = f.getCanonicalFile();
        if (!SafeFiles.isInside(b, p)) throw new java.io.IOException("outside root");
        return p.equals(b) ? ROOT : p.getPath().substring(b.getPath().length() + 1)
                .replace(File.separatorChar, '/');
    }
    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor c = new MatrixCursor(projection == null ? ROOT_PROJECTION : projection);
        MatrixCursor.RowBuilder r = c.newRow();
        r.add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT);
        r.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT);
        r.add(DocumentsContract.Root.COLUMN_TITLE, "DSHA");
        r.add(DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.FLAG_SUPPORTS_CREATE
                        | DocumentsContract.Root.FLAG_LOCAL_ONLY);
        r.add(DocumentsContract.Root.COLUMN_ICON, com.deepseekharness.app.R.mipmap.ic_launcher);
        r.add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*");
        return c;
    }
    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        MatrixCursor c = new MatrixCursor(projection == null ? DOC_PROJECTION : projection);
        include(c, file(id), id); return c;
    }
    @Override public Cursor queryChildDocuments(String parentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        MatrixCursor c = new MatrixCursor(projection == null ? DOC_PROJECTION : projection);
        File p = file(parentId); File[] fs = p.listFiles();
        if (fs != null) {
            int shown = 0;
            for (File f : fs) {
                if (shown >= 5000) break; // 恶意/损坏目录不能一次塞爆 Binder Cursor
                try { include(c, f, id(f)); shown++; } catch (Exception ignored) { }
            }
        }
        return c;
    }
    private void include(MatrixCursor c, File f, String id) {
        MatrixCursor.RowBuilder r = c.newRow();
        r.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, id);
        r.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, f.getName().isEmpty() ? "DSHA" : f.getName());
        r.add(DocumentsContract.Document.COLUMN_MIME_TYPE, f.isDirectory() ? DocumentsContract.Document.MIME_TYPE_DIR : "application/octet-stream");
        r.add(DocumentsContract.Document.COLUMN_FLAGS, f.isDirectory() ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE : DocumentsContract.Document.FLAG_SUPPORTS_WRITE);
        r.add(DocumentsContract.Document.COLUMN_SIZE, f.isFile() ? f.length() : null);
        r.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, f.lastModified());
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        // 必须走 parseMode：SAF 客户端传的是 "r" / "w" / "wt" / "wa" / "rw" / "rwt"。
        // 自己 contains("w") → MODE_READ_WRITE 会**漏掉 truncate** —— 文件管理器保存一个
        // 变短了的文件时，尾部会残留上一版的内容（改 package.json 这种最容易踩到）。
        return ParcelFileDescriptor.open(file(id),
                ParcelFileDescriptor.parseMode(mode == null || mode.isEmpty() ? "r" : mode));
    }
    @Override public String createDocument(String parentId, String mimeType, String displayName) throws FileNotFoundException {
        File p = file(parentId);
        if (!p.isDirectory()) throw new FileNotFoundException("parent is not a directory");
        try {
            // DocumentsUI 的 displayName 也是外部输入。先清理、再 canonicalize，创建之前就
            // 把 ../ 与软链逃逸挡住，不能等 id(out) 时才发现已经在目录外写过了。
            String name = SafeFiles.cleanName(displayName);
            if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
                throw new java.io.IOException("invalid display name");
            }
            File out = SafeFiles.inside(p, name);
            boolean made = DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)
                    ? out.mkdir() : out.createNewFile();
            if (!made) throw new java.io.IOException("exists or create failed");
            return id(out);
        } catch (Exception e) {
            throw new FileNotFoundException("cannot create document");
        }
    }
    @Override public void deleteDocument(String id) throws FileNotFoundException {
        if (id == null || id.isEmpty() || ROOT.equals(id)) throw new FileNotFoundException("cannot delete root");
        File f = file(id);
        if (!f.delete()) throw new FileNotFoundException("cannot delete document");
    }
    @Override public String getDocumentType(String id) throws FileNotFoundException { File f=file(id); return f.isDirectory() ? DocumentsContract.Document.MIME_TYPE_DIR : "application/octet-stream"; }
}
