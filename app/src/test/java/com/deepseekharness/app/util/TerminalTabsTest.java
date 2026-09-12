package com.deepseekharness.app.util;
import org.junit.Test;
import static org.junit.Assert.*;
public class TerminalTabsTest {
    @Test public void visibleNumbersReuseVacanciesWithoutReusingSessionIdentity() {
        TerminalTabs<String> tabs=new TerminalTabs<>();
        var first=tabs.add("one");assertEquals(1,first.number);
        assertTrue(tabs.beginClose(first.id));
        var second=tabs.add("two");assertEquals(2,second.number);
        tabs.closeFailed(first.id);assertEquals(1,first.number);
        tabs.remove(first.id);
        var replacement=tabs.add("replacement");assertEquals(1,replacement.number);
        assertNotEquals(first.id,replacement.id);
        tabs.closeFailed(first.id);tabs.remove(first.id);
        assertSame(replacement,tabs.current());assertEquals(2,second.number);
        tabs.remove(second.id);tabs.remove(replacement.id);
        assertEquals(1,tabs.add("fresh").number);
    }
    @Test public void stableSelectionAndIndependentValues() {
        TerminalTabs<String> tabs=new TerminalTabs<>();assertFalse(tabs.wasInitialized());
        var a=tabs.add("a");var b=tabs.add("b");var c=tabs.add("c");
        assertTrue(tabs.select(b.id));tabs.remove(a.id);assertEquals("b",tabs.current().value);
        tabs.remove(b.id);assertEquals("c",tabs.current().value);
        tabs.remove(c.id);assertNull(tabs.current());assertTrue(tabs.wasInitialized());
        assertTrue(tabs.add("d").id>c.id);assertFalse(tabs.select(a.id));
    }
    @Test public void failedCloseRetainsSessionAndCanRetry() {
        TerminalTabs<Object> tabs=new TerminalTabs<>();Object value=new Object();var tab=tabs.add(value);
        assertTrue(tabs.beginClose(tab.id));assertFalse(tabs.beginClose(tab.id));
        assertSame(value,tabs.current().value);tabs.closeFailed(tab.id);assertFalse(tab.isClosing());
        assertTrue(tabs.beginClose(tab.id));tabs.remove(tab.id);assertFalse(tabs.beginClose(tab.id));
    }
    @Test public void snapshotIsDetachedAndImmutable() {
        TerminalTabs<String> tabs=new TerminalTabs<>();tabs.add("one");var copy=tabs.snapshot();tabs.add("two");
        assertEquals(1,copy.size());try{copy.clear();fail();}catch(UnsupportedOperationException expected){}
    }
}
