package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.R;
import com.deepseekharness.app.saf.SafGrants;
import com.deepseekharness.app.util.UiText;
import com.google.android.material.materialswitch.MaterialSwitch;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 设备能力授权 → 跨应用文件访问。
 *
 * <p>只管「授权簿」：选哪些目录、允不允许助手写、怎么撤销。真实读写走 3090 桥
 * （{@code /app/saf/*}）与 {@link SafBrowseFragment}；授权本身由系统 DocumentsUI 发放，
 * DSHA 不持 MANAGE_DOCUMENTS，也不改任何外部 APK。
 *
 * <p>列表要在后台线程查：每个授权都要问一次目标应用的 DocumentsProvider
 * （应用名 + 目录名），Provider 慢或离线时不能把主线程卡成 ANR。
 */
public final class SafAccessFragment extends Fragment {

  private LinearLayout grants;
  private TextView empty;
  private TextView limit;
  private ExecutorService executor;
  private final Handler main = new Handler(Looper.getMainLooper());
  private long renderGeneration;

  /** 明确交给系统 DocumentsUI 的选择器；默认处理程序被厂商改写时用它绕开。 */
  private final ActivityResultLauncher<Uri> picker =
      registerForActivityResult(new SystemDocumentPicker.TreeContract(), this::treeChosen);

  /** 系统默认选择器；用户明确选择继续用厂商界面时才走这里。 */
  private final ActivityResultLauncher<Uri> defaultPicker =
      registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), this::treeChosen);

  private void treeChosen(Uri uri) {
    if (uri != null) addGrant(uri);
  }

  @Nullable
  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater,
      @Nullable ViewGroup container,
      @Nullable Bundle savedInstanceState) {
    View root = inflater.inflate(R.layout.fragment_saf_access, container, false);
    PressFeedback.attachButtons(root);
    Context ctx = requireContext();
    executor = Executors.newSingleThreadExecutor();

    TextView intro = root.findViewById(R.id.saf_intro);
    // 结论（能读写选中的文件夹）在前，系统文档选择器的来历收进 ⓘ；同一段既有文案，不新增翻译键。
    intro.setText(
        UiText.choose(
            "授权后，助手可以读写你选中的那个文件夹。授权来自系统文档选择器：只有自己提供文档服务的应用（或被 MT 一类工具注入过的应用）会出现在里面，普通应用的私有目录不会出现——那是系统隔离，不是功能没生效。",
            "Once granted, the assistant can read and write the folder you picked. Grants come from the system document picker. Only apps that expose document services (or were patched by tools like MT) show up; other apps' private folders never do — that is system isolation, not a broken switch."));
    InfoHint.attach(intro, UiText.choose("跨应用文件访问", "Cross-app files"));

    ((TextView) root.findViewById(R.id.saf_grants_label))
        .setText(UiText.choose("已授权目录", "Granted folders"));

    empty = root.findViewById(R.id.saf_empty);
    empty.setText(UiText.choose("还没有授权任何目录。", "No folders granted yet."));

    limit = root.findViewById(R.id.saf_limit);
    ((Button) root.findViewById(R.id.saf_add)).setText(UiText.choose("选择文件夹", "Choose folder"));
    root.findViewById(R.id.saf_add).setOnClickListener(button -> chooseFolder());

    grants = root.findViewById(R.id.saf_grants);
    return root;
  }

  @Override
  public void onResume() {
    super.onResume();
    if (getView() != null) render(requireContext());
  }

  /** 列表查询全部放后台线程；只有最新一代的结果会落到界面上。 */
  private void render(Context ctx) {
    long generation = ++renderGeneration;
    grants.removeAllViews();
    empty.setVisibility(View.GONE);
    limit.setText(UiText.choose("正在读取授权列表…", "Loading grants…"));
    ExecutorService running = executor;
    if (running == null) return;
    running.execute(
        () -> {
          List<SafGrants.Grant> list = SafGrants.list(ctx);
          main.post(
              () -> {
                if (generation != renderGeneration || getView() == null) return;
                applyList(ctx, list);
              });
        });
  }

  private void applyList(Context ctx, List<SafGrants.Grant> list) {
    grants.removeAllViews();
    empty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
    limit.setText(
        UiText.format(
            UiText.choose(
                "最多 %d 个目录；当前 %d 个。授权只在 DSHA 内生效，系统设置里也能撤销。",
                "Up to %d folders; %d granted. Grants only apply inside DSHA and can be revoked in system settings."),
            SafGrants.MAX_TREES,
            list.size()));
    LayoutInflater inflater = LayoutInflater.from(ctx);
    for (SafGrants.Grant grant : list) grants.addView(row(ctx, inflater, grant));
  }

  private View row(Context ctx, LayoutInflater inflater, SafGrants.Grant grant) {
    View item = inflater.inflate(R.layout.item_saf_grant, grants, false);
    item.setContentDescription(grant.summary() + " " + stateText(grant));
    ((TextView) item.findViewById(R.id.saf_grant_title)).setText(grant.summary());
    TextView sub = item.findViewById(R.id.saf_grant_sub);
    sub.setText(stateText(grant));

    MaterialSwitch write = item.findViewById(R.id.saf_grant_write);
    write.setContentDescription(UiText.choose("允许助手写入", "Allow assistant writes"));
    write.setChecked(grant.writable && grant.valid);
    write.setEnabled(grant.valid && grant.canWrite);
    // 没有写权限时开关没有意义；读也拿不到的只写授权由副标题说明。
    write.setVisibility(grant.valid && grant.canWrite ? View.VISIBLE : View.GONE);
    write.setOnCheckedChangeListener(
        (button, checked) -> {
          // 开关只落偏好、改本地文案；不重新问 Provider（它可能正慢着）。
          SafGrants.setWritable(ctx, grant.uri, checked);
          sub.setText(
              checked
                  ? UiText.choose("可读写", "Read and write")
                  : UiText.choose("只读（已关闭助手写入）", "Read only (assistant writes off)"));
        });

    Button browse = item.findViewById(R.id.saf_grant_browse);
    browse.setText(UiText.choose("浏览", "Browse"));
    browse.setEnabled(grant.valid && grant.readable);
    browse.setOnClickListener(
        button ->
            SettingsPanes.open(
                this, SafBrowseFragment.forTree(grant.uri, grant.summary()), "saf-browse"));

    Button remove = item.findViewById(R.id.saf_grant_remove);
    remove.setText(UiText.choose("移除", "Remove"));
    remove.setOnClickListener(button -> confirmRemove(ctx, grant));
    return item;
  }

  private String stateText(SafGrants.Grant grant) {
    if (!grant.valid)
      return UiText.choose("已失效：目标应用可能已卸载，或授权被系统撤销", "Expired: app uninstalled or grant revoked");
    if (!grant.readable)
      return UiText.choose("只写（对方没给读权限）", "Write only (provider grants no read)");
    if (!grant.canWrite)
      return UiText.choose("只读（对方没给写权限）", "Read only (provider grants no write)");
    return grant.writable
        ? UiText.choose("可读写", "Read and write")
        : UiText.choose("只读（已关闭助手写入）", "Read only (assistant writes off)");
  }

  /**
   * 选择文件夹：优先系统 DocumentsUI。
   *
   * <p>厂商文件管理器常把 {@code ACTION_OPEN_DOCUMENT_TREE} 的默认处理程序改成自家界面，
   * 那种界面看不到 SD 卡 / U 盘等存储挂载点。检测到接管时先说清楚，再让用户选系统选择器、
   * 去应用信息清除默认设置，或明确继续用默认界面；没有系统选择器时保持原行为。
   */
  private void chooseFolder() {
    Context ctx = requireContext();
    SystemDocumentPicker.Status status = SystemDocumentPicker.inspect(ctx);
    if (!status.hasSystem()) {
      launch(defaultPicker, ctx);
      return;
    }
    if (!status.overridden()) {
      launch(picker, ctx);
      return;
    }
    new DshaDialogBuilder(ctx)
        .setTitle(UiText.choose("选择文件夹", "Choose folder"))
        .setMessage(
            UiText.format(
                UiText.choose(
                    "系统当前默认用「%s」打开文件夹选择，它可能看不到 SD 卡、U 盘等存储挂载点。建议改用系统文件选择器；也可以在它的应用信息里清除默认设置。",
                    "Your system currently opens folder selection with \"%s\", which may not show storage mounts such as SD cards or USB drives. Use the system file picker instead, or clear that app's default in its app info."),
                status.defaultName()))
        .setPositiveButton(
            UiText.choose("系统文件选择器", "System picker"), (dialog, which) -> launch(picker, ctx))
        .setNeutralButton(
            UiText.choose("更改默认应用", "Change default app"),
            (dialog, which) -> openDefaultApp(ctx, status.defaultPackage))
        .setNegativeButton(
            UiText.choose("继续用默认", "Keep default"), (dialog, which) -> launch(defaultPicker, ctx))
        .show();
  }

  private void launch(ActivityResultLauncher<Uri> launcher, Context ctx) {
    try {
      // 结果型外部选择器在途时不能结束 UI 任务（T124 严格模式守卫）。
      RecentsPrivacyGuard.launch(launcher, null);
    } catch (RuntimeException missing) {
      toast(UiText.text("没有可用的文件选择器"));
    }
  }

  /** 跳到接管者的应用信息页，用户可在「默认打开」里清除；入口不可用时退回系统设置。 */
  private void openDefaultApp(Context ctx, String packageName) {
    try {
      if (packageName != null) {
        startActivity(
            new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + packageName)));
      } else {
        startActivity(new Intent(Settings.ACTION_SETTINGS));
      }
    } catch (RuntimeException missing) {
      try {
        startActivity(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS));
      } catch (RuntimeException ignored) {
        toast(UiText.choose("无法打开系统设置", "Cannot open system settings"));
      }
    }
  }

  /**
   * 拉起系统选择器并固化授权券。
   *
   * <p>持久 URI 授权按「App + URI」记在系统里、不分功能：备份等其它功能对同一目录已有的位
   * 不会被重复申请，也不会在移除时被一起释放；只释放本功能新拿到的那部分。
   */
  private void addGrant(Uri tree) {
    Context app = requireContext().getApplicationContext();
    ExecutorService running = executor;
    if (running == null) return;
    running.execute(
        () -> {
          SafGrants.AddResult result = SafGrants.add(app, tree, true);
          main.post(
              () -> {
                if (!isAdded() || getView() == null) return;
                applyAddResult(result);
              });
        });
  }

  /** add 的 Provider 查询/磁盘写在后台线程完成，这里只更新界面。 */
  private void applyAddResult(SafGrants.AddResult result) {
    Context ctx = requireContext();
    if (!result.added) {
      switch (result.failure) {
        case "OWN_PROVIDER":
          toast(UiText.choose("不能选 DSHA 自己的目录", "DSHA's own folder is not allowed"));
          break;
        case "LIMIT":
          toast(UiText.choose("授权数量已到上限，请先移除一个", "Grant limit reached; remove one first"));
          break;
        case "WRITE_ONLY":
          toast(
              UiText.choose(
                  "该文件夹只给了写权限，请重新授权并允许读写",
                  "That folder only grants write access; grant it again and allow reading"));
          break;
        case "WRITE_ONLY_PENDING":
          toast(
              UiText.choose(
                  "该文件夹只给了写权限，且系统未释放写授权；已记录，稍后会自动重试清理",
                  "The folder only grants write access and the system did not release it; cleanup will be retried later"));
          break;
        default:
          toast(UiText.choose("没有拿到授权：请重新选择", "No permission granted: try again"));
          break;
      }
      return;
    }
    render(ctx);
    if (result.writable)
      toast(UiText.choose("已授权：助手可以读写这个目录", "Granted: assistant can read and write"));
    else if (result.readable)
      toast(UiText.choose("已授权（只读）：对方没给写权限", "Granted read-only: provider has no write"));
    else toast(UiText.choose("已授权（只写）：对方没给读权限", "Granted write-only: provider has no read"));
  }

  private static String consequence() {
    return UiText.choose(
        "移除后助手不能再读写它；只撤销本页添加的权限，其它功能对同一目录的授权会保留。",
        "The assistant loses access. Only permissions added here are released; grants held by other features stay.");
  }

  private void confirmRemove(Context ctx, SafGrants.Grant grant) {
    new DshaDialogBuilder(ctx)
        .setTitle(UiText.choose("移除这个目录？", "Remove this folder?"))
        .setMessage(grant.summary() + "\n\n" + consequence())
        .setPositiveButton(
            UiText.choose("移除", "Remove"),
            (dialog, which) -> {
              SafGrants.forget(ctx, grant.uri);
              render(ctx);
            })
        .setNegativeButton(UiText.choose("取消", "Cancel"), null)
        .show();
  }

  private void toast(String text) {
    if (!isAdded()) return;
    Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show();
  }

  @Override
  public void onDestroyView() {
    renderGeneration++;
    ExecutorService running = executor;
    executor = null;
    if (running != null) running.shutdownNow();
    grants = null;
    empty = null;
    limit = null;
    super.onDestroyView();
  }
}
