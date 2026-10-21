package com.deepseekharness.app.runtime;

import android.content.Context;
import com.deepseekharness.app.backup.AndroidBackupFileSystem;
import com.deepseekharness.app.data.KeyVault;
import com.deepseekharness.app.util.DshAuthUrl;
import com.deepseekharness.app.util.WebSessionProof;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** 本机加密实例记录，放在noBackup；不进入用户归档或Android备份。 */
public final class StoredWebSession {
  public record Record(
      WebSessionProof.Saved proof,
      String authUrl,
      String app,
      String root,
      String descriptor,
      boolean compatible,
      String taskToken) {}

  private final Context context;
  private final AndroidBackupFileSystem fs = new AndroidBackupFileSystem();
  private final KeyVault vault;

  public StoredWebSession(Context context) {
    this.context = context.getApplicationContext();
    vault = new KeyVault(context, "dsha_web_session");
  }

  private File home() throws IOException {
    try {
      File directory = context.getNoBackupFilesDir();
      if (directory == null) throw new IOException("WEB_SESSION_PRIVATE_DIRECTORY");
      return directory;
    } catch (RuntimeException unavailable) {
      throw new IOException("WEB_SESSION_PRIVATE_DIRECTORY", unavailable);
    }
  }

  public String appIdentity() throws IOException {
    try {
      var info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
      return context.getPackageName()
          + ":"
          + info.lastUpdateTime
          + ":"
          + com.deepseekharness.app.BuildConfig.VERSION_NAME;
    } catch (Exception unavailable) {
      throw new IOException("WEB_SESSION_APK_IDENTITY", unavailable);
    }
  }

  public boolean exists() throws IOException {
    return !fs.stat(fs.child(home(), "web-session.enc")).type.equals("MISSING");
  }

  public Record read() throws IOException {
    File file = fs.child(home(), "web-session.enc");
    if (fs.stat(file).type.equals("MISSING")) return null;
    try {
      String encrypted = new String(fs.small(file, 32768), StandardCharsets.UTF_8);
      String plain = vault.decryptChecked(encrypted); // 读取失败绝不新建或替换旧密钥。
      JSONObject j = new JSONObject(plain);
      if (j.getInt("schema") != 1) throw new IOException("WEB_SESSION_SCHEMA");
      var p =
          new WebSessionProof.Saved(
              j.getString("boot"),
              j.getLong("generation"),
              j.getString("instance"),
              j.getString("nonce"),
              j.getInt("webPid"),
              j.getLong("webStarted"),
              j.getString("webCommand"),
              j.getInt("launcherPid"),
              j.getLong("launcherStarted"),
              j.getString("launcherCommand"));
      if (!p.boot().matches(com.deepseekharness.app.util.Ids.UUID_PATTERN)
          || p.generation() < 1
          || p.generation() == Long.MAX_VALUE
          || !p.instance().matches(com.deepseekharness.app.util.Ids.UUID_PATTERN)
          || p.webPid() <= 1
          || p.webStarted() <= 0
          || p.launcherPid() <= 1
          || p.launcherStarted() <= 0
          || p.webCommand().isEmpty()
          || p.launcherCommand().isEmpty()) throw new IOException("WEB_SESSION_IDENTITY");
      String url = j.getString("authUrl"), token = j.getString("taskToken");
      if (DshAuthUrl.parse(url) == null
          || !token.matches("[a-f0-9]{64}")
          || !token.equals(p.nonce())) throw new IOException("WEB_SESSION_CREDENTIAL");
      return new Record(
          p,
          url,
          j.getString("app"),
          j.getString("root"),
          j.getString("descriptor"),
          j.getBoolean("compatible"),
          token);
    } catch (IOException failure) {
      throw failure;
    } catch (Exception invalid) {
      throw new IOException("WEB_SESSION_UNREADABLE", invalid);
    }
  }

  public void write(Record record) throws IOException {
    try {
      File home = home();
      var p = record.proof();
      JSONObject j =
          new JSONObject()
              .put("schema", 1)
              .put("boot", p.boot())
              .put("generation", p.generation())
              .put("instance", p.instance())
              .put("nonce", p.nonce())
              .put("webPid", p.webPid())
              .put("webStarted", p.webStarted())
              .put("webCommand", p.webCommand())
              .put("launcherPid", p.launcherPid())
              .put("launcherStarted", p.launcherStarted())
              .put("launcherCommand", p.launcherCommand())
              .put("authUrl", record.authUrl())
              .put("app", record.app())
              .put("root", record.root())
              .put("descriptor", record.descriptor())
              .put("compatible", record.compatible())
              .put("taskToken", record.taskToken());
      String encrypted = vault.encrypt(j.toString());
      if (encrypted.isEmpty() || !j.toString().equals(vault.decryptChecked(encrypted)))
        throw new IOException("WEB_SESSION_ENCRYPTION_UNAVAILABLE");
      fs.atomic(home, "web-session.enc", encrypted.getBytes(StandardCharsets.UTF_8));
      fs.syncDirectory(home);
    } catch (IOException failure) {
      throw failure;
    } catch (Exception invalid) {
      throw new IOException("WEB_SESSION_WRITE", invalid);
    }
  }

  /** 调用方必须持有对应停止已完成或旧实例已退休的新启动围栏。 */
  public void clear() throws IOException {
    File home = home();
    File file = fs.child(home, "web-session.enc");
    if (fs.stat(file).type.equals("MISSING")) return;
    fs.delete(file);
    fs.syncDirectory(home);
  }
}
