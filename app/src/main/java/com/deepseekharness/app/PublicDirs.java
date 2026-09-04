package com.deepseekharness.app;

/**
 * DSHA 在公开 Download 目录下的目录布局 —— <b>唯一</b>定义。
 *
 * <pre>
 *   Download/DSHA/存档/    备份（存档）
 *   Download/DSHA/插件/    单个插件导出
 *   Download/DSHA/下载/    WebUI 里触发的文件下载
 *   Download/DSHA/         老版本把所有东西都堆在这里 —— 仍然要能读
 * </pre>
 *
 * <p><b>为什么收成一个类</b>：写入公开目录的代码原先有三份（BackupManager 的
 * MediaStore 写入与直写、PluginController 的 copyToDownloads），路径各自硬编码
 * {@code DIRECTORY_DOWNLOADS + "/DSHA"}。再往里分子目录的话就是三处各改一遍，
 * 而漏掉一处的症状是「文件不见了」——用户根本不知道该去哪找。
 *
 * <p><b>老目录必须一直兼容</b>：已经躺在用户手机上的备份都在 {@code DSHA/} 根下，
 * 迁移新目录不能让它们从恢复列表里消失。所以扫描按 {@link #archiveSubdirs()} 的顺序
 * 走「新目录 + 老目录」两处，写入只用新目录。
 *
 * <p>刻意不碰 Android API（{@code Environment.DIRECTORY_DOWNLOADS} 由调用方传进来），
 * 好让 {@code tools/pure-logic-test.sh} 直接对拼路径下断言。
 */
final class PublicDirs {

    /** 一级目录名。 */
    static final String ROOT = "DSHA";

    /** 备份（存档）。 */
    static final String ARCHIVES = "存档";
    /** 单个插件导出。 */
    static final String PLUGINS = "插件";
    /** WebUI 里触发的文件下载。 */
    static final String DOWNLOADS = "下载";
    /** 分享进来的东西的目录名（ShareInboxActivity 用）。
     *  ⚠️ 它**不在**这个类描述的公开 Download 布局里 —— 实际落在 rootfs 工作区
     *  （见 ShareInboxActivity#inboxDir 的说明）。放在这里只是为了名字有唯一出处，
     *  所以刻意不加进 allSubdirs()：加进去会让备份扫描去找一个永不存在的目录。 */
    static final String INBOX = "收件";
    /** 老版本的位置：DSHA 根目录，没有子目录。 */
    static final String LEGACY = "";

    private PublicDirs() {
    }

    /**
     * MediaStore {@code RELATIVE_PATH} 用的相对路径，<b>不带</b>尾斜杠。
     *
     * @param base 传 {@code Environment.DIRECTORY_DOWNLOADS}（在设备上是 "Download"）
     * @param sub  子目录名，{@link #LEGACY} 或 null 表示 DSHA 根
     */
    static String relative(String base, String sub) {
        String b = base == null ? "" : base;
        return (sub == null || sub.isEmpty()) ? b + "/" + ROOT : b + "/" + ROOT + "/" + sub;
    }

    /** 带尾斜杠的形态。MediaStore 里 RELATIVE_PATH 存的到底带不带尾斜杠因设备而异，
     *  查询时两种都要试 —— 这个坑在 {@code scanExternalBackups} 里已经踩过一次。 */
    static String relativeSlash(String base, String sub) {
        return relative(base, sub) + "/";
    }

    /** 给用户看的绝对路径（拼给提示文案用，不做文件操作）。 */
    static String display(String externalRoot, String base, String sub) {
        String r = externalRoot == null ? "" : externalRoot;
        if (r.endsWith("/")) r = r.substring(0, r.length() - 1);
        return r + "/" + relative(base, sub);
    }

    /** 存档的扫描顺序：新目录优先，老目录兜底（用户手机上已有的备份都在根下）。 */
    static String[] archiveSubdirs() {
        return new String[] { ARCHIVES, LEGACY };
    }

    /** 所有会被 DSHA 写入的子目录（自检与「打开目录」入口用）。 */
    static String[] allSubdirs() {
        return new String[] { ARCHIVES, PLUGINS, DOWNLOADS };
    }

    /** 把插件包名变成能当文件名用的形式：{@code @scope/name} 里的斜杠会被当路径分隔符。 */
    static String safeFileName(String pluginName) {
        if (pluginName == null || pluginName.isEmpty()) return "plugin";
        String s = pluginName;
        if (s.startsWith("@")) s = s.substring(1);          // @dsh-external/x → dsh-external/x
        s = s.replace('/', '-').replace('\\', '-');
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            // 保守白名单：FAT32 与 MediaStore 都不接受 : * ? " < > | 这些
            sb.append((ch == ':' || ch == '*' || ch == '?' || ch == '"'
                    || ch == '<' || ch == '>' || ch == '|') ? '-' : ch);
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? "plugin" : out;
    }
}
