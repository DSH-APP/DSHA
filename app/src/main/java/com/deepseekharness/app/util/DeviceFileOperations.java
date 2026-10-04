package com.deepseekharness.app.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.List;

/** 受管设备文件动作；目录句柄由平台层固定，不能检查完后重新交给 shell 解析路径。 */
public final class DeviceFileOperations {
  private DeviceFileOperations() {}

  public interface Access {
    Entry open(String path, boolean write, boolean parents) throws IOException;

    void validate(String path, boolean write, boolean parents) throws IOException;
  }

  public interface Entry extends AutoCloseable {
    String path();

    String name();

    String type() throws IOException;

    Entry child(String name) throws IOException;

    List<String> names() throws IOException;

    InputStream read() throws IOException;

    OutputStream write() throws IOException;

    void directory() throws IOException;

    void touch() throws IOException;

    void metadataTo(Entry target) throws IOException;

    void delete() throws IOException;

    void move(Entry target, boolean noClobber) throws IOException;

    @Override
    void close() throws IOException;
  }

  public static void execute(DeviceShellPolicy.Plan plan, Access access) throws IOException {
    if (plan == null || plan.kind != DeviceShellPolicy.Kind.FILE || access == null)
      throw new IOException("FILE_PLAN_REQUIRED");
    cancelled();
    String op = plan.command();
    // 先拒绝整批已知链接/特殊节点；执行期间仍由已打开的目录句柄再次核对。
    for (int i = 0; i < plan.operands.size(); i++)
      access.validate(
          plan.operands.get(i),
          !(op.equals("cp") && i + 1 < plan.operands.size()),
          op.equals("mkdir") && flag(plan, 'p'));
    if (op.equals("cp") || op.equals("mv")) {
      preflightTransfers(plan, access);
      try (Entry destination =
          access.open(plan.operands.get(plan.operands.size() - 1), true, false)) {
        boolean directory = destination.type().equals("DIRECTORY");
        if (plan.operands.size() > 2 && !directory)
          throw new IOException("DESTINATION_DIRECTORY_REQUIRED");
        for (int i = 0; i + 1 < plan.operands.size(); i++) {
          cancelled();
          try (Entry source = access.open(plan.operands.get(i), op.equals("mv"), false)) {
            if (directory) {
              try (Entry target = destination.child(source.name())) {
                transfer(plan, source, target);
              }
            } else transfer(plan, source, destination);
          }
        }
      }
      return;
    }
    if (op.equals("rm") || op.equals("rmdir"))
      for (String path : plan.operands) {
        try (Entry entry = access.open(path, true, false)) {
          if (entry.type().equals("DIRECTORY")) inspect(entry, new int[] {0}, 0);
        }
      }
    for (String path : plan.operands) {
      cancelled();
      try (Entry target = access.open(path, true, op.equals("mkdir") && flag(plan, 'p'))) {
        switch (op) {
          case "mkdir":
            if (!(flag(plan, 'p') && target.type().equals("DIRECTORY"))) target.directory();
            break;
          case "touch":
            if (!(flag(plan, 'c') && target.type().equals("MISSING"))) target.touch();
            break;
          case "rm":
            if (target.type().equals("DIRECTORY")) inspect(target, new int[] {0}, 0);
            if (flag(plan, 'd')
                && !flag(plan, 'r')
                && !flag(plan, 'R')
                && target.type().equals("DIRECTORY")) {
              target.delete();
              break;
            }
            remove(target, flag(plan, 'r') || flag(plan, 'R'), flag(plan, 'f'), new int[] {0}, 0);
            break;
          case "rmdir":
            if (!target.type().equals("DIRECTORY")) throw new IOException("DIRECTORY_REQUIRED");
            target.delete();
            break;
          default:
            throw new IOException("FILE_OPERATION_UNSUPPORTED");
        }
      }
    }
  }

  private static void preflightTransfers(DeviceShellPolicy.Plan plan, Access access)
      throws IOException {
    try (Entry destination =
        access.open(plan.operands.get(plan.operands.size() - 1), true, false)) {
      boolean directory = destination.type().equals("DIRECTORY");
      if (plan.operands.size() > 2 && !directory)
        throw new IOException("DESTINATION_DIRECTORY_REQUIRED");
      for (int i = 0; i + 1 < plan.operands.size(); i++) {
        try (Entry source = access.open(plan.operands.get(i), plan.command().equals("mv"), false)) {
          inspect(source, new int[] {0}, 0);
          if (directory) {
            try (Entry target = destination.child(source.name())) {
              if (target.type().equals("DIRECTORY")) inspect(target, new int[] {0}, 0);
              else if (!target.type().equals("FILE") && !target.type().equals("MISSING"))
                throw new IOException("LINK_OR_SPECIAL_FILE");
            }
          }
        }
      }
    }
  }

  private static void transfer(DeviceShellPolicy.Plan plan, Entry source, Entry target)
      throws IOException {
    String from = DeviceShellPolicy.normalize(source.path()),
        to = DeviceShellPolicy.normalize(target.path());
    if (from.equals(to) || to.startsWith(from + "/"))
      throw new IOException("SELF_OR_DESCENDANT_TARGET");
    if (flag(plan, 'n') && !target.type().equals("MISSING")) return;
    if (plan.command().equals("mv")) {
      inspect(source, new int[] {0}, 0);
      cancelled();
      source.move(target, flag(plan, 'n'));
    } else {
      inspect(source, new int[] {0}, 0);
      copy(
          source,
          target,
          flag(plan, 'r') || flag(plan, 'R'),
          flag(plan, 'n'),
          flag(plan, 'p'),
          new int[] {0},
          0);
    }
  }

  private static void inspect(Entry source, int[] count, int depth) throws IOException {
    bounded(count, depth);
    String type = source.type();
    if (type.equals("DIRECTORY")) {
      for (String name : source.names()) {
        try (Entry child = source.child(name)) {
          inspect(child, count, depth + 1);
        }
      }
    } else if (!type.equals("FILE")) throw new IOException("LINK_OR_SPECIAL_FILE");
  }

  private static void copy(
      Entry source,
      Entry target,
      boolean recursive,
      boolean noClobber,
      boolean preserve,
      int[] count,
      int depth)
      throws IOException {
    bounded(count, depth);
    String type = source.type();
    if (type.equals("DIRECTORY")) {
      if (!recursive) throw new IOException("RECURSIVE_COPY_REQUIRED");
      if (target.type().equals("MISSING")) target.directory();
      if (!target.type().equals("DIRECTORY"))
        throw new IOException("DESTINATION_DIRECTORY_REQUIRED");
      for (String name : source.names()) {
        try (Entry from = source.child(name);
            Entry to = target.child(name)) {
          copy(from, to, true, noClobber, preserve, count, depth + 1);
        }
      }
      if (preserve) source.metadataTo(target);
      return;
    }
    if (!type.equals("FILE")) throw new IOException("LINK_OR_SPECIAL_FILE");
    if (noClobber && !target.type().equals("MISSING")) return;
    try (InputStream input = source.read();
        OutputStream output = target.write()) {
      byte[] buffer = new byte[32768];
      int size;
      while ((size = input.read(buffer)) >= 0) {
        cancelled();
        if (size > 0) output.write(buffer, 0, size);
      }
      cancelled();
    }
    if (preserve) source.metadataTo(target);
  }

  private static void remove(Entry entry, boolean recursive, boolean force, int[] count, int depth)
      throws IOException {
    bounded(count, depth);
    String type = entry.type();
    if (type.equals("MISSING")) {
      if (force) return;
      throw new IOException("FILE_MISSING");
    }
    if (type.equals("DIRECTORY")) {
      if (!recursive) throw new IOException("RECURSIVE_DELETE_REQUIRED");
      for (String name : entry.names()) {
        try (Entry child = entry.child(name)) {
          remove(child, true, force, count, depth + 1);
        }
      }
    } else if (!type.equals("FILE")) throw new IOException("LINK_OR_SPECIAL_FILE");
    cancelled();
    entry.delete();
  }

  private static void bounded(int[] count, int depth) throws IOException {
    cancelled();
    if (++count[0] > 20000 || depth > 128) throw new IOException("FILE_OPERATION_LIMIT");
  }

  public static void cancelled() throws InterruptedIOException {
    if (Thread.currentThread().isInterrupted())
      throw new InterruptedIOException("FILE_OPERATION_CANCELLED");
  }

  public static boolean flag(DeviceShellPolicy.Plan plan, char flag) {
    for (int i = 1; i < plan.argv.size(); i++) {
      String value = plan.argv.get(i);
      if (value.equals("--")) break;
      if (value.startsWith("-") && value.indexOf(flag, 1) >= 0) return true;
    }
    return false;
  }

  /** 只规范化系统共享存储根；从此根以下的每一级都必须 NOFOLLOW。 */
  public static String writeRoot(String value) throws IOException {
    String path = DeviceShellPolicy.normalize(value);
    if (!DeviceShellPolicy.writeAllowed(path)) throw new IOException("PROTECTED_WRITE_PATH");
    if (path.startsWith("/data/local/tmp/")) return "/data/local/tmp";
    String[] parts = path.substring(1).split("/");
    return parts[1].equals("emulated") ? "/storage/emulated/" + parts[2] : "/storage/" + parts[1];
  }
}
