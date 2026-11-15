#!/usr/bin/env python3
"""Execute the current diagnostic feedback observers on a JVM with UI/LiveData doubles.

Tests the production observer bodies and feedback helpers (renderActions/renderLogs/enable/
resultsEmpty) of ui/DiagnosticActivity, not Android lifecycle, clipboard, or SAF. No APK, SDK,
device, network, or user data is required. Based on upstream DSH-APP/DSHA#213 by
lzh2034855417-beep, adapted to the build252+ diagnostic page.
"""
import argparse
from pathlib import Path
import os
import re
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/java/com/deepseekharness/app'


def method(source, name):
    match = re.search(r'  private (?:void|boolean) ' + name + r'\([^)]*\) \{', source)
    if not match:
        raise SystemExit('MISSING_HELPER ' + name)
    depth, end = 1, match.end()
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[match.start():end]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', type=Path, required=True)
    args = parser.parse_args()
    if sys.flags.optimize:
        parser.error('TEST_ASSERTIONS_REQUIRE_UNOPTIMIZED_PYTHON')
    activity = (APP / 'ui/DiagnosticActivity.java').read_text(encoding='utf-8')
    repository = (APP / 'core/DiagnosticRepository.java').read_text(encoding='utf-8')
    start = activity.index('    repository.results.observe(')
    observers = activity[start:activity.index('    findViewById(R.id.diagnostic_back)', start)]
    helpers = [method(activity, name) for name in ('renderActions', 'renderLogs', 'enable', 'resultsEmpty')]
    fields = [re.search(r'  public final MutableLiveData<[^;]+> ' + name + r' =[^;]+;', repository, re.S).group()
              for name in ('report', 'busy', 'phase', 'results')]
    enum = re.search(r'  public enum Phase \{[^}]+\}', repository).group()
    harness = r'''
import java.util.*;
import java.util.function.Consumer;
import com.deepseekharness.app.core.MutableLiveData;
public final class DiagnosticFeedbackProbe {
  static final class DiagnosticRepository {
    ENUM
    static final class Result {}
    FIELDS
  }
  static final class R { static final class id {
    static final int diagnostic_report=1, diagnostic_refresh=4, diagnostic_repair=5, diagnostic_copy=6,
      diagnostic_export=7, diagnostic_logs_status=8, diagnostic_logs_divider=9;
  } }
  static class View {
    static final int VISIBLE = 0, GONE = 8;
    boolean enabled = true;
    float alpha = 1f;
    int visibility = VISIBLE;
    void setEnabled(boolean value) { enabled = value; }
    void setAlpha(float value) { alpha = value; }
    void setVisibility(int value) { visibility = value; }
    int getVisibility() { return visibility; }
  }
  static final class TextView extends View {
    String text;
    void setText(String value) { text = value; }
  }
  // T114 第二批：DiagnosticActivity 的状态文本改用 UiMotion 过渡；宿主夹具补最小桩。
  static final class UiMotion {
    static void crossFadeText(TextView view, CharSequence text) { view.setText(text.toString()); }
    static void flash(View view) {}
    static void reveal(View view) { view.setAlpha(1f); }
  }
  static final class ResultList {
    void show(Object items) {}
    void setRunning(boolean running) {}
  }
  final Map<Integer, TextView> views = new HashMap<>();
  final DiagnosticRepository repository;
  final com.deepseekharness.app.core.ErrorLogRepository logs = new com.deepseekharness.app.core.ErrorLogRepository();
  final ResultList resultList = new ResultList();
  boolean exportTouched;
  DiagnosticFeedbackProbe(DiagnosticRepository repository) {
    this.repository = repository;
    OBSERVERS
  }
  void renderHero() {}
  @SuppressWarnings("unchecked")
  <T extends View> T findViewById(int id) { return (T) views.computeIfAbsent(id, ignored -> new TextView()); }
  HELPERS
  static int checks;
  void expect(boolean copy, boolean export, String name) {
    if (findViewById(R.id.diagnostic_copy).enabled != copy) throw new AssertionError("copy: " + name);
    if (findViewById(R.id.diagnostic_export).enabled != export) throw new AssertionError("export: " + name);
    checks += 2;
  }
  static List<DiagnosticRepository.Result> cards() { return List.of(new DiagnosticRepository.Result()); }
  public static void main(String[] args) {
    var repo = new DiagnosticRepository();
    var page = new DiagnosticFeedbackProbe(repo);
    // 故障包不依赖本次检查结果，初始即可导出；文本复制需要检查结果。
    page.expect(false, true, "initial page has no check results");
    // generate(): results 清空 → RUNNING → busy=true，再写中间报告。
    repo.results.setValue(List.of());
    repo.phase.setValue(DiagnosticRepository.Phase.RUNNING);
    repo.busy.setValue(true);
    repo.report.setValue("partial observations");
    page.expect(false, false, "collection in progress");
    // 完成：postValue 顺序 report → results → phase → busy=false。
    repo.report.setValue("full report");
    repo.results.setValue(cards());
    page.expect(false, false, "results arrived before busy lease is released");
    repo.phase.setValue(DiagnosticRepository.Phase.SUCCEEDED);
    repo.busy.setValue(false);
    page.expect(true, true, "completed report available");
    new DiagnosticFeedbackProbe(repo).expect(true, true, "recreated page with completed report");
    // 再次检查时旧结果先被清空，不能复制上一轮的报告。
    repo.results.setValue(List.of());
    repo.phase.setValue(DiagnosticRepository.Phase.RUNNING);
    repo.busy.setValue(true);
    page.expect(false, false, "starting another collection with old report");
    new DiagnosticFeedbackProbe(repo).expect(false, false, "recreated page during collection");
    // 采集失败也必须允许反馈。
    repo.report.setValue("failure details");
    repo.results.setValue(cards());
    repo.phase.setValue(DiagnosticRepository.Phase.FAILED);
    repo.busy.setValue(false);
    page.expect(true, true, "failure report can still be shared");
    // 早期失败（维护未完成 / 任务门忙）不经 busy=true：只有 report → results → phase 到达。
    repo = new DiagnosticRepository();
    page = new DiagnosticFeedbackProbe(repo);
    repo.report.setValue("maintenance interrupted");
    page.expect(false, true, "early failure report before result cards");
    repo.results.setValue(cards());
    page.expect(true, true, "early failure result cards enable copy");
    repo.phase.setValue(DiagnosticRepository.Phase.FAILED);
    page.expect(true, true, "early failure phase");
    if (!page.findViewById(R.id.diagnostic_repair).enabled)
      throw new AssertionError("repair action disabled after early failure");
    // 错误日志导出进行中时禁用导出入口。
    page.logs.state.setValue(new com.deepseekharness.app.core.ErrorLogRepository.State(true, "busy", null));
    page.expect(true, false, "error-log export in progress");
    checks++;
    System.out.println("PASS diagnostic feedback: " + checks + " assertions; JVM UI doubles, no Android UI claim");
  }
}
'''
    for key, value in [('ENUM', enum), ('FIELDS', '\n'.join(fields)),
                       ('OBSERVERS', observers), ('HELPERS', '\n'.join(helpers))]:
        harness = harness.replace(key, value)
    stubs = {
        'com/deepseekharness/app/core/MutableLiveData.java': '''package com.deepseekharness.app.core;
import java.util.*;
import java.util.function.Consumer;
public final class MutableLiveData<T> {
  T value;
  final List<Consumer<T>> observers = new ArrayList<>();
  public MutableLiveData(T value) { this.value = value; }
  public MutableLiveData() {}
  public T getValue() { return value; }
  public void observe(Object owner, Consumer<T> observer) { observers.add(observer); observer.accept(value); }
  public void setValue(T next) { value = next; for (var observer : observers) observer.accept(next); }
}''',
        'com/deepseekharness/app/core/ErrorLogRepository.java': '''package com.deepseekharness.app.core;
public final class ErrorLogRepository {
  public static final class State {
    public final boolean busy; public final String message; public final Object uri;
    public State(boolean busy, String message, Object uri) { this.busy = busy; this.message = message; this.uri = uri; }
  }
  public final MutableLiveData<State> state = new MutableLiveData<>(new State(false, "", null));
}''',
        'com/deepseekharness/app/util/UiStateText.java': '''package com.deepseekharness.app.util;
public final class UiStateText { public static String render(String value) { return value; } }''',
    }
    test_home = ROOT / 'app/build/host-tests'
    test_home.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='diagnostic-feedback-', dir=test_home) as directory:
        work = Path(directory)
        sources = [work / 'DiagnosticFeedbackProbe.java']
        sources[0].write_text(harness, encoding='utf-8')
        for name, body in stubs.items():
            path = work / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(body, encoding='utf-8')
            sources.append(path)
        suffix = '.exe' if os.name == 'nt' else ''
        subprocess.run([str(args.jdk / 'bin' / ('javac' + suffix)), '--release', '17', '-encoding', 'UTF-8',
                        '-d', str(work), *map(str, sources)], check=True, timeout=60)
        subprocess.run([str(args.jdk / 'bin' / ('java' + suffix)), '-Dfile.encoding=UTF-8',
                        '-cp', str(work), 'DiagnosticFeedbackProbe'], check=True, timeout=30)


if __name__ == '__main__':
    main()
