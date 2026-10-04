package com.deepseekharness.app.util;

import java.io.File;
import java.io.FileInputStream;
import com.deepseekharness.app.backup.BackupFileSystem;
import java.io.IOException;
import java.util.Properties;
import java.util.UUID;

/** 同一私有文件系统上的环境切换；提交并验证个人数据后才释放旧环境。失败现场与安全归档保留。 */
public final class MaintenanceTransaction {
  private final File files, directory, environment;
  private final BackupFileSystem fs;

  private MaintenanceTransaction(BackupFileSystem fs, File files, File directory)
      throws IOException {
    this.fs = fs;
    this.files = files.getCanonicalFile();
    this.directory = directory;
    environment = new File(this.files, "linux");
    requireLocal(directory.getParentFile());
    requireLocal(directory);
    requireLocal(environment);
  }

  public static MaintenanceTransaction create(BackupFileSystem fs, File files) throws IOException {
    File home = new File(files.getCanonicalFile(), "maintenance");
    if (!home.getCanonicalFile().equals(home.getAbsoluteFile()))
      throw new IOException(com.deepseekharness.app.util.UiText.text("维护目录不能是外部链接"));
    if (fs.stat(home).type.equals("MISSING")) fs.directory(home);
    if (!fs.stat(home).type.equals("DIRECTORY"))
      throw new IOException(com.deepseekharness.app.util.UiText.text("无法建立私有维护目录"));
    File directory = new File(home, UUID.randomUUID().toString());
    fs.directory(directory);
    return new MaintenanceTransaction(fs, files, directory);
  }

  public static MaintenanceTransaction pending(BackupFileSystem fs, File files) throws IOException {
    File home = new File(files.getCanonicalFile(), "maintenance");
    if (!home.getCanonicalFile().equals(home.getAbsoluteFile()))
      throw new IOException(com.deepseekharness.app.util.UiText.text("维护目录不安全"));
    if (fs.stat(home).type.equals("MISSING")) return null;
    java.util.List<String> entries = fs.list(home);
    MaintenanceTransaction pending = null;
    for (String id : entries) {
      if (!Ids.uuid(id)) continue;
      File entry = fs.child(home, id);
      MaintenanceTransaction task = new MaintenanceTransaction(fs, files, entry);
      if (task.marker("intent.properties") && !task.finished()) {
        if (pending != null)
          throw new IOException(com.deepseekharness.app.util.UiText.text("存在多份未完成维护，已阻止覆盖环境"));
        pending = task;
      }
    }
    return pending;
  }

  public File directory() {
    return directory;
  }

  public File archive() {
    return new File(directory, "safety.tar.gz");
  }

  public File personalArchive() {
    return new File(directory, "personal.tar.gz");
  }

  public boolean finished() throws IOException {
    return marker("committed") || marker("rolled-back");
  }

  private boolean marker(String name) throws IOException {
    String type = fs.stat(new File(directory, name)).type;
    if (type.equals("MISSING")) return false;
    if (!type.equals("FILE")) throw new IOException("MAINTENANCE_RECORD_TYPE");
    return true;
  }

  private void requireLocal(File file) throws IOException {
    File absolute = file.getAbsoluteFile();
    if (!absolute.getCanonicalFile().equals(absolute)
        || !absolute.getPath().startsWith(files.getPath() + File.separator))
      throw new IOException(
          com.deepseekharness.app.util.UiText.format("维护路径不在预期私有目录：%s", file.getName()));
  }

  private void mark(String name, String text) throws IOException {
    File target = new File(directory, name);
    requireLocal(target);
    if (!fs.stat(target).type.equals("MISSING"))
      throw new IOException(com.deepseekharness.app.util.UiText.format("维护阶段已存在：%s", name));
    fs.atomic(directory, name, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  public void verify(String expectedHash) throws IOException {
    requireLocal(archive());
    var archiveNode = fs.stat(archive());
    try (java.io.InputStream input = fs.read(archive(), archiveNode)) {
      if (archiveNode.size == 0
          || !expectedHash.equals(FileIntegrity.copy(input, null, 16L * 1024 * 1024 * 1024).sha256))
        throw new IOException(com.deepseekharness.app.util.UiText.text("安全备份校验失败，原环境未切换"));
    }
    mark("verified", expectedHash);
  }

  public void begin(boolean fresh) throws IOException {
    requireLocal(environment);
    boolean exists = !fs.stat(environment).type.equals("MISSING");
    if (fresh && exists)
      throw new IOException(com.deepseekharness.app.util.UiText.text("已有环境，必须先完成安全备份"));
    if (!fresh && !marker("verified"))
      throw new IOException(com.deepseekharness.app.util.UiText.text("未校验安全备份，禁止切换环境"));
    // 意图先落盘；任意进程退出点均可按 old/new 的存在情况恢复。
    mark("intent.properties", "hadEnvironment=" + exists + "\n");
    if (exists) move(environment, new File(directory, "previous-linux"));
  }

  public void commit() throws IOException {
    mark("committed", "ok\n");
  }

  /** 个人目录已在新环境落盘并逐文件验证，之后才允许释放旧运行时。 */
  public void dataPreserved(String personalHash) throws IOException {
    if (personalHash == null || !personalHash.matches("[a-f0-9]{64}"))
      throw new IOException(com.deepseekharness.app.util.UiText.text("个人数据验证摘要无效"));
    mark("data-preserved", personalHash);
  }

  public interface TreeCleaner {
    void delete(File root) throws IOException;
  }

  /** 可中断、可重试；旧版维护没有迁移证明，因此绝不自动清理其目录。 */
  public boolean cleanup(TreeCleaner cleaner) throws IOException {
    if (!marker("committed") || !marker("data-preserved")) return false;
    if (marker("cleaned")) return true;
    File previous = new File(directory, "previous-linux");
    requireLocal(previous);
    requireLocal(personalArchive());
    if (previous.exists()) cleaner.delete(previous);
    if (personalArchive().exists() && !personalArchive().delete())
      throw new IOException(com.deepseekharness.app.util.UiText.text("无法释放个人文件临时归档"));
    mark("cleaned", "ok\n");
    return true;
  }

  public static void cleanupCompleted(BackupFileSystem fs, File files, TreeCleaner cleaner)
      throws IOException {
    File home = new File(files.getCanonicalFile(), "maintenance");
    if (!home.getCanonicalFile().equals(home.getAbsoluteFile()))
      throw new IOException(com.deepseekharness.app.util.UiText.text("维护目录不安全"));
    if (fs.stat(home).type.equals("MISSING")) return;
    for (String id : fs.list(home))
      if (Ids.uuid(id)) new MaintenanceTransaction(fs, files, fs.child(home, id)).cleanup(cleaner);
  }

  private void move(File source, File target) throws IOException {
    requireLocal(source);
    requireLocal(target);
    fs.move(source, target);
    fs.syncDirectory(source.getParentFile());
    if (!source.getParentFile().equals(target.getParentFile()))
      fs.syncDirectory(target.getParentFile());
  }

  public void rollback() throws IOException {
    if (finished()) return;
    Properties intent = new Properties();
    intent.load(
        new java.io.ByteArrayInputStream(fs.small(new File(directory, "intent.properties"), 4096)));
    String previous = intent.getProperty("hadEnvironment");
    if (!"true".equals(previous) && !"false".equals(previous))
      throw new IOException(com.deepseekharness.app.util.UiText.text("维护日志不完整，已阻止覆盖环境"));
    File old = new File(directory, "previous-linux"), failed = new File(directory, "failed-linux");
    requireLocal(old);
    requireLocal(failed);
    requireLocal(environment);
    if (!fs.stat(old).type.equals("MISSING") || "false".equals(previous)) {
      if (!fs.stat(environment).type.equals("MISSING")) move(environment, failed);
      if (!fs.stat(old).type.equals("MISSING")) move(old, environment);
    } else if (!fs.stat(environment).type.equals("DIRECTORY")) {
      throw new IOException(com.deepseekharness.app.util.UiText.text("原环境位置不明，已保留所有现场文件"));
    }
    mark("rolled-back", "ok\n");
  }
}
