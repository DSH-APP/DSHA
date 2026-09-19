package com.deepseekharness.app.util;

import java.time.*;

/** 日历计划按本地时区计算；到期时繁忙则延后，不中断用户工作。 */
public final class AutomaticBackupPolicy {
    private AutomaticBackupPolicy() { }
    public static boolean due(String mode, int intervalHours, int minuteOfDay, long now, long anchor, long last, long stopped, ZoneId zone) {
        long since=Math.max(anchor,last);
        if(now<since)return false;
        if("stop".equals(mode))return stopped>since && now>=stopped;
        if("interval".equals(mode))return now-since>=Math.max(1,Math.min(168,intervalHours))*3_600_000L;
        ZonedDateTime start=Instant.ofEpochMilli(since).atZone(zone);
        int minute=Math.max(0,Math.min(1439,minuteOfDay));
        ZonedDateTime next=start.toLocalDate().atTime(minute/60,minute%60).atZone(zone);
        if(!next.isAfter(start))next=next.toLocalDate().plusDays(1).atTime(minute/60,minute%60).atZone(zone);
        return now>=next.toInstant().toEpochMilli();
    }
}
