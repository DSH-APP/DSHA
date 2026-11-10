package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Flash preflight decisions. A missing safety fact is a refusal, never an approval. */
public final class ExternalPreflight {
  public static final class Facts {
    public final String serial;
    public final String product;
    public final boolean usb;
    public final boolean fastbootd;
    public final boolean unlocked;
    public final boolean unlockedKnown;
    public final int battery;
    public final long currentRollback;
    public final long targetRollback;
    public final long partitionSize;

    public Facts(
        String serial,
        String product,
        boolean usb,
        boolean fastbootd,
        boolean unlocked,
        boolean unlockedKnown,
        int battery,
        long currentRollback,
        long targetRollback,
        long partitionSize) {
      this.serial = serial == null ? "" : serial;
      this.product = product == null ? "" : product;
      this.usb = usb;
      this.fastbootd = fastbootd;
      this.unlocked = unlocked;
      this.unlockedKnown = unlockedKnown;
      this.battery = battery;
      this.currentRollback = currentRollback;
      this.targetRollback = targetRollback;
      this.partitionSize = partitionSize;
    }
  }

  public static final class Result {
    public final boolean allowed;
    public final List<String> reasons;

    private Result(List<String> reasons) {
      this.allowed = reasons.isEmpty();
      this.reasons = Collections.unmodifiableList(new ArrayList<>(reasons));
    }

    public String message() {
      return String.join("；", reasons);
    }
  }

  private ExternalPreflight() {}

  public static Result check(String boundSerial, String partition, long imageSize, Facts facts) {
    List<String> reasons = new ArrayList<>();
    if (facts == null) {
      reasons.add("无法读取 fastboot 真实状态");
      return new Result(reasons);
    }
    if (!ExternalDevicePolicy.isUsbSerial(boundSerial) || !facts.usb) reasons.add("目标必须是 USB 直连设备");
    if (boundSerial == null || !boundSerial.equals(facts.serial)) reasons.add("目标序列号已漂移，拒绝刷写");
    if (facts.battery >= 0 && facts.battery < 30) reasons.add("目标电量低于 30%");
    if (facts.battery < 0) reasons.add("无法读取目标电量");
    if (!facts.unlockedKnown) reasons.add("无法从 bootloader 证明 Bootloader 已解锁");
    else if (!facts.unlocked) reasons.add("Bootloader 未解锁");
    if (facts.partitionSize >= 0 && imageSize > facts.partitionSize) reasons.add("镜像大于目标分区");
    if (facts.partitionSize < 0) reasons.add("无法读取目标分区大小");
    if (facts.currentRollback >= 0 && facts.targetRollback < 0)
      reasons.add("无法证明镜像 rollback index 不低于当前值");
    else if (facts.currentRollback >= 0
        && facts.targetRollback >= 0
        && facts.targetRollback < facts.currentRollback)
      reasons.add("镜像 rollback index 低于当前值，拒绝防回滚降级");
    if (isDynamic(partition) && !facts.fastbootd) reasons.add("动态分区必须在 fastbootd 中刷写");
    if (partition == null || !ExternalDevicePolicy.supportedFlashPartition(partition))
      reasons.add("刷写分区不在受管白名单");
    if (imageSize <= 0) reasons.add("镜像为空或大小未知");
    return new Result(reasons);
  }

  private static boolean isDynamic(String partition) {
    return partition != null
        && Set.of("system", "vendor", "product", "odm", "super", "vbmeta_system", "vbmeta_vendor")
            .contains(partition);
  }
}
