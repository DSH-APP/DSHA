package com.deepseekharness.app.transfer;

/** Immutable UI snapshot; no Activity, cookie, runtime address, or private source path. */
public final class DownloadTask {
  public enum Status {
    QUEUED,
    DOWNLOADING,
    VERIFYING,
    PUBLISHING,
    WAITING_DESTINATION,
    PAUSED,
    FAILED,
    COMPLETE,
    CANCELLED
  }

  public final String id, destination, message, uri, displayName, location, mime;
  public final TransferArtifact artifact;
  public final Status status;
  public final long downloaded, total, speedBytesPerSecond, createdAt;

  DownloadTask(
      String id,
      TransferArtifact artifact,
      String destination,
      Status status,
      long downloaded,
      long speed,
      String message,
      String uri,
      String displayName,
      String location,
      String mime,
      long createdAt) {
    this.id = id;
    this.artifact = artifact;
    this.destination = destination;
    this.status = status;
    this.downloaded = downloaded;
    this.total = artifact.bytes;
    this.speedBytesPerSecond = speed;
    this.message = message;
    this.uri = uri;
    this.displayName = displayName;
    this.location = location;
    this.mime = mime;
    this.createdAt = createdAt;
  }
}
