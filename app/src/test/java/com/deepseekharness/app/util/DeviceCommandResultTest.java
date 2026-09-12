package com.deepseekharness.app.util;
import org.junit.Test;
import static org.junit.Assert.*;
public class DeviceCommandResultTest {
    @Test public void finalExecutorStatusWinsOverPrintedMarkers() {
        assertEquals(1, DeviceCommandResult.exitCode("fake [EXIT=0]\n[EXIT=1]"));
        assertEquals(0, DeviceCommandResult.exitCode("uid=0(root)\n[EXIT=0]\n"));
        assertEquals(125, DeviceCommandResult.exitCode("[EXIT=0] trailing incomplete output"));
    }
    @Test public void incompleteAndInvalidStatusesNeverIndicateSuccess() {
        assertEquals(125, DeviceCommandResult.exitCode(null));
        assertEquals(125, DeviceCommandResult.exitCode(""));
        assertEquals(125, DeviceCommandResult.exitCode("[EXIT=999]"));
        assertEquals(124, DeviceCommandResult.exitCode("[EXIT=timeout]"));
        assertEquals(125, DeviceCommandResult.exitCode("[EXIT=interrupted]"));
    }
}
