package com.deepseekharness.app.plugins;

import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.backup.JvmBackupFileSystem;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.Assume;

/** Native attribute test port. Every Windows identity is a fresh real os.lstat, never a path cache. */
final class PluginHostFiles {
  private static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");
  private static Process worker;
  private static BufferedWriter requests;
  private static BufferedReader responses;
  private static java.util.concurrent.ExecutorService reader;

  private PluginHostFiles() {}

  static synchronized BackupFileSystem.Node stat(File file) throws IOException {
    if (!WINDOWS) return new JvmBackupFileSystem().stat(file);
    if (worker == null) {
      String python = System.getenv("DSHA_PYTHON");
      if (python == null || python.isBlank()) python = "python";
      String script =
          "import base64,os,stat,sys\n"
              + "for line in sys.stdin:\n"
              + " try:\n"
              + "  p=base64.b64decode(line.strip()).decode('utf-8');s=os.lstat(p)\n"
              + "  kind='LINK' if stat.S_ISLNK(s.st_mode) else 'DIRECTORY' if stat.S_ISDIR(s.st_mode) else 'FILE' if stat.S_ISREG(s.st_mode) else 'SPECIAL'\n"
              + "  print(kind,s.st_dev,s.st_ino,s.st_size,s.st_mtime_ns//1000000,stat.S_IMODE(s.st_mode),sep='\\t',flush=True)\n"
              + " except FileNotFoundError: print('MISSING',flush=True)\n"
              + " except Exception as e: print('ERROR:'+str(e),flush=True)\n";
      worker =
          new ProcessBuilder(python, "-B", "-u", "-c", script)
              .redirectError(ProcessBuilder.Redirect.INHERIT)
              .start();
      requests =
          new BufferedWriter(
              new OutputStreamWriter(worker.getOutputStream(), StandardCharsets.UTF_8));
      responses =
          new BufferedReader(
              new InputStreamReader(worker.getInputStream(), StandardCharsets.UTF_8));
      reader =
          java.util.concurrent.Executors.newSingleThreadExecutor(
              work -> {
                Thread thread = new Thread(work, "plugin-host-stat-read");
                thread.setDaemon(true);
                return thread;
              });
    }
    requests.write(
        Base64.getEncoder()
            .encodeToString(file.getAbsolutePath().getBytes(StandardCharsets.UTF_8)));
    requests.newLine();
    requests.flush();
    String response;
    try {
      response = reader.submit(responses::readLine).get(5, java.util.concurrent.TimeUnit.SECONDS);
    } catch (Exception failure) {
      close();
      if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new IOException("HOST_NATIVE_STAT_RESPONSE_TIMEOUT_OR_FAILURE", failure);
    }
    if (response == null || response.startsWith("ERROR:"))
      throw new IOException("HOST_NATIVE_STAT_FAILED:" + response);
    if (response.equals("MISSING")) return new BackupFileSystem.Node("MISSING", "", 0, 0, 0, 0);
    String[] fields = response.split("\t");
    if (fields.length != 6) throw new IOException("HOST_NATIVE_STAT_PROTOCOL:" + response);
    return new BackupFileSystem.Node(
        fields[0],
        fields[1] + ":" + fields[2],
        Long.parseLong(fields[3]),
        Long.parseLong(fields[4]),
        Long.parseLong(fields[1]),
        Integer.parseInt(fields[5]));
  }

  static synchronized void close() {
    if (worker == null) return;
    worker.destroyForcibly();
    try {
      if (!worker.waitFor(3, java.util.concurrent.TimeUnit.SECONDS))
        throw new AssertionError("Host stat helper did not exit");
      requests.close();
      responses.close();
      reader.shutdownNow();
      if (!reader.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS))
        throw new AssertionError("Host stat response reader did not exit");
    } catch (IOException failure) {
      throw new AssertionError("Cannot close host stat helper", failure);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Host stat close interrupted", failure);
    } finally {
      worker = null;
      requests = null;
      responses = null;
      reader = null;
    }
  }

  /** Only actual Windows symlink-creation denial may skip a link-specific case. */
  static void symlink(Path link, Path target) throws IOException {
    try {
      Files.createSymbolicLink(link, target);
    } catch (java.nio.file.FileSystemException denied) {
      String reason = String.valueOf(denied.getReason()).toLowerCase(java.util.Locale.ROOT);
      if (WINDOWS && (reason.contains("privilege") || reason.contains("特权")))
        Assume.assumeNoException(
            "Windows rejected the real symlink setup; covered by the POSIX gate", denied);
      throw denied;
    }
  }
}
