package com.deepseekharness.app.ui;

import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/** 慢磁盘检查不持有界面或导航权限；每次回调都须通过可撤销票据。 */
final class MainEntryGate {
  interface Check {
    boolean needsMaintenance() throws Exception;
  }

  interface Delivery {
    void accept(boolean maintenance, boolean failed);
  }

  private long revision;

  void cancel() {
    revision++;
  }

  void submit(
      Executor worker, Executor main, Check check, BooleanSupplier current, Delivery delivery) {
    long ticket = ++revision;
    worker.execute(
        () -> {
          boolean maintenance = false, failed = false;
          try {
            maintenance = check.needsMaintenance();
          } catch (Exception unavailable) {
            failed = true;
          }
          final boolean result = maintenance, error = failed;
          main.execute(
              () -> {
                if (ticket == revision && current.getAsBoolean()) delivery.accept(result, error);
              });
        });
  }
}
