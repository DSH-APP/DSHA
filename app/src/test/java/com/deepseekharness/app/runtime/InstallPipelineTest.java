package com.deepseekharness.app.runtime;

import com.deepseekharness.app.util.InstallProbe;
import com.deepseekharness.app.util.InstallTask;
import org.junit.Test;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.junit.Assert.*;

public class InstallPipelineTest {
    static final class Environment implements InstallPipeline.Environment {
        boolean ready = true, empty, cancelDuringRepair, throwRepair, missingResult, earlyExit;
        int launches, tools, pnpm, groups, patches, extractions;
        final Set<String> failures = new HashSet<>();
        InstallTask active;
        @Override public boolean ready() { return ready; }
        @Override public boolean canExtractFresh() { return empty; }
        @Override public void extract(Consumer<Long> progress) { extractions++; ready = true; progress.accept(20_000_000L); }
        @Override public void repairTools(InstallProbe.Results checked, InstallTask task) {
            task.stage(2, "修复工具", false); tools++; failures.removeAll(Set.of("curl", "git", "python"));
        }
        @Override public boolean repairPnpm() {
            pnpm++;
            if (throwRepair) throw new IllegalStateException("password=private-value 修复异常");
            failures.remove("pnpm"); if (cancelDuringRepair) active.requestCancel(); return true;
        }
        @Override public void repairGroups() { groups++; failures.remove("groups"); }
        @Override public int execute(String script, long timeout, boolean cancellable, InstallTask task, Consumer<String> output) throws IOException {
            launches++; active = task;
            if (script.startsWith("python3 -B -u")) {
                assertFalse(cancellable); patches++; failures.removeAll(Set.of("dns", "session", "settings")); return 0;
            }
            assertTrue(cancellable);
            if (earlyExit) { output.accept("DSHA_CHECK_BEGIN:curl"); return 23; }
            Matcher matcher = Pattern.compile("DSHA_CHECK_BEGIN:([a-z]+)").matcher(script);
            while (matcher.find()) {
                String key = matcher.group(1); output.accept("DSHA_CHECK_BEGIN:" + key);
                output.accept("版本或诊断输出");
                if (!missingResult) output.accept("DSHA_CHECK_RESULT:" + key + ":" + (failures.contains(key) ? 1 : 0));
            }
            return 0;
        }
        InstallTask run(boolean repair, int step) throws Exception {
            InstallTask task = new InstallTask(); task.start(repair, step); active = task;
            new InstallPipeline(this).run(task, repair, step); return task;
        }
        int repairs() { return tools + pnpm + groups + patches + extractions; }
    }
    @Test public void healthyCheckAndRepairBothLaunchOnlyOneProbeAndDoNotWrite() throws Exception {
        for (boolean repair : new boolean[]{false, true}) {
            Environment environment = new Environment(); InstallTask task = environment.run(repair, 0);
            assertEquals(InstallTask.Outcome.SUCCEEDED, task.snapshot().outcome);
            assertEquals(1, environment.launches); assertEquals(0, environment.repairs());
        }
    }
    @Test public void readOnlyFailuresNeverRepair() throws Exception {
        Environment environment = new Environment(); environment.failures.addAll(Set.of("curl", "pnpm", "dns"));
        InstallTask task = environment.run(false, 0);
        assertEquals(InstallTask.Outcome.FAILED, task.snapshot().outcome);
        assertEquals(1, environment.launches); assertEquals(0, environment.repairs());
        assertEquals(InstallTask.Step.OK, task.snapshot().steps[2]);
        assertEquals(InstallTask.Step.OK, task.snapshot().steps[4]);
        for (InstallTask.Step step : task.snapshot().steps) assertTrue(step == InstallTask.Step.OK || step == InstallTask.Step.FAILED);
    }
    @Test public void pnpmRepairOnlyRepairsAndRechecksPnpm() throws Exception {
        Environment environment = new Environment(); environment.failures.add("pnpm");
        InstallTask task = environment.run(true, 0);
        assertEquals(InstallTask.Outcome.SUCCEEDED, task.snapshot().outcome);
        assertEquals(2, environment.launches); assertEquals(1, environment.pnpm); assertEquals(1, environment.repairs());
    }
    @Test public void nonemptyBrokenEnvironmentIsPreserved() throws Exception {
        Environment environment = new Environment(); environment.ready = false;
        InstallTask task = environment.run(true, 0);
        assertEquals(InstallTask.Outcome.FAILED, task.snapshot().outcome);
        assertEquals(0, environment.launches); assertEquals(0, environment.repairs());
        for (int i = 0; i < 6; i++) {
            assertEquals(InstallTask.Step.FAILED, task.snapshot().steps[i]);
            assertFalse(task.snapshot().details[i].equals("尚未检查"));
        }
    }
    @Test public void freshExtractionRequiresRepairRequest() throws Exception {
        Environment environment = new Environment(); environment.ready = false; environment.empty = true;
        environment.run(false, 1); assertEquals(0, environment.extractions);
        InstallTask task = environment.run(true, 1);
        assertEquals(1, environment.extractions); assertEquals(0, environment.launches);
        assertEquals(InstallTask.Outcome.SUCCEEDED, task.snapshot().outcome);
    }
    @Test public void cancellationDuringRepairStopsBeforeRecheckAndFurtherWrites() throws Exception {
        Environment environment = new Environment(); environment.cancelDuringRepair = true;
        environment.failures.addAll(Set.of("pnpm", "dns"));
        assertThrows(InstallTask.Cancelled.class, () -> environment.run(true, 0));
        assertEquals(1, environment.pnpm); assertEquals(0, environment.patches); assertEquals(1, environment.launches);
    }
    @Test public void repairFailureIsRecordedAndOtherStepsCanFinish() throws Exception {
        Environment environment = new Environment(); environment.throwRepair = true;
        environment.failures.addAll(Set.of("pnpm", "dns"));
        InstallTask task = environment.run(true, 0);
        assertEquals(InstallTask.Outcome.FAILED, task.snapshot().outcome);
        assertEquals(1, environment.patches); assertFalse(task.snapshot().log.contains("private-value"));
    }
    @Test public void zeroProcessExitWithoutProbeResultsIsFailure() throws Exception {
        Environment environment = new Environment(); environment.missingResult = true;
        InstallTask task = environment.run(false, 3);
        assertEquals(InstallTask.Outcome.FAILED, task.snapshot().outcome);
        assertTrue(task.snapshot().log.contains("未收到结果"));
    }
    @Test public void earlyContainerExitLeavesAllSixResultsAndDoesNotStartRepairs() throws Exception {
        Environment environment = new Environment(); environment.earlyExit = true;
        InstallTask task = new InstallTask(); task.start(true, 0);
        assertThrows(IOException.class, () -> new InstallPipeline(environment).run(task, true, 0));
        assertEquals(InstallTask.Step.OK, task.snapshot().steps[0]);
        for (int i = 1; i < 6; i++) {
            assertEquals(InstallTask.Step.FAILED, task.snapshot().steps[i]);
            assertTrue(task.snapshot().details[i].contains("未收到结果"));
        }
        assertEquals(0, environment.repairs());
    }
}
