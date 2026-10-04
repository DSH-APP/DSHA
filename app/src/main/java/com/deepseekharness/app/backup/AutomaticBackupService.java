package com.deepseekharness.app.backup;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Handler;
import android.os.Looper;
import com.deepseekharness.app.util.ScheduledBackupOwner;
import java.util.Arrays;

/** JobScheduler 持有任务寿命，不从后台强行启动前台服务或停止用户会话。 */
public final class AutomaticBackupService extends JobService {
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ScheduledBackupOwner<JobParameters> owner = new ScheduledBackupOwner<>();
  private Runnable poll;

  @Override
  public boolean onStartJob(JobParameters params) {
    var ticket = owner.begin(params);
    if (ticket == null) return false;
    WorkerQuiescence.Lease producer = AutomaticBackups.beginProducer();
    if (producer == null) {
      owner.finish(ticket);
      return false;
    }
    try {
      boolean manual = params.getExtras().getBoolean("manual");
      Thread worker =
          new Thread(() -> prepare(ticket, manual, producer), "automatic-backup-schedule");
      worker.start();
      return true;
    } catch (RuntimeException | Error unavailable) {
      producer.close();
      owner.finish(ticket);
      AutomaticBackups.recordError(this, "WORKER_UNAVAILABLE");
      return false;
    }
  }

  /** Only preparation runs here. No worker may submit or publish ownership of a native job. */
  private void prepare(
      ScheduledBackupOwner.Ticket<JobParameters> ticket,
      boolean manual,
      WorkerQuiescence.Lease producer) {
    char[] secret = null;
    boolean retry = false;
    String error = "";
    boolean handedOff = false;
    try {
      if (AutomaticBackups.enabled(this) && (manual || AutomaticBackups.due(this))) {
        if (!AutomaticBackups.idle(this)) retry = manual;
        else secret = AutomaticBackups.password(this);
      }
    } catch (Exception | LinkageError failure) {
      error = failureCode(failure);
    } finally {
      char[] prepared = secret;
      boolean reschedule = retry;
      String failure = error;
      try {
        handedOff =
            main.post(() -> completePreparation(ticket, prepared, reschedule, failure, producer));
      } finally {
        if (!handedOff) {
          erase(prepared);
          producer.close();
        }
      }
    }
  }

  /** Submission and exact ID registration are serialized with onStopJob on the main event thread. */
  private void completePreparation(
      ScheduledBackupOwner.Ticket<JobParameters> ticket,
      char[] secret,
      boolean retry,
      String error,
      WorkerQuiescence.Lease producer) {
    try {
      owner.accept(
          ticket,
          () -> {
            if (!error.isEmpty()) {
              AutomaticBackups.recordError(this, error);
              finish(ticket, false);
              return;
            }
            if (secret == null || !AutomaticBackups.enabled(this)) {
              finish(ticket, retry);
              return;
            }
            NativeBackupJobs jobs = NativeBackupJobs.get(this);
            synchronized (jobs) {
              if (!jobs.exportAutomatic(secret)) {
                finish(ticket, false);
                return;
              }
              owner.submitted(ticket, jobs.state().id);
            }
            poll = () -> poll(ticket);
            main.post(poll);
          });
    } catch (Exception | LinkageError failure) {
      cancelOwned(ticket);
      AutomaticBackups.recordError(this, failureCode(failure));
      finish(ticket, false);
    } finally {
      erase(secret);
      producer.close();
    }
  }

  private void poll(ScheduledBackupOwner.Ticket<JobParameters> ticket) {
    if (!owner.current(ticket)) return;
    NativeBackupJobs jobs = NativeBackupJobs.get(this);
    NativeBackupJobs.State state;
    synchronized (jobs) {
      state = jobs.state();
      if (!AutomaticBackups.enabled(this) && state.busy && owner.owns(ticket, state.id))
        jobs.cancel();
    }
    if (!owner.owns(ticket, state.id) || !state.busy) {
      if (owner.owns(ticket, state.id) && !state.error.isEmpty())
        AutomaticBackups.recordError(this, state.error);
      finish(ticket, false);
      return;
    }
    main.postDelayed(poll, 300);
  }

  private void finish(ScheduledBackupOwner.Ticket<JobParameters> ticket, boolean retry) {
    if (owner.finish(ticket)) {
      if (poll != null) main.removeCallbacks(poll);
      poll = null;
      jobFinished(ticket.parameters, retry);
    }
  }

  private void cancelOwned(ScheduledBackupOwner.Ticket<JobParameters> ticket) {
    String id = owner.jobId(ticket);
    if (id.isEmpty()) return;
    NativeBackupJobs jobs = NativeBackupJobs.get(this);
    // Its state and cancellation use the same monitor: a new unrelated export cannot slip in.
    synchronized (jobs) {
      var state = jobs.state();
      if (state.busy && owner.owns(ticket, state.id)) jobs.cancel();
    }
  }

  @Override
  public boolean onStopJob(JobParameters params) {
    var stopped = owner.stop(params);
    if (stopped == null) return false;
    if (poll != null) main.removeCallbacks(poll);
    poll = null;
    cancelOwned(stopped);
    return false;
  }

  @Override
  public void onDestroy() {
    var stopped = owner.stopCurrent();
    if (poll != null) main.removeCallbacks(poll);
    poll = null;
    if (stopped != null) cancelOwned(stopped);
    // Prepared callbacks remain queued to erase their secret and release the producer lease.
    super.onDestroy();
  }

  private static void erase(char[] secret) {
    if (secret != null) Arrays.fill(secret, '\0');
  }

  private static String failureCode(Throwable failure) {
    return failure instanceof Exception
        ? NativeBackupJobs.code((Exception) failure)
        : "RUNTIME_LINKAGE_UNAVAILABLE";
  }
}
