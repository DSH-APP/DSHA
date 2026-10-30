#!/usr/bin/env python3
"""Execute the current diagnostic feedback observers on a JVM with UI/LiveData doubles.

Tests the production observer bodies and feedback helpers, not Android lifecycle,
clipboard, or SAF. No APK, SDK, device, network, or user data is required.
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', type=Path, required=True)
    args = parser.parse_args()
    if sys.flags.optimize:
        parser.error('TEST_ASSERTIONS_REQUIRE_UNOPTIMIZED_PYTHON')
    activity = (APP / 'ui/DiagnosticActivity.java').read_text(encoding='utf-8')
    repository = (APP / 'core/DiagnosticRepository.java').read_text(encoding='utf-8')
    start = activity.index('    repository.report.observe(')
    observers = activity[start:activity.index('    if (saved == null)', start)]
    # Include the actual private helper if the Activity uses one. No replacement policy.
    helpers = []
    for name in ('updateFeedbackActions', 'canShareReport'):
        match = re.search(r'  private (?:void|boolean) ' + name + r'\(\) \{', activity)
        if match:
            depth, end = 1, match.end()
            while depth:
                depth += (activity[end] == '{') - (activity[end] == '}')
                end += 1
            helpers.append(activity[match.start():end])
    fields = []
    for name in ('report', 'busy', 'phase'):
        fields.append(re.search(r'  public final MutableLiveData<[^>]+> ' + name + r' = [^;]+;', repository).group())
    enum = re.search(r'  public enum Phase \{[^}]+\}', repository).group()
    harness = r'''
import java.util.*;
import java.util.function.Consumer;
public final class DiagnosticFeedbackProbe {
  static final class MutableLiveData<T> {
    T value;
    final List<Consumer<T>> observers = new ArrayList<>();
    MutableLiveData(T value) { this.value = value; }
    T getValue() { return value; }
    void observe(Object owner, Consumer<T> observer) { observers.add(observer); observer.accept(value); }
    void setValue(T next) { value = next; for (var observer : observers) observer.accept(next); }
  }
  static final class DiagnosticRepository {
    ENUM
    FIELDS
  }
  static final class R { static final class id {
    static final int diagnostic_report=1, diagnostic_headline=2, diagnostic_status=3,
      diagnostic_refresh=4, diagnostic_repair=5, diagnostic_copy=6, diagnostic_export=7,
      diagnostic_logs_download=8;
  } }
  static class TextView {
    boolean enabled = true;
    String text;
    void setText(String value) { text = value; }
    void setEnabled(boolean value) { enabled = value; }
  }
  final Map<Integer, TextView> views = new HashMap<>();
  final DiagnosticRepository repository;
  DiagnosticFeedbackProbe(DiagnosticRepository repository) {
    this.repository = repository;
    OBSERVERS
  }
  TextView findViewById(int id) { return views.computeIfAbsent(id, ignored -> new TextView()); }
  HELPERS
  static int checks;
  void expect(boolean enabled, String name) {
    for (int id : new int[]{R.id.diagnostic_copy, R.id.diagnostic_export}) {
      if (findViewById(id).enabled != enabled) throw new AssertionError(name + ": id=" + id);
      checks++;
    }
    if (!findViewById(R.id.diagnostic_logs_download).enabled)
      throw new AssertionError("independent error logs disabled: " + name);
    checks++;
  }
  public static void main(String[] args) {
    var repo = new DiagnosticRepository();
    var page = new DiagnosticFeedbackProbe(repo);
    page.expect(false, "initial page has no diagnostic report");
    repo.phase.setValue(DiagnosticRepository.Phase.RUNNING);
    repo.busy.setValue(true);
    repo.report.setValue("partial observations");
    page.expect(false, "partial report during collection");
    repo.phase.setValue(DiagnosticRepository.Phase.SUCCEEDED);
    page.expect(false, "finished phase before busy lease is released");
    repo.busy.setValue(false);
    page.expect(true, "completed report available");
    new DiagnosticFeedbackProbe(repo).expect(true, "recreated page with completed report");
    repo.phase.setValue(DiagnosticRepository.Phase.RUNNING);
    page.expect(false, "starting another collection with old report");
    repo.busy.setValue(true);
    new DiagnosticFeedbackProbe(repo).expect(false, "recreated page during collection");
    repo.report.setValue("failure details");
    repo.phase.setValue(DiagnosticRepository.Phase.FAILED);
    repo.busy.setValue(false);
    page.expect(true, "failure report can still be shared");
    new DiagnosticFeedbackProbe(repo).expect(true, "recreated page with failure report");
    repo.report.setValue("");
    page.expect(false, "empty report cannot be shared even after failure");
    repo.report.setValue("   \n\t");
    page.expect(false, "whitespace report cannot be shared");
    repo.report.setValue(null);
    page.expect(false, "missing report cannot be shared");
    // Early failures (maintenance pending / gate busy) do not enter busy=true first.
    repo = new DiagnosticRepository();
    page = new DiagnosticFeedbackProbe(repo);
    repo.phase.setValue(DiagnosticRepository.Phase.FAILED);
    page.expect(false, "failure phase arrived before report");
    repo.report.setValue("maintenance interrupted");
    page.expect(true, "early failure report arrives after phase");
    if (!page.findViewById(R.id.diagnostic_refresh).enabled)
      throw new AssertionError("retry action disabled after early failure");
    System.out.println("PASS diagnostic feedback: " + checks + " assertions across 14 states; JVM UI doubles, no Android UI claim");
  }
}
'''
    for key, value in [('ENUM', enum), ('FIELDS', '\n'.join(fields)),
                       ('OBSERVERS', observers), ('HELPERS', '\n'.join(helpers))]:
        harness = harness.replace(key, value)
    test_home = ROOT / 'app/build/host-tests'
    test_home.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='diagnostic-feedback-', dir=test_home) as directory:
        work = Path(directory)
        (work / 'DiagnosticFeedbackProbe.java').write_text(harness, encoding='utf-8')
        # Translation is outside the policy under test; both methods only render labels.
        ui = work / 'com/deepseekharness/app/util/UiText.java'
        ui.parent.mkdir(parents=True)
        ui.write_text('''package com.deepseekharness.app.util;
public final class UiText {
 public static String text(String value) { return value; }
 public static String choose(String zh, String en) { return zh; }
}''', encoding='utf-8')
        suffix = '.exe' if os.name == 'nt' else ''
        subprocess.run([str(args.jdk / 'bin' / ('javac' + suffix)), '--release', '17', '-encoding', 'UTF-8',
                        '-d', str(work), str(work / 'DiagnosticFeedbackProbe.java'), str(ui)],
                       check=True, timeout=60)
        subprocess.run([str(args.jdk / 'bin' / ('java' + suffix)), '-Dfile.encoding=UTF-8',
                        '-cp', str(work), 'DiagnosticFeedbackProbe'], check=True, timeout=30)


if __name__ == '__main__':
    main()
