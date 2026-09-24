package com.deepseekharness.app.backup;
import org.junit.Test;
import java.io.*;
import static org.junit.Assert.*;
public class BackupErrorCodeTest {
    @Test public void classifiesFullDiskPermissionAndTruncationWithoutLeakingPaths(){
        assertEquals("NO_SPACE",BackupErrorCode.from(new IOException("write failed: ENOSPC at /private/conversation")));
        assertEquals("PERMISSION_DENIED",BackupErrorCode.from(new IOException("wrapped",new SecurityException("secret path"))));
        assertEquals("ARCHIVE_TRUNCATED",BackupErrorCode.from(new EOFException()));assertEquals("AUTHENTICATION_FAILED",BackupErrorCode.from(new IOException("AUTHENTICATION_FAILED")));
        assertEquals("IO_FAILURE",BackupErrorCode.from(new IOException("Bearer secret-private-token")));
    }
    @Test public void recognizedErrnoAndInterruptedWorkHaveDistinctCodes(){
        assertEquals("NO_SPACE",BackupErrorCode.errno(28));assertEquals("PERMISSION_DENIED",BackupErrorCode.errno(13));assertEquals("CANCELLED",BackupErrorCode.from(new InterruptedIOException()));
    }
    @Test public void plainAndroidPermissionMessageGetsStableCode(){
        assertEquals("PERMISSION_DENIED",BackupErrorCode.from(new IOException("Permission denied")));
    }
}
