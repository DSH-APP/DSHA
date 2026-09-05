package com.deepseekharness.app;

/**
 * 「什么算用户数据」的<b>唯一</b>定义。备份、恢复、重解压三条路都必须问这里。
 *
 * <p><b>为什么要有这个类</b>：这三条路原先各自维护一份判断，而它们必须一致 ——
 * 只要有一处认为某个文件是用户数据、另一处认为不是，就会出现「跨环境带回了不该带的东西」
 * 这类静默故障。真机上已经付过两次代价：
 *
 * <ul>
 *   <li><b>3090 桥 token 错位</b>：BackupManager 备份时明确排除 {@code .dsh/.bridge_token}
 *       （那是本机凭据），而重解压的数据保护把整个 {@code .dsh} 备份还原、把它带了回来。
 *       结果 App 校验内存里的新 token、插件与 skill 读到还原的旧 token，一律 401 ——
 *       现象却是「悬浮窗不显示 + agent 工具调用失败」，而不走桥的 UI 注入、提示词注入全好，
 *       完全看不出是 token。</li>
 *   <li><b>内置插件全部消失</b>：{@code .dsh/builtin-assets.version} 是「本机已安置到哪个版本」
 *       的标记，而插件实体在 {@code /root/dsha-*}。重解压换掉整个 rootfs（实体全没），
 *       却把 {@code .dsh} 还原回来（标记还活着）→ 判定「已是最新」→ 永不重装。</li>
 * </ul>
 *
 * <p>两次的形状完全一样：<b>标记住在会被保护的地方，它描述的东西住在会被换掉的地方。</b>
 * 所以判断只能有一份，而且要能被断言覆盖 —— 见 {@code tools/pure-logic-test.sh} 里
 * 「同一份定义派生出的两个列表必须一一对应」那几条。
 *
 * <p>这个类刻意不碰任何 Android API：只做路径字符串处理，方便纯逻辑测试。
 */
final class UserDataPolicy {

    /**
     * 本机专属、<b>不算用户数据</b>的文件（rootfs 相对路径）。
     *
     * <p>判定标准只有一条：<b>它描述的对象是否可能被换掉，而它自己会被保护下来。</b>
     * 符合这条的就必须列在这里，让它跟着环境一起失效、由 App 重新生成。
     */
    static final String[] MACHINE_LOCAL_PATHS = {
            // 3090 桥凭据。每台机器、每次重装都该重新生成：带进备份还会让公共 Download
            // 目录里的备份包泄露本机 token（任何有存储权限的 App 都读得到）。
            "root/.dsh/.bridge_token",
            // 内置插件「已安置到哪个版本」的标记。插件实体在 /root/dsha-*，rootfs 一换就没了，
            // 这个标记必须跟着失效，否则 App 会认为无事可做、插件永不重装。
            "root/.dsh/builtin-assets.version",
    };

    /**
     * 按<b>文件名</b>判定的本机专属文件（出现在任何目录下都算）。
     *
     * <p>与上面那份精确路径列表互补：有些文件的位置是运行时才决定的，写不成固定路径。
     * {@code session.lock} 就是这种 —— dsh 0.1.3 起每个会话目录里都有一个，
     * 路径形如 {@code .dsh/sessions/<项目目录>/<会话 id>/session.lock}。
     *
     * <p>它必须被排除的理由与 {@code .bridge_token} 完全同类：<b>它描述的是「本机哪个进程
     * 正在写这个会话」</b>，跟着备份跑到另一台机器、或者跟着镜像恢复回来，都只会是一个
     * 陈旧的空壳。锁本身由内核按 inode 维护，文件内容没有任何可迁移的含义。
     */
    static final String[] MACHINE_LOCAL_NAMES = {
            "session.lock",
    };

    private UserDataPolicy() {
    }

    /** 这个路径是不是本机专属（因而不算用户数据）。传 rootfs 相对路径，允许带前导斜杠。 */
    static boolean isMachineLocal(String rootfsRelPath) {
        if (rootfsRelPath == null) return false;
        String p = normalize(rootfsRelPath);
        if (p.isEmpty()) return false;
        for (String m : MACHINE_LOCAL_PATHS) {
            if (p.equals(m)) return true;
        }
        String name = p.substring(p.lastIndexOf('/') + 1);
        for (String n : MACHINE_LOCAL_NAMES) {
            if (name.equals(n)) return true;
        }
        return false;
    }

    /**
     * 备份时给 tar 的 {@code --exclude} 模式。
     *
     * <p>备份的工作目录是 rootfs 里的 {@code /root}，所以这里去掉 {@code root/} 前缀。
     * 这个前缀转换本身也在纯逻辑断言覆盖范围内 —— 写错了备份就会带上凭据而没人发现。
     */
    static String[] tarExcludePatterns() {
        String[] out = new String[MACHINE_LOCAL_PATHS.length + MACHINE_LOCAL_NAMES.length];
        for (int i = 0; i < MACHINE_LOCAL_PATHS.length; i++) {
            out[i] = stripRootPrefix(MACHINE_LOCAL_PATHS[i]);
        }
        // 名字型条目直接给 tar：它的 --exclude 默认是 unanchored 的，
        // 单独一个 session.lock 就能匹配任意深度目录下的同名文件。
        System.arraycopy(MACHINE_LOCAL_NAMES, 0, out, MACHINE_LOCAL_PATHS.length,
                MACHINE_LOCAL_NAMES.length);
        return out;
    }

    /** 拼好的 tar 参数串（含单引号，末尾带一个空格），直接插进 shell 命令。 */
    static String tarExcludeArgs() {
        StringBuilder sb = new StringBuilder();
        for (String p : tarExcludePatterns()) {
            // 路径全是本文件里的字面量，不含单引号；仍然加引号，避免以后有人加带空格的路径
            sb.append("--exclude='").append(p).append("' ");
        }
        return sb.toString();
    }

    /**
     * 恢复备份、或重解压还原数据之后，必须删掉的文件（rootfs 相对路径）。
     *
     * <p>与 {@link #tarExcludePatterns()} 同源：新备份里本来就没有这些文件，但老备份有、
     * 而重解压的数据保护是整目录复制回来的，所以还得再清一遍。
     */
    static String[] purgeAfterRestore() {
        return MACHINE_LOCAL_PATHS.clone();
    }

    /**
     * 恢复之后要按名字清理的文件，以及从哪个子树开始找。
     *
     * <p>{@link #purgeAfterRestore()} 那份是固定路径，直接 delete 就行；这份的位置是
     * 运行时才定的（每个会话目录里一个 {@code session.lock}），所以调用方需要递归。
     * 限定搜索根是刻意的 —— 全 rootfs 扫一遍名字既慢又容易误伤。
     */
    static String purgeNamesSearchRoot() {
        return "root/.dsh/sessions";
    }

    /** 与 {@link #purgeNamesSearchRoot()} 配对：在那棵子树下按名字删掉这些文件。 */
    static String[] purgeAfterRestoreNames() {
        return MACHINE_LOCAL_NAMES.clone();
    }

    private static String normalize(String p) {
        String s = p.replace('\\', '/').trim();
        while (s.startsWith("/")) s = s.substring(1);
        while (s.startsWith("./")) s = s.substring(2);
        return s;
    }

    private static String stripRootPrefix(String p) {
        return p.startsWith("root/") ? p.substring("root/".length()) : p;
    }
}
