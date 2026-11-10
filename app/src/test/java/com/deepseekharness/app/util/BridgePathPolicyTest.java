package com.deepseekharness.app.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 桥路径策略的回归用例。
 *
 * <p>这张表是「agent 能不能把凭据递出去」的唯一闸门，漏一个名字就等于整条链路失守。
 * 下面每一条都对应一个真实存在于容器里的明文令牌文件。
 */
public class BridgePathPolicyTest {

  @Test
  public void deniesEveryCredentialThatCarriesAPlaintextToken() {
    String[] denied = {
      "/root/.dsh/.bridge_token",
      "/root/.dsh/.credentials.yaml",
      "/root/.ssh/id_ed25519",
      "/root/.ssh/authorized_keys",
      "/root/.aws/credentials",
      "/root/.config/gcloud/application_default_credentials.json",
      "/root/.kube/config",
      "/root/.config/gh/hosts.yml", // gh auth login 的 token
      "/root/.docker/config.json", // registry 凭据
      "/root/.netrc",
      "/root/.pypirc",
      "/root/.gitconfig",
      "/root/.git-credentials",
      "/root/.npmrc",
      "/root/.yarnrc.yml",
      "/root/.cargo/credentials.toml",
      "/root/report/.netrc", // 名字判据在任何层级都生效
      "/root/id_rsa",
    };
    for (String path : denied) {
      assertTrue("必须拒绝: " + path, BridgePathPolicy.denied(path));
    }
  }

  @Test
  public void stillAllowsOrdinaryUserArtifacts() {
    String[] allowed = {
      "/root/report.md",
      "/root/work/output.json",
      "/sdcard/我的项目/说明.txt",
      "/sdcard/Download/DSHA/diagnostics.tar.gz",
    };
    for (String path : allowed) {
      assertFalse("不该拒绝: " + path, BridgePathPolicy.denied(path));
    }
  }

  @Test
  public void rejectsRelativeAndTrickyPaths() {
    String[] denied = {
      "",
      "relative/path.txt",
      "../.dsh/.bridge_token",
      "/root/./.dsh/.bridge_token",
      "/root//.dsh//.bridge_token",
      "\\root\\.dsh\\.bridge_token",
    };
    for (String path : denied) {
      assertTrue("必须拒绝: " + path, BridgePathPolicy.denied(path));
    }
  }

  @Test
  public void hostViewStillRejectsAppPrivateDataOutsideRootfs() {
    assertTrue(
        BridgePathPolicy.deniedHostPath(
            "/data/data/com.other.app/files/x",
            "/data/data/io.github.sunsetrne.dsha/files/linux/ubuntu"));
    // rootfs 内部交给 guest 视角判据，不因位于 /data/data 下一律拒绝
    assertFalse(
        BridgePathPolicy.deniedHostPath(
            "/data/data/io.github.sunsetrne.dsha/files/linux/ubuntu/root/report.md",
            "/data/data/io.github.sunsetrne.dsha/files/linux/ubuntu"));
  }
}
