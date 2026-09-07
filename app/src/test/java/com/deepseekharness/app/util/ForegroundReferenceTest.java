package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class ForegroundReferenceTest {
    @Test public void mainPauseCannotClearNewWebHost() {
        ForegroundReference<Object> state = new ForegroundReference<>();
        Object main = new Object(), web = new Object();
        state.resumed(main); state.resumed(web); state.left(main);
        assertSame(web, state.current());
    }
    @Test public void oldWebDestroyCannotClearGeckoOrRecreatedHost() {
        ForegroundReference<Object> state = new ForegroundReference<>();
        Object web = new Object(), gecko = new Object(), recreated = new Object();
        state.resumed(web); state.resumed(gecko); state.left(web);
        assertSame(gecko, state.current());
        state.resumed(recreated); state.left(gecko); assertSame(recreated, state.current());
    }
    @Test public void leavingCurrentHostDoesNotFallBackToAnOldBackgroundPage() {
        ForegroundReference<Object> state = new ForegroundReference<>();
        Object main = new Object(), web = new Object();
        state.resumed(main); state.resumed(web); state.left(main); state.left(web);
        assertNull(state.current()); state.left(web); assertNull(state.current());
        state.resumed(main); assertSame(main, state.current());
    }
    @Test public void unrelatedLifecycleCallbackLeavesCurrentOwnerAlone() {
        ForegroundReference<Object> state = new ForegroundReference<>();
        Object current = new Object(); state.resumed(current);
        state.left(new Object()); assertSame(current, state.current());
    }
}
