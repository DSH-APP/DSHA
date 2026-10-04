package com.deepseekharness.app.util;

import java.io.File;
import java.io.FileInputStream;
import com.deepseekharness.app.backup.BackupFileSystem;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/** 受管运行时的同盘改名事务。意图先落盘，失败和进程中断均能回切，不复制用户数据。 */
public final class RuntimeUpdateTransaction {
  private final File files, directory;
  private final BackupFileSystem fs;

  private RuntimeUpdateTransaction(BackupFileSystem fs, File files, File directory)
      throws IOException {
    this.fs = fs;
    this.files = files.getCanonicalFile();
    this.directory = directory.getAbsoluteFile();
    local(this.directory);
    local(new File(this.files, "runtime-updates"));
  }

  public static RuntimeUpdateTransaction create(BackupFileSystem fs, File files)
      throws IOException {
    if (pending(fs, files) != null)
      throw new IOException(com.deepseekharness.app.util.UiText.text("先恢复上次运行时更新"));
    File home = new File(files.getCanonicalFile(), "runtime-updates");
    if (!home.getAbsoluteFile().equals(home.getCanonicalFile()))
      throw new IOException(com.deepseekharness.app.util.UiText.text("运行时维护目录不安全"));
    if (fs.stat(home).type.equals("MISSING")) fs.directory(home);
    if (!fs.stat(home).type.equals("DIRECTORY"))
      throw new IOException(com.deepseekharness.app.util.UiText.text("无法建立运行时维护目录"));
    File entry = new File(home, UUID.randomUUID().toString());
    fs.directory(entry);
    return new RuntimeUpdateTransaction(fs, files, entry);
  }

  public static RuntimeUpdateTransaction pending(BackupFileSystem fs, File files)
      throws IOException {
    File home = new File(files.getCanonicalFile(), "runtime-updates");
    if (!home.getAbsoluteFile().equals(home.getCanonicalFile()))
      throw new IOException(com.deepseekharness.app.util.UiText.text("运行时维护目录不安全"));
    if (fs.stat(home).type.equals("MISSING")) return null;
    RuntimeUpdateTransaction pending = null;
    for (String id : fs.list(home))
      if (Ids.uuid(id)) {
        File entry = fs.child(home, id);
        RuntimeUpdateTransaction item = new RuntimeUpdateTransaction(fs, files, entry);
        if (item.marker("intent.properties") && !item.finished()) {
          if (pending != null)
            throw new IOException(com.deepseekharness.app.util.UiText.text("多份运行时维护未完成，已停止切换"));
          pending = item;
        }
      }
    return pending;
  }

  public File directory() {
    return directory;
  }

  public File stage() {
    return new File(directory, "stage");
  }

  private boolean finished() throws IOException {
    return marker("committed") || marker("rolled-back");
  }

  private boolean marker(String name) throws IOException {
    String type = fs.stat(new File(directory, name)).type;
    if (type.equals("MISSING")) return false;
    if (!type.equals("FILE")) throw new IOException("RUNTIME_RECORD_TYPE");
    return true;
  }

  private void local(File file) throws IOException {
    File absolute = file.getAbsoluteFile();
    if (!absolute.getCanonicalFile().equals(absolute)
        || !absolute.getPath().startsWith(files.getPath() + File.separator))
      throw new IOException(
          com.deepseekharness.app.util.UiText.format("运行时维护路径不安全：%s", file.getName()));
  }

  private void parent(File file) throws IOException {
    local(file.getParentFile());
  }

  private boolean present(File file) throws IOException {
    return !fs.stat(file).type.equals("MISSING");
  }

  private void mark(String name, String text) throws IOException {
    File target = new File(directory, name);
    local(target);
    if (present(target))
      throw new IOException(com.deepseekharness.app.util.UiText.format("运行时阶段已存在：%s", name));
    fs.atomic(directory, name, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  public void begin(List<String> paths) throws IOException {
    if (paths.isEmpty() || paths.size() > 2048 || new HashSet<>(paths).size() != paths.size())
      throw new IOException(com.deepseekharness.app.util.UiText.text("运行时更新清单无效"));
    StringBuilder intent = new StringBuilder("count=" + paths.size() + "\n");
    for (int i = 0; i < paths.size(); i++) {
      String path = paths.get(i);
      if (!ManagedRuntimeLayout.allowed(path))
        throw new IOException(com.deepseekharness.app.util.UiText.text("禁止更新用户数据路径"));
      File target = new File(files, path), source = new File(stage(), path);
      parent(target);
      parent(source);
      if (!present(source))
        throw new IOException(com.deepseekharness.app.util.UiText.format("新运行时缺少文件：%s", path));
      intent
          .append("path.")
          .append(i)
          .append('=')
          .append(path)
          .append('\n')
          .append("had.")
          .append(i)
          .append('=')
          .append(present(target))
          .append('\n');
    }
    mark("intent.properties", intent.toString());
  }

  private Properties intent() throws IOException {
    Properties value = new Properties();
    File log = new File(directory, "intent.properties");
    local(log);
    value.load(new java.io.ByteArrayInputStream(fs.small(log, 1024 * 1024)));
    return value;
  }

  private List<String> paths(Properties intent) throws IOException {
    int count;
    try {
      count = Integer.parseInt(intent.getProperty("count", ""));
    } catch (NumberFormatException error) {
      throw new IOException(com.deepseekharness.app.util.UiText.text("运行时维护日志无效"), error);
    }
    if (count <= 0 || count > 2048)
      throw new IOException(com.deepseekharness.app.util.UiText.text("运行时维护清单数量无效"));
    List<String> result = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      String path = intent.getProperty("path." + i), had = intent.getProperty("had." + i);
      if (!ManagedRuntimeLayout.allowed(path)
          || result.contains(path)
          || !("true".equals(had) || "false".equals(had)))
        throw new IOException(com.deepseekharness.app.util.UiText.text("运行时维护清单无效"));
      result.add(path);
    }
    return result;
  }

  private void move(File from, File to) throws IOException {
    parent(from);
    parent(to);
    String relative =
        to.getPath().substring(files.getPath().length() + 1).replace(File.separatorChar, '/');
    fs.parents(files, relative);
    fs.move(from, to);
    fs.syncDirectory(from.getParentFile());
    if (!from.getParentFile().equals(to.getParentFile())) fs.syncDirectory(to.getParentFile());
  }

  public void replace() throws IOException {
    if (finished()) throw new IOException(com.deepseekharness.app.util.UiText.text("运行时事务已结束"));
    Properties intent = intent();
    List<String> paths = paths(intent);
    for (int i = 0; i < paths.size(); i++) {
      File target = new File(files, paths.get(i)), source = new File(stage(), paths.get(i));
      File previous = new File(directory, "previous/" + i);
      if ("true".equals(intent.getProperty("had." + i))) move(target, previous);
      move(source, target);
    }
  }

  public void commit() throws IOException {
    for (String path : paths(intent())) {
      File target = new File(files, path);
      parent(target);
      if (!present(target) || present(new File(stage(), path)))
        throw new IOException(com.deepseekharness.app.util.UiText.text("运行时文件尚未切换完整"));
    }
    mark("committed", "ok\n");
  }

  public void rollback() throws IOException {
    if (finished()) return;
    Properties intent = intent();
    List<String> paths = paths(intent);
    for (int i = paths.size() - 1; i >= 0; i--) {
      File target = new File(files, paths.get(i)), previous = new File(directory, "previous/" + i);
      parent(target);
      parent(previous);
      boolean had = "true".equals(intent.getProperty("had." + i));
      if (present(previous) || !had) {
        if (present(target)) move(target, new File(directory, "failed/" + i));
        if (present(previous)) move(previous, target);
      } else if (!present(target))
        throw new IOException(com.deepseekharness.app.util.UiText.text("旧运行时位置不明，已保留全部现场"));
    }
    mark("rolled-back", "ok\n");
  }

  public void cleanup(MaintenanceTransaction.TreeCleaner cleaner) throws IOException {
    if (!marker("committed") || marker("cleaned")) return;
    for (String name : new String[] {"previous", "stage"}) {
      File child = new File(directory, name);
      local(child);
      if (child.exists()) cleaner.delete(child);
    }
    mark("cleaned", "ok\n");
  }

  public static void cleanupCompleted(
      BackupFileSystem fs, File files, MaintenanceTransaction.TreeCleaner cleaner)
      throws IOException {
    File home = new File(files.getCanonicalFile(), "runtime-updates");
    if (!home.getAbsoluteFile().equals(home.getCanonicalFile()))
      throw new IOException(com.deepseekharness.app.util.UiText.text("运行时维护目录不安全"));
    if (fs.stat(home).type.equals("MISSING")) return;
    for (String id : fs.list(home))
      if (Ids.uuid(id))
        new RuntimeUpdateTransaction(fs, files, fs.child(home, id)).cleanup(cleaner);
  }
}
