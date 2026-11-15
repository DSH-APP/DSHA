package com.deepseekharness.app.util;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 敏感界面分类器（T119，纯逻辑）。
 *
 * <p>输入是宿主无障碍树里**已经裁剪过**的只读字段（文本/描述/提示/资源 id/类名/是否密码框），输出只有类别与
 * 稳定依据码，不返回任何界面文本。分类是保守提示，不是安全证明：自定义控件、Canvas、语言/ROM 差异都可能漏判；
 * 漏判时回退到原有的按应用确认，而不是宣称安全。
 *
 * <p>类别优先级：PASSWORD &gt; OTP &gt; PAYMENT &gt; SYSTEM_SECURITY。密码框存在即整屏按密码界面处理；
 * 验证码关键词优先于支付关键词（支付页的短信验证码也属于验证码）；仅金融类应用或支付关键词才判支付；
 * 系统安全只在设置/权限/系统界面出现对应页面关键词时判。
 */
public final class SensitiveScreenClassifier {
  private SensitiveScreenClassifier() {}

  /** 单个节点的只读样本；构造时裁剪长度，避免把整页文本带进策略层。 */
  public static final class Node {
    public final String text, description, hint, viewId, className;
    public final boolean password, editable;
    public final int inputType;

    public Node(
        CharSequence text,
        CharSequence description,
        CharSequence hint,
        String viewId,
        String className,
        boolean password,
        boolean editable,
        int inputType) {
      this.text = clip(password ? "" : string(text));
      this.description = clip(password ? "" : string(description));
      this.hint = clip(password ? "" : string(hint));
      this.viewId = clip(viewId);
      this.className = clip(className);
      this.password = password;
      this.editable = editable;
      this.inputType = inputType;
    }

    private static String string(CharSequence value) {
      return value == null ? "" : value.toString();
    }

    private static String clip(String value) {
      if (value == null || value.isEmpty()) return "";
      return value.length() > 160 ? value.substring(0, 160) : value;
    }
  }

  /** 分类结果；basis 是只读依据码，可安全放进回执与诊断。 */
  public static final class Result {
    public final SensitiveScreenPolicy.Category category;
    public final String basis;

    Result(SensitiveScreenPolicy.Category category, String basis) {
      this.category = category;
      this.basis = basis;
    }
  }

  private static final String[] OTP_KEYS = {
    "验证码",
    "动态码",
    "校验码",
    "短信码",
    "一次性密码",
    "动态口令",
    "otp",
    "one-time password",
    "one time password",
    "verification code",
    "verify code",
    "security code",
    "sms code",
    "auth code"
  };

  private static final String[] PAYMENT_TEXT_KEYS = {
    "支付",
    "付款",
    "收银台",
    "转账",
    "金额",
    "余额",
    "账单",
    "付款码",
    "收款码",
    "确认支付",
    "立即支付",
    "免密支付",
    "payment",
    "pay now",
    "checkout",
    "transfer",
    "amount",
    "balance",
    "billing",
    "qr pay",
    "scan to pay"
  };

  private static final String[] SYSTEM_SECURITY_KEYS = {
    "无障碍",
    "辅助功能",
    "开发者选项",
    "开发者模式",
    "usb 调试",
    "usb调试",
    "设备管理",
    "应用管理",
    "权限管理",
    "账户",
    "账号",
    "锁屏",
    "屏幕锁定",
    "恢复出厂",
    "安装未知应用",
    "安全",
    "设备安全",
    "accessibility",
    "developer options",
    "developer mode",
    "usb debugging",
    "device admin",
    "app management",
    "permission",
    "account",
    "lock screen",
    "factory reset",
    "unknown apps",
    "security"
  };

  private static final String[] FINANCE_PACKAGE_KEYS = {
    "alipay", "unionpay", "paypal", "jdpay", "wallet", "bank", "icbc", "ccb", "abchina",
    "bankofchina", "cmbchina", "bankcomm", "psbc", "cebbank", "cmbc", "spdb", "citic", "hxb",
    "payment", "securities", "broker", "trading", "stock"
  };

  private static final Set<String> SYSTEM_PACKAGES =
      Set.of("com.android.settings", "com.android.permissioncontroller", "com.android.systemui");

  /** 分类；没有命中返回 null（交给原有确认逻辑，绝不回退成「安全」）。 */
  public static Result classify(String packageName, List<Node> nodes) {
    if (nodes == null || nodes.isEmpty()) return null;
    for (Node node : nodes)
      if (node.password)
        return new Result(SensitiveScreenPolicy.Category.PASSWORD, "PASSWORD_FIELD");
    for (Node node : nodes)
      if (node.editable
          && (matches(node.text, OTP_KEYS)
              || matches(node.description, OTP_KEYS)
              || matches(node.hint, OTP_KEYS)
              || matches(node.viewId, OTP_KEYS)))
        return new Result(SensitiveScreenPolicy.Category.OTP, "OTP_KEYWORD");
    String pkg = packageName == null ? "" : packageName.toLowerCase(Locale.ROOT);
    if (financeApp(pkg)) return new Result(SensitiveScreenPolicy.Category.PAYMENT, "PAYMENT_APP");
    for (Node node : nodes)
      if (matches(node.text, PAYMENT_TEXT_KEYS)
          || matches(node.description, PAYMENT_TEXT_KEYS)
          || matches(node.viewId, PAYMENT_TEXT_KEYS))
        return new Result(SensitiveScreenPolicy.Category.PAYMENT, "PAYMENT_TEXT");
    if (systemPackage(pkg))
      for (Node node : nodes)
        if (matches(node.text, SYSTEM_SECURITY_KEYS)
            || matches(node.description, SYSTEM_SECURITY_KEYS)
            || matches(node.viewId, SYSTEM_SECURITY_KEYS))
          return new Result(SensitiveScreenPolicy.Category.SYSTEM_SECURITY, "SYSTEM_SECURITY_PAGE");
    return null;
  }

  /**
   * 已知敏感应用但树读不出来：不能判「安全」，按无法识别的敏感界面交给策略（默认 ASK）。
   * 包名为空表示拿不到前台身份（例如虚拟屏还是空屏），不在这里制造一次无目标的询问；
   * 原有按应用确认与空屏处理会先给出「先启动应用」等明确结果。
   */
  public static Result unreadableSensitiveApp(String packageName) {
    if (packageName == null || packageName.isEmpty()) return null;
    if (!SensitiveAppPolicy.sensitive(packageName)) return null;
    return new Result(SensitiveScreenPolicy.Category.SECURE_UNKNOWN, "UNREADABLE_SENSITIVE_APP");
  }

  private static boolean financeApp(String pkg) {
    if (pkg.isEmpty()) return false;
    // 微信/QQ 等通用应用只有出现支付关键词才按支付页处理，不能整包误判。
    if (pkg.equals("com.tencent.mm") || pkg.equals("com.tencent.mobileqq")) return false;
    for (String key : FINANCE_PACKAGE_KEYS) if (pkg.contains(key)) return true;
    return false;
  }

  private static boolean systemPackage(String pkg) {
    if (pkg.isEmpty()) return false;
    if (SYSTEM_PACKAGES.contains(pkg)) return true;
    return pkg.contains("securitycenter") || pkg.contains("safecenter");
  }

  private static boolean matches(String value, String[] keys) {
    if (value == null || value.isEmpty()) return false;
    for (String key : keys) if (value.contains(key)) return true;
    return false;
  }
}
