package com.deepseekharness.app.backup;

import java.io.*;
import java.util.*;

/** 保留区只读清单：固定类别和 UUID，不接受界面传入任意物理路径，不提供删除。 */
public final class RetainedCatalogue {
  public static final class PageChanged extends IOException {
    public PageChanged() {
      super("RETAINED_PAGE_CHANGED");
    }
  }

  public enum Kind {
    BACKUP,
    ENVIRONMENT,
    RUNTIME,
    RESTORE,
    PLUGIN,
    QUARANTINE,
    SETTINGS,
    PRESET,
    MIGRATION
  }

  public static final class Entry {
    public final Kind kind;
    public final String id, part, scope, status, protection, displayName;
    public final File directory, source;
    public final long modified;

    Entry(
        Kind kind,
        String id,
        String part,
        String scope,
        String status,
        String protection,
        File directory,
        File source,
        long modified) {
      this(kind, id, part, scope, status, protection, directory, source, modified, part);
    }

    Entry(
        Kind kind,
        String id,
        String part,
        String scope,
        String status,
        String protection,
        File directory,
        File source,
        long modified,
        String displayName) {
      this.kind = kind;
      this.id = id;
      this.part = part;
      this.scope = scope;
      this.status = status;
      this.protection = protection;
      this.directory = directory;
      this.source = source;
      this.modified = modified;
      this.displayName = displayName;
    }

    public String key() {
      return kind.name() + ":" + id + ":" + part;
    }
  }

  private final BackupFileSystem fs;
  private final File files, home;

  public RetainedCatalogue(BackupFileSystem fs, File files, File home) {
    this.fs = fs;
    this.files = files.getAbsoluteFile();
    this.home = home.getAbsoluteFile();
  }

  private static boolean uuid(String id) {
    return id.matches(com.deepseekharness.app.util.Ids.UUID_PATTERN);
  }

  public List<Entry> list() throws IOException {
    List<Entry> entries = new ArrayList<>();
    collect(entries);
    entries.sort(NEWEST_FIRST);
    return Collections.unmodifiableList(entries);
  }

  private static final Comparator<Entry> NEWEST_FIRST =
      Comparator.<Entry>comparingLong(entry -> entry.modified)
          .reversed()
          .thenComparing(Entry::key)
          .thenComparing(entry -> entry.directory.getAbsolutePath());
  private static final Comparator<Entry> WORST_FIRST = (a, b) -> NEWEST_FIRST.compare(b, a);

  /** Display cursor only. The fingerprint describes the visible catalogue, not source health. */
  public static final class Cursor {
    public final long modified;
    public final String key, path, fingerprint;

    Cursor(Entry last, String fingerprint) {
      this.modified = last.modified;
      key = last.key();
      path = last.directory.getAbsolutePath();
      this.fingerprint = fingerprint;
    }

    Cursor(long modified, String key, String path, String fingerprint) {
      this.modified = modified;
      this.key = key;
      this.path = path;
      this.fingerprint = fingerprint;
    }
  }

  public static final class Page {
    public final List<Entry> entries;
    public final int total, size;
    public final Cursor next;

    Page(List<Entry> entries, int total, int size, Cursor next) {
      this.entries = Collections.unmodifiableList(entries);
      this.total = total;
      this.size = size;
      this.next = next;
    }

    public boolean hasNext() {
      return next != null;
    }
  }

  private static final class PageSink extends AbstractList<Entry> {
    final int size;
    final Cursor after;
    final PriorityQueue<Entry> newest;
    final byte[] sum = new byte[32];
    int total, eligible;
    boolean boundarySeen;

    PageSink(Cursor after, int size) {
      this.after = after;
      this.size = size;
      newest = new PriorityQueue<>(11, WORST_FIRST);
    }

    @Override
    public boolean add(Entry entry) {
      byte[] hash =
          BackupArchive.sha()
              .digest(
                  (entry.key()
                          + "\n"
                          + entry.modified
                          + "\n"
                          + entry.status
                          + "\n"
                          + entry.scope
                          + "\n"
                          + entry.protection
                          + "\n"
                          + entry.displayName
                          + "\n"
                          + entry.directory.getAbsolutePath()
                          + "\n"
                          + (entry.source == null ? "" : entry.source.getAbsolutePath()))
                      .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      int carry = 0;
      for (int i = 31; i >= 0; i--) {
        int value = (sum[i] & 255) + (hash[i] & 255) + carry;
        sum[i] = (byte) value;
        carry = value >>> 8;
      }
      total++;
      if (after != null) {
        if (entry.modified == after.modified
            && entry.key().equals(after.key)
            && entry.directory.getAbsolutePath().equals(after.path)) boundarySeen = true;
        if (entry.modified > after.modified
            || entry.modified == after.modified && entry.key().compareTo(after.key) < 0
            || entry.modified == after.modified
                && entry.key().equals(after.key)
                && entry.directory.getAbsolutePath().compareTo(after.path) <= 0) return true;
      }
      eligible++;
      if (newest.size() < size) newest.add(entry);
      else if (WORST_FIRST.compare(entry, newest.peek()) > 0) {
        newest.poll();
        newest.add(entry);
      }
      return true;
    }

    String fingerprint() {
      return BackupArchive.hex(sum) + ":" + total;
    }

    List<Entry> page() {
      List<Entry> ordered = new ArrayList<>(newest);
      ordered.sort(NEWEST_FIRST);
      return ordered;
    }

    @Override
    public Entry get(int index) {
      throw new IndexOutOfBoundsException();
    }

    @Override
    public int size() {
      return newest.size();
    }
  }

  /** Scan requested history explicitly; retain only one page and reject stale display cursors. */
  public Page page(Cursor after, int size) throws IOException {
    if (size < 1 || size > 100) throw new IOException("RETAINED_PAGE");
    PageSink sink = new PageSink(after, size);
    collect(sink);
    String fingerprint = sink.fingerprint();
    if (after != null && (!sink.boundarySeen || !fingerprint.equals(after.fingerprint)))
      throw new PageChanged();
    List<Entry> entries = sink.page();
    Cursor next =
        sink.eligible > size && !entries.isEmpty()
            ? new Cursor(entries.get(entries.size() - 1), fingerprint)
            : null;
    return new Page(entries, sink.total, size, next);
  }

  private void collect(List<Entry> entries) throws IOException {
    File backupRoot = HostOperationArchive.root(files);
    scan(entries, Kind.BACKUP, backupRoot);
    File completedBackups = HostOperationArchive.completedRoot(files);
    if (fs.stat(completedBackups).type.equals("DIRECTORY"))
      scan(entries, Kind.BACKUP, completedBackups);
    for (var domain :
        List.of(
            Map.entry(Kind.ENVIRONMENT, new File(files, EnvironmentRebuildTransaction.HOME)),
            Map.entry(Kind.RUNTIME, new File(files, ManagedRuntimeTransaction.HOME)),
            Map.entry(Kind.PLUGIN, new File(home, PluginInstallJournals.DIRECTORY)))) {
      scan(entries, domain.getKey(), domain.getValue());
      File completed = new File(domain.getValue(), "completed");
      if (fs.stat(completed).type.equals("DIRECTORY")) scan(entries, domain.getKey(), completed);
    }
    scan(entries, Kind.QUARANTINE, new File(files, "plugin-imports"));
    presets(entries);
    migrationRecords(entries);
  }

  private void scan(List<Entry> entries, Kind kind, File parent) throws IOException {
    if (fs.stat(parent).type.equals("MISSING")) return;
    if (!fs.stat(parent).type.equals("DIRECTORY"))
      throw new IOException("RETAINED_DIRECTORY_UNREADABLE");
    // Both filesystem implementations return sorted lists. Do not copy the full
    // directory merely to remove the two reserved names for display.
    List<String> names = fs.list(parent);
    for (String id : names) {
      if (id.equals(HostOperationArchive.COMPLETED) || id.equals(".completed-proof-v1.json"))
        continue;
      if (!uuid(id)) {
        entries.add(
            new Entry(
                kind,
                id,
                "record",
                "unknown",
                "UNRECOGNIZED",
                "UNKNOWN_ORIGINAL_RETAINED",
                new File(parent, id),
                null,
                0));
        continue;
      }
      File directory = fs.child(parent, id);
      try {
        if (!fs.stat(directory).type.equals("DIRECTORY")) throw new IOException("RETAINED_TYPE");
        String status = "RETAINED";
        if (marker(directory, "committed") || marker(directory, "finalized")) status = "COMMITTED";
        else if (marker(directory, "rolled-back")) status = "ROLLED_BACK";
        else if (exists(directory, "switching")
            || kind == Kind.PLUGIN && exists(directory, "plan.json")) status = "RECOVERY_REQUIRED";
        File other =
            parent.getName().equals(HostOperationArchive.COMPLETED)
                ? new File(parent.getParentFile(), id)
                : new File(parent, HostOperationArchive.COMPLETED + "/" + id);
        if (!fs.stat(other).type.equals("MISSING")) status = "DUPLICATE";
        if (status.equals("DUPLICATE")) {
          entries.add(
              new Entry(
                  kind,
                  id,
                  "record",
                  "unknown",
                  status,
                  "UNKNOWN_ORIGINAL_RETAINED",
                  directory,
                  null,
                  fs.stat(directory).modified));
          continue;
        }
        if (kind == Kind.BACKUP && exists(directory, "verified.json")) {
          var copy = VerifiedBackupCopy.inspect(fs, HostOperationArchive.root(files), id);
          entries.add(
              new Entry(
                  kind,
                  id,
                  "encrypted",
                  copy.scope,
                  "RECORDED_VERIFIED_COPY",
                  "VERIFY_BEFORE_EXPORT_OR_RESTORE",
                  directory,
                  copy.artifact,
                  fs.stat(directory).modified));
        }
        boolean found = false;
        Set<String> seenProfiles = new HashSet<>();
        if (kind == Kind.QUARANTINE) {
          File presets = new File(directory, "declarations/.agent-presets");
          if (fs.stat(presets).type.equals("DIRECTORY"))
            for (String preset : fs.list(presets)) {
              if (!com.deepseekharness.app.util.ProfileConfigPath.profile(preset)) continue;
              File source = fs.child(presets, preset);
              if (!fs.stat(source).type.equals("DIRECTORY")) continue;
              entries.add(
                  new Entry(
                      Kind.PRESET,
                      id,
                      "legacy-preset-" + NativeDataLocations.hash(preset),
                      "projects",
                      "CONVERSION_REQUIRED",
                      "REVIEW_REQUIRED",
                      directory,
                      source,
                      fs.stat(directory).modified,
                      preset));
              found = true;
            }
        }
        if (kind == Kind.QUARANTINE)
          for (String prefix : List.of("settings/profiles", "profiles")) {
            File profiles = new File(directory, prefix);
            if (!fs.stat(profiles).type.equals("DIRECTORY")) continue;
            List<String> profileNames = fs.list(profiles);
            if (profileNames.size() > 512) throw new IOException("PROFILE_LIMIT");
            for (String profile : profileNames) {
              if (!com.deepseekharness.app.util.ProfileConfigPath.profile(profile)) continue;
              File source = fs.child(profiles, profile);
              if (!fs.stat(source).type.equals("DIRECTORY")) continue;
              if (!fs.stat(new File(source, "cordis.patch.yml")).type.equals("FILE")
                  && !fs.stat(new File(source, "package.json")).type.equals("FILE")) continue;
              if (!seenProfiles.add(profile)) continue;
              entries.add(
                  new Entry(
                      Kind.SETTINGS,
                      id,
                      "profile-" + NativeDataLocations.hash(prefix + "/" + profile),
                      "settings",
                      "QUARANTINED",
                      "REVIEW_REQUIRED",
                      directory,
                      source,
                      fs.stat(directory).modified,
                      profile));
              found = true;
            }
          }
        if (kind == Kind.QUARANTINE) {
          File packages = new File(directory, "packages");
          if (fs.stat(packages).type.equals("DIRECTORY")) {
            List<String> nodes = fs.list(packages);
            if (nodes.size() > 4096) throw new IOException("PLUGIN_GRAPH_LIMIT");
            for (String node : nodes)
              if (node.matches("package-[a-f0-9]{20}")) {
                File json = fs.child(packages, node + "/package.json");
                if (!fs.stat(json).type.equals("FILE")) continue;
                Map<String, Object> value =
                    BackupJson.read(fs.small(json, BackupLimits.MANIFEST), BackupLimits.MANIFEST);
                if (value.get("dsh") instanceof Map
                    && ((Map<?, ?>) value.get("dsh")).containsKey("bundle")
                    && value.get("name") instanceof String) {
                  entries.add(
                      new Entry(
                          kind,
                          id,
                          node,
                          "projects",
                          "QUARANTINED",
                          "REVIEW_REQUIRED",
                          directory,
                          directory,
                          fs.stat(directory).modified,
                          (String) value.get("name")));
                  found = true;
                }
              }
          }
        }
        if (kind == Kind.ENVIRONMENT)
          for (String part : List.of("previous-linux", "failed-linux")) {
            File root = new File(directory, part + "/ubuntu");
            if (fs.stat(root).type.equals("DIRECTORY")) {
              File data = new File(root, "root/.dsh");
              if (!fs.stat(data).type.equals("MISSING")) {
                add(entries, kind, id, part + "-data", "application", status, directory, data);
                found = true;
              }
              File personal = new File(root, "root");
              if (fs.stat(personal).type.equals("DIRECTORY")) {
                add(entries, kind, id, part + "-personal", "projects", status, directory, personal);
                found = true;
              }
            }
          }
        if (kind == Kind.RUNTIME || kind == Kind.BACKUP)
          for (String part : List.of("previous", "failed")) {
            File root = new File(directory, part);
            if (fs.stat(root).type.equals("DIRECTORY") && !fs.list(root).isEmpty()) {
              File data = new File(root, "dsh-home");
              if (kind == Kind.BACKUP && fs.stat(data).type.equals("DIRECTORY"))
                add(
                    entries,
                    Kind.RESTORE,
                    id,
                    part + "-data",
                    "application",
                    status,
                    directory,
                    data);
              add(
                  entries,
                  kind == Kind.BACKUP ? Kind.RESTORE : kind,
                  id,
                  part,
                  "projects",
                  status,
                  directory,
                  root);
              found = true;
            }
          }
        if (kind == Kind.PLUGIN)
          for (String part :
              List.of(
                  "old",
                  "failed",
                  "previous-history",
                  "new",
                  "delete-source",
                  "delete-link",
                  "delete-marker",
                  "delete-history")) {
            File root = new File(directory, part);
            String type = fs.stat(root).type;
            if (type.equals("DIRECTORY")) {
              add(entries, kind, id, part, "projects", status, directory, root);
              found = true;
            } else if (!type.equals("MISSING")) {
              entries.add(
                  new Entry(
                      kind,
                      id,
                      part,
                      "projects",
                      status,
                      "ORIGINALS_OR_RECORDS_RETAINED",
                      directory,
                      null,
                      fs.stat(directory).modified));
              found = true;
            }
          }
        if (!found && !(kind == Kind.BACKUP && exists(directory, "verified.json")))
          entries.add(
              new Entry(
                  kind,
                  id,
                  "record",
                  "unknown",
                  status,
                  "ORIGINALS_OR_RECORDS_RETAINED",
                  directory,
                  null,
                  fs.stat(directory).modified));
      } catch (IOException error) {
        entries.add(
            new Entry(
                kind,
                id,
                "record",
                "unknown",
                "UNREADABLE",
                "UNKNOWN_ORIGINAL_RETAINED",
                directory,
                null,
                0));
      }
    }
  }

  private void presets(List<Entry> entries) throws IOException {
    File parent = new File(home, ".dsha-rc1-migration/legacy-agent-presets");
    if (fs.stat(parent).type.equals("MISSING")) return;
    if (!fs.stat(parent).type.equals("DIRECTORY"))
      throw new IOException("PRESET_CANDIDATES_UNREADABLE");
    List<String> names = fs.list(parent);
    if (names.size() > 512) throw new IOException("PRESET_LIMIT");
    for (String name : names) {
      if (!com.deepseekharness.app.util.ProfileConfigPath.profile(name)) continue;
      File versions = fs.child(parent, name);
      if (!fs.stat(versions).type.equals("DIRECTORY")) continue;
      List<String> revisions = fs.list(versions);
      if (revisions.size() > 256) throw new IOException("PRESET_LIMIT");
      for (String revision : revisions) {
        if (!revision.matches("(?:[a-f0-9]{16,64}|[a-f0-9]{16}-[a-f0-9]{8})")) continue;
        File candidate = fs.child(versions, revision);
        if (!fs.stat(candidate).type.equals("DIRECTORY")) continue;
        String id =
            UUID.nameUUIDFromBytes(
                    (name + "/" + revision).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString();
        File bundle = new File(candidate, "bundle");
        boolean converted = fs.stat(new File(bundle, "package.json")).type.equals("FILE");
        entries.add(
            new Entry(
                Kind.PRESET,
                id,
                "legacy-preset",
                "projects",
                converted ? "QUARANTINED" : "CONVERSION_REQUIRED",
                "REVIEW_REQUIRED",
                candidate,
                converted ? bundle : candidate,
                fs.stat(candidate).modified,
                name));
      }
    }
  }

  private void migrationRecords(List<Entry> entries) throws IOException {
    List<File> roots =
        List.of(new File(files, "rc1-migration-state"), new File(home, ".dsha-rc1-migration"));
    for (File root : roots) {
      if (fs.stat(root).type.equals("MISSING")) continue;
      if (!fs.stat(root).type.equals("DIRECTORY"))
        throw new IOException("MIGRATION_RECORDS_UNREADABLE");
      String id =
          UUID.nameUUIDFromBytes(
                  ("DSHA-MIGRATION:" + root.getAbsolutePath())
                      .getBytes(java.nio.charset.StandardCharsets.UTF_8))
              .toString();
      String status = "RETAINED";
      File receipt = new File(root, "receipt.json");
      File current = new File(root, "current.json");
      File marker = fs.stat(current).type.equals("FILE") ? current : receipt;
      if (fs.stat(marker).type.equals("FILE"))
        try {
          Map<String, Object> value =
              BackupJson.read(fs.small(marker, BackupLimits.MANIFEST), BackupLimits.MANIFEST);
          String state = BackupJson.string(value, "status");
          if (state.equals("pending")) status = "PENDING_RETRY";
          else if (state.equals("committed") || state.equals("verified")) status = "COMMITTED";
        } catch (IOException ignored) {
          status = "UNREADABLE";
        }
      entries.add(
          new Entry(
              Kind.MIGRATION,
              id,
              "records",
              "settings",
              status,
              "READ_ONLY_RESCUE_NO_AUTOMATIC_DELETION",
              root,
              root,
              fs.stat(root).modified,
              "rc1 migration records"));
    }
  }

  private void add(
      List<Entry> out,
      Kind kind,
      String id,
      String part,
      String scope,
      String status,
      File directory,
      File source)
      throws IOException {
    out.add(
        new Entry(
            kind,
            id,
            part,
            scope,
            status,
            "READ_ONLY_RESCUE_NO_AUTOMATIC_DELETION",
            directory,
            source,
            fs.stat(directory).modified));
  }

  private boolean exists(File directory, String name) throws IOException {
    return !fs.stat(new File(directory, name)).type.equals("MISSING");
  }

  private boolean marker(File directory, String name) throws IOException {
    return TransactionMarkers.read(fs, directory, name, "RETAINED_MARKER_UNREADABLE");
  }

  public static final class Lookup {
    public final Map<String, Entry> entries;
    public final Map<String, String> errors;
    private final RetainedCatalogue owner;

    Lookup(RetainedCatalogue owner, Map<String, Entry> entries, Map<String, String> errors) {
      this.owner = owner;
      this.entries = Collections.unmodifiableMap(entries);
      this.errors = Collections.unmodifiableMap(errors);
    }
  }

  /** One explicit metadata scan for a selection. It is never a restore authorization. */
  public Lookup resolveAll(Set<String> requested) throws IOException {
    if (requested == null || requested.size() > 100_000)
      throw new IOException("RETAINED_KEYS_LIMIT");
    Set<String> keys = new LinkedHashSet<>(requested), owners = new HashSet<>();
    Map<String, Entry> found = new LinkedHashMap<>();
    Map<String, Integer> hits = new HashMap<>();
    Map<String, String> errors = new LinkedHashMap<>();
    for (String key : keys) {
      if (key == null || key.length() > 512) {
        errors.put(key, "RETAINED_KEY_INVALID");
        continue;
      }
      int first = key.indexOf(':'), second = first < 0 ? -1 : key.indexOf(':', first + 1);
      if (first < 1 || second < first + 2 || second == key.length() - 1) {
        errors.put(key, "RETAINED_KEY_INVALID");
        continue;
      }
      owners.add(key.substring(0, second + 1));
    }
    Set<String> seenOwners = new HashSet<>(), duplicateOwners = new HashSet<>();
    class BatchSink extends AbstractList<Entry> {
      @Override
      public boolean add(Entry entry) {
        String key = entry.key(), prefix = entry.kind.name() + ":" + entry.id + ":";
        if (owners.contains(prefix)) seenOwners.add(prefix);
        if (entry.status.equals("DUPLICATE")) duplicateOwners.add(prefix);
        if (!keys.contains(key)) return true;
        if (hits.merge(key, 1, Integer::sum) > 1) {
          errors.put(key, "RETAINED_SOURCE_DUPLICATE");
          found.remove(key);
          return true;
        }
        if (errors.containsKey(key)) return true;
        if (!uuid(entry.id)) {
          errors.put(key, "RETAINED_KEY_INVALID");
          found.remove(key);
        } else if (entry.status.equals("UNREADABLE")) {
          errors.put(key, "RETAINED_SOURCE_UNREADABLE");
        } else found.put(key, entry);
        return true;
      }

      @Override
      public Entry get(int index) {
        throw new IndexOutOfBoundsException();
      }

      @Override
      public int size() {
        return found.size();
      }
    }
    collect(new BatchSink());
    for (String key : keys) {
      int first = key.indexOf(':'), second = first < 0 ? -1 : key.indexOf(':', first + 1);
      if (second >= 0 && duplicateOwners.contains(key.substring(0, second + 1))) {
        found.remove(key);
        errors.put(key, "RETAINED_SOURCE_DUPLICATE");
      } else if (!found.containsKey(key) && !errors.containsKey(key))
        errors.put(
            key,
            second < 0
                ? "RETAINED_KEY_INVALID"
                : seenOwners.contains(key.substring(0, second + 1))
                    ? "RETAINED_SOURCE_CHANGED"
                    : "RETAINED_SOURCE_MISSING");
    }
    return new Lookup(this, found, errors);
  }

  public static boolean sameListing(Entry expected, Entry actual) {
    return expected != null
        && actual != null
        && expected.key().equals(actual.key())
        && expected.modified == actual.modified
        && expected.status.equals(actual.status)
        && expected.scope.equals(actual.scope)
        && expected.directory.getAbsolutePath().equals(actual.directory.getAbsolutePath())
        && Objects.equals(
            expected.source == null ? null : expected.source.getAbsolutePath(),
            actual.source == null ? null : actual.source.getAbsolutePath());
  }

  public Entry resolve(String key) throws IOException {
    Lookup checked = resolveAll(Collections.singleton(key));
    Entry found = checked.entries.get(key);
    if (found != null) return found;
    throw new IOException(checked.errors.getOrDefault(key, "RETAINED_SOURCE_CHANGED"));
  }

  public List<BackupSource> sources(Entry selected) throws IOException {
    return sourcesResolved(resolve(selected.key()));
  }

  /** Read-only batch inspection uses its freshly resolved entry; export/restore still call sources(). */
  public List<BackupSource> inspectionSources(Lookup checked, String key) throws IOException {
    if (checked == null || checked.owner != this || checked.errors.containsKey(key))
      throw new IOException("RETAINED_SOURCE_CHANGED");
    Entry entry = checked.entries.get(key);
    if (entry == null) throw new IOException("RETAINED_SOURCE_MISSING");
    return sourcesResolved(entry);
  }

  private List<BackupSource> sourcesResolved(Entry entry) throws IOException {
    if (entry.source == null || entry.part.equals("encrypted"))
      throw new IOException("RETAINED_SOURCE_NOT_A_TREE");
    File rootfs =
        entry.kind == Kind.ENVIRONMENT
            ? new File(
                entry.directory,
                entry.part.startsWith("previous") ? "previous-linux/ubuntu" : "failed-linux/ubuntu")
            : entry.source;
    GuestDataResolver resolver = new GuestDataResolver(fs, rootfs, null, Collections.emptyList());
    List<BackupSource> roots = new ArrayList<>();
    if (entry.kind == Kind.MIGRATION) {
      roots.add(
          new FileBackupSource(fs, "migration-records", "settings", entry.source, false, null) {
            @Override
            public Map<String, Object> description() {
              var value = super.description();
              value.put("logicalKind", "migration-record");
              value.put("name", "rc1-migration-records");
              return value;
            }
          });
      return roots;
    }
    if (entry.kind == Kind.SETTINGS) {
      for (String name : List.of("package.json", "cordis.patch.yml")) {
        File source = new File(entry.source, name);
        if (!fs.stat(source).type.equals("FILE")) continue;
        String logical = "profiles/" + entry.displayName + "/" + name;
        BackupSource configSource =
            new FileBackupSource(
                fs,
                "profile-config-" + NativeDataLocations.hash(logical),
                "settings",
                source,
                false,
                null) {
              @Override
              public Map<String, Object> description() {
                var value = super.description();
                value.put("logicalKind", "dsh-profile-config");
                value.put("name", logical);
                return value;
              }
            };
        roots.add(configSource);
      }
      return roots;
    }
    if (entry.scope.equals("application")) {
      File home;
      try {
        home = resolver.resolve(entry.source).file;
      } catch (IOException unreadable) {
        return List.of(
            new UnavailableBackupSource(
                "retained-data", "application", "RETAINED_LINK_UNREADABLE", false));
      }
      if (!fs.stat(home).type.equals("DIRECTORY"))
        return List.of(
            new UnavailableBackupSource(
                "retained-data", "application", "RETAINED_DATA_UNREADABLE", false));
      for (String name : fs.list(home)) {
        if (DataRootPolicy.machine(name)
            || name.equals(com.deepseekharness.app.util.CredentialPaths.LEGACY_API_KEY)) continue;
        String scope = NativeDataLocations.classify(name);
        File child = fs.child(home, name);
        File actual;
        try {
          actual = resolver.resolve(child).file;
        } catch (IOException unavailable) {
          roots.add(
              new UnavailableBackupSource(
                  NativeDataLocations.id(name), scope, "RETAINED_LINK_UNREADABLE", false));
          continue;
        }
        BackupSource source =
            new FileBackupSource(
                fs,
                NativeDataLocations.id(name),
                scope,
                actual,
                false,
                (relative, node) -> DataRootPolicy.exclusion(name, relative),
                resolver) {
              @Override
              public Map<String, Object> description() {
                var value = super.description();
                value.put("logicalKind", "dsh-child");
                value.put("name", name);
                value.put("retainedSource", entry.key());
                return value;
              }
            };
        roots.add(
            name.equals(CredentialProjection.FILE)
                ? new CredentialFileBackupSource(source)
                : source);
      }
    } else {
      String id = "project-" + NativeDataLocations.hash(entry.key());
      roots.add(
          new FileBackupSource(fs, id, "projects", entry.source, false, null, resolver) {
            @Override
            public Map<String, Object> description() {
              var value = super.description();
              value.put("logicalKind", "project");
              value.put("name", entry.part);
              value.put("retainedSource", entry.key());
              return value;
            }
          });
    }
    if (roots.isEmpty())
      roots.add(
          new UnavailableBackupSource(
              "retained-data", entry.scope, "RETAINED_DATA_EMPTY_UNCONFIRMED", false));
    return roots;
  }
}
