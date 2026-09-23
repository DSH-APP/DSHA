package com.deepseekharness.app.util;

/** 虚拟屏的纯尺寸、坐标和过期帧规则；不依赖 Android。 */
public final class VirtualScreenPolicy {
    private VirtualScreenPolicy() { }
    public static final int MIN_API = 30;
    public static boolean supported(int api) { return api >= MIN_API; }
    public static int[] phoneSize(int requestedWidth, int requestedHeight) {
        boolean landscape = requestedWidth > requestedHeight;
        int source = Math.min(Math.max(requestedWidth, 1), Math.max(requestedHeight, 1));
        int edge = Math.max(144, Math.min(1440, Math.round(source / 144f) * 144));
        int longEdge = edge * 16 / 9;
        return landscape ? new int[]{longEdge, edge} : new int[]{edge, longEdge};
    }
    public static float[] map(float x, float y, int viewWidth, int viewHeight, int screenWidth, int screenHeight) {
        if (viewWidth <= 0 || viewHeight <= 0 || screenWidth <= 0 || screenHeight <= 0) return new float[]{0, 0};
        return new float[]{Math.max(0, Math.min(screenWidth, x * screenWidth / viewWidth)),
                Math.max(0, Math.min(screenHeight, y * screenHeight / viewHeight))};
    }
    public static boolean fresh(long observed, long current) { return observed > 0 && observed == current; }
}
