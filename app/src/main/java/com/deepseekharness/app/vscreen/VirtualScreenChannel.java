package com.deepseekharness.app.vscreen;

import android.content.Context;

/** 虚拟屏核心的受管启动通道；实现不能把它退化成任意 shell。 */
public interface VirtualScreenChannel {
    String id();
    boolean available(Context context);
    String start(Context context, String command);
}
