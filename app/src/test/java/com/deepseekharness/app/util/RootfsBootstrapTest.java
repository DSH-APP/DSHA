package com.deepseekharness.app.util;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class RootfsBootstrapTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private File root;
    private RootfsBootstrap.Resolver fixture() throws Exception {
        root = temporary.newFolder(); new File(root, "root").mkdir();
        elf("bin/bash", "/lib/ld-linux-aarch64.so.1"); elf("lib/ld-linux-aarch64.so.1", null);
        return path -> new File(root, path.substring(1));
    }
    private void elf(String path, String interpreter) throws Exception {
        ByteBuffer data = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
        data.putInt(0,0x464c457f); data.put(4,(byte)2); data.put(5,(byte)1); data.putShort(18,(short)183);
        data.putLong(32,64);data.putShort(54,(short)56);data.putShort(56,(short)1);
        if(interpreter!=null){byte[] name=(interpreter+"\0").getBytes(StandardCharsets.UTF_8);
            data.putInt(64,3);data.putLong(72,128);data.putLong(96,name.length);data.position(128);data.put(name);}
        File file=new File(root,path);file.getParentFile().mkdirs();try(var out=new FileOutputStream(file)){out.write(data.array());}file.setExecutable(true);
    }
    @Test public void validShellAndLoader() throws Exception {assertTrue(RootfsBootstrap.ready(fixture()));}
    @Test public void recoverySelectionAcceptsUbuntuDotPrefixAndExcludesNodeAndPersonalData() {
        for(String name:new String[]{"./bin","./usr/bin/bash","./usr/lib/aarch64-linux-gnu/libc.so.6","lib/","./lib"})
            assertTrue(name,RootfsBootstrap.recoveryAsset(name));
        for(String name:new String[]{"./usr/local/bin/node","./root/.dsh/sessions/a","./usr/local/lib/node_modules/a"})
            assertFalse(name,RootfsBootstrap.recoveryAsset(name));
    }
    @Test public void otherBashPathDoesNotMaskMissingEntry() throws Exception {
        var resolver=fixture();elf("usr/bin/bash","/lib/ld-linux-aarch64.so.1");assertTrue(new File(root,"bin/bash").delete());assertFalse(RootfsBootstrap.ready(resolver));
    }
    @Test public void missingLoaderRejectsStaleReadyMarker() throws Exception {
        var resolver=fixture();assertTrue(new File(root,"lib/ld-linux-aarch64.so.1").delete());assertFalse(RootfsBootstrap.ready(resolver));
    }
    @Test public void truncatedShellIsNotReady() throws Exception {
        var resolver=fixture();try(var out=new FileOutputStream(new File(root,"bin/bash"))){out.write(127);}assertFalse(RootfsBootstrap.ready(resolver));
    }
    @Test public void invalidInterpreterAndLinkFailureAreRejected() throws Exception {
        var resolver=fixture();elf("bin/bash","relative-loader");assertFalse(RootfsBootstrap.ready(resolver));
        assertFalse(RootfsBootstrap.ready(path->{throw new IOException("link loop");}));
    }
    @Test public void invalidHeaderBoundsAreRejected() throws Exception {
        var resolver=fixture();try(var file=new RandomAccessFile(new File(root,"bin/bash"),"rw")){file.seek(32);file.writeLong(Long.MAX_VALUE);}assertFalse(RootfsBootstrap.ready(resolver));
    }
}
