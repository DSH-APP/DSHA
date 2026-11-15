package com.deepseekharness.app.ui;

import android.content.Context;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import com.deepseekharness.app.transfer.DownloadTask;
import com.deepseekharness.app.util.TransferListModel;
import com.deepseekharness.app.util.TransferListModel.Failure;
import com.deepseekharness.app.util.UiText;
import java.time.LocalDate;
import java.time.ZoneId;

/** 下载记录页文案与格式；只组合完整句或带参数模板，不拼接已翻译的片段。 */
final class TransferText {
  private TransferText() {}

  /** 状态色调，对应 shell_accent / warn / bad / good / text_muted。 */
  enum Tone {
    ACCENT,
    WARN,
    BAD,
    GOOD,
    MUTED
  }

  static String t(String zh, String en) {
    return UiText.choose(zh, en);
  }

  static String f(String zh, String en, Object... values) {
    return String.format(java.util.Locale.ROOT, t(zh, en), values);
  }

  /** 「已下载 / 总量」不含可翻译文字。 */
  static String pair(String done, String total) {
    return done + " / " + total;
  }

  /** 元数据分隔符不含可翻译文字。 */
  static String join(String first, String second) {
    return first + " · " + second;
  }

  static String name(DownloadTask task) {
    return task.displayName == null || task.displayName.isEmpty()
        ? task.artifact.name
        : task.displayName;
  }

  static Tone tone(DownloadTask.Status status) {
    return switch (status) {
      case PAUSED -> Tone.WARN;
      case FAILED -> Tone.BAD;
      case COMPLETE -> Tone.GOOD;
      case CANCELLED -> Tone.MUTED;
      default -> Tone.ACCENT;
    };
  }

  /** 状态标签（详情页文件头）。 */
  static String status(DownloadTask.Status status) {
    return switch (status) {
      case QUEUED -> t("排队中", "Queued");
      case DOWNLOADING -> t("正在下载", "Downloading");
      case VERIFYING -> t("正在校验", "Verifying");
      case PUBLISHING -> t("正在保存", "Saving");
      case WAITING_DESTINATION -> t("待选择保存位置", "Choose a destination");
      case PAUSED -> t("已暂停", "Paused");
      case FAILED -> t("失败", "Failed");
      case COMPLETE -> t("已完成", "Complete");
      case CANCELLED -> t("已取消", "Cancelled");
    };
  }

  static String size(Context context, long bytes) {
    return bytes < 0 ? t("大小未知", "Unknown size") : Formatter.formatFileSize(context, bytes);
  }

  static String clock(Context context, long millis) {
    return DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_TIME);
  }

  static String fullTime(Context context, long millis) {
    return DateUtils.formatDateTime(
        context,
        millis,
        DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR | DateUtils.FORMAT_SHOW_TIME);
  }

  /** 分组标题：今天 / 昨天 / 本地日期。 */
  static String day(Context context, long epochDay, ZoneId zone) {
    long today = LocalDate.now(zone).toEpochDay();
    if (epochDay == today) return t("今天", "Today");
    if (epochDay == today - 1) return t("昨天", "Yesterday");
    long millis = LocalDate.ofEpochDay(epochDay).atStartOfDay(zone).toInstant().toEpochMilli();
    int flags = DateUtils.FORMAT_SHOW_DATE;
    if (LocalDate.ofEpochDay(epochDay).getYear() != LocalDate.now(zone).getYear())
      flags |= DateUtils.FORMAT_SHOW_YEAR;
    return DateUtils.formatDateTime(context, millis, flags);
  }

  static String eta(long seconds) {
    if (seconds < 0) return "";
    if (seconds < 60) return f("剩余约 %d 秒", "About %d s left", seconds);
    if (seconds < 3600)
      return f("剩余约 %d 分钟", "About %d min left", Math.max(1, Math.round(seconds / 60.0)));
    return f("剩余约 %d 小时", "About %d h left", Math.max(1, Math.round(seconds / 3600.0)));
  }

  static String location(DownloadTask task) {
    if ("所选文档".equals(task.location)) return t("所选文档", "Selected document");
    if ("应用分享缓存".equals(task.location)) return t("应用分享缓存", "App share storage");
    if (task.location != null && !task.location.isEmpty()) return task.location;
    return switch (task.destination) {
      case "downloads" -> "Download/DSHA";
      case "gallery" -> "Pictures/DSHA";
      case "share" -> t("应用分享缓存", "App share storage");
      default -> t("等待选择文档位置", "Waiting for a document destination");
    };
  }

  static boolean destinationPending(DownloadTask task) {
    return "saveas".equals(task.destination)
        && (task.location == null || task.location.isEmpty())
        && task.status != DownloadTask.Status.COMPLETE;
  }

  static String locationNote(DownloadTask task) {
    if (destinationPending(task))
      return t("尚未选择，选好后才会写入", "Not chosen yet. Nothing is written until you choose.");
    if ("share".equals(task.destination))
      return t("应用私有缓存，只用于这次分享", "Private app storage used only for this share");
    if ("gallery".equals(task.destination)) return t("系统相册可见", "Visible in the system gallery");
    if ("saveas".equals(task.destination))
      return t("通过系统保存界面选择的位置", "Chosen in the system save screen");
    return t("系统「下载」目录中的 DSHA 文件夹", "DSHA folder in the system Downloads directory");
  }

  /** 失败卡标题；完整说明见 {@link #failureBody}。 */
  static String failureTitle(Failure failure) {
    return switch (failure) {
      case CANCELLED -> t("已取消", "Cancelled");
      case NO_SPACE -> t("存储空间不足", "Not enough storage");
      case PERMISSION -> t("无法访问保存位置", "Cannot access the destination");
      case INTEGRITY -> t("完整性校验未通过", "Integrity check failed");
      case RUNTIME -> t("运行环境未就绪", "Runtime not ready");
      case UNAUTHORIZED -> t("文件访问未获授权", "File access not authorized");
      case SOURCE_GONE -> t("源文件已不可用", "Source file unavailable");
      case INTERRUPTED -> t("传输中断", "Transfer interrupted");
      case LENGTH -> t("下载长度校验失败", "Download size check failed");
      case SIZE_LIMIT -> t("文件超过 5 GB 上限", "File exceeds the 5 GB limit");
      case TASK_LIMIT -> t("下载记录已满", "Download history is full");
      case NOT_IMAGE -> t("不是有效图片", "Not a valid image");
      case BUSY -> t("下载器正在收尾", "Downloader is finishing");
      case SAVE -> t("无法完成保存", "Could not save the file");
      case UNAVAILABLE -> t("记录或文件已不可用", "Record or file unavailable");
      case OTHER -> t("操作未完成", "Operation did not complete");
    };
  }

  static String failureBody(Failure failure, String raw) {
    return switch (failure) {
      case CANCELLED -> cancelled();
      case NO_SPACE ->
          t(
              "存储空间不足。请释放空间后重试；已完成文件不会被覆盖。",
              "Not enough storage. Free some space and retry. Completed files will not be overwritten.");
      case PERMISSION ->
          t(
              "无法访问保存位置。请检查文件权限，或重新选择保存位置。",
              "Cannot access the destination. Check file permissions or choose another destination.");
      case INTEGRITY ->
          t(
              "文件身份或完整性校验未通过，未发布文件。请重新获取下载。",
              "File identity or integrity verification failed. No file was published. Request a new download.");
      case RUNTIME ->
          t(
              "当前网页运行环境已停止、切换或未就绪。请确认网页正常后重试，下载记录仍保留。",
              "The web runtime stopped, changed, or is not ready. Check that the web app is ready and retry. The download record is retained.");
      case UNAUTHORIZED ->
          t(
              "文件访问未获授权。请返回当前网页重新获取下载。",
              "File access was not authorized. Request the download again from the current web page.");
      case SOURCE_GONE ->
          t(
              "源文件已不可用。请返回网页重新获取文件。",
              "The source file is no longer available. Request the file again from the web app.");
      case INTERRUPTED ->
          t(
              "传输中断或文件服务暂不可用。已下载进度保留，请检查运行环境后重试。",
              "The transfer was interrupted or the file service is unavailable. Downloaded progress is retained. Check the runtime and retry.");
      case LENGTH ->
          t(
              "下载长度或续传响应校验失败，未发布文件。请重新获取下载。",
              "Download size or resume response verification failed. No file was published. Request a new download.");
      case SIZE_LIMIT ->
          t("文件超过当前单个文件 5 GB 的上限。", "The file exceeds the current 5 GB per-file limit.");
      case TASK_LIMIT ->
          t(
              "下载记录已达上限，请移除不再需要的记录后重试。已完成文件会保留。",
              "Download history is full. Remove unneeded records and retry. Completed files will be kept.");
      case NOT_IMAGE ->
          t(
              "文件未通过真实图片校验，未写入相册。可从网页选择普通下载。",
              "The file did not pass image verification and was not saved to the gallery. Use a regular download from the web app.");
      case BUSY ->
          t(
              "下载器正在完成本条记录的收尾，请稍后再移除。",
              "The downloader is finishing this record. Try removing it again shortly.");
      case SAVE ->
          t(
              "无法完成文件保存。请确认保存位置仍可用后重试；另存为可重新选择位置。",
              "The file could not be saved. Check that the destination is available and retry. Save As lets you choose a destination again.");
      case UNAVAILABLE ->
          t(
              "下载记录或完成文件已不可用，请重新获取文件。",
              "The download record or completed file is unavailable. Request the file again.");
      case OTHER -> {
        String value = raw == null ? "" : raw;
        String redacted =
            com.deepseekharness.app.util.SensitiveData.redact(
                value.length() > 350 ? value.substring(0, 350) : value);
        yield redacted.isEmpty()
            ? t("操作未完成，可以重试。", "The operation did not complete. You can retry.")
            : f("操作未完成：%s", "Operation did not complete: %s", redacted);
      }
    };
  }

  /** 用于 Snackbar 与提示条：一句完整的失败说明。 */
  static String error(String raw) {
    return failureBody(TransferListModel.failure(raw), raw);
  }

  static String cancelled() {
    return t(
        "已取消。需要再次下载请从网页重新发起。",
        "Cancelled. To download again, start a new download from the web app.");
  }

  static String action(TransferListModel.Action action, DownloadTask.Status status) {
    return switch (action) {
      case OPEN -> t("打开", "Open");
      case SHARE -> t("分享", "Share");
      case PAUSE -> t("暂停", "Pause");
      case RESUME -> t("继续", "Resume");
      case RETRY -> t("重试", "Retry");
      case CHOOSE -> t("选择保存位置", "Choose destination");
      case CANCEL ->
          status == DownloadTask.Status.WAITING_DESTINATION
              ? t("取消保存", "Cancel save")
              : t("取消下载", "Cancel download");
      case REMOVE -> t("移除记录", "Remove record");
    };
  }
}
