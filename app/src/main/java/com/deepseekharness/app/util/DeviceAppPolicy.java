package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 每次结束应用均使用新清单；先验证全部目标，再执行，PID 只用于定位包名。 */
public final class DeviceAppPolicy {
    private DeviceAppPolicy() { }
    public static final class App {
        public final String name;
        public final int uid;
        public final Set<Integer> uids;
        public final boolean system;
        App(String name, Set<Integer> uids, boolean system) {
            this.name = name; this.uids = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(uids));
            this.uid = uids.iterator().next(); this.system = system;
        }
    }
    public static final class Snapshot {
        public final Map<String, App> apps = new LinkedHashMap<>();
        private final Set<Integer> protectedUids = new HashSet<>();
        private void add(String output, boolean system) {
            for (String line : output.split("\\r?\\n")) {
                if (line.trim().isEmpty()) continue;
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("^package:([A-Za-z0-9_.]+) uid:([0-9]+(?:,[0-9]+)*)$").matcher(line.trim());
                if (!m.matches() || !m.group(1).matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)*")) throw new IllegalArgumentException("应用清单格式无法确认");
                Set<Integer> uids = new LinkedHashSet<>();
                for (String value : m.group(2).split(",")) uids.add(Integer.parseInt(value));
                App old = apps.get(m.group(1));
                if (old != null && (!old.uids.equals(uids) || old.system != system)) throw new IllegalArgumentException("应用归属冲突");
                App app = new App(m.group(1), uids, system); apps.put(app.name, app);
                if (system || uids.stream().anyMatch(uid -> uid % 100000 < 10000) || app.name.equals("com.dsh.client")
                        || app.name.equals("moe.shizuku.privileged.api")) protectedUids.addAll(uids);
            }
        }
        public String grouped() {
            StringBuilder text = new StringBuilder();
            for (boolean system : new boolean[]{false, true}) {
                text.append(system ? "[系统应用]\n" : "[用户应用]\n");
                apps.values().stream().filter(app -> app.system == system).sorted((a,b) -> a.name.compareTo(b.name))
                        .forEach(app -> text.append(app.name).append(" uid=")
                                .append(app.uids.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")))
                                .append(app.uids.stream().anyMatch(protectedUids::contains) ? " [保护]" : "").append('\n'));
            }
            return text.toString();
        }
        private String approved(String name) {
            App app = apps.get(name);
            if (app == null || app.system || app.uids.stream().anyMatch(protectedUids::contains))
                throw new IllegalArgumentException("系统、关键或未识别应用不可结束：" + name);
            return name;
        }
        public List<String> targets(DeviceShellPolicy.Plan plan, String ps) {
            Set<String> targets = new LinkedHashSet<>();
            if (!plan.command().equals("kill")) {
                for (String name : plan.operands) targets.add(approved(name));
            } else {
                Map<Integer,Integer> processes = new HashMap<>();
                for (String line : ps.split("\\r?\\n")) {
                    String[] columns = line.trim().split("\\s+", 3);
                    if (columns.length != 3 || columns[0].equals("PID")) continue;
                    if (!columns[0].matches("[0-9]+") || !columns[1].matches("[0-9]+"))
                        throw new IllegalArgumentException("无法确认进程 UID");
                    processes.put(Integer.parseInt(columns[0]), Integer.parseInt(columns[1]));
                }
                for (String value : plan.operands) {
                    Integer uid = processes.get(Integer.parseInt(value));
                    if (uid == null || uid % 100000 < 10000 || protectedUids.contains(uid))
                        throw new IllegalArgumentException("系统关键进程或归属不明的 PID 不可结束：" + value);
                    List<String> owners = new ArrayList<>();
                    for (App app : apps.values()) if (app.uids.contains(uid)) owners.add(app.name);
                    if (owners.size() != 1) throw new IllegalArgumentException("PID 对应共享或未知 UID，请使用明确包名");
                    targets.add(approved(owners.get(0)));
                }
            }
            return new ArrayList<>(targets);
        }
    }
    public static Snapshot snapshot(String users, String systems) {
        if (systems == null || systems.trim().isEmpty() || users == null) throw new IllegalArgumentException("应用清单不完整，未结束任何应用");
        Snapshot snapshot = new Snapshot(); snapshot.add(users, false); snapshot.add(systems, true); return snapshot;
    }
}
