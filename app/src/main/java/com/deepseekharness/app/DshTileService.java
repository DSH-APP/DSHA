package com.deepseekharness.app;

import android.content.Intent;
import android.graphics.drawable.Icon;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * 快捷设置磁贴：下拉通知栏一键启停 dsh。
 *
 * <p><b>为什么是磁贴而不是别的。</b>官方对磁贴的适用场景划得很死：给「用户经常访问
 * 且需要快速访问」的功能，并明确列出该避免的用法 —— 别用磁贴启动 App（那是 App
 * Shortcuts 的活）、别做一次性操作、别只显示信息不互动（那该用通知或微件）、
 * 每个应用最多两个。「启停 dsh」正好落在推荐区：使用频率高，而且现在要开 App、
 * 进启动页、点按钮，三步才够。
 *
 * <p>状态判断刻意不做网络探测。onStartListening 在主线程上被频繁调用，往里塞
 * 「连一下 3080 端口」会卡住下拉面板；改用 HarnessService 自己维护的静态标记，
 * 同进程读取，零成本。代价是「服务活着但 dsh 进程死了」这种情况磁贴会显示成运行中
 * —— 那属于自愈逻辑该管的事，不该由磁贴来兜。
 */
public class DshTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        refresh();
    }

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        refresh();
    }

    @Override
    public void onClick() {
        super.onClick();
        boolean running = HarnessService.isRunning();
        try {
            Intent it = new Intent(this, HarnessService.class)
                    .setAction(running ? HarnessService.ACTION_STOP : HarnessService.ACTION_START);
            if (running) {
                // 停止走普通 startService：服务已在前台，不需要再走前台启动那条路。
                startService(it);
            } else {
                startForegroundService(it);
            }
        } catch (Throwable t) {
            android.util.Log.w("DSHA", "磁贴操作失败: " + t);
        }
        // 立刻把磁贴翻到目标状态，别等服务回调 —— 用户点完看到没反应会以为没生效。
        // 真实状态会在下一次 onStartListening 校正。
        updateTile(!running);
    }

    private void refresh() {
        updateTile(HarnessService.isRunning());
    }

    private void updateTile(boolean running) {
        Tile tile = getQsTile();
        if (tile == null) return;
        try {
            tile.setState(running ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            tile.setLabel(running ? "DSH 运行中" : "启动 DSH");
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                tile.setSubtitle(running ? "点击停止" : "点击启动");
            }
            tile.setIcon(Icon.createWithResource(this, R.drawable.ic_tile_dsh));
            tile.updateTile();
        } catch (Throwable t) {
            android.util.Log.w("DSHA", "磁贴刷新失败: " + t);
        }
    }
}
