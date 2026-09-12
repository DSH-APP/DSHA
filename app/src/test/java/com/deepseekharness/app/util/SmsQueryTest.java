package com.deepseekharness.app.util;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class SmsQueryTest {
    @Test public void smsRequiresItsOwnCapabilityInsteadOfReadonlyAllowance() {
        DeviceShellPolicy.Plan plan = DeviceShellPolicy.inspect("content query --uri content://sms/inbox --projection _id:address:body:date --where 'date >= 100 AND read = 0' --sort 'date DESC'");
        assertEquals(DeviceShellPolicy.Kind.SENSITIVE_READ, plan.kind);
        List<String> normalized = SmsQuery.forUser(plan.argv, 10);
        assertEquals("10", normalized.get(normalized.indexOf("--user") + 1));
        assertEquals(DeviceShellPolicy.Kind.SENSITIVE_READ,
                DeviceShellPolicy.inspect("/system/bin/content query --uri content://sms/123 --user current").kind);
    }
    @Test public void cannotExtendCapabilityToWritesOtherProvidersOrQueryInjection() {
        for (String command : new String[]{
                "content delete --uri content://sms", "content update --uri content://sms --bind read:i:1",
                "content call --uri content://sms --method deleteAll", "content read --uri content://sms",
                "content query --uri content://contacts", "content query --uri content://0@sms",
                "content query --uri 'content://sms?limit=5'", "content query --uri content://sms/../settings",
                "content query --uri content://sms --uri content://settings", "content query --uri content://sms --extra x:s:1",
                "content query --uri content://sms --projection 'body FROM other_table'",
                "content query --uri content://sms --where '1=1 UNION SELECT secret FROM other'",
                "content query --uri content://sms --sort 'date DESC LIMIT 1;DELETE FROM sms'",
                "content query --uri content://sms; reboot", "content query --uri content://sms --user all"})
            assertEquals(command, DeviceShellPolicy.Kind.DENY, DeviceShellPolicy.inspect(command).kind);
    }
    @Test public void preventsCrossAndroidUserEvenWhenRootIsEnabled() {
        List<String> current = DeviceShellPolicy.inspect("content query --uri content://sms --user current").argv;
        assertEquals("10", SmsQuery.forUser(current, 10).get(5));
        List<String> other = DeviceShellPolicy.inspect("content query --uri content://sms --user 0").argv;
        assertThrows(IllegalArgumentException.class, () -> SmsQuery.forUser(other, 10));
        assertThrows(IllegalArgumentException.class, () -> SmsQuery.forUser(current, -1));
    }
    @Test public void canProbeWithoutReadingAnyActualMessage() {
        assertEquals(DeviceShellPolicy.Kind.SENSITIVE_READ,
                DeviceShellPolicy.inspect("content query --uri content://sms --projection _id --where 0").kind);
    }
}
