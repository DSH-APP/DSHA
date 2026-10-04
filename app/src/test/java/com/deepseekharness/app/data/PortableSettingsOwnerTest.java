package com.deepseekharness.app.data;

import static org.junit.Assert.*;

import android.content.SharedPreferences;
import com.deepseekharness.app.util.Constants;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class PortableSettingsOwnerTest {
  static final class Preferences implements SharedPreferences {
    final Map<String, Object> values = new HashMap<>();
    final Set<OnSharedPreferenceChangeListener> listeners = new HashSet<>();
    int registrations;
    volatile boolean failCommits;
    Runnable beforeCommit = () -> {};
    Runnable onUnregister = () -> {};

    public synchronized Map<String, ?> getAll() {
      return new HashMap<>(values);
    }

    public synchronized String getString(String key, String fallback) {
      return (String) values.getOrDefault(key, fallback);
    }

    @SuppressWarnings("unchecked")
    public synchronized Set<String> getStringSet(String key, Set<String> fallback) {
      return (Set<String>) values.getOrDefault(key, fallback);
    }

    public synchronized int getInt(String key, int fallback) {
      return (Integer) values.getOrDefault(key, fallback);
    }

    public synchronized long getLong(String key, long fallback) {
      return (Long) values.getOrDefault(key, fallback);
    }

    public synchronized float getFloat(String key, float fallback) {
      return (Float) values.getOrDefault(key, fallback);
    }

    public synchronized boolean getBoolean(String key, boolean fallback) {
      return (Boolean) values.getOrDefault(key, fallback);
    }

    public synchronized boolean contains(String key) {
      return values.containsKey(key);
    }

    public synchronized void registerOnSharedPreferenceChangeListener(
        OnSharedPreferenceChangeListener listener) {
      registrations++;
      listeners.add(listener);
    }

    public synchronized void unregisterOnSharedPreferenceChangeListener(
        OnSharedPreferenceChangeListener listener) {
      listeners.remove(listener);
      onUnregister.run();
    }

    public synchronized OnSharedPreferenceChangeListener listener() {
      return listeners.iterator().next();
    }

    public Editor edit() {
      return new Editor() {
        final Map<String, Object> changes = new HashMap<>();
        final Set<String> removed = new HashSet<>();
        boolean clear;

        private Editor put(String key, Object value) {
          changes.put(key, value);
          return this;
        }

        public Editor putString(String key, String value) {
          return put(key, value);
        }

        public Editor putStringSet(String key, Set<String> value) {
          return put(key, value);
        }

        public Editor putInt(String key, int value) {
          return put(key, value);
        }

        public Editor putLong(String key, long value) {
          return put(key, value);
        }

        public Editor putFloat(String key, float value) {
          return put(key, value);
        }

        public Editor putBoolean(String key, boolean value) {
          return put(key, value);
        }

        public Editor remove(String key) {
          removed.add(key);
          return this;
        }

        public Editor clear() {
          clear = true;
          return this;
        }

        public void apply() {
          commit();
        }

        public boolean commit() {
          beforeCommit.run();
          Set<OnSharedPreferenceChangeListener> observers;
          synchronized (Preferences.this) {
            if (failCommits) return false;
            if (clear) values.clear();
            for (String key : removed) values.remove(key);
            values.putAll(changes);
            observers = Set.copyOf(listeners);
          }
          for (var observer : observers) {
            if (clear) observer.onSharedPreferenceChanged(Preferences.this, null);
            for (String key : changes.keySet())
              observer.onSharedPreferenceChanged(Preferences.this, key);
          }
          return true;
        }
      };
    }
  }

  private static void drain(ExecutorService writer) throws Exception {
    writer.submit(() -> {}).get(2, TimeUnit.SECONDS);
  }

  @Test
  public void applicationOwnersDoNotShareListenersErrorsOrWriteQueues() throws Exception {
    ExecutorService firstWriter = Executors.newSingleThreadExecutor(),
        secondWriter = Executors.newSingleThreadExecutor();
    try {
      PortableSettings first = new PortableSettings(firstWriter),
          second = new PortableSettings(secondWriter);
      Preferences firstActive = new Preferences(),
          firstPortable = new Preferences(),
          secondActive = new Preferences(),
          secondPortable = new Preferences();
      firstActive.values.put(Constants.KEY_PORT, "3101");
      secondActive.values.put(Constants.KEY_PORT, "3102");
      first.initializePreferences(firstActive, firstPortable);
      first.initializePreferences(firstActive, firstPortable);
      second.initializePreferences(secondActive, secondPortable);
      drain(firstWriter);
      drain(secondWriter);
      assertEquals(1, firstActive.registrations);
      assertEquals(1, secondActive.registrations);
      assertEquals("3101", firstPortable.getString(Constants.KEY_PORT, ""));
      assertEquals("3102", secondPortable.getString(Constants.KEY_PORT, ""));
      firstPortable.failCommits = true;
      firstActive.edit().putString(Constants.KEY_PORT, "3103").commit();
      drain(firstWriter);
      assertEquals("SETTINGS_PROJECTION_FAILED", first.errorCode());
      assertEquals("", second.errorCode());
      assertEquals("3102", secondPortable.getString(Constants.KEY_PORT, ""));
    } finally {
      firstWriter.shutdownNow();
      secondWriter.shutdownNow();
    }
  }

  @Test
  public void resetWaitsForTheAcceptedWriteAndOldCallbacksCannotRestoreOldPreferences()
      throws Exception {
    ExecutorService writer = Executors.newSingleThreadExecutor();
    try {
      PortableSettings owner = new PortableSettings(writer);
      Preferences active = new Preferences(), portable = new Preferences();
      active.values.put(Constants.KEY_PORT, "old-value");
      CountDownLatch writing = new CountDownLatch(1),
          allowCommit = new CountDownLatch(1),
          detached = new CountDownLatch(1);
      AtomicBoolean first = new AtomicBoolean(true);
      portable.beforeCommit =
          () -> {
            if (!first.compareAndSet(true, false)) return;
            writing.countDown();
            try {
              assertTrue(allowCommit.await(2, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
              throw new AssertionError(interrupted);
            }
          };
      active.onUnregister = detached::countDown;
      owner.initializePreferences(active, portable);
      var oldListener = active.listener();
      assertTrue(writing.await(2, TimeUnit.SECONDS));
      AtomicReference<Throwable> failure = new AtomicReference<>();
      Thread reset =
          new Thread(
              () -> {
                try {
                  owner.resetPreferences(active, portable);
                } catch (Throwable error) {
                  failure.set(error);
                }
              });
      reset.start();
      assertTrue(detached.await(2, TimeUnit.SECONDS));
      assertEquals("old-value", active.getString(Constants.KEY_PORT, ""));
      allowCommit.countDown();
      reset.join(2500);
      assertFalse(reset.isAlive());
      assertNull(failure.get());
      drain(writer);
      assertFalse(active.contains(Constants.KEY_PORT));
      assertFalse(portable.contains(Constants.KEY_PORT));
      assertEquals(1, active.listeners.size());
      assertEquals(2, active.registrations);
      Map<String, ?> afterReset = portable.getAll();
      oldListener.onSharedPreferenceChanged(active, Constants.KEY_PORT);
      drain(writer);
      assertEquals(afterReset, portable.getAll());
    } finally {
      writer.shutdownNow();
    }
  }

  @Test
  public void failedInitialImportKeepsThePortableOriginalAndRetryRegistersOneListener()
      throws Exception {
    ExecutorService writer = Executors.newSingleThreadExecutor();
    try {
      PortableSettings owner = new PortableSettings(writer);
      Preferences active = new Preferences(), portable = new Preferences();
      portable.values.put("projectionVersion", 1);
      portable.values.put(Constants.KEY_PORT, "source-value");
      active.failCommits = true;
      owner.initializePreferences(active, portable);
      drain(writer);
      assertEquals("SETTINGS_PROJECTION_FAILED", owner.errorCode());
      assertEquals("source-value", portable.getString(Constants.KEY_PORT, ""));
      assertEquals(0, active.registrations);
      active.failCommits = false;
      owner.initializePreferences(active, portable);
      drain(writer);
      assertEquals("source-value", active.getString(Constants.KEY_PORT, ""));
      assertEquals(1, active.registrations);
      assertEquals("", owner.errorCode());
    } finally {
      writer.shutdownNow();
    }
  }
}
