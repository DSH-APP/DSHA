package com.deepseekharness.app.runtime;

import com.deepseekharness.app.backup.BackupFileSystem;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Shared mechanics only; each trial retains its own data, validation and close contract. */
final class TrialSupport {
  private TrialSupport() {}

  interface ExitCheck {
    boolean confirmed(Process launcher) throws IOException;
  }

  static void write(BackupFileSystem fs, File root, String name, byte[] bytes) throws IOException {
    fs.parents(root, name);
    File target = fs.child(root, name);
    try (OutputStream out = fs.create(target)) {
      out.write(bytes);
    }
    fs.syncDirectory(target.getParentFile());
  }

  static String identityPrefix() {
    return "printf '%s\\n' $$ > .dsha-web.pid; IFS= read -r DSHA_STAT < /proc/$$/stat; "
        + "DSHA_FIELDS=${DSHA_STAT##*) }; set -- $DSHA_FIELDS; "
        + "printf '%s %s\\n' $$ ${20} > .dsha-web.identity; ";
  }

  static Process checkedExit(Process launcher, ExitCheck check, Runnable stop, String errorCode) {
    if (launcher == null || check == null || stop == null)
      throw new IllegalArgumentException("TRIAL_EXIT_ARGUMENT");
    return new CheckedExit(launcher, check, stop, errorCode);
  }

  /** A launcher exit cannot release a lease while its owned guest remains unconfirmed. */
  private static final class CheckedExit extends Process {
    private final Process launcher;
    private final ExitCheck check;
    private final Runnable stop;
    private final String errorCode;

    CheckedExit(Process launcher, ExitCheck check, Runnable stop, String errorCode) {
      this.launcher = launcher;
      this.check = check;
      this.stop = stop;
      this.errorCode = errorCode;
    }

    @Override
    public int exitValue() {
      int value = launcher.exitValue();
      try {
        if (!check.confirmed(launcher)) throw new IllegalThreadStateException(errorCode);
      } catch (IOException unknown) {
        IllegalThreadStateException error = new IllegalThreadStateException(errorCode);
        error.initCause(unknown);
        throw error;
      }
      return value;
    }

    @Override
    public int waitFor() throws InterruptedException {
      for (; ; ) {
        try {
          return exitValue();
        } catch (IllegalThreadStateException waiting) {
          Thread.sleep(100);
        }
      }
    }

    @Override
    public InputStream getInputStream() {
      return launcher.getInputStream();
    }

    @Override
    public InputStream getErrorStream() {
      return launcher.getErrorStream();
    }

    @Override
    public OutputStream getOutputStream() {
      return launcher.getOutputStream();
    }

    @Override
    public void destroy() {
      stop.run();
    }
  }
}
