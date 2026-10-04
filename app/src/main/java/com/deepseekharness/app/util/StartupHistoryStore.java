package com.deepseekharness.app.util;

import com.deepseekharness.app.backup.BackupFileSystem;
import java.io.*;
import java.util.*;

/** 最近五次启动的小型诊断记录；只访问独立目录，不遍历工作区、会话或插件。 */
public final class StartupHistoryStore {
  private final File directory;
  private final BackupFileSystem fs;

  public StartupHistoryStore(BackupFileSystem fs, File files) {
    this.fs = Objects.requireNonNull(fs);
    // Android /data/user/0 是 /data/data 的宿主别名；先固定可信的父目录，再拒绝记录目录本身的软链。
    File base;
    try {
      base = files.getCanonicalFile();
    } catch (IOException error) {
      base = files.getAbsoluteFile();
    }
    directory = new File(base, "startup-history");
  }

  public static final class Entry {
    public final String id, status, stage, log, reason;
    public final long started, elapsed;
    public final boolean safe;

    Entry(String id, Properties p, String language) {
      this.id = id;
      status = p.getProperty("status", "interrupted");
      started = Long.parseLong(p.getProperty("started", "0"));
      elapsed = Long.parseLong(p.getProperty("elapsed", "0"));
      safe = Boolean.parseBoolean(p.getProperty("safe", "false"));
      stage = p.getProperty(language + "Stage", "");
      log = p.getProperty(language + "Log", "");
      reason = p.getProperty("reason", "");
    }
  }

  public synchronized void save(
      String id,
      long started,
      String status,
      String reason,
      StartupTrace.Snapshot zh,
      StartupTrace.Snapshot en)
      throws IOException {
    if (!Ids.uuid(id)) throw new IOException("Invalid startup record ID");
    if (fs.stat(directory).type.equals("MISSING")) fs.directory(directory);
    if (!fs.stat(directory).type.equals("DIRECTORY"))
      throw new IOException("Cannot create startup history");
    if (!directory.getCanonicalFile().equals(directory.getAbsoluteFile()))
      throw new IOException("Startup history cannot be a symbolic link");
    Properties p = new Properties();
    p.setProperty("status", status);
    p.setProperty("started", Long.toString(started));
    p.setProperty("elapsed", Long.toString(zh.elapsedMs));
    p.setProperty("safe", Boolean.toString(zh.safe));
    p.setProperty("reason", SensitiveData.redact(reason));
    p.setProperty("zhStage", zh.stage);
    p.setProperty("enStage", en.stage);
    p.setProperty("zhLog", zh.log);
    p.setProperty("enLog", en.log);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    p.store(bytes, "DSHA startup diagnostics");
    fs.atomic(directory, id + ".properties", bytes.toByteArray());
    List<Entry> records = list("zh");
    for (int i = 5; i < records.size(); i++) {
      File old = recordFile(records.get(i).id);
      if (!fs.stat(old).type.equals("FILE")) throw new IOException("Cannot rotate startup record");
      fs.delete(old);
      fs.syncDirectory(directory);
    }
  }

  public synchronized List<Entry> list(String language) {
    List<Entry> result = new ArrayList<>();
    try {
      if (!fs.stat(directory).type.equals("DIRECTORY")) return result;
      if (!directory.getCanonicalFile().equals(directory.getAbsoluteFile())) return result;
      for (String name : fs.list(directory)) {
        String suffix =
            name.endsWith(".properties.previous") ? ".properties.previous" : ".properties";
        if (!name.endsWith(suffix)) continue;
        String id = name.substring(0, name.length() - suffix.length());
        if (!Ids.uuid(id)) continue;
        File file = fs.child(directory, name);
        if (suffix.equals(".properties.previous")
            && !fs.stat(new File(directory, id + ".properties")).type.equals("MISSING")) continue;
        try {
          Properties p = new Properties();
          p.load(new ByteArrayInputStream(fs.small(file, 1024 * 1024)));
          result.add(new Entry(id, p, language));
        } catch (IOException | RuntimeException ignored) {
        }
      }
    } catch (IOException unavailable) {
      return result;
    }
    result.sort((a, b) -> Long.compare(b.started, a.started));
    return result;
  }

  private File recordFile(String id) throws IOException {
    File current = fs.child(directory, id + ".properties");
    return fs.stat(current).type.equals("MISSING")
        ? fs.child(directory, id + ".properties.previous")
        : current;
  }
}
