package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class VirtualScreenRoutesTest {
    @Test public void everySupportedOperationRequiresItsExactFullPath() {
        for(String operation:new String[]{"create","status","launch","tree","node","editor","edit","submit",
                "touch","preview","see","tap","swipe","key","type","close"}) {
            assertEquals(operation,VirtualScreenRoutes.operation("/app/vscreen/"+operation));
            for(String invalid:new String[]{"/app/vscreen/unknown/"+operation,"/app/vscreen/"+operation+"XXX",
                    "/app/vscreen/"+operation+"/","/app/vscreen//"+operation,"/app/vscreen/../"+operation,
                    "/app/vscreen%2f"+operation,"/x/app/vscreen/"+operation,operation})
                assertEquals(invalid,"",VirtualScreenRoutes.operation(invalid));
        }
        assertEquals("",VirtualScreenRoutes.operation(null));
        assertEquals("",VirtualScreenRoutes.operation("/app/vscreen/unknown"));
    }
}
