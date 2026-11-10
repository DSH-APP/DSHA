package com.deepseekharness.app.util;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.security.cert.CertPathValidatorException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLPeerUnverifiedException;

/** Updater errors have fixed user-facing messages; remote text and Java exceptions stay out of UI. */
public final class UpdateFailures {
  private UpdateFailures() {}

  public static final class HttpFailure extends IOException {
    public final int status;

    public HttpFailure(int status) {
      super("UPDATE_HTTP_" + status);
      this.status = status;
    }
  }

  public static final class InvalidManifest extends IOException {
    public InvalidManifest() {
      super("UPDATE_MANIFEST_INVALID");
    }

    public InvalidManifest(Throwable cause) {
      super("UPDATE_MANIFEST_INVALID", cause);
    }
  }

  public enum Kind {
    CERTIFICATE,
    TLS,
    TIMEOUT,
    OFFLINE,
    HTTP,
    MANIFEST,
    OTHER
  }

  public static Kind kind(Throwable failure) {
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    Kind result = Kind.OTHER;
    for (Throwable next = failure; next != null && seen.add(next); next = next.getCause()) {
      if (next instanceof CertificateException
          || next instanceof CertPathValidatorException
          || next instanceof SSLPeerUnverifiedException) return Kind.CERTIFICATE;
      if (next instanceof SocketTimeoutException) result = Kind.TIMEOUT;
      else if (next instanceof SSLException && result == Kind.OTHER) result = Kind.TLS;
      else if (next instanceof UnknownHostException && result == Kind.OTHER) result = Kind.OFFLINE;
      else if (next instanceof HttpFailure && result == Kind.OTHER) result = Kind.HTTP;
      else if (next instanceof InvalidManifest && result == Kind.OTHER) result = Kind.MANIFEST;
    }
    return result;
  }

  public static String message(Throwable failure) {
    if (kind(failure) == Kind.OTHER && failure != null) {
      for (String local :
          new String[] {
            "无法创建更新目录", "存储不足，请留出剩余下载大小加 32 MiB 空间", "下载清单无效",
            "无法清理无效下载", "服务器返回的续传范围与安装包不符", "服务器返回的文件大小与清单不符",
            "下载超过清单大小", "网络传输中断，进度已保留，可继续下载", "安装包大小发生变化，请重新下载",
            "SHA-256 校验失败，已清理损坏文件，请重新下载", "安装包不是 DSHA", "安装包不是正式非调试版本",
            "安装包版本不匹配或不是更新版本", "安装包不支持当前 Android 版本", "安装包不是当前高/低安卓版本",
            "安装包签名不匹配", "签名与已安装版本不同，已阻止安装"
          }) if (UiText.text(local).equals(failure.getMessage())) return UiText.text(local);
    }
    switch (kind(failure)) {
      case CERTIFICATE:
        return UiText.choose(
            "无法验证更新服务的安全证书，请检查设备日期时间或切换网络后重试",
            "Cannot verify the update service certificate. Check the device date and time or try another network.");
      case TLS:
        return UiText.choose(
            "无法与更新服务建立安全连接，请切换网络或稍后重试",
            "Cannot establish a secure connection to the update service. Try another network or retry later.");
      case TIMEOUT:
        return UiText.choose("连接更新服务超时，请稍后重试", "The update service timed out. Try again later.");
      case OFFLINE:
        return UiText.choose(
            "无法连接更新服务，请检查网络后重试", "Cannot reach the update service. Check your network and retry.");
      case HTTP:
        return UiText.choose(
            "更新服务暂时不可用，请稍后重试", "The update service is unavailable. Try again later.");
      case MANIFEST:
        return UiText.choose(
            "更新信息暂时不可用，请稍后重试", "Update information is unavailable. Try again later.");
      default:
        return UiText.choose(
            "本次操作未完成，请检查网络和存储后重试",
            "The operation did not finish. Check your network and storage, then retry.");
    }
  }
}
