package com.deepseekharness.app;

import java.io.File;

/** 仅测试包注册，使用 cache 下独立文件树，不读写真实容器或个人数据。 */
public final class DocumentsAuditProvider extends DshaDocumentsProvider {
    @Override protected File documentBase() {
        File dir = new File(getContext().getCacheDir(), "documents-audit-fixture"); dir.mkdirs(); return dir;
    }
}
