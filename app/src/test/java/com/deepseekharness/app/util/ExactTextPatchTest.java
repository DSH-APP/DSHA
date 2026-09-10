package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class ExactTextPatchTest {
    @Test public void patchesOneExactOccurrenceAndIsIdempotent() {
        String updated = ExactTextPatch.apply("head\nold\ntail", "\nold\n", "\nnew\n");
        assertEquals("head\nnew\ntail", updated);
        assertSame(updated, ExactTextPatch.apply(updated, "\nold\n", "\nnew\n"));
    }
    @Test public void unknownDuplicateAndPartialStatesAreRejected() {
        for (String source : new String[]{"changed", "old old", "old new", "new new"}) {
            try { ExactTextPatch.apply(source, "old", "new"); fail(source); }
            catch (IllegalArgumentException expected) { }
        }
    }
    @Test public void arbitraryReplacementCharactersRemainLiteral() {
        assertEquals("x$1\\quote'x", ExactTextPatch.apply("xOLDx", "OLD", "$1\\quote'"));
    }
    @Test public void additivePatchDoesNotRepeatItsOwnPrefix() {
        assertEquals("value + guard", ExactTextPatch.apply("value", "value", "value + guard"));
        assertEquals("value + guard", ExactTextPatch.apply("value + guard", "value", "value + guard"));
    }
}
