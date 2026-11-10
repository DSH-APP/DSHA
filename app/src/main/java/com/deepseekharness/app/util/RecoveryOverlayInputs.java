package com.deepseekharness.app.util;

import java.io.IOException;
import java.util.Arrays;

/** 独立应急覆盖层的签名输入名单；与 tools/recovery_runtime_overlay.py 的 input_assets() 同源。 */
public final class RecoveryOverlayInputs {
  private static final String[] FIXED = {
    "web-integration/es-compat.js", "web-integration/startup.js", "web-integration/language.js"
  };
  private static final String[] RECIPES = {"pdf-compat-patch.json", "language-patch.json"};

  private RecoveryOverlayInputs() {}

  /** recipe 只取描述里独立锁定的版本目录，绝不回退到正式运行时的顶层 recipe。 */
  public static String[] forVersion(String dshVersion) throws IOException {
    if (dshVersion == null || !dshVersion.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[a-zA-Z0-9.-]+)?"))
      throw new IOException("RECOVERY_RECIPE_VERSION");
    String[] names = Arrays.copyOf(FIXED, FIXED.length + RECIPES.length);
    for (int i = 0; i < RECIPES.length; i++)
      names[FIXED.length + i] = "recovery-recipes/" + dshVersion + "/" + RECIPES[i];
    return names;
  }

  /** language.js 内含十语种词典，上限 8 MiB；recipe 与其余脚本 1 MiB。 */
  public static int limit(String name) {
    return "web-integration/language.js".equals(name) ? 8 * 1024 * 1024 : 1024 * 1024;
  }
}
