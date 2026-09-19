package com.deepseekharness.app.util;
import org.junit.Test;
import java.time.*;
import static org.junit.Assert.*;
public class AutomaticBackupPolicyTest {
    private long at(String value){return Instant.parse(value).toEpochMilli();}
    @Test public void intervalDoesNotBackupOnEveryStart(){long start=100000;assertFalse(AutomaticBackupPolicy.due("interval",24,0,start+1000,start,0,0,ZoneOffset.UTC));assertTrue(AutomaticBackupPolicy.due("interval",24,0,start+86400000,start,0,0,ZoneOffset.UTC));}
    @Test public void stopIsConsumedOnce(){assertTrue(AutomaticBackupPolicy.due("stop",1,0,300,100,0,200,ZoneOffset.UTC));assertFalse(AutomaticBackupPolicy.due("stop",1,0,400,100,300,200,ZoneOffset.UTC));}
    @Test public void localDailyDefersToNextScheduledTime(){long start=at("2026-09-17T00:00:00Z");assertFalse(AutomaticBackupPolicy.due("daily",1,180,at("2026-09-17T18:59:00Z"),start,0,0,ZoneId.of("Asia/Shanghai")));assertTrue(AutomaticBackupPolicy.due("daily",1,180,at("2026-09-17T19:00:00Z"),start,0,0,ZoneId.of("Asia/Shanghai")));}
    @Test public void dstAndClockRollback(){long start=at("2026-03-08T06:00:00Z");assertFalse(AutomaticBackupPolicy.due("daily",1,150,start-1,start,0,0,ZoneId.of("America/New_York")));assertTrue(AutomaticBackupPolicy.due("daily",1,150,at("2026-03-08T07:30:00Z"),start,0,0,ZoneId.of("America/New_York")));}
}
