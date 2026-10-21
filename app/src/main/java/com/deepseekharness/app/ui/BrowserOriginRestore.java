package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.Intent;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.UiText;
import com.deepseekharness.app.util.WebPreviewPolicy;
import java.util.ArrayList;

/** Explicit old-origin recovery; it never takes an occupied port or changes browser credentials. */
public final class BrowserOriginRestore {
  public static final String QUIESCE = "dsh.android.client.ORIGIN_RESTORE_QUIESCE";
  private static final Object GATE = new Object();
  private static final java.util.Set<String> PREVIEWS = new java.util.HashSet<>();
  private static String restoring;

  private BrowserOriginRestore() {}

  /** Release only after engine/retained-resource cleanup, at the end of the preview's onDestroy. */
  public static Runnable registerPreview(android.app.Activity activity) {
    String registration = java.util.UUID.randomUUID().toString();
    synchronized (GATE) {
      PREVIEWS.add(registration);
      if (restoring != null) activity.runOnUiThread(activity::finish);
    }
    android.content.BroadcastReceiver receiver =
        new android.content.BroadcastReceiver() {
          @Override
          public void onReceive(Context context, Intent intent) {
            if (QUIESCE.equals(intent.getAction())) activity.finish();
          }
        };
    try {
      androidx.core.content.ContextCompat.registerReceiver(
          activity,
          receiver,
          new android.content.IntentFilter(QUIESCE),
          androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
    } catch (RuntimeException failure) {
      synchronized (GATE) {
        PREVIEWS.remove(registration);
        GATE.notifyAll();
      }
      throw failure;
    }
    java.util.concurrent.atomic.AtomicBoolean released =
        new java.util.concurrent.atomic.AtomicBoolean();
    return () -> {
      if (!released.compareAndSet(false, true)) return;
      try {
        activity.unregisterReceiver(receiver);
      } catch (IllegalArgumentException ignored) {
      }
      synchronized (GATE) {
        PREVIEWS.remove(registration);
        GATE.notifyAll();
      }
    };
  }

  static void awaitClosed(String ticket, java.util.function.BooleanSupplier current)
      throws Exception {
    long deadline = System.nanoTime() + 20_000_000_000L;
    synchronized (GATE) {
      while (!PREVIEWS.isEmpty()) {
        if (!ticket.equals(restoring) || !current.getAsBoolean())
          throw new java.io.IOException("RESTORE_CANCELLED");
        if (System.nanoTime() >= deadline)
          throw new java.io.IOException("RESTORE_PREVIEW_CLOSE_UNCONFIRMED");
        GATE.wait(250);
      }
      if (!ticket.equals(restoring) || !current.getAsBoolean())
        throw new java.io.IOException("RESTORE_CANCELLED");
    }
  }

  static void end(String ticket) {
    synchronized (GATE) {
      if (ticket != null && ticket.equals(restoring)) restoring = null;
      GATE.notifyAll();
    }
  }

  public static void show(Context context) {
    var controller = HarnessController.get(context);
    var identity = controller.getReadyWebPageIdentity();
    if (identity == null) {
      new DshaDialogBuilder(context)
          .setMessage(
              UiText.choose(
                  "请先启动 DSH，再恢复旧端口草稿。", "Start DSH before restoring previous-port drafts."))
          .setPositiveButton(android.R.string.ok, null)
          .show();
      return;
    }
    String base = WebPreviewPolicy.loopbackBaseUrl(identity.authUrl());
    if (base == null) return;
    int current = android.net.Uri.parse(base).getPort();
    var ports = new ArrayList<Integer>(new ConfigStore(context).webOriginPorts());
    ports.removeIf(port -> port == current);
    java.util.Collections.sort(ports);
    if (ports.isEmpty()) {
      new DshaDialogBuilder(context)
          .setMessage(
              UiText.choose(
                  "没有其它已记录端口。当前端口的草稿仍在原网页内。",
                  "There are no other recorded ports. Current-port drafts remain in the current browser."))
          .setPositiveButton(android.R.string.ok, null)
          .show();
      return;
    }
    var labels = new ArrayList<String>();
    var selected = new ArrayList<Integer>();
    var gecko = new ArrayList<Boolean>();
    for (int port : ports) {
      labels.add("WebView · " + port);
      selected.add(port);
      gecko.add(false);
      if (BrowserOriginRestoreFactory.supportsGecko()) {
        labels.add("Gecko · " + port);
        selected.add(port);
        gecko.add(true);
      }
    }
    new DshaDialogBuilder(context)
        .setTitle(UiText.choose("恢复旧端口草稿", "Restore previous-port drafts"))
        .setItems(
            labels.toArray(new String[0]),
            (dialog, which) ->
                open(context, selected.get(which), gecko.get(which), identity.authUrl()))
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  public static void open(Context context, int oldPort, boolean gecko, String currentAuthUrl) {
    if (!new ConfigStore(context).webOriginPorts().contains(oldPort))
      throw new IllegalArgumentException("ORIGIN_RESTORE_SOURCE");
    String ticket = java.util.UUID.randomUUID().toString();
    synchronized (GATE) {
      if (restoring != null) {
        new DshaDialogBuilder(context)
            .setMessage(
                UiText.choose(
                    "已有恢复操作，请先完成或返回关闭该操作。",
                    "A restore is already open. Finish it or return to close it first."))
            .setPositiveButton(android.R.string.ok, null)
            .show();
        return;
      }
      restoring = ticket;
    }
    context.sendBroadcast(new Intent(QUIESCE).setPackage(context.getPackageName()));
    try {
      context.startActivity(
          new Intent(context, BrowserOriginRestoreActivity.class)
              .putExtra("restore-ticket", ticket)
              .putExtra("old-port", oldPort)
              .putExtra("gecko", gecko)
              .putExtra("current-auth-url", currentAuthUrl));
    } catch (RuntimeException failure) {
      end(ticket);
      throw failure;
    }
  }
}
