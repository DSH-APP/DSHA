package com.deepseekharness.app.util;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.util.*;
import static org.junit.Assert.*;

public class StartupHistoryStoreTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();
    @Test public void rotatesFiveRecordsKeepsFailuresAndBothLanguages() throws Exception {
        StartupHistoryStore store=new StartupHistoryStore(folder.getRoot());
        for(int i=0;i<7;i++) {
            StartupTrace trace=new StartupTrace();trace.begin(i+1,0,false);trace.stage(i+1,1,"等待鉴权链接");
            store.save(UUID.randomUUID().toString(),100+i,i==6?"failed":"ready","token=secret-value",trace.snapshot(3,"zh"),trace.snapshot(3,"en"));
        }
        List<StartupHistoryStore.Entry> entries=new StartupHistoryStore(folder.getRoot()).list("en");
        assertEquals(5,entries.size());assertEquals(106,entries.get(0).started);assertEquals("failed",entries.get(0).status);
        assertFalse(entries.get(0).log.contains("等待"));assertTrue(store.list("zh").get(0).log.contains("等待"));
    }
    @Test public void invalidRecordIdCannotEscapeDirectory() throws Exception {
        StartupHistoryStore store=new StartupHistoryStore(folder.getRoot());StartupTrace trace=new StartupTrace();trace.begin(1,0,false);
        try {store.save("../escape",0,"failed","",trace.snapshot(1),trace.snapshot(1));fail();}catch(java.io.IOException expected){}
        assertTrue(store.list("zh").isEmpty());
    }
    @Test public void trustedParentAliasIsNormalizedBeforeCheckingRecordPaths() throws Exception {
        folder.newFolder("alias");StartupHistoryStore store=new StartupHistoryStore(new java.io.File(folder.getRoot(),"alias/.."));
        StartupTrace trace=new StartupTrace();trace.begin(1,0,false);
        store.save(UUID.randomUUID().toString(),1,"ready","",trace.snapshot(1,"zh"),trace.snapshot(1,"en"));
        assertEquals(1,new StartupHistoryStore(folder.getRoot()).list("en").size());
    }
}
