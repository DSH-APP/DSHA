package com.deepseekharness.app.backup;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class FactoryResetTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void removesOnlyLegacyDshaDataTree() throws Exception {
        File storage = temporary.newFolder("storage");
        File documents = new File(storage, "Documents");
        File data = new File(documents, "dshdata/sessions");
        assertTrue(data.mkdirs());
        put(new File(data, "session.json"), "private conversation");
        File keep = new File(documents, "keep.txt");
        put(keep, "user file");

        FactoryReset.eraseLegacyPublicData(new JvmBackupFileSystem(), storage,
                new BackupControl(null));

        assertFalse(new File(documents, "dshdata").exists());
        assertEquals("user file", java.nio.file.Files.readString(keep.toPath()));

        File privateRoot = temporary.newFolder("private-root");
        put(new File(privateRoot, "linux/ubuntu/root/session.json"), "session");
        put(new File(privateRoot, "host-backup-operations/copy.dshbak"), "backup");
        java.util.concurrent.atomic.AtomicLong reported = new java.util.concurrent.atomic.AtomicLong();
        FactoryReset.eraseContents(new JvmBackupFileSystem(), privateRoot,
                new BackupControl((stage, entries, bytes) -> reported.set(entries)),
                FactoryReset.STAGE_RUNTIME, new long[]{0, 0});
        assertTrue(privateRoot.isDirectory());
        assertEquals(0, java.util.Objects.requireNonNull(privateRoot.list()).length);
        assertTrue(reported.get() >= 6);
    }

    @Test public void missingLegacyDirectoryIsAlreadyFormatted() throws Exception {
        File storage = temporary.newFolder("empty-storage");
        File documents=new File(storage,"Documents");assertTrue(documents.mkdir());
        FaultFs fs=new FaultFs();fs.failSync=documents.getAbsoluteFile();
        FactoryReset.eraseLegacyPublicData(fs, storage,
                new BackupControl(null));
        assertTrue(storage.isDirectory());
    }

    @Test public void publicFuseSyncFailureIsWarningAfterLegacyDataWasRemoved() throws Exception {
        File storage=temporary.newFolder("fuse-storage"),documents=new File(storage,"Documents");
        assertTrue(new File(documents,"dshdata").mkdirs());
        put(new File(documents,"dshdata/session.json"),"session");
        File keep=new File(documents,"keep.txt");put(keep,"keep");
        FaultFs fs=new FaultFs();fs.failSync=documents.getAbsoluteFile();

        String warning=FactoryReset.eraseLegacyPublicDataBestEffort(fs,storage,new BackupControl(null));

        assertTrue(warning.contains("FILESYSTEM_22"));
        assertFalse(new File(documents,"dshdata").exists());
        assertEquals("keep",java.nio.file.Files.readString(keep.toPath()));
    }

    @Test public void unavailablePublicStorageDoesNotWeakenPrivateDeletion() throws Exception {
        File storage=temporary.newFolder("denied-storage");
        FaultFs denied=new FaultFs();denied.failStat=storage.getAbsoluteFile();
        String warning=FactoryReset.eraseLegacyPublicDataBestEffort(denied,storage,new BackupControl(null));
        assertTrue(warning.contains("PERMISSION_DENIED"));

        File privateRoot=temporary.newFolder("strict-private");
        put(new File(privateRoot,"linux/data.txt"),"private");
        FactoryReset.eraseContents(new JvmBackupFileSystem(),privateRoot,new BackupControl(null),
                FactoryReset.STAGE_PRIVATE,new long[]{0,0});
        assertEquals(0,java.util.Objects.requireNonNull(privateRoot.list()).length);
    }

    @Test public void externalSyncFailureIsWarningButSamePrivateFailureIsFatal() throws Exception {
        File external=temporary.newFolder("external"),privateRoot=temporary.newFolder("private");
        put(new File(external,"cache.bin"),"cache");put(new File(privateRoot,"secret.bin"),"secret");
        FaultFs optional=new FaultFs();optional.failSync=external.getAbsoluteFile();
        String warning=FactoryReset.eraseOptionalContents(optional,external,new BackupControl(null),
                FactoryReset.STAGE_CACHE,new long[]{0,0});
        assertTrue(warning.contains("FILESYSTEM_22"));

        FaultFs strict=new FaultFs();strict.failSync=privateRoot.getAbsoluteFile();
        try{
            FactoryReset.eraseContents(strict,privateRoot,new BackupControl(null),
                    FactoryReset.STAGE_PRIVATE,new long[]{0,0});
            fail("private fsync failure must remain fatal");
        }catch(IOException expected){assertEquals("FILESYSTEM_22",expected.getMessage());}
    }

    @Test public void relistsDirectoryWhenLateWriterCausesEnotempty() throws Exception {
        File root=temporary.newFolder("late-root"),directory=new File(root,"volatile");
        assertTrue(directory.mkdir());put(new File(directory,"first.txt"),"first");
        FaultFs fs=new FaultFs();fs.lateDirectory=directory.getAbsoluteFile();

        FactoryReset.eraseContents(fs,root,new BackupControl(null),FactoryReset.STAGE_RUNTIME,new long[]{0,0});

        assertTrue(root.isDirectory());
        assertEquals(0,java.util.Objects.requireNonNull(root.list()).length);
        assertEquals(1,fs.enotemptyCount);
    }

    @Test public void relistsDirectoryForJdkDirectoryNotEmptyExceptionWithoutMessageCode() throws Exception {
        File root=temporary.newFolder("jdk-late-root"),directory=new File(root,"volatile");
        assertTrue(directory.mkdir());put(new File(directory,"first.txt"),"first");
        FaultFs fs=new FaultFs();fs.lateDirectory=directory.getAbsoluteFile();
        fs.useJdkDirectoryNotEmptyException=true;

        FactoryReset.eraseContents(fs,root,new BackupControl(null),FactoryReset.STAGE_RUNTIME,new long[]{0,0});

        assertEquals(0,java.util.Objects.requireNonNull(root.list()).length);
        assertEquals(1,fs.enotemptyCount);
    }

    @Test public void persistentSystemCacheWriterIsWarningButPrivateDataStaysStrict() throws Exception {
        File cacheRoot=temporary.newFolder("cache-root"),cacheDirectory=new File(cacheRoot,"WebView");
        assertTrue(cacheDirectory.mkdir());put(new File(cacheDirectory,"first.tmp"),"cache");
        FaultFs cacheFs=new FaultFs();cacheFs.lateDirectory=cacheDirectory.getAbsoluteFile();
        cacheFs.lateWritesRemaining=FactoryResetTest.removePasses();

        String warning=FactoryReset.eraseRegenerableContents(cacheFs,cacheRoot,new BackupControl(null),
                FactoryReset.STAGE_CACHE,new long[]{0,0});

        assertEquals("REGENERABLE:FILESYSTEM_39",warning);
        assertTrue("bounded retry leaves the racing system cache for a later cleanup",cacheDirectory.isDirectory());

        File privateRoot=temporary.newFolder("private-race"),privateDirectory=new File(privateRoot,"linux");
        assertTrue(privateDirectory.mkdir());put(new File(privateDirectory,"session.json"),"private");
        FaultFs privateFs=new FaultFs();privateFs.lateDirectory=privateDirectory.getAbsoluteFile();
        privateFs.lateWritesRemaining=FactoryResetTest.removePasses();
        try{
            FactoryReset.eraseContents(privateFs,privateRoot,new BackupControl(null),
                    FactoryReset.STAGE_RUNTIME,new long[]{0,0});
            fail("core private roots must never downgrade ENOTEMPTY to a warning");
        }catch(IOException expected){assertEquals("FILESYSTEM_39",expected.getMessage());}
    }

    @Test public void retryAfterPreviousEnotemptyCanFinishOnceWriterIsGone() throws Exception {
        File root=temporary.newFolder("retry-root"),directory=new File(root,"linux");
        assertTrue(directory.mkdir());put(new File(directory,"session.json"),"private");
        FaultFs fs=new FaultFs();fs.lateDirectory=directory.getAbsoluteFile();
        fs.lateWritesRemaining=FactoryResetTest.removePasses();
        try{
            FactoryReset.eraseContents(fs,root,new BackupControl(null),FactoryReset.STAGE_RUNTIME,new long[]{0,0});
            fail("first attempt must retain a directory that still has a writer");
        }catch(IOException expected){assertEquals("FILESYSTEM_39",expected.getMessage());}

        fs.lateWritesRemaining=0;
        FactoryReset.eraseContents(fs,root,new BackupControl(null),FactoryReset.STAGE_RUNTIME,new long[]{0,0});
        assertEquals(0,java.util.Objects.requireNonNull(root.list()).length);
    }

    @Test public void regenerableCleanupNeverTurnsCancellationIntoSuccess() throws Exception {
        File root=temporary.newFolder("cancelled-cache");put(new File(root,"cache.tmp"),"cache");
        BackupControl cancelled=new BackupControl(null);cancelled.cancel();
        try{
            FactoryReset.eraseRegenerableContents(new JvmBackupFileSystem(),root,cancelled,
                    FactoryReset.STAGE_CACHE,new long[]{0,0});
            fail("cancellation must escape the best-effort cache boundary");
        }catch(java.io.InterruptedIOException expected){assertEquals("CANCELLED",expected.getMessage());}
        assertTrue(new File(root,"cache.tmp").isFile());
    }

    /** Mirrors FactoryReset's private retry bound so the persistent-writer fixture exhausts it. */
    private static int removePasses(){return 8;}

    private static void put(File file, String value) throws Exception {
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new java.io.IOException("mkdir");
        java.nio.file.Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8));
    }

    private static final class FaultFs implements BackupFileSystem {
        private final JvmBackupFileSystem delegate=new JvmBackupFileSystem();
        File failStat,failSync,lateDirectory;
        int lateWritesRemaining=1;
        int enotemptyCount;
        boolean useJdkDirectoryNotEmptyException;
        private static boolean same(File a,File b){return a!=null&&b!=null&&a.getAbsoluteFile().equals(b.getAbsoluteFile());}
        @Override public Node stat(File file)throws IOException{if(same(file,failStat))throw new IOException("PERMISSION_DENIED");return delegate.stat(file);}
        @Override public String readLink(File file)throws IOException{return delegate.readLink(file);}
        @Override public List<String> list(File file)throws IOException{return delegate.list(file);}
        @Override public InputStream read(File file,Node expected)throws IOException{return delegate.read(file,expected);}
        @Override public OutputStream create(File file)throws IOException{return delegate.create(file);}
        @Override public void directory(File file)throws IOException{delegate.directory(file);}
        @Override public void move(File source,File target)throws IOException{delegate.move(source,target);}
        @Override public void delete(File file)throws IOException{
            if(same(file,lateDirectory)&&lateWritesRemaining>0){
                lateWritesRemaining--;enotemptyCount++;
                java.nio.file.Files.write(new File(file,"late-"+enotemptyCount+".txt").toPath(),new byte[]{1});
                if(useJdkDirectoryNotEmptyException)throw jdkDirectoryNotEmpty(file);
                throw new IOException("FILESYSTEM_39");
            }
            delegate.delete(file);
        }
        @Override public void syncDirectory(File directory)throws IOException{if(same(directory,failSync))throw new IOException("FILESYSTEM_22");delegate.syncDirectory(directory);}
        @Override public void mode(File file,int mode)throws IOException{delegate.mode(file,mode);}
        @Override public void symlink(String target,File link)throws IOException{delegate.symlink(target,link);}
        @Override public void prepareOwnedRemoval(File directory)throws IOException{delegate.prepareOwnedRemoval(directory);}

        private static IOException jdkDirectoryNotEmpty(File file){
            try{
                Class<?> type=Class.forName("java.nio.file.DirectoryNotEmptyException");
                return (IOException)type.getConstructor(String.class).newInstance(file.getAbsolutePath());
            }catch(ReflectiveOperationException unavailable){throw new AssertionError(unavailable);}
        }
    }
}
