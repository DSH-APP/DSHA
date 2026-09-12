package com.deepseekharness.app.util;

import org.junit.Test;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class DocumentPathsTest {
    private final File base = new File("build/document-path-test").getAbsoluteFile();
    private final Map<String, String> links = new HashMap<>();
    private DocumentPaths paths() throws IOException {
        return new DocumentPaths(base, file -> links.get(file.getPath()));
    }
    private void link(String name, String target) { links.put(new File(base, name).getPath(), target); }
    @Test public void oldIdsAndChineseNamesRemainValid() throws Exception {
        DocumentPaths paths = paths();
        assertEquals(base.getCanonicalFile(), paths.resolve("root", true));
        assertEquals("root", paths.id(""));
        String id = "linux/ubuntu/root/灵儿.120516.json";
        assertEquals(new File(base, id), paths.resolve(id, true));
        assertTrue(paths.childOf("root", id));
        assertTrue(paths.childOf("linux/ubuntu/root", id));
        assertFalse(paths.childOf("linux/ubuntu/root", "linux/ubuntu/root2/file"));
        assertFalse(paths.childOf(id, id));
        assertEquals("linux/ubuntu/root/名字 100%.json", paths.child("linux/ubuntu/root", "名字 100%.json"));
    }
    @Test public void pathInjectionIsRejectedBeforeFilesystemAccess() throws Exception {
        for (String id : new String[]{null, "/data/system", "../shared_prefs", "linux/../../cache", "linux//ubuntu",
                "linux/./ubuntu", "linux\\ubuntu", "C:/secret", "bad\0name"}) {
            try { paths().resolve(id, true); fail("接受了非法路径 " + id); } catch (IOException expected) { }
        }
        for (String name : new String[]{"..", "/tmp/file", "a/b", "a\\b", "a\0b", ""}) {
            try { paths().child("root", name); fail("接受了非法名称"); } catch (IOException expected) { }
        }
    }
    @Test public void guestAbsoluteRelativeAndL2sLinksStayInsideRootfs() throws Exception {
        link("linux/ubuntu/root/absolute", "/root/灵儿.json");
        link("linux/ubuntu/root/relative", "../home/文件.json");
        link("linux/ubuntu/root/l2s", new File(base, "linux/ubuntu/.l2s/inode").getPath());
        assertEquals(new File(base, "linux/ubuntu/root/灵儿.json"), paths().resolve("linux/ubuntu/root/absolute", true));
        assertEquals(new File(base, "linux/ubuntu/home/文件.json"), paths().resolve("linux/ubuntu/root/relative", true));
        assertEquals(new File(base, "linux/ubuntu/.l2s/inode"), paths().resolve("linux/ubuntu/root/l2s", true));
        assertEquals(new File(base, "linux/ubuntu/root/absolute"), paths().resolve("linux/ubuntu/root/absolute", false));
        link("linux/ubuntu/root/folder", "/home/user");
        assertEquals(new File(base, "linux/ubuntu/home/user/child.txt"), paths().resolve("linux/ubuntu/root/folder/child.txt", false));
    }
    @Test public void linksCannotExpandSubtreeGrantOrEscapePrivateDirectory() throws Exception {
        link("linux/ubuntu/root/cross", "/etc/private.txt");
        assertFalse(paths().childOf("linux/ubuntu/root", "linux/ubuntu/root/cross"));
        assertTrue(paths().childOf("root", "linux/ubuntu/root/cross"));
        link("escape", "../secret.txt");
        try { paths().resolve("escape", true); fail("越界链接未拒绝"); } catch (IOException expected) { }
        link("outside", new File(base.getParentFile(), "other.txt").getAbsolutePath());
        try { paths().resolve("outside", true); fail("宿主绝对链接越界未拒绝"); } catch (IOException expected) { }
    }
    @Test public void loopsAreBoundedAndAnchorsAreProtected() throws Exception {
        link("linux/ubuntu/root/a", "b"); link("linux/ubuntu/root/b", "a");
        try { paths().resolve("linux/ubuntu/root/a", true); fail("链接循环未拒绝"); } catch (IOException expected) { }
        assertTrue(paths().anchor("root")); assertTrue(paths().anchor("linux/ubuntu/root"));
        assertFalse(paths().anchor("linux/ubuntu/root/file"));
        assertFalse(DocumentPaths.within(base, new File(base.getPath() + "2/secret")));
    }
}
