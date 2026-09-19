package com.deepseekharness.app.util;
import org.junit.Test;
import static org.junit.Assert.*;
public class OverlayFrameBufferTest {
    @Test public void rapidTokensCoalesceAndKeepWhitespaceAndUnicode(){
        var buffer=new OverlayFrameBuffer();assertTrue(buffer.offer("one","Hello "));
        for(int i=0;i<1000;i++)assertFalse(buffer.offer("one","Hello 世界🙂\n第二行 "+i));
        var frame=buffer.take();assertEquals("Hello 世界🙂\n第二行 999",frame.text);assertTrue(buffer.current(frame));
        assertNull(buffer.take());assertTrue(buffer.offer("two","new"));assertFalse(buffer.current(frame));
    }
    @Test public void dismissalInvalidatesQueuedFramesWithoutBlockingLaterStreams(){
        var buffer=new OverlayFrameBuffer();buffer.offer("old","text");var old=buffer.take();
        buffer.clear();assertFalse(buffer.current(old));assertNull(buffer.take());
        assertTrue(buffer.offer("new","kept"));assertTrue(buffer.current(buffer.take()));
    }
}
