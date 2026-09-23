package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class DeviceShellPolicyTest {
    private void denied(String... commands) {
        for (String cmd : commands) assertEquals(cmd, DeviceShellPolicy.Kind.DENY, DeviceShellPolicy.inspect(cmd).kind);
    }
    @Test public void everyReadableDirectoryIsEligible() {
        for (String cmd : new String[]{"ls /", "ls /system", "cat /proc/version", "ls /sdcard/DCIM", "ls /storage/emulated/0/Android/data",
                "cat /data/user/0/example.app/file", "getprop", "settings get global airplane_mode_on", "settings --user 0 list secure",
                "pm list packages -U -s", "dumpsys activity processes", "date +%s", "logcat -d -t 50", "find / -name '*.jpg'"})
            assertEquals(cmd, DeviceShellPolicy.Kind.READ, DeviceShellPolicy.inspect(cmd).kind);
    }
    @Test public void rootAndProtectedDirectoriesCannotBeMutated() {
        for (String root : new String[]{"/", "/*", "/system", "/data", "/storage", "/storage/emulated/0", "/sdcard/DCIM",
                "/storage/emulated/0/Pictures", "/storage/emulated/0/Android/data", "/storage/emulated/0/Android/obb",
                "/mnt/runtime/write/emulated/0/DCIM", "/storage/self/primary/Pictures", "/mnt/user/0/primary/Android/data"}) {
            denied("mkdir " + root, "touch " + root, "rm -rf " + root, "mv " + root + " /sdcard/Download/x",
                    "cp /sdcard/Download/x " + root, "rmdir " + root);
        }
        denied("rm -rf /sdcard/pIcTuReS/a", "rm -rf /storage/emulated/10/DCIM/a", "rm -rf /storage/ABCD-0123/DCIM/a");
    }
    @Test public void ordinaryFilesAndCopiesFromReadOnlyDirectoriesAreAllowed() {
        for (String cmd : new String[]{"mkdir -p /sdcard/Download/project/sub", "touch /sdcard/Download/a",
                "cp /sdcard/DCIM/photo.jpg /sdcard/Download/photo.jpg", "cp /system/build.prop /sdcard/Download/info.txt",
                "mv /sdcard/Download/a /sdcard/Download/b", "rename /sdcard/Download/a /sdcard/Download/b",
                "rm -rf /sdcard/Download/project", "mkdir /data/local/tmp/dsha-test", "touch /storage/ABCD-0123/Documents/a"})
            assertEquals(cmd, DeviceShellPolicy.Kind.FILE, DeviceShellPolicy.inspect(cmd).kind);
    }
    @Test public void unsafePathsAndUnknownOptionsFailClosed() {
        denied("rm -rf /sdcard/*", "mv /sdcard/Download/../Pictures/a /sdcard/Download/b", "cp a b", "mkdir /data/local/tmp",
                "cp --target-directory=/sdcard/Pictures /sdcard/Download/a", "touch -r /system/build.prop /sdcard/Pictures/a",
                "rm --preserve-root=no /", "rmdir -p /data/local/tmp/empty", "rename 's/a/b/' /sdcard/Download/a");
        assertTrue(DeviceShellPolicy.writeAllowed("/sdcard/Pictures2/a"));
        assertFalse(DeviceShellPolicy.writeAllowed("/sdcardEVIL/a"));
    }
    @Test public void allDestructiveDeviceClassesAreBlocked() {
        denied("dd if=/dev/zero of=/dev/block/sda", "dd if=/dev/block/sda of=/sdcard/Download/image", "mkfs.ext4 /dev/block/sda",
                "fdisk -l", "parted /dev/block/sda print", "setenforce 0", "chcon -R u:object_r:system_file:s0 /data",
                "restorecon -RF /data", "settings put global x 1", "settings delete secure x", "setprop persist.foo 1",
                "setprop debug.foo 1", "mount -o remount,rw /system", "umount /sdcard", "fastboot devices",
                "flash boot image", "format data", "erase userdata", "wipe data", "pm uninstall example.app", "pm clear example.app",
                "pm grant com.dsh.client android.permission.WRITE_SECURE_SETTINGS", "cmd settings put global x 1", "service call power 1");
    }
    @Test public void shellLanguageAndAlternateExecutorsCannotBypass() {
        denied("sh -c 'rm -rf /sdcard/Pictures'", "python3 -c 'print(1)'", "node -e 'process.exit()'", "env ls /",
                "ls /; rm /sdcard/Pictures/a", "cat $(getprop)", "cat `id`", "echo hi > /dev/block/a", "echo hi >> /sdcard/Pictures/a",
                "ls / | head", "ls / && id", "ls /\nid", "r''m -rf /sdcard/Pictures", "toybox dd if=/dev/zero of=/dev/block/a",
                "/data/local/tmp/ls /", "/system/bin/../bin/sh -c id", "UNKNOWN", "", "getprop\0");
        assertEquals(DeviceShellPolicy.Kind.READ, DeviceShellPolicy.inspect("echo 'rm -rf /*; setenforce 0'").kind);
        assertEquals(DeviceShellPolicy.Kind.READ, DeviceShellPolicy.inspect("/system/bin/toybox ls /").kind);
    }
    @Test public void writableDiagnosticsAreNotReadOnly() {
        denied("date -s 20260101", "date 010100002026", "dumpsys battery set level 0", "dumpsys deviceidle force-idle",
                "logcat -c", "logcat -d -f /sdcard/Download/log", "logcat -G 32M", "find / -delete", "find / -exec rm {} +");
    }
    @Test public void processTargetsMustBeExplicit() {
        for (String cmd : new String[]{"kill 12345", "kill -9 12345", "pkill -x example.app", "killall example.app",
                "am force-stop example.app", "am kill example.app"})
            assertEquals(cmd, DeviceShellPolicy.Kind.STOP, DeviceShellPolicy.inspect(cmd).kind);
        denied("kill -1", "kill -9 -1", "kill 0", "kill 1", "kill -STOP 123", "pkill -f example", "killall node", "am kill-all");
    }
    @Test public void acceptedArgumentsAreDataAndImmutable() {
        DeviceShellPolicy.Plan plan = DeviceShellPolicy.inspect("cp '/sdcard/Download/a b' '/sdcard/Download/c d'");
        assertEquals("/sdcard/Download/a b", plan.argv.get(1));
        assertEquals("/sdcard/Download/c d", plan.argv.get(2));
        try { plan.argv.set(0, "dd"); fail(); } catch (UnsupportedOperationException expected) { }
    }
    @Test public void virtualScreenLauncherIsNarrowlyAllowlisted() {
        String command="app_process -Djava.class.path=/data/app/com.dsh.client/base.apk /system/bin "
                + "com.deepseekharness.app.vscreen.VirtualScreenCore --launch --port 8998 --token "
                + "0123456789abcdef0123456789abcdef";
        assertEquals(DeviceShellPolicy.Kind.VIRTUAL_SCREEN,DeviceShellPolicy.inspect(command).kind);
        denied("app_process /system/bin com.example.Main", "app_process -Djava.class.path=/data/app/x.apk /system/bin com.example.Main --launch --port 8998 --token 0123456789abcdef0123456789abcdef");
    }
}
