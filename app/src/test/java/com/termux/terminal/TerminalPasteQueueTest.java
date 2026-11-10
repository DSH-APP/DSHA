package com.termux.terminal;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class TerminalPasteQueueTest {
    private static final class Capture extends TerminalOutput {
        int writes;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        @Override public void write(byte[] data, int offset, int count) {
            writes++;
            bytes.write(data, offset, count);
        }
        @Override public void titleChanged(String oldTitle, String newTitle) {}
        @Override public void onCopyTextToClipboard(String text) {}
        @Override public void onPasteTextFromClipboard() {}
        @Override public void onBell() {}
        @Override public void onColorsChanged() {}
        String text() { return new String(bytes.toByteArray(), StandardCharsets.UTF_8); }
    }

    private static TerminalEmulator emulator(Capture capture) {
        TerminalSessionClient client = (TerminalSessionClient) Proxy.newProxyInstance(
            TerminalSessionClient.class.getClassLoader(), new Class<?>[] {TerminalSessionClient.class},
            (proxy, method, args) -> null);
        return new TerminalEmulator(capture, 80, 24, 2000, client);
    }

    @Test public void bracketedPasteIsOneWriteAndKeepsUpstreamSanitization() {
        Capture capture = new Capture();
        TerminalEmulator emulator = emulator(capture);
        emulator.doDecSetOrReset(true, 2004);
        emulator.paste("甲\r\n乙\n\u001b\u0085Z");
        assertEquals(1, capture.writes);
        assertEquals("\u001b[200~甲\r乙\rZ\u001b[201~", capture.text());
    }

    @Test public void plainPasteDoesNotAddMarkersOrAnEnter() {
        Capture capture = new Capture();
        emulator(capture).paste("first\nsecond");
        assertEquals(1, capture.writes);
        assertEquals("first\rsecond", capture.text());
    }
}
