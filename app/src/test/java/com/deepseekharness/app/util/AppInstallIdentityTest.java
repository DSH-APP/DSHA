package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class AppInstallIdentityTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private String load(File directory) throws IOException {
    return AppInstallIdentity.loadOrCreate(
        new com.deepseekharness.app.backup.JvmBackupFileSystem(), directory);
  }

  @Test
  public void guardedNioAndApi23StorageKeepTheSameInstallation() throws Exception {
    File root = temporary.newFolder();
    String identity = com.deepseekharness.app.core.NioInstallationIdentity.loadOrCreate(root);
    assertEquals(identity, load(root));
    assertEquals(identity, com.deepseekharness.app.core.NioInstallationIdentity.loadOrCreate(root));
  }

  @Test
  public void journalIdentityCreatesAndReopensWhenFileStoreQueriesAreDenied() throws Exception {
    File root = temporary.newFolder();
    var locations = new java.util.LinkedHashSet<String>();
    for (Class<?> owner :
        new Class<?>[] {
          AppInstallIdentityTest.class,
          com.deepseekharness.app.core.NioInstallationIdentity.class,
          AppInstallIdentity.class
        })
      locations.add(
          new File(owner.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath());
    Process process =
        new ProcessBuilder(
                new File(System.getProperty("java.home"), "bin/java").getPath(),
                "-Djava.security.manager=allow",
                "-cp",
                String.join(File.pathSeparator, locations),
                FileStoreDeniedProbe.class.getName(),
                root.getPath())
            .redirectErrorStream(true)
            .start();
    try {
      assertTrue(
          "Isolated identity probe timed out",
          process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS));
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertEquals(output, 0, process.exitValue());
      assertTrue(output, output.contains("PASS_IDENTITY_WITHOUT_FILESTORE"));
      assertEquals(
          load(root), com.deepseekharness.app.core.NioInstallationIdentity.loadOrCreate(root));
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
  }

  /** Reproduce Android's denied FileStore discovery without changing the JUnit process policy. */
  public static final class FileStoreDeniedProbe {
    public static void main(String[] args) throws Exception {
      System.setSecurityManager(
          new SecurityManager() {
            @Override
            public void checkPermission(java.security.Permission permission) {
              if (permission instanceof RuntimePermission
                  && permission.getName().equals("getFileStoreAttributes"))
                throw new SecurityException("FILESTORE_QUERY_DENIED");
            }
          });
      File root = new File(args[0]);
      String first = com.deepseekharness.app.core.NioInstallationIdentity.loadOrCreate(root);
      String reopened = com.deepseekharness.app.core.NioInstallationIdentity.loadOrCreate(root);
      if (!first.equals(reopened)) throw new AssertionError("Installation identity changed");
      System.out.println("PASS_IDENTITY_WITHOUT_FILESTORE");
    }
  }

  @Test
  public void privatePermissionsAndLinksAreRequiredBeforeRead() throws Exception {
    File root = temporary.newFolder();
    File record = new File(root, AppInstallIdentity.FILE_NAME);
    byte[] bytes = AppInstallIdentity.encode(UUID.randomUUID().toString());
    Files.write(record.toPath(), bytes);
    for (String type : new String[] {"FILE", "LINK"}) {
      var fs =
          new com.deepseekharness.app.backup.JvmBackupFileSystem() {
            @Override
            public Node stat(File file) throws IOException {
              Node node = super.stat(file);
              return file.equals(record)
                  ? new Node(type, node.key, node.size, node.modified, node.device, 0644)
                  : node;
            }

            @Override
            public java.io.InputStream read(File file, Node expected) {
              throw new AssertionError("Untrusted record was opened");
            }
          };
      assertThrows(IOException.class, () -> AppInstallIdentity.loadOrCreate(fs, root));
      assertArrayEquals(bytes, Files.readAllBytes(record.toPath()));
    }
  }

  @Test
  public void failedExclusiveCreateIsNotReinterpretedAsAnEmptyInstallation() throws Exception {
    File root = temporary.newFolder();
    var fs =
        new com.deepseekharness.app.backup.JvmBackupFileSystem() {
          @Override
          public java.io.OutputStream create(File file) throws IOException {
            throw new IOException("PERMISSION_DENIED");
          }
        };
    IOException failure =
        assertThrows(IOException.class, () -> AppInstallIdentity.loadOrCreate(fs, root));
    assertEquals("PERMISSION_DENIED", failure.getMessage());
    assertFalse(new File(root, AppInstallIdentity.FILE_NAME).exists());
  }

  @Test
  public void directoryReplacementAfterReadInvalidatesIdentity() throws Exception {
    File root = temporary.newFolder();
    load(root);
    var fs =
        new com.deepseekharness.app.backup.JvmBackupFileSystem() {
          boolean read;

          @Override
          public byte[] small(File file, int limit) throws IOException {
            byte[] value = super.small(file, limit);
            read = true;
            return value;
          }

          @Override
          public Node stat(File file) throws IOException {
            Node node = super.stat(file);
            return read && file.equals(root)
                ? new Node(
                    node.type,
                    node.key + "-replaced",
                    node.size,
                    node.modified,
                    node.device,
                    node.mode)
                : node;
          }
        };
    assertThrows(IOException.class, () -> AppInstallIdentity.loadOrCreate(fs, root));
  }

  @Test
  public void persistedIdentitySurvivesIndependentCalls() throws Exception {
    File files = temporary.newFolder();
    String first = load(files);
    assertEquals(first, UUID.fromString(first).toString());
    assertEquals(first, load(files));
  }

  @Test
  public void differentInstallRootsHaveDifferentIdentity() throws Exception {
    assertNotEquals(load(temporary.newFolder()), load(temporary.newFolder()));
  }

  @Test
  public void partialRecordIsPreservedAndRejected() throws Exception {
    File files = temporary.newFolder();
    File record = new File(files, AppInstallIdentity.FILE_NAME);
    byte[] original = "DSHA-INSTALLATION/1\n".getBytes(StandardCharsets.UTF_8);
    Files.write(record.toPath(), original);
    try {
      load(files);
      fail("Partial identity must not be replaced");
    } catch (IOException expected) {
      assertArrayEquals(original, Files.readAllBytes(record.toPath()));
    }
  }

  @Test
  public void foreignPackageIdentityIsNotAdopted() throws Exception {
    File files = temporary.newFolder();
    File record = new File(files, AppInstallIdentity.FILE_NAME);
    byte[] original =
        ("DSHA-INSTALLATION/1\ncom.dsh.client.experimental\n" + UUID.randomUUID() + "\n")
            .getBytes(StandardCharsets.UTF_8);
    Files.write(record.toPath(), original);
    try {
      load(files);
      fail("A record from another package is not this production installation");
    } catch (IOException expected) {
      assertArrayEquals(original, Files.readAllBytes(record.toPath()));
    }
  }
}
