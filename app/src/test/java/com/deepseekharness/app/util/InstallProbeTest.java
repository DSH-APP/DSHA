package com.deepseekharness.app.util;

import org.junit.Test;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;

public class InstallProbeTest {
    @Test public void eachSelectedStepOnlyProbesItsOwnCommands() {
        Set<Integer> seen = new HashSet<>();
        for (InstallProbe.Check check : InstallProbe.checks(0)) seen.add(check.step);
        assertEquals(Set.of(2, 3, 4, 5, 6), seen);
        for (int step = 2; step <= 6; step++) for (InstallProbe.Check check : InstallProbe.checks(step)) assertEquals(step, check.step);
        assertTrue(InstallProbe.checks(1).isEmpty());
    }
    @Test public void successfulLookingOutputIsNotSuccessfulExitCode() {
        InstallProbe.Results result = new InstallProbe.Results(InstallProbe.checks(3));
        assertFalse(result.accept("v24.0.0")); assertFalse(result.ok(3));
        result.accept("DSHA_CHECK_RESULT:node:127"); assertFalse(result.ok(3));
        assertTrue(result.detail(3).contains("127"));
    }
    @Test public void missingDuplicateAndUnknownResultsFailClosed() {
        InstallProbe.Results result = new InstallProbe.Results(InstallProbe.checks(2));
        assertFalse(result.accept("DSHA_CHECK_RESULT:evil:0"));
        result.accept("DSHA_CHECK_RESULT:curl:0"); result.accept("DSHA_CHECK_RESULT:git:0"); assertFalse(result.ok(2));
        result.accept("DSHA_CHECK_RESULT:python:0"); assertTrue(result.ok(2));
        result.accept("DSHA_CHECK_RESULT:python:0"); assertFalse(result.ok(2));
    }
    @Test public void invalidExitStatusCannotBecomeSuccess() {
        for (String code : new String[]{"bogus", "-1", "256", "0:0"}) {
            InstallProbe.Results result = new InstallProbe.Results(InstallProbe.checks(3));
            result.accept("DSHA_CHECK_RESULT:node:" + code); assertFalse(result.ok(3));
        }
    }
    @Test public void readOnlyProbePreservesExitStatusWithoutHeadOrRepairCommands() {
        String script = InstallProbe.script(InstallProbe.checks(0));
        assertTrue(script.contains("code=$?")); assertTrue(script.contains("timeout 20s"));
        for (String bad : List.of("apt-get", "ensure", "head -1", "rm -", "pip install", "npm install", "flatten-l2s")) assertFalse(bad, script.contains(bad));
        assertTrue(script.contains("PYTHONDONTWRITEBYTECODE=1"));
    }

    @Test public void newExclusivePublishAliasIsRecognizedAndNeverRewrittenAsRename() {
        InstallProbe.Check session = InstallProbe.checks(6).stream().filter(c -> c.key.equals("session")).findFirst().orElseThrow();
        assertTrue(session.command.contains("&& ! grep -Fq " + ShellQuote.arg(InstallProbe.SESSION_PUBLISH_IMPORT)));
        assertTrue(InstallProbe.patchScript().contains("publishSessionExclusive as link"));
        assertTrue(InstallProbe.patchScript().contains("not in s"));
    }
}
