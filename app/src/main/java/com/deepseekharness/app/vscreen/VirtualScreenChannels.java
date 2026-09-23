package com.deepseekharness.app.vscreen;

import android.content.Context;
import com.deepseekharness.app.DeviceBridgeService;
import com.deepseekharness.app.RootShell;
import com.deepseekharness.app.ShizukuShell;
import com.deepseekharness.app.bridge.AdbBridge;

import java.util.ArrayList;
import java.util.List;

/** 复用 DSHA 的设备能力授权；结果未知时调用方不得换通道重放。 */
final class VirtualScreenChannels {
    private VirtualScreenChannels() { }

    static List<VirtualScreenChannel> all() {
        List<VirtualScreenChannel> channels = new ArrayList<>();
        channels.add(new VirtualScreenChannel() {
            public String id() { return "root"; }
            public boolean available(Context c) { return RootShell.enabled(c) && RootShell.present(); }
            public String start(Context c, String command) { return RootShell.exec(c, command, -1); }
        });
        channels.add(new VirtualScreenChannel() {
            public String id() { return "shizuku"; }
            public boolean available(Context c) { return ShizukuShell.hasPermission(); }
            public String start(Context c, String command) { return ShizukuShell.exec(command); }
        });
        channels.add(new VirtualScreenChannel() {
            public String id() { return "adb"; }
            public boolean available(Context c) { return DeviceBridgeService.isAdbEnabled(c); }
            public String start(Context c, String command) { return AdbBridge.executeVirtualScreen(c, command); }
        });
        return channels;
    }

    static VirtualScreenChannel find(String id) {
        for (VirtualScreenChannel channel : all()) if (channel.id().equals(id)) return channel;
        return null;
    }

    static VirtualScreenChannel choose(Context context) {
        for (VirtualScreenChannel channel : all()) if (channel.available(context)) return channel;
        return null;
    }
}
