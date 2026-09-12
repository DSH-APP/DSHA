package com.deepseekharness.app.util;
import org.junit.Test;
import static org.junit.Assert.*;
public class ResolverConfigTest {
    @Test public void upgradePreservesCustomDnsAndSearchAndIsIdempotent() {
        String old="nameserver 192.0.2.53\nsearch private.example\noptions timeout:2 attempts:2\n";
        String patched=ResolverConfig.reconcile(old,"ipv4");assertTrue(patched.startsWith(old));assertTrue(patched.contains("options no-aaaa"));
        assertEquals(patched,ResolverConfig.reconcile(patched,"ipv4"));
        assertEquals(old,ResolverConfig.reconcile(patched,"auto"));assertEquals(old,ResolverConfig.reconcile(patched,"native"));
    }
    @Test public void commentsAreNotNameserversOrNoAaaaDirectives() {
        String patched=ResolverConfig.reconcile("# nameserver x\n# options no-aaaa\n","ipv4");
        assertTrue(patched.contains("\nnameserver 8.8.8.8\n"));assertTrue(patched.endsWith("options no-aaaa\n"));
    }
    @Test public void automaticModePreservesIpv6AndExplicitUserChoices() {
        String custom="nameserver 2001:db8::53\noptions no-aaaa edns0\n";
        assertEquals(custom,ResolverConfig.reconcile(custom,"auto"));assertEquals(custom,ResolverConfig.reconcile(custom,"native"));
        assertFalse(ResolverConfig.reconcile("","auto").contains("no-aaaa"));assertEquals("auto",ResolverConfig.mode(null));
    }
    @Test public void missingFinalNewlineAndCrLfRemainValid() {
        String text=ResolverConfig.reconcile("nameserver 192.0.2.53","ipv4");assertTrue(text.contains("53\n#"));
        String windows=text.replace("\n","\r\n");assertFalse(ResolverConfig.reconcile(windows,"native").contains("no-aaaa"));
    }
}
