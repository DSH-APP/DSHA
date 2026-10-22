import com.deepseekharness.app.backup.BackupControl;
import com.deepseekharness.app.backup.JvmBackupFileSystem;
import com.deepseekharness.app.skills.SkillImports;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 正式 host 入口的有界真实导入夹具；无需 JUnit、Android 类或旧 build158 产物。 */
public final class SkillImportFixture {
  public static void main(String[] arguments) throws Exception {
    if (arguments.length != 1) throw new IllegalArgumentException("SKILL_FIXTURE_ARGUMENT");
    Path files = Path.of(arguments[0]).toAbsolutePath();
    Files.createDirectories(files.resolve("user-data-v5/dsh"));
    SkillImports imports = new SkillImports(new JvmBackupFileSystem(), files.toFile());
    byte[] regular =
        ("---\nname: hello-world\ndescription: |\n  A real imported skill\n  用于验证实际发现\n---\n# Body\nDo not run: touch should-never-exist\n")
            .getBytes(StandardCharsets.UTF_8);
    byte[] explicit =
        ("---\nname: user-only\ndescription: Explicit invocation only\ndisable-model-invocation: true\nuser-invocable: 1\n---\nUser body\n")
            .getBytes(StandardCharsets.UTF_8);
    imports.install("SKILL.md", regular, new BackupControl(null));
    imports.install("SKILL.md", explicit, new BackupControl(null));
    if (imports.list(new BackupControl(null)).entries.size() != 2)
      throw new IllegalStateException("SKILL_FIXTURE_IMPORT_COUNT");
    if (!java.util.Arrays.equals(regular,
        Files.readAllBytes(files.resolve("user-data-v5/dsh/skills/hello-world/SKILL.md"))))
      throw new IllegalStateException("SKILL_FIXTURE_IMPORT_BYTES");
    System.out.println("PASS_JVM_SKILL_IMPORT");
  }
}
