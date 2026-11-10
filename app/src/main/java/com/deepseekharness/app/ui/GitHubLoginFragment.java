package com.deepseekharness.app.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.GitHubLogin;
import com.deepseekharness.app.util.GhAuthCommands;
import com.deepseekharness.app.util.GhAuthProbe;
import com.deepseekharness.app.util.GhHostsFile;
import com.deepseekharness.app.util.GhLoginState;
import com.deepseekharness.app.util.GhManagedInstall;
import com.deepseekharness.app.util.UiText;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GitHub 登录管理原生页：显示 hosts.yml 的账号/作用域等非密元数据与受管 gh 状态，提供
 * {@code gh auth login --web} 引导、重新授权、退出与刷新。
 *
 * <p>不在页面、日志、SharedPreferences 或桥接口中保留 token；退出只删除本机记录，不撤销远端授权。
 * 状态读取与退出都在后台线程执行，页面只渲染最近一次快照。
 */
public final class GitHubLoginFragment extends Fragment {
  private static final Handler MAIN = new Handler(Looper.getMainLooper());
  private static final AtomicInteger TASKS = new AtomicInteger();

  private CardPage ui;
  private TextView statusValue;
  private TextView accountValue;
  private TextView scopesValue;
  private TextView credentialValue;
  private TextView protocolValue;
  private TextView sourceValue;
  private TextView onlineValue;
  private TextView onlineDetail;
  private TextView managedValue;
  private TextView managedVersionValue;
  private TextView managedHint;
  private android.widget.Button loginButton;
  private android.widget.Button logoutButton;
  private android.widget.Button refreshButton;
  private GitHubLogin.Snapshot snapshot;
  private GhAuthProbe.Result probe;
  private boolean probing;
  private boolean loading;
  private final AtomicInteger loadGeneration = new AtomicInteger();

  @Nullable
  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater,
      @Nullable ViewGroup container,
      @Nullable Bundle savedInstanceState) {
    ui = new CardPage(requireContext(), UiText.text("GitHub 登录"), UiText.text("管理 GitHub CLI 登录"));
    ui.label(UiText.text("当前账号"));
    LinearLayout account = ui.card();
    statusValue = kv(account, UiText.text("状态"));
    accountValue = kv(account, UiText.text("账号"));
    scopesValue = kv(account, UiText.text("作用域"));
    credentialValue = kv(account, UiText.text("凭据"));
    protocolValue = kv(account, UiText.text("Git 协议"));
    sourceValue = kv(account, UiText.text("来源"));
    onlineValue = kv(account, UiText.text("在线校验"));
    onlineDetail = ui.text("", 11, R.color.shell_text_muted);
    onlineDetail.setPadding(0, ui.dp(4), 0, ui.dp(2));
    onlineDetail.setLineSpacing(ui.dp(2), 1);
    onlineDetail.setVisibility(View.GONE);
    account.addView(onlineDetail);

    ui.label(UiText.text("受管 GitHub CLI"));
    LinearLayout managed = ui.card();
    managedValue = kv(managed, UiText.text("状态"));
    managedVersionValue = kv(managed, UiText.text("版本"));
    kv(managed, UiText.text("路径")).setText(UiText.raw(GhManagedInstall.BIN));
    managedHint = ui.text("", 12, R.color.shell_text_muted);
    managedHint.setPadding(0, ui.dp(8), 0, ui.dp(2));
    managedHint.setLineSpacing(ui.dp(2), 1);
    managedHint.setVisibility(View.GONE);
    managed.addView(managedHint);

    ui.label(UiText.text("在终端核对"));
    LinearLayout more = ui.card();
    ui.entry(
        more,
        UiText.text("在终端核对作用域"),
        UiText.text("查看登录状态与权限范围"),
        R.drawable.ic_ui_link,
        this::openStatusTerminal);

    TextView policy = ui.text(UiText.text("登录凭据存于容器，网页与插件可能读取"), 11, R.color.shell_text_muted);
    policy.setLineSpacing(ui.dp(2), 1);
    policy.setPadding(0, ui.dp(4), 0, ui.dp(8));
    // 行内只留存储位置这一句；本页不读、不写日志、网页与插件仍可读等边界收进 ⓘ。
    InfoHint.attach(policy, UiText.text("凭据"));
    ui.content.addView(policy);

    loginButton = ui.button(ui.footer, UiText.text("登录"), true, this::startLogin);
    logoutButton = ui.button(ui.footer, UiText.text("退出登录"), false, this::confirmLogout);
    logoutButton.setVisibility(View.GONE);
    refreshButton = ui.button(ui.footer, UiText.text("刷新"), false, this::reload);
    setLoading(true);
    return ui.root;
  }

  @Override
  public void onResume() {
    super.onResume();
    reload();
  }

  @Override
  public void onDestroyView() {
    loadGeneration.incrementAndGet();
    super.onDestroyView();
  }

  /** 行值可更新；标签沿用 CardPage.kv 的排版。 */
  private TextView kv(LinearLayout card, String label) {
    LinearLayout row = new LinearLayout(requireContext());
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setMinimumHeight(ui.dp(44));
    row.addView(
        ui.text(label, 12, R.color.shell_text_secondary), new LinearLayout.LayoutParams(0, -2, 1));
    TextView value = ui.text("", 13, R.color.shell_text);
    value.setMaxWidth(ui.dp(196));
    value.setGravity(Gravity.END);
    row.addView(value);
    card.addView(row);
    return value;
  }

  private void reload() {
    if (!isAdded() || getView() == null || ui == null) return;
    final int generation = loadGeneration.incrementAndGet();
    final android.content.Context app = requireContext().getApplicationContext();
    setLoading(true);
    Thread thread =
        new Thread(
            () -> {
              GitHubLogin.Snapshot loaded = GitHubLogin.load(app);
              final boolean shouldProbe =
                  loaded.environmentReady && loaded.managed == GhManagedInstall.State.AVAILABLE;
              MAIN.post(
                  () -> {
                    if (!isAdded() || getView() == null || ui == null) return;
                    if (generation != loadGeneration.get()) return;
                    snapshot = loaded;
                    probe = null;
                    probing = shouldProbe;
                    setLoading(false);
                    render();
                  });
              if (!shouldProbe) return;
              GhAuthProbe.Result checked = GitHubLogin.probe(app);
              MAIN.post(
                  () -> {
                    if (!isAdded() || getView() == null || ui == null) return;
                    if (generation != loadGeneration.get()) return;
                    probe = checked;
                    probing = false;
                    renderOnline();
                  });
            },
            "dsha-github-login-" + TASKS.incrementAndGet());
    thread.setDaemon(true);
    thread.start();
  }

  private void render() {
    GitHubLogin.Snapshot current = snapshot;
    if (current == null) return;
    GhHostsFile.Result hosts = current.hosts;
    String account = hosts.login;
    statusValue.setText(stateText(current));
    accountValue.setText(
        account != null
            ? UiText.raw(account)
            : !hosts.accounts.isEmpty()
                ? UiText.raw(String.join(", ", hosts.accounts))
                : UiText.text("未登录"));
    scopesValue.setText(scopesText(current, hosts));
    credentialValue.setText(
        hosts.credentialStored ? UiText.text("已保存（内容不显示）") : UiText.text("未保存"));
    protocolValue.setText(
        hosts.gitProtocol.isEmpty() ? UiText.text("未记录") : UiText.raw(hosts.gitProtocol));
    sourceValue.setText(
        UiText.raw(
            new java.io.File(GhManagedInstall.HOSTS).getName()
                + (current.hostsPresent ? "" : " · " + UiText.text("未写入"))));
    managedValue.setText(managedText(current.managed));
    managedVersionValue.setText(
        current.managed == GhManagedInstall.State.AVAILABLE
            ? UiText.raw("v" + GhManagedInstall.VERSION)
            : UiText.text("—"));
    managedHint.setText(UiText.text("缺失时会在首次运行自动补齐"));
    managedHint.setVisibility(
        current.managed == GhManagedInstall.State.MISSING
                || current.managed == GhManagedInstall.State.SIZE_MISMATCH
            ? View.VISIBLE
            : View.GONE);

    boolean ready = current.environmentReady;
    loginButton.setText(
        current.login == GhLoginState.Login.LOGGED_IN ? UiText.text("重新授权") : UiText.text("登录"));
    loginButton.setEnabled(ready && !loading);
    loginButton.setAlpha(ready && !loading ? 1f : .55f);
    boolean canLogout = ready && GhLoginState.canLogout(current.login) && !loading;
    logoutButton.setVisibility(GhLoginState.canLogout(current.login) ? View.VISIBLE : View.GONE);
    logoutButton.setEnabled(canLogout);
    logoutButton.setAlpha(canLogout ? 1f : .55f);
    refreshButton.setEnabled(!loading);
    renderOnline();
  }

  /** 在线校验只在环境与受管二进制都可用时执行；其余状态明确说明跳过原因。 */
  private void renderOnline() {
    GitHubLogin.Snapshot current = snapshot;
    if (current == null || onlineValue == null) return;
    String value;
    String detail = "";
    if (!current.environmentReady) {
      value = UiText.text("跳过：运行环境未就绪");
    } else if (current.managed != GhManagedInstall.State.AVAILABLE) {
      value = UiText.text("跳过：受管 gh 不可用");
    } else if (probing) {
      value = UiText.text("校验中…");
    } else if (probe == null) {
      value = UiText.text("—");
    } else {
      switch (probe.state) {
        case VALID:
          value = UiText.text("已登录");
          break;
        case NOT_LOGGED_IN:
          value = UiText.text("未登录");
          break;
        case TOKEN_INVALID:
          value = UiText.text("Token 失效");
          break;
        case TLS_CA_FAILURE:
          value = UiText.text("证书校验失败");
          break;
        case NETWORK_ERROR:
          value = UiText.text("网络不可用");
          break;
        case TIMEOUT:
          value = UiText.text("校验超时");
          break;
        case BINARY_UNAVAILABLE:
          value = UiText.text("跳过");
          break;
        case SKIPPED_ENV:
          value = UiText.text("跳过");
          break;
        default:
          value = UiText.text("无法校验");
          break;
      }
      detail = probe.detail;
    }
    onlineValue.setText(value);
    onlineDetail.setText(detail == null || detail.isEmpty() ? "" : UiText.raw(detail));
    onlineDetail.setVisibility(detail == null || detail.isEmpty() ? View.GONE : View.VISIBLE);
  }

  /** 作用域优先用在线校验结果；失败时说明未取得，而不是把 hosts.yml 缺省当成没有权限。 */
  private String scopesText(GitHubLogin.Snapshot current, GhHostsFile.Result hosts) {
    if (probe != null && probe.state == GhAuthProbe.State.VALID && !probe.scopes.isEmpty())
      return UiText.raw(String.join(", ", probe.scopes));
    if (!hosts.scopes.isEmpty()) return UiText.raw(String.join(", ", hosts.scopes));
    if (probe != null && probe.state != GhAuthProbe.State.VALID) return UiText.text("未取得");
    return UiText.text("未记录");
  }

  private String stateText(GitHubLogin.Snapshot current) {
    if (!current.environmentReady) return UiText.text("运行环境未就绪");
    if (loading) return UiText.text("正在读取…");
    switch (current.problem) {
      case HOSTS_READ_FAILED:
        return UiText.text("无法读取登录记录");
      case HOSTS_TOO_LARGE:
        return UiText.text("登录记录超过大小上限");
      case HOSTS_NOT_REGULAR:
        return UiText.text("登录记录不是普通文件");
      case HOSTS_MALFORMED:
        return UiText.text("登录记录结构无法识别");
      default:
        break;
    }
    switch (current.login) {
      case LOGGED_IN:
        return UiText.text("已登录");
      case CREDENTIAL_MISSING:
        return UiText.text("已登录");
      case ACCOUNT_UNKNOWN:
        return UiText.text("已保存（账号未知）");
      case OTHER_HOSTS_ONLY:
        return UiText.text("其他主机");
      case MALFORMED:
        return UiText.text("登录记录结构无法识别");
      default:
        return UiText.text("未登录");
    }
  }

  private String managedText(GhManagedInstall.State state) {
    switch (state) {
      case AVAILABLE:
        return UiText.text("可用");
      case MISSING:
        return UiText.text("缺失");
      case SIZE_MISMATCH:
        return UiText.text("大小与固定输入不符");
      case NOT_REGULAR:
        return UiText.text("不是普通文件");
      case UNREADABLE:
        return UiText.text("无法读取");
      default:
        return UiText.text("运行环境未就绪");
    }
  }

  private void setLoading(boolean value) {
    loading = value;
    if (ui == null) return;
    if (value) {
      if (statusValue != null) statusValue.setText(UiText.text("正在读取…"));
      if (accountValue != null) accountValue.setText("—");
      if (scopesValue != null) scopesValue.setText("—");
      if (credentialValue != null) credentialValue.setText("—");
      if (protocolValue != null) protocolValue.setText("—");
      if (sourceValue != null) sourceValue.setText("—");
      if (managedValue != null) managedValue.setText("—");
      if (managedVersionValue != null) managedVersionValue.setText("—");
      if (onlineValue != null) onlineValue.setText("—");
      if (onlineDetail != null) onlineDetail.setVisibility(View.GONE);
      if (loginButton != null) loginButton.setEnabled(false);
      if (logoutButton != null) logoutButton.setEnabled(false);
      if (refreshButton != null) refreshButton.setEnabled(false);
    } else if (refreshButton != null) {
      refreshButton.setEnabled(true);
    }
  }

  /** 已登录时同一个按钮执行重新授权（gh auth refresh），否则走网页登录。 */
  private void startLogin() {
    GitHubLogin.Snapshot current = snapshot;
    loginGuide(current != null && current.login == GhLoginState.Login.LOGGED_IN);
  }

  private void loginGuide(boolean refresh) {
    GitHubLogin.Snapshot current = snapshot;
    if (current == null) return;
    if (!current.environmentReady) {
      toast(UiText.text("环境未就绪，请先安装"));
      return;
    }
    String command = refresh ? GhAuthCommands.refresh() : GhAuthCommands.login();
    boolean tlsFailure = probe != null && probe.state == GhAuthProbe.State.TLS_CA_FAILURE;
    String note =
        refresh
            ? UiText.choose("重新授权会重新打开网页授权流程。", "Reauthorizing opens the web flow again.")
            : UiText.choose(
                "gh 默认申请作用域：repo、read:org、gist。",
                "gh requests these scopes by default: repo, read:org, gist.");
    final boolean reauthorize = refresh;
    DshaDialogBuilder builder = new DshaDialogBuilder(requireContext());
    builder.setTitle(UiText.text(reauthorize ? "重新授权 GitHub" : "登录 GitHub"));
    builder.setView(loginGuideView(command, note, tlsFailure));
    builder.setPositiveButton(
        UiText.text("打开终端"), (dialog, which) -> openTerminalForLogin(reauthorize));
    builder.setNeutralButton(
        UiText.choose("复制命令", "Copy command"),
        (dialog, which) -> copyLoginValue(UiText.choose("已复制命令", "Command copied"), command));
    builder.setNegativeButton(UiText.text("取消"), null);
    builder.show();
  }

  /** 登录引导内容：命令与授权链接都可复制（长按也能选），一键执行仍走「打开终端」。 */
  private View loginGuideView(String command, String note, boolean tlsFailure) {
    var ctx = requireContext();
    float density = getResources().getDisplayMetrics().density;
    int gap = Math.round(density * 10);
    LinearLayout root = new LinearLayout(ctx);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(gap, Math.round(density * 3), gap, 0);

    TextView lead = new TextView(ctx);
    lead.setText(
        UiText.choose(
            "两种方式任选：复制命令到任意终端执行，或直接点「打开终端」自动执行。",
            "Two ways: copy the command into any terminal, or open the built-in terminal to run it."));
    lead.setTextSize(13);
    lead.setTextColor(ctx.getColor(R.color.shell_text_secondary));
    lead.setLineSpacing(gap / 5f, 1f);
    root.addView(lead, new LinearLayout.LayoutParams(-1, -2));

    TextView commandView = new TextView(ctx);
    commandView.setText(command);
    commandView.setTextSize(13);
    commandView.setTypeface(android.graphics.Typeface.MONOSPACE);
    commandView.setTextColor(ctx.getColor(R.color.shell_text));
    commandView.setTextIsSelectable(true);
    commandView.setPadding(gap, gap, gap, gap);
    commandView.setBackgroundResource(R.drawable.bg_data_stat);
    LinearLayout.LayoutParams commandParams = new LinearLayout.LayoutParams(-1, -2);
    commandParams.topMargin = Math.round(density * 5);
    root.addView(commandView, commandParams);

    LinearLayout copyRow = new LinearLayout(ctx);
    copyRow.setOrientation(LinearLayout.HORIZONTAL);
    copyRow.setGravity(Gravity.END);
    copyRow.addView(
        textAction(
            UiText.choose("复制命令", "Copy command"),
            UiText.choose("已复制命令", "Command copied"),
            command),
        new LinearLayout.LayoutParams(-2, -2));
    LinearLayout.LayoutParams linkParams = new LinearLayout.LayoutParams(-2, -2);
    linkParams.setMarginStart(gap);
    copyRow.addView(
        textAction(
            UiText.choose("复制授权链接", "Copy link"),
            UiText.choose("已复制链接", "Link copied"),
            GhAuthCommands.BROWSER_URL),
        linkParams);
    root.addView(copyRow, new LinearLayout.LayoutParams(-1, -2));

    TextView steps = new TextView(ctx);
    steps.setText(
        UiText.choose(
            "1. 终端显示一次性验证码。\n2. 用手机浏览器打开下方授权链接，输入验证码并授权 GitHub CLI。\n3. 回到本页，状态会自动刷新。",
            "1. The terminal shows a one-time code.\n2. Open the authorization link below in your phone browser, enter the code and approve GitHub CLI.\n3. Come back here; the status refreshes automatically."));
    steps.setTextSize(13);
    steps.setTextColor(ctx.getColor(R.color.shell_text_secondary));
    steps.setLineSpacing(gap / 5f, 1f);
    steps.setTextIsSelectable(true);
    root.addView(steps, new LinearLayout.LayoutParams(-1, -2));

    TextView authLink = new TextView(ctx);
    authLink.setText(GhAuthCommands.BROWSER_URL);
    authLink.setTextSize(13);
    authLink.setTypeface(android.graphics.Typeface.MONOSPACE);
    authLink.setTextColor(ctx.getColor(R.color.shell_accent));
    authLink.setTextIsSelectable(true);
    authLink.setPadding(0, Math.round(density * 3), 0, 0);
    root.addView(authLink, new LinearLayout.LayoutParams(-1, -2));

    TextView notes = new TextView(ctx);
    notes.setText(note);
    notes.setTextSize(12);
    notes.setTextColor(ctx.getColor(R.color.shell_text_muted));
    notes.setLineSpacing(gap / 5f, 1f);
    notes.setTextIsSelectable(true);
    notes.setPadding(0, Math.round(density * 5), 0, 0);
    root.addView(notes, new LinearLayout.LayoutParams(-1, -2));

    if (tlsFailure) {
      TextView warning = new TextView(ctx);
      warning.setText(
          UiText.choose(
              "当前 gh 无法完成 TLS 校验（CA 路径失效），终端会显示真实错误；修复运行环境后再试。",
              "gh cannot verify TLS right now (CA path is broken); the terminal will show the real error. Repair the runtime and retry."));
      warning.setTextSize(12);
      warning.setTextColor(ctx.getColor(R.color.shell_warn));
      warning.setLineSpacing(gap / 5f, 1f);
      warning.setTextIsSelectable(true);
      warning.setPadding(0, Math.round(density * 3), 0, 0);
      root.addView(warning, new LinearLayout.LayoutParams(-1, -2));
    }
    return root;
  }

  private TextView textAction(CharSequence label, String toastMessage, String value) {
    var ctx = requireContext();
    float density = getResources().getDisplayMetrics().density;
    TextView action = new TextView(ctx);
    action.setText(label);
    action.setTextSize(13);
    action.setTextColor(ctx.getColor(R.color.shell_accent));
    action.setGravity(Gravity.CENTER);
    action.setPadding(0, Math.round(density * 6), 0, Math.round(density * 6));
    action.setBackgroundResource(R.drawable.bg_action_plain);
    action.setOnClickListener(view -> copyLoginValue(toastMessage, value));
    return action;
  }

  private void copyLoginValue(String toastMessage, String value) {
    var clipboard =
        (android.content.ClipboardManager)
            requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
    if (clipboard == null) {
      toast(UiText.choose("剪贴板不可用", "Clipboard unavailable"));
      return;
    }
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("DSHA", value));
    toast(toastMessage);
  }

  private void openTerminalForLogin(boolean refresh) {
    String[] argv = refresh ? GhAuthCommands.refreshGuestArgv() : GhAuthCommands.loginGuestArgv();
    String title = UiText.text(refresh ? "GitHub 授权" : "GitHub 登录");
    openTerminal(argv, title);
  }

  private void openStatusTerminal() {
    GitHubLogin.Snapshot current = snapshot;
    if (current == null || !current.environmentReady) {
      toast(UiText.text("环境未就绪，请先安装"));
      return;
    }
    openTerminal(GhAuthCommands.statusGuestArgv(), UiText.text("GitHub 状态"));
  }

  private void openTerminal(String[] argv, String title) {
    if (!(getActivity() instanceof MainActivity main)) {
      toast(UiText.text("无法打开终端页面"));
      return;
    }
    main.openTerminalWithCommand(argv, title);
  }

  private void confirmLogout() {
    GitHubLogin.Snapshot current = snapshot;
    if (current == null || !GhLoginState.canLogout(current.login)) return;
    if (!current.environmentReady) {
      toast(UiText.text("环境未就绪，请先安装"));
      return;
    }
    String message =
        current.hosts.login != null
            ? UiText.format(
                "将从本机 hosts.yml 删除账号 %s 的登录。只影响本机，不会撤销 GitHub 上的授权；如需撤销，请到 GitHub 设置中操作。",
                current.hosts.login)
            : UiText.text("删除本机登录记录；不影响 GitHub 授权");
    new DshaDialogBuilder(requireContext())
        .setTitle(UiText.text("退出 GitHub 登录？"))
        .setMessage(message)
        .setPositiveButton(UiText.text("退出"), (dialog, which) -> logout(current.hosts.login))
        .setNegativeButton(UiText.text("取消"), null)
        .show();
  }

  private void logout(String account) {
    if (loading) return;
    setLoading(true);
    final android.content.Context app = requireContext().getApplicationContext();
    Thread thread =
        new Thread(
            () -> {
              GitHubLogin.LogoutResult result = GitHubLogin.logout(app, account);
              MAIN.post(
                  () -> {
                    if (!isAdded() || getView() == null) return;
                    setLoading(false);
                    if (result.ok) {
                      toast(UiText.text("已退出 GitHub 登录（仅本机）"));
                    } else {
                      showLogoutFailure(result);
                    }
                    reload();
                  });
            },
            "dsha-github-logout-" + TASKS.incrementAndGet());
    thread.setDaemon(true);
    thread.start();
  }

  private void showLogoutFailure(GitHubLogin.LogoutResult result) {
    String message =
        result.problem == GitHubLogin.Problem.ENV_NOT_READY
            ? UiText.text("环境未就绪，请先安装")
            : result.detail == null || result.detail.isEmpty()
                ? UiText.text("退出失败")
                : UiText.format("退出失败：%s", result.detail);
    new DshaDialogBuilder(requireContext())
        .setTitle(UiText.text("退出失败"))
        .setMessage(message)
        .setPositiveButton(android.R.string.ok, null)
        .show();
  }

  private void toast(String message) {
    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
  }
}
