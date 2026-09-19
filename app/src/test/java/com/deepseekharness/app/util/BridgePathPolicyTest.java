package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * 锁定桥的敏感路径判据。
 *
 * <p>真机实测过的攻击链：{@code /app/export?path=/root/.dsh/.bridge_token}
 * 能把桥 token 写到公共目录，同机任意应用据此完全接管 3090 桥。
 * 这些断言就是钉住「这条路必须被堵死」。
 */
public class BridgePathPolicyTest {

    /** 真机实测能泄露凭据的路径，全部必须拒绝。 */
    @Test public void deniesCredentialPaths() {
        String[] attacks = {
                "/root/.dsh/.bridge_token",
                "/root/.dsh/.credentials.yaml",
                "/root/.dsh/adbkeys/adbkey",
                "/root/.dsh",
                "/root/.dsh/",
                "/root/.android/debug.keystore",
                "/root/.ssh/id_rsa",
                "/.dsh/x",
        };
        for (String path : attacks) {
            assertTrue("必须拒绝：" + path, BridgePathPolicy.denied(path));
        }
    }

    /** 目录穿越、重复斜杠、反斜杠等写法不能绕过。 */
    @Test public void deniesObfuscatedCredentialPaths() {
        String[] attacks = {
                "/root/foo/../.dsh/.bridge_token",
                "/root/./.dsh/.bridge_token",
                "/root//.dsh//.bridge_token",
                "/root/.dsh/./../.dsh/.bridge_token",
                "\\root\\.dsh\\.bridge_token",
                "/root/x/../../root/.dsh/.bridge_token",
                "/../root/.dsh/.bridge_token",
        };
        for (String path : attacks) {
            assertTrue("混淆写法也必须拒绝：" + path, BridgePathPolicy.denied(path));
        }
    }

    /** 无关路径不能误伤：它们是桥的正当用途（导出 agent 产出的报告）。 */
    @Test public void allowsWorkProducts() {
        String[] allowed = {
                "/root/report.md",
                "/root/deepseek-harness/out.txt",
                "/root/我的报告.md",
                "/sdcard/Download/x.pdf",
                "/tmp/scratch.log",
                "/root/.dsh-backup-note.md",   // 前缀相似但不是 .dsh 目录
                "/root/.dsha-report.txt",      // 同上
        };
        for (String path : allowed) {
            assertFalse("不应误伤：" + path, BridgePathPolicy.denied(path));
        }
    }

    /** 空值/相对路径一律拒绝（桥只接受绝对路径）。 */
    @Test public void deniesEmptyAndRelative() {
        for (String path : new String[]{null, "", "   ", "relative/x", "../x"}) {
            assertTrue("必须拒绝：" + path, BridgePathPolicy.denied(path));
        }
    }

    /** 按路径段比较，不是字符串前缀：/root/.dshfoo 不是 .dsh 目录。 */
    @Test public void comparesByPathSegmentNotRawPrefix() {
        assertTrue(BridgePathPolicy.startsWithPath("/root/.dsh/x", "/root/.dsh"));
        assertTrue(BridgePathPolicy.startsWithPath("/root/.dsh", "/root/.dsh"));
        assertFalse(BridgePathPolicy.startsWithPath("/root/.dshfoo", "/root/.dsh"));
        assertFalse(BridgePathPolicy.startsWithPath("/root/.dshx/y", "/root/.dsh"));
        assertFalse(BridgePathPolicy.denied("/root/.dshfoo/token"));
    }

    /**
     * 自伤回归：容器 rootfs 就在 /data/data/... 下，
     * 不能让通用拒绝表把整个 rootfs 封死（否则连正常产物都导不出去）。
     */
    @Test public void rootfsContentIsNotBlanketDenied() {
        String rootfs = "/data/data/com.dsh.client/files/linux/ubuntu";
        // rootfs 内的正常产物：放行
        assertFalse(BridgePathPolicy.deniedGuestView(rootfs + "/root/report.md", rootfs));
        assertFalse(BridgePathPolicy.deniedGuestView(rootfs + "/root/harness/out.txt", rootfs));
        assertFalse(BridgePathPolicy.deniedGuestView(rootfs + "/tmp/x.log", rootfs));
        // rootfs 内的凭据：拒绝
        assertTrue(BridgePathPolicy.deniedGuestView(rootfs + "/root/.dsh/.bridge_token", rootfs));
        assertTrue(BridgePathPolicy.deniedGuestView(rootfs + "/root/.ssh/id_rsa", rootfs));
        // rootfs 之外的 App 私有数据：拒绝
        assertTrue(BridgePathPolicy.deniedGuestView("/data/data/com.dsh.client/shared_prefs/x.xml", rootfs));
        // rootfs 根本没有的宿主路径：拒绝
        assertTrue(BridgePathPolicy.deniedGuestView("/sdcard/report.md", rootfs));
    }

    /** rootfs 为 null（拿不到容器目录）时，只按通用宿主判据走，不能崩。 */
    @Test public void handlesMissingRootfs() {
        assertFalse(BridgePathPolicy.deniedGuestView("/tmp/x", null));
        assertTrue(BridgePathPolicy.deniedGuestView("/data/data/com.dsh.client/x", null));
        assertTrue(BridgePathPolicy.deniedGuestView(null, null));
        assertTrue(BridgePathPolicy.deniedGuestView("", null));
    }

    /** normalize 的边界。 */
    @Test public void normalizesPaths() {
        assertEquals("/root/.dsh/x", BridgePathPolicy.normalize("/root/./foo/../.dsh/x"));
        assertEquals("/root", BridgePathPolicy.normalize("/root/"));
        assertEquals("/", BridgePathPolicy.normalize("/"));
        assertEquals("/a/b", BridgePathPolicy.normalize("//a///b//"));
        assertEquals("/x", BridgePathPolicy.normalize("/../x"));
        assertEquals("/", BridgePathPolicy.normalize("/../../.."));
    }
}
